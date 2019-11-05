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
import com.amazonaws.services.autoscaling.model.{AmazonAutoScalingException,AttachLoadBalancerTargetGroupsRequest,CreateAutoScalingGroupRequest,DeleteAutoScalingGroupRequest,LaunchTemplateSpecification,Tag,UpdateAutoScalingGroupRequest}

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
    val tgPrefix = targetGroupNamePrefix(lb.name, v)
    val size = asgSize(p)

    for {
      l <- createNLB(name, cfg.lbScheme)
      t <- createTargetGroups(tgPrefix, lb.routes.toList.map(_.port), l.getVpcId)
      _ <- createListeners(l, t)
      _ <- createASG(name, ns, size)
      _ <- attachASGToTargetGroups(name, t)
    } yield l.getDNSName
  }

  def loadbalancerName(name: String, v: MajorVersion, hash: String) =
    s"$name--${v.minVersion.toExternalString}--${hash}"

  // The target groups have a restriction such that name of the target group can't
  // have "double dashes" and the entire name must be less than 32 characters.
  // Unfortunately, target groups will not have the same name as the loadbalancers.
  // An example target group will be named stack-1-0-0-443 where the last number is the
  // port that it listens on (instead of the hash). This allows stack-1-0-0-80 to be
  // a different target group for the same loadbalancer. Since target groups are only
  // associated with a single loadbalancer at a time, it'll be easy to reason about which target
  // groups are related to which loadbalancers.
  def targetGroupNamePrefix(name: String, v: MajorVersion) =
    s"$name-${v.minVersion.toExternalString}"

  def getLoadBalancerArn(name: String): IO[String] = {
    val describeLoadBalancerRequest = new DescribeLoadBalancersRequest().withNames(name)
    (for {
      r <- IO(cfg.nlb.describeLoadBalancers(describeLoadBalancerRequest))
      x = r.getLoadBalancers.asScala.toList
      a = x.head.getLoadBalancerArn
    } yield a).recoverWith{
      case e: Exception =>
        log.error(s"aws call to get loadbalancer ARN ${describeLoadBalancerRequest.getNames.asScala.head} failed.")
        IO.raiseError(FailedLoadbalancerDeploy(s"could not find loadbalancer and therefore could not delete loadbalancer", e.getMessage))
    }
  }

  def deleteNLB(lbarn: String): IO[Unit] =
    IO {
      val req = new DeleteLoadBalancerRequest()
        .withLoadBalancerArn(lbarn)

      cfg.nlb.deleteLoadBalancer(req)
      ()
    }

  def createNLB(name: String, scheme: NlbScheme): IO[LoadBalancer] = {
    IO {
      val req = new CreateLoadBalancerRequest()
        .withName(name)
        .withType(LoadBalancerTypeEnum.Network)
        .withScheme(scheme.toString)
        .withSubnets(cfg.availabilityZones.map(_.publicSubnet).asJava)

      val resp = cfg.nlb.createLoadBalancer(req)
      val loadbalancers: List[LoadBalancer] = resp.getLoadBalancers.asScala.toList

      loadbalancers.headOption.yolo("AWS call return an empty list of loadbalancers")
    }
  }

  def createTargetGroups(targetGroupNamePrefix: String, p: List[Port], vpcID: String): IO[List[TargetGroup]] = {

    def createRequest(p: Manifest.Port): CreateTargetGroupRequest =
      new CreateTargetGroupRequest()
        .withPort(p.port)
        .withProtocol(ProtocolEnum.TCP)
        .withName(s"${targetGroupNamePrefix}-${p.port.toString}")
        .withTargetType(TargetTypeEnum.Instance)
        .withVpcId(vpcID)

    def createSingleTargetGroup(request: CreateTargetGroupRequest): IO[TargetGroup] = {
      (for {
        r <- IO(cfg.nlb.createTargetGroup(request))
        l = r.getTargetGroups.asScala.toList
      } yield l.head).recoverWith {
        case e: Exception =>
          log.error(s"aws call to create target group for ${request.getName} failed.")
          IO.raiseError(FailedLoadbalancerDeploy("empty result from AWS create target group call", e.getMessage))
      }
    }

    val ctgreqs: List[CreateTargetGroupRequest] = p.map(port => createRequest(port))

    import cats.implicits._
    ctgreqs.traverse(req => createSingleTargetGroup(req))
  }

  def getTargetGroupsForNLB(lbarn: String): IO[List[TargetGroup]] = {
    val req = new DescribeTargetGroupsRequest()
      .withLoadBalancerArn(lbarn)

    IO(cfg.nlb.describeTargetGroups(req)
      ).map(resp => resp.getTargetGroups.asScala.toList)
  }

  def deleteTargetGroups(targetGroups: List[TargetGroup]): IO[Unit] =
    IO {
      targetGroups
        .map(tg => new DeleteTargetGroupRequest().withTargetGroupArn(tg.getTargetGroupArn))
        .map(req => cfg.nlb.deleteTargetGroup(req))
    }.map(_ => ())

  def createListeners(loadbalancer: LoadBalancer, targetGroups: List[TargetGroup]): IO[List[Listener]] = {

    def createRequest(lbarn: String, targetGroupARN: String, targetGroupPort: Int): CreateListenerRequest = {
      val defaultAction = new Action()
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
        r <- IO(cfg.nlb.createListener(request))
        l = r.getListeners.asScala.toList
      } yield l.head).recoverWith{
        case e: Exception =>
          log.error(s"aws call to create listener for port ${request.getPort} failed")
          IO.raiseError(FailedLoadbalancerDeploy(s"empty result from AWS create Listener call", e.getMessage))
      }
    }

    import cats.implicits._

    targetGroups
      .map(tg => createRequest(loadbalancer.getLoadBalancerArn, tg.getTargetGroupArn, tg.getPort))
      .traverse(req => createSingleListener(req))
  }

  def attachASGToTargetGroups(asgName: String, targetGroups: List[TargetGroup]): IO[Unit] = {
    val arns = targetGroups.map(_.getTargetGroupArn)
    val req = new AttachLoadBalancerTargetGroupsRequest()
      .withAutoScalingGroupName(asgName)
      .withTargetGroupARNs(arns.asJava)

    IO(cfg.asg.attachLoadBalancerTargetGroups(req)).map(_ => ())
  }

  def deleteASG(name: String): IO[Unit] = {
    val req = new DeleteAutoScalingGroupRequest()
      .withAutoScalingGroupName(name)
      .withForceDelete(true) // terminates all associated ec2s

    IO(cfg.asg.deleteAutoScalingGroup(req)).map(_ => ()).recoverWith {
      // Matching on the error message is only way to detect a 404. I trolled the aws
      // documnetation and this is the best I could find, super janky.
      // swallow 404, as we're being asked to delete something that does not exist
      // this can happen when a workflow fails and the cleanup process is subsequently called
      case t: AmazonAutoScalingException if t.getErrorMessage.contains("not found") =>
        log.info(s"aws call to delete asg responded with ${t.getMessage} for ${name}, swallowing error and marking deployment as terminated")
        IO.unit
    }
  }

  def resizeASG(name: String, size: ASGSize): IO[Unit] = {
    val req = new UpdateAutoScalingGroupRequest()
      .withAutoScalingGroupName(name)
      .withDesiredCapacity(size.desired)
      .withMaxSize(size.max)
      .withMinSize(size.min)

    IO(cfg.asg.updateAutoScalingGroup(req)).map(_ => ()).recoverWith {
      case t: AmazonAutoScalingException if t.getErrorMessage.contains("not found") =>
        log.info(s"aws call to resize asg responded with ${t.getMessage} for ${name}, swallowing error and doing nothing")
        IO.unit
    }
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
