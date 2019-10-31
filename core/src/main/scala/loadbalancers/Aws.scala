//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson
package loadbalancers

import nelson.Manifest.{Port,Plan}

import com.amazonaws.services.elasticloadbalancingv2.model.{Action,ActionTypeEnum,CreateListenerRequest,CreateLoadBalancerRequest,CreateTargetGroupRequest,DeleteLoadBalancerRequest,DeleteTargetGroupRequest,DescribeLoadBalancersRequest,DescribeTargetGroupsRequest,Listener,LoadBalancer,LoadBalancerTypeEnum,ProtocolEnum,TargetGroup,TargetTypeEnum}
import com.amazonaws.services.autoscaling.model.{AmazonAutoScalingException,AttachLoadBalancerTargetGroupsRequest, CreateAutoScalingGroupRequest, DeleteAutoScalingGroupRequest, LaunchTemplateSpecification, Tag, UpdateAutoScalingGroupRequest}

import cats.~>
import cats.effect.IO
import cats.syntax.applicativeError._

import journal.Logger

final case class ASGSize(desired: Int, min: Int, max: Int)

/*
 * Modeling the internal vs internet NLB schemes
 */
sealed trait NlbScheme
final object NlbScheme {
  final case object External extends NlbScheme {
    override def toString: String = "internet-facing"
  }
  final case object Internal extends NlbScheme {
    override def toString: String = "internal"
  }
}

final class Aws(cfg: Infrastructure.Aws) extends (LoadbalancerOp ~> IO) {

  import LoadbalancerOp._
  import scala.collection.JavaConverters._


  val log = Logger[this.type]

  // in case desired isn't specified, launch one per az
  private val defaultSize = cfg.availabilityZones.toList.length

  override def apply[A](op: LoadbalancerOp[A]): IO[A] = op match {
    case DeleteLoadbalancer(lb, _, _) =>
      delete(lb)
    case LaunchLoadbalancer(lb, v, _, ns, p, hash) =>
      launch(lb, v, ns, p, hash)
    case ResizeLoadbalancer(lb, p) =>
      val name = loadbalancerName(lb.loadbalancer.name, lb.loadbalancer.version, lb.hash)
      resizeASG(name, asgSize(p))
  }

  def delete(lb: Datacenter.LoadbalancerDeployment): IO[Unit] = {
    val name = loadbalancerName(lb.loadbalancer.name, lb.loadbalancer.version, lb.hash)
    for {
      lbarn <- getLoadBalancerArn(name)
      _ <- deleteNLB(lbarn)

      targetGroups <- getTargetGroupsForNLB(lbarn)
      _ <- deleteTargetGroups(targetGroups)

      _ <- deleteASG(name)
    } yield ()
  }

  def launch(lb: Manifest.Loadbalancer, v: MajorVersion, ns: NamespaceName, p: Plan, hash: String): IO[DNSName] = {
    val name = loadbalancerName(lb.name, v, hash)
    log.debug(s"calling aws client to launch $name")
    val size = asgSize(p)

    for {
      loadbalancer <- createNLB(name, cfg.lbScheme)

      targetGroups <- createTargetGroups(name, lb.routes.toList.map(_.port))
      _ <- createListeners(loadbalancer, targetGroups)

      _ <- createASG(name, ns, size)
      _ <- attachASGToTargetGroups(name, targetGroups)
    } yield loadbalancer.getDNSName
  }

  // The target group has this restriction where the name of the target group can't
  // have "double dashes". The loadbalancer name should match the target group name,
  // so the loadbalancer won't have the double dashes either.
  def loadbalancerName(name: String, v: MajorVersion, hash: String) =
    s"$name-${v.minVersion.toExternalString}-${hash}"

  def getLoadBalancerArn(name: String): IO[String] = {
    val describeLoadBalancerRequest = new DescribeLoadBalancersRequest().withNames(name)
    (for {
      response          <- IO(cfg.nlb.describeLoadBalancers(describeLoadBalancerRequest))
      loadbalancerList  = response.getLoadBalancers.asScala.toList
      loadbalancerARN   = loadbalancerList.head.getLoadBalancerArn
    } yield loadbalancerARN).recoverWith{
      case e: Exception =>
        log.error(s"aws call to get loadbalancer ARN ${describeLoadBalancerRequest.getNames.asScala.head} failed.")
        IO.raiseError(FailedLoadbalancerDeploy(s"could not find loadbalancer and therefore could not delete loadbalancer", e.getMessage))
    }
  }

