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

import com.amazonaws.services.elasticloadbalancing.model.{Listener,CreateLoadBalancerRequest,DeleteLoadBalancerRequest}
import com.amazonaws.services.autoscaling.model.{Tag,CreateAutoScalingGroupRequest,DeleteAutoScalingGroupRequest,UpdateAutoScalingGroupRequest}
import com.amazonaws.services.autoscaling.model.AmazonAutoScalingException

import cats.~>
import cats.effect.IO
import cats.syntax.apply._
import cats.syntax.applicativeError._

import journal.Logger

final case class ASGSize(desired: Int, min: Int, max: Int)

final class Aws(cfg: Infrastructure.Aws) extends (LoadbalancerOp ~> IO) {

  import LoadbalancerOp._
  import scala.collection.JavaConverters._


  val log = Logger[this.type]

  // in case desired isn't specified, launch one per az
  private val defaultSize = cfg.availabilityZones.toList.length

  override def apply[A](op: LoadbalancerOp[A]): IO[A] = op match {
    case DeleteLoadbalancer(lb, dc, ns) =>
      delete(lb, dc)
    case LaunchLoadbalancer(lb, v, dc, ns, p, hash) =>
      launch(lb, v, dc, ns, p, hash)
    case ResizeLoadbalancer(lb, p) =>
      val name = loadbalancerName(lb.loadbalancer.name, lb.loadbalancer.version, lb.hash)
      resizeASG(name, asgSize(p))
  }

  def delete(lb: Datacenter.LoadbalancerDeployment, dc: Datacenter): IO[Unit] = {
    val name = loadbalancerName(lb.loadbalancer.name, lb.loadbalancer.version, lb.hash)
    deleteELB(name) *> deleteASG(name)
  }

  def launch(lb: Manifest.Loadbalancer, v: MajorVersion, dc: Datacenter, ns: NamespaceName, p: Plan, hash: String): IO[DNSName] = {
    val name = loadbalancerName(lb.name, v, hash)
    log.debug(s"caling aws client to launch $name")
    val size = asgSize(p)
    createELB(name, lb.routes.map(_.port)) <* createASG(name, ns, size)
  }

  def loadbalancerName(name: String, v: MajorVersion, hash: String) =
    s"$name--${v.minVersion.toExternalString}--${hash}"

  def deleteELB(name: String): IO[Unit] =
    IO {
      val req = new DeleteLoadBalancerRequest(name)
      cfg.elb.deleteLoadBalancer(req)
      ()
    }

  def createELB(name: String, p: Vector[Port]): IO[DNSName] =
    IO {
      val listeners = p.map(p => new Listener(TCP,p.port,p.port)).toList
      val req = new CreateLoadBalancerRequest(name)
        .withListeners(listeners.asJava)
        .withSecurityGroups(cfg.elbSecurityGroupNames.toList.asJava)
        .withSubnets(cfg.availabilityZones.map(_.publicSubnet).asJava)

      val resp = cfg.elb.createLoadBalancer(req)

      resp.getDNSName()
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
      // pass deployment specifics to haproxy
      val lbTag = new Tag()
        .withKey("NELSON_LB_NAME")
        .withValue(name)

      val iTag = new Tag()
        .withKey("NELSON_HAPROXY_IMAGE")
        .withValue(cfg.image.toString)

      val nameTag = new Tag()
        .withKey("Name")
        .withValue(name)

      val namespaceTag = new Tag()
        .withKey("NELSON_NAMESPACE")
        .withValue(namespace.asString)

      val envTag = new Tag()
        .withKey("NELSON_ENV")
        .withValue(namespace.root.asString)

      val req = new CreateAutoScalingGroupRequest()
        .withAutoScalingGroupName(name)
        .withTags(lbTag, nameTag, iTag, namespaceTag, envTag)
        .withLaunchConfigurationName(cfg.launchConfigurationName)
        .withVPCZoneIdentifier(cfg.availabilityZones.map(_.privateSubnet).mkString(","))
        .withLoadBalancerNames(name) // ELB name is the same at asg name
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

  private val TCP = "TCP"
}
