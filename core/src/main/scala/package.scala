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
import nelson.Manifest.{Loadbalancer, UnitDef, Versioned}
import nelson.Nelson.NelsonK
import nelson.Versionable.AllOps

import scalaz.@@
package object nelson {

  import argonaut.{Parse,DecodeJson}
  import concurrent.duration, duration.Duration
  import java.io.File
  import java.nio.file.{Files, Path, Paths}
  import java.nio.charset.StandardCharsets
  import java.util.Locale
  import scala.concurrent.duration._
  import scalaz.concurrent.Task
  import scalaz.stream.Process
  import scalaz.syntax.kleisli._
  import scalaz.{~>,Monad,Order}

  type ID = Long
  type GUID = String
  type TagName = String
  type UnitName = String
  type DeploymentHash = String
  type TempoaryAccessCode = String
  type WorkflowRef = String
  type DatacenterRef = String
  type StatusMessage = String
  type DependencyEdge = (routing.RoutingNode, routing.RoutingNode)
  type ExpirationPolicyRef = String
  type EmailAddress = String
  type UnitRef = String
  type PlanRef = String
  type LoadbalancerRef = String
  type DNSName = String
  type DeploymentStatusString = String

  /**
   * Given we're mostly parsing string results to task, make a simple decoder
   * utility function for it.
   */
  def fromJson[A : DecodeJson](in: String): Task[A] =
    Parse.decodeEither[A](in)
         .fold(s => Task.fail(new RuntimeException(s)), Task.delay(_))

  implicit def versionableOps[A: Versionable](a: A): AllOps[A] = Versionable.ops.toAllVersionableOps[A](a)

  implicit val versionableUnit: Versionable[UnitDef @@ Versioned] = new Versionable[UnitDef @@ Versioned] {
    def version(u: UnitDef @@ Versioned): Version =
      Manifest.Versioned.unwrap(u).deployable.yolo(
        s"no deployable for $u when attempting to extract version").version
  }

  implicit val versionableLoadbalancer: Versionable[Loadbalancer @@ Versioned] = new Versionable[Loadbalancer @@ Versioned] {
    def version(lb: Loadbalancer @@ Versioned): Version =
      Manifest.Versioned.unwrap(lb).majorVersion.yolo(
        s"no major version for $lb when attempting to extract version").minVersion
  }

  implicit class BedazzledOpt[A](in: Option[A]){

    private def fail[B](err: NelsonError): Task[B] =
      Task.fail(err)

    def nfold[B](e: NelsonError)(f: A => B): NelsonK[B] =
      tfold(e)(f).liftKleisli

    def tfold[B](e: NelsonError)(f: A => B): Task[B] =
      in.fold(fail[B](e))(a => Task.delay(f(a)))
  }

  implicit class BedazzledTask[A](in: Task[A]){
    def retryExponentially(seed: Duration = 15.seconds, limit: Int = 5): Task[A] = {
      val periods = List.fill(limit)(seed).zipWithIndex.map {
        case (d,i) => d * i.toDouble
      }
      in.retry(periods)
    }
  }

  implicit class BedazzledString(s: String) {
    /**
     * Convert a string to snake case
     */
    def toSnakeCase: String =
      s.replaceAll("""(\p{Lower})(\p{Upper})""", "$1_$2")
        .replaceAll("""(\p{Upper}+)(\p{Upper}\p{Lower})""", "$1_$2")
        .replaceAll("""[\s_]+""", "_")
        .toLowerCase(Locale.ROOT)

    def withTrailingSlash: String =
      if (s.trim.endsWith("/")) s
      else s"${s}/"
  }

  import java.net.URI

  /**
   * Whenever one needs to refernce another location on the Nelson service,
   * and we expect it to be referenced by an external caller (e.g. Github or browser)
   * then we need to use the `linkTo` function which will generate a valid
   * URL with all the external configuration settings needed for the link to
   * work properly (i.e. accounting for HTTP(S) and such)
   */
  def linkTo(resource: String)(network: NetworkConfig): URI = {
    val path = if(resource.startsWith("/")) resource
               else s"/$resource"

    val pro  = if(network.tls) "https" else "http"
    // specifically support http and https; these are implicit
    // based on the protocol default ports
    val por  = if(network.externalPort == 80 || network.externalPort == 443) ""
               else s":${network.externalPort}"

    new URI(s"${pro}://${network.externalHost}${por}${path}")
  }

  private[this] val rng = new java.security.SecureRandom

  def randomAlphaNumeric(desiredLength: Int): String =
    rng.synchronized(new java.math.BigInteger(desiredLength * 5, rng).toString(32))

  private[nelson] final implicit class OptionOps[A](val oa: Option[A]) extends AnyVal {
    def yolo(err: => String): A = oa.getOrElse(throw new NoSuchElementException(err))
  }

  private[nelson] implicit val orderInstant: Order[java.time.Instant] =
    Order.order((i1, i2) => scalaz.Ordering.fromInt(i1.compareTo(i2)))

  final implicit class GithubFreeOps[A](val req: Github.GithubOpF[A]) extends AnyVal {
    def runWith[F[_]:Monad](interpreter: Github.GithubOp ~> F): F[A] = Github.run(interpreter, req)
  }

  def featureVersionFrom1or2DotString(versionString: String): Option[FeatureVersion] = {
    Version
      .fromString(versionString)
      .map(_.toFeatureVersion)
      .orElse(FeatureVersion.fromString(versionString))
  }

  private val DefaultTempDir =
    Paths.get(Option(System.getProperty("java.io.tmpdir")).getOrElse("/tmp"))

  def withTempFile[A](s: String, prefix: String = "nelson-", suffix: String = ".tmp", dir: Path = DefaultTempDir)(f: File => Process[Task, A]): Process[Task, A] =
    Process.bracket(writeTempFile(dir, s, prefix, suffix))(file => Process.eval_(Task.delay(file.delete())))(f)

  private def writeTempFile(dir: Path, s: String, prefix: String, suffix: String): Task[File] =
    Task.delay {
      val path = Files.createTempFile(dir, prefix, suffix)
      val file = path.toFile
      Files.write(path, s.getBytes(StandardCharsets.UTF_8))
      file.deleteOnExit()
      file
    }
}