  def deleteNLB(lbarn: String): IO[Unit] =
    IO {
      val req: DeleteLoadBalancerRequest = new DeleteLoadBalancerRequest()
        .withLoadBalancerArn(lbarn)

      cfg.nlb.deleteLoadBalancer(req)
      ()
    }

  // https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/elasticloadbalancingv2/model/CreateLoadBalancerResult.html
  // has no method getDNS().
  def createNLB(name: String, scheme: NlbScheme): IO[LoadBalancer] = {
    IO {
      val req = new CreateLoadBalancerRequest()
        .withName(name)
        .withType(LoadBalancerTypeEnum.Network)
        .withScheme(scheme.toString)
        .withSubnets(cfg.availabilityZones.map(_.publicSubnet).asJava)

      val resp = cfg.nlb.createLoadBalancer(req)
      val loadbalancers: List[LoadBalancer] = resp.getLoadBalancers.asScala.toList

      loadbalancers.head
    }.recoverWith {
      case e: java.lang.NullPointerException => IO.raiseError(FailedLoadbalancerDeploy("AWS call return an empty list of loadbalancers", e.getMessage))
    }
  }

  def createTargetGroups(name: String, p: List[Port]): IO[List[TargetGroup]] = {

    def createRequest(p: Manifest.Port, name: String): CreateTargetGroupRequest =
      new CreateTargetGroupRequest()
        .withPort(p.port)
        .withProtocol(ProtocolEnum.TCP)
        .withName(name+"-"+ p.port.toString())
        .withTargetType(TargetTypeEnum.Instance)

    def createSingleTargetGroup(request: CreateTargetGroupRequest): IO[TargetGroup] = {
      (for {
        response <- IO(cfg.nlb.createTargetGroup(request))
        targetGroupList = response.getTargetGroups.asScala.toList
        targetGroup = targetGroupList.head
      } yield targetGroup).recoverWith{
            case e: Exception =>
              log.error(s"aws call to create target group for ${request.getName} failed.")
              IO.raiseError(FailedLoadbalancerDeploy("empty result from AWS something something", e.getMessage))
      }
    }

    val ctgreqs: List[CreateTargetGroupRequest] = p.map(port => createRequest(port, name))

    // (@timperret): I can't think of a way to import this. When I put it at the top,
    // I got all kinds of weird errors saying parameter is "never used".
    import cats.implicits._
    ctgreqs.traverse(req => createSingleTargetGroup(req))
  }

  def getTargetGroupsForNLB(lbarn: String): IO[List[TargetGroup]] = {
    val req = new DescribeTargetGroupsRequest()
      .withLoadBalancerArn(lbarn)

    IO(
      cfg.nlb.describeTargetGroups(req)
    ).map(resp => resp.getTargetGroups.asScala.toList)
  }

  def deleteTargetGroups(targetGroups: List[TargetGroup]): IO[Unit] = {
    def createRequest(targetGroupARN: String): DeleteTargetGroupRequest =
      new DeleteTargetGroupRequest()
          .withTargetGroupArn(targetGroupARN)

    IO{
      val reqs: List[DeleteTargetGroupRequest] = targetGroups.map(targetGroup => createRequest(targetGroup.getTargetGroupArn))

      reqs.map(req => cfg.nlb.deleteTargetGroup(req))
      ()
    }
  }

  def createListeners(loadbalancer: LoadBalancer, targetGroups: List[TargetGroup]): IO[List[Listener]] = {

    def createRequest(lbarn: String, targetGroupARN: String, targetGroupPort: Int): CreateListenerRequest = {
      val defaultAction: Action = new Action()
        .withType(ActionTypeEnum.Forward)
        .withTargetGroupArn(targetGroupARN)

      new CreateListenerRequest()
        .withLoadBalancerArn(lbarn)
        .withPort(targetGroupPort)
        .withProtocol(ProtocolEnum.TCP)
        .withDefaultActions(defaultAction)
    }

    def createSingleListener(request: CreateListenerRequest): IO[Listener] = {
      (for{
        response <- IO(cfg.nlb.createListener(request))
        listenerList = response.getListeners.asScala.toList
        listener = listenerList.head
      } yield listener).recoverWith{
        case e: Exception =>
          log.error(s"aws call to create listener for port ${request.getPort} failed")
          IO.raiseError(FailedLoadbalancerDeploy(s"empty result from AWS create Listener call", e.getMessage))
      }
    }

    val reqs: List[CreateListenerRequest] = targetGroups.map(tg => createRequest(loadbalancer.getLoadBalancerArn, tg.getTargetGroupArn, tg.getPort))

    // (@timperret): I can't think of a way to import this. When I put it at the top,
    // I got all kinds of weird errors saying parameter is "never used".
    import cats.implicits._
    reqs.traverse(req => createSingleListener(req))
  }

  def attachASGToTargetGroups(asgName: String, targetGroups: List[TargetGroup]): IO[Unit] = {
    IO{
      val req = new AttachLoadBalancerTargetGroupsRequest()
        .withAutoScalingGroupName(asgName)
        .withTargetGroupARNs(targetGroups.map(tg => tg.getTargetGroupArn).asJava)

      cfg.asg.attachLoadBalancerTargetGroups(req)

      ()
    }
  }

  def deleteASG(name: String): IO[Unit] =
    IO {
      val req = new DeleteAutoScalingGroupRequest()
        .withAutoScalingGroupName(name)
        .withForceDelete(true) // terminates all associated ec2s
      cfg.asg.deleteAutoScalingGroup(req)
    }.map(_ => ()).recoverWith {
      // Matching on the error message is only way to detect a 404. I trolled the aws
      // documnetation and this is the best I could find, super janky.
      // swallow 404, as we're being asked to delete something that does not exist
      // this can happen when a workflow fails and the cleanup process is subsequently called
      case t: AmazonAutoScalingException if t.getErrorMessage.contains("not found") =>
        log.info(s"aws call to delete asg responded with ${t.getMessage} for ${name}, swallowing error and marking deployment as terminated")
        IO.unit
    }

  def resizeASG(name: String, size: ASGSize): IO[Unit] =
    IO {
      val req = new UpdateAutoScalingGroupRequest()
        .withAutoScalingGroupName(name)
        .withDesiredCapacity(size.desired)
        .withMaxSize(size.max)
        .withMinSize(size.min)

      cfg.asg.updateAutoScalingGroup(req)

      ()
    }

  def createASG(name: String, namespace: NamespaceName, size: ASGSize): IO[Unit] =
    IO {
      val nameTag = new Tag()
        .withKey("Name")
        .withValue(name)

      val lbTag = new Tag()
        .withKey("nelson:ingress:name")
        .withValue(name)

      val iTag = new Tag()
        .withKey("nelson:ingress:container")
        .withValue(cfg.image.toString)

      val namespaceTag = new Tag()
        .withKey("nelson:ingress:namespace")
        .withValue(namespace.asString)

      val envTag = new Tag()
        .withKey("nelson:ingress:env")
        .withValue(namespace.root.asString)

      // There is significant nuance here. In order to support terraforming
      // or otherwise out-of-band creation of these launch templates, it makes
      // sense to use the revision determinated by the administratior. In effect
      // the admin can control what launch template is used, by setting the default
      // flag on the launch template. The AWS API for this is documented here:
      // https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/autoscaling/model/LaunchTemplateSpecification.html#withVersion-java.lang.String-
      val launchTemplateId = new LaunchTemplateSpecification()
        .withLaunchTemplateId(cfg.launchTemplateId)
        .withVersion("$Default")

      val req = new CreateAutoScalingGroupRequest()
        .withAutoScalingGroupName(name)
        .withTags(lbTag, nameTag, iTag, namespaceTag, envTag)
        .withLaunchTemplate(launchTemplateId)
        .withVPCZoneIdentifier(cfg.availabilityZones.map(_.privateSubnet).mkString(","))
        .withDesiredCapacity(size.desired)
        .withMaxSize(size.max)
        .withMinSize(size.min)

      cfg.asg.createAutoScalingGroup(req)

      ()
    }

  private def asgSize(p: Plan): ASGSize = {
    val desired = p.environment.desiredInstances getOrElse defaultSize
    val min = desired
    val max = scala.math.ceil(desired * 1.3).toInt // for some wiggle room
    ASGSize(desired, min, max)
  }
}
