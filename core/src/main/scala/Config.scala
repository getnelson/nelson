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

import nelson.BannedClientsConfig.HttpUserAgent
import nelson.Infrastructure.KubernetesMode
import nelson.audit.{Auditor,AuditEvent}
import nelson.cleanup.ExpirationPolicy
import nelson.docker.Docker
import nelson.health.KubernetesHealthClient
import nelson.logging.{WorkflowLogger,LoggingOp}
import nelson.notifications.{SlackHttp,SlackOp,EmailOp,EmailServer}
import nelson.scheduler.{KubernetesShell, SchedulerOp}
import nelson.storage.StoreOp
import nelson.vault._
import nelson.vault.http4s._

import com.amazonaws.auth.{AWSCredentialsProviderChain, BasicAWSCredentials, EC2ContainerCredentialsProviderWrapper}
import com.amazonaws.internal.StaticCredentialsProvider

import cats.~>
import cats.effect.{Effect, IO}
import cats.implicits._

import java.nio.file.{Path, Paths}
import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService, ThreadFactory}
import javax.net.ssl.SSLContext

import journal.Logger

import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 *
 */
final case class GithubConfig(
  domain: Option[Uri],
  clientId: String,
  clientSecret: String,
  redirectUri: String,
  scope: String,
  systemAccessToken: AccessToken,
  systemUsername: String,
  organizationBlacklist: List[String],
  organizationAdminList: List[String]
){
  def isEnterprise: Boolean = domain.nonEmpty

  val oauth = domain match {
    case None => Uri.uri("https://github.com")
    case Some(uri) => Uri.unsafeFromString(s"https://${uri.toString}")
  }

  val api = domain match {
    case None => Uri.uri("https://api.github.com")
    case Some(uri) => Uri.unsafeFromString(s"https://${(uri / "api" / "v3").toString}")
  }

  val tokenEndpoint = oauth / "login" / "oauth" / "access_token"

  val loginEndpoint = {
    val queryParams = Map(
      ("client_id", List(clientId)),
      ("redirect_uri", List(redirectUri)),
      ("scope", List(scope))
    )
    (oauth / "login" / "oauth" / "authorize").setQueryParams(queryParams)
  }

  val userEndpoint = api / "user"

  val userOrgsEndpoint = userEndpoint / "orgs"

  def orgEndpoint(login: String) = api / "orgs" / login

  def repoEndpoint(page: Int = 1) = {
    val queryParams = Map(
      ("affiliation", List("owner,organization_member")),
      ("visibility", List("all")),
      ("direction", List("asc")),
      ("page", List(page.toString))
    )

    (api / "user" / "repos").setQueryParams(queryParams)
  }

  def webhookEndpoint(slug: Slug) =
    api / "repos" / slug.owner / slug.repository / "hooks"

  def contentsEndpoint(slug: Slug, path: String) =
    api / "repos" / slug.owner / slug.repository / "contents" / path

  def deploymentEndpoint(slug: Slug, deploymentId: Long) =
    api / "repos" / slug.owner / slug.repository / "deployments" / deploymentId.toString

  private [nelson] def encodeURI(uri: String): String =
    java.net.URLEncoder.encode(uri, "UTF-8")
}

/**
 * configuration options for the docker cli controller.
 */
final case class DockerConfig(
  connection: String,
  verifyTLS: Boolean
)

final case class NomadConfig(
  applicationPrefix: Option[String],
  requiredServiceTags: Option[List[String]]
)

/**
 * specify the settings for the network configuration of
 * nelson: what ports and addresses are bound, versus which
 * are used for remote callers.
 */
final case class NetworkConfig(
  bindHost: String,
  bindPort: Int,
  externalHost: String,
  externalPort: Int,
  tls: Boolean,
  monitoringPort: Int,
  idleTimeout: Duration
)

final case class DatabaseConfig(
  driver: String,
  connection: String,
  username: Option[String],
  password: Option[String],
  maxConnections: Option[Int]
)

import nelson.crypto.{AuthEnv, TokenAuthenticator}
import scodec.bits.ByteVector

final case class SecurityConfig(
  encryptionKeyBase64: String,
  signingKeyBase64: String,
  expireLoginAfter: Duration,
  useEnvironmentSession: Boolean
){
  private val rng = new java.security.SecureRandom

  val env = AuthEnv.instance(
    encryptKey = ByteVector.view(java.util.Base64.getDecoder.decode(encryptionKeyBase64)),
    sigKey = ByteVector.view(java.util.Base64.getDecoder.decode(signingKeyBase64)),
    getNextNonce = IO(crypto.Nonce.fromSecureRandom(rng))
  )

  val authenticator: TokenAuthenticator[String, Session] =
    Session.authenticatorForEnv(env)
}

final case class ManifestConfig(
  filename: String
)

/**
 * controls how the workflow pipeline executes, and what
 * concurrency limits are in place.
 */
final case class PipelineConfig(
  concurrencyLimit: Int,
  bufferLimit: Int
)

final case class AuditConfig(
  concurrencyLimit: Int,
  bufferLimit: Int
)

final case class TemplateConfig(
  tempDir: Path,
  memoryMegabytes: Int,
  cpuPeriod: Int,
  cpuQuota: Int,
  timeout: FiniteDuration,
  templateEngineImage: String,
  vaultAddress: Option[String]
)

final case class WorkflowLoggerConfig(
  bufferLimit: Int,
  filePath: java.nio.file.Path
)

final case class SlackConfig(
  webhook: Uri,
  username: String
)

final case class EmailConfig(
  host: String,
  port: Int,
  auth: javax.mail.Authenticator,
  from: EmailAddress,
  useSSL: Boolean = true
)

final case class CacheConfig(
  stackStatusCache: Cache[(String,String,String), DeploymentStatus]
)

final case class ExpirationPolicyConfig(
  defaultPeriodic: ExpirationPolicy,
  defaultNonPeriodic: ExpirationPolicy
)

import java.net.URI
import fs2.async.boundedQueue
import fs2.async.mutable.Queue

final case class Pools(defaultPool: ExecutorService,
                       serverPool: ExecutorService,
                       schedulingPool: ScheduledExecutorService) {
  val defaultExecutor = ExecutionContext.fromExecutorService(defaultPool)
  val serverExecutor = ExecutionContext.fromExecutorService(serverPool)
  val schedulingExecutor = ExecutionContext.fromExecutorService(schedulingPool)
}

object Pools {
  def daemonThreads(name: String) = new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  }

  def default: Pools = {
    val defaultPool: ExecutorService =
      Executors.newFixedThreadPool(8, daemonThreads("nelson-thread"))

    val serverPool: ExecutorService =
      Executors.newCachedThreadPool(daemonThreads("nelson-server"))

    val schedulingPool: ScheduledExecutorService =
      Executors.newScheduledThreadPool(4, daemonThreads("nelson-scheduled-tasks"))

    Pools(defaultPool,
          serverPool,
          schedulingPool)
  }
}

final case class Interpreters(
  git: Github.GithubOp ~> IO,
  storage: StoreOp ~> IO,
  slack: Option[SlackOp ~> IO],
  email: Option[EmailOp ~> IO]
)

/**
 * Configuration for banning/allowing various clients.
 *
 * @param httpUserAgents
 */

final case class BannedClientsConfig(
  httpUserAgents: List[BannedClientsConfig.HttpUserAgent]
)

object BannedClientsConfig {
  final case class HttpUserAgent(
    name: String,
    maxBannedVersion: Option[Version])
}

/*
 * allowed list or ports that a proxy can expose
 */
final case class ProxyPortWhitelist(ports: List[Int])

/*
 * Configuration for the cleanup pipeline
 */
final case class CleanupConfig(
  initialTTL: Duration,
  extendTTL: Duration,
  cleanupDelay: FiniteDuration,
  sweeperDelay: FiniteDuration
)

final case class DeploymentMonitorConfig(
  delay: FiniteDuration
)

/**
 * Configuration for servicing the user interface
 *
 * @param filePath Location of the UI static files on the filesystem.
 */
final case class UIConfig(
  enabled: Boolean,
  filePath: Option[String]
)

/** Configuration for auto-generated Vault policies */
final case class PolicyConfig(
  resourceCredsPath: String,
  pkiPath: Option[String]
)

/**
 * Global configuration for all the various external inputs that Nelson
 * actually cares about.
 */
final case class NelsonConfig(
  git: GithubConfig,
  network: NetworkConfig,
  security: SecurityConfig,
  database: DatabaseConfig,
  dockercfg: DockerConfig,
  nomadcfg: NomadConfig,
  manifest: ManifestConfig,
  timeout: Duration,
  cleanup: CleanupConfig,
  deploymentMonitor: DeploymentMonitorConfig,
  datacenters: List[Datacenter],
  pipeline: PipelineConfig,
  audit: AuditConfig,
  template: TemplateConfig,
  http: Client[IO],
  pools: Pools,
  interpreters: Interpreters,
  workflowLogger: WorkflowLogger,
  bannedClients: Option[BannedClientsConfig],
  ui: UIConfig,
  proxyPortWhitelist: Option[ProxyPortWhitelist],
  defaultNamespace: NamespaceName,
  expirationPolicy: ExpirationPolicyConfig,
  discoveryDelay: FiniteDuration,
  queue: Queue[IO, Manifest.Action],
  auditQueue: Queue[IO, AuditEvent[_]]
){

  val log = Logger[NelsonConfig.type]

  lazy val storage = interpreters.storage

  lazy val github = interpreters.git

  lazy val slack = interpreters.slack

  lazy val email = interpreters.email

  lazy val auditor = new Auditor(auditQueue, git.systemUsername)

  // i've currently assigned these pretty arbitrary values
  // but this should protect nelson from really hammering
  // its own database.
  lazy val caches = CacheConfig(
    stackStatusCache = Cache(
      maximumSize = Some(100),
      expireAfterAccess = Some(15.minutes)
    )
  )

  //////////////////////// THREADING ////////////////////////////

  def datacenter(dc: String): IO[Datacenter] =
    datacenters.find(_.name == dc).fold[IO[Datacenter]](IO.raiseError(MisconfiguredDatacenter(dc, s"Datacenter not configured.")))(IO.pure)
}

import knobs.{Config => KConfig}
import doobie.imports._

object Config {

  private[this] val log = Logger[Config.type]

  def readConfig(cfg: KConfig, httpBuilder: BlazeClientConfig => IO[Client[IO]], xa: DatabaseConfig => Transactor[IO]): IO[NelsonConfig] = {
    // TIM: Don't turn this on for any deployed version; it will dump all the credentials
    // into the log, so be careful.
    // log.debug("configured with the following knobs:")
    // log.debug(cfg.toString)

    val timeout = cfg.require[FiniteDuration]("nelson.timeout")
    val http = httpBuilder(BlazeClientConfig.defaultConfig.copy(requestTimeout = timeout))

    val pools = Pools.default

    val nomadcfg = readNomad(cfg.subconfig("nelson.nomad"))

    val gitcfg = readGithub(cfg.subconfig("nelson.github"))

    val workflowConf = readWorkflowLogger(cfg.subconfig("nelson.workflow-logger"))
    val workflowlogger =
      boundedQueue[IO, (ID,String)](workflowConf.bufferLimit)(Effect[IO], pools.defaultExecutor).
        map(new WorkflowLogger(_, workflowConf.filePath))

    val databasecfg = readDatabase(cfg.subconfig("nelson.database"))
    val storage = new nelson.storage.H2Storage(xa(databasecfg))

    val email = readEmail(cfg.subconfig("nelson.email")).map(new EmailServer(_))

    val cleanup = readCleanup(cfg.subconfig("nelson.cleanup"))

    val deploymentMonitor = cfg.require[FiniteDuration]("nelson.readiness-delay")

    val discoveryDelay = cfg.require[FiniteDuration]("nelson.discovery-delay")

    val dockercfg = readDocker(cfg.subconfig("nelson.docker"))

    val whitelist = cfg.lookup[List[Int]]("nelson.proxy-port-whitelist").map(ProxyPortWhitelist)

    val nsStr = cfg.require[String]("nelson.default-namespace")
    val defaultNS = NamespaceName.fromString(nsStr).toOption.yolo(s"unable to parse $nsStr into a namespace")

    val expirationPolicy = readExpirationPolicy(cfg.subconfig("nelson.expiration-policy"))

    val manifestcfg = cfg.require[String]("nelson.manifest-filename")

    for {
      wflogger <- workflowlogger
      dcs      <- readDatacenters(
        cfg = cfg.subconfig("nelson.datacenters"),
        nomadcfg = nomadcfg,
        dockercfg = dockercfg,
        schedulerPool = pools.schedulingPool,
        ec = pools.defaultExecutor,
        stg = storage,
        logger = wflogger
      )
      pipeline   =  readPipeline(cfg.subconfig("nelson.pipeline"))
      queue      <- boundedQueue[IO, Manifest.Action](pipeline.bufferLimit)(Effect[IO], pools.defaultExecutor)

      audit      =  readAudit(cfg.subconfig("nelson.audit"))
      auditQueue <- boundedQueue[IO, AuditEvent[_]](audit.bufferLimit)(Effect[IO], pools.defaultExecutor)
      httpClient <- http
      gitClient  = new Github.GithubHttp(gitcfg, httpClient, timeout, pools.defaultExecutor)
      slackClient = readSlack(cfg.subconfig("nelson.slack")).map(new SlackHttp(_, httpClient))
    } yield {
      NelsonConfig(
        git                = gitcfg,
        network            = readNetwork(cfg.subconfig("nelson.network")),
        security           = readSecurity(cfg.subconfig("nelson.security")),
        database           = databasecfg,
        dockercfg          = dockercfg,
        nomadcfg           = nomadcfg,
        manifest           = ManifestConfig(manifestcfg),
        timeout            = timeout,
        cleanup            = cleanup,
        deploymentMonitor  = DeploymentMonitorConfig(deploymentMonitor),
        datacenters        = dcs,
        pipeline           = pipeline,
        audit              = audit,
        template           = readTemplate(cfg),
        http               = httpClient,
        pools              = pools,
        interpreters       = Interpreters(gitClient,storage,slackClient,email),
        workflowLogger     = wflogger,
        bannedClients      = readBannedClients(cfg.subconfig("nelson.banned-clients")),
        ui                 = readUI(cfg.subconfig("nelson.ui")),
        proxyPortWhitelist = whitelist,
        defaultNamespace   = defaultNS,
        expirationPolicy   = expirationPolicy,
        discoveryDelay     = discoveryDelay,
        queue              = queue,
        auditQueue         = auditQueue
      )
    }
  }

  /* Here we're ok to require fields, because they are always specified in
     defaults.cfg. functioanltiy is affected by ui.enabled in the config */
  private[nelson] def readUI(cfg: KConfig): UIConfig =
    UIConfig(
      enabled  = cfg.require[Boolean]("enabled"),
      filePath = cfg.lookup[String]("file-path")
    )

  private[nelson] def readAudit(cfg: KConfig): AuditConfig =
    AuditConfig(
      concurrencyLimit = cfg.require[Int]("concurrency-limit"),
      bufferLimit = cfg.require[Int]("inbound-buffer-limit")
    )

  private[nelson] def readTemplate(cfg: KConfig): TemplateConfig = {
    val dcCfg = cfg.subconfig("nelson.datacenters")
    val firstDcId = dcCfg.env.keys.toVector.sorted.headOption.flatMap(_.toString.split('.').headOption)
    val vaultAddress = firstDcId.flatMap(dcCfg.subconfig(_).lookup[String]("infrastructure.vault.endpoint"))

    val tCfg = cfg.subconfig("nelson.template")
    TemplateConfig(
      tempDir = Paths.get(tCfg.require[String]("temp-dir")),
      memoryMegabytes = tCfg.require[Int]("memory-mb"),
      cpuPeriod = tCfg.require[Int]("cpu-period"),
      cpuQuota = tCfg.require[Int]("cpu-quota"),
      timeout = tCfg.require[FiniteDuration]("timeout"),
      templateEngineImage = tCfg.require[String]("template-engine-image"),
      vaultAddress = vaultAddress
    )
  }

  private[nelson] def readWorkflowLogger(cfg: KConfig): WorkflowLoggerConfig =
    WorkflowLoggerConfig(
      bufferLimit = cfg.require[Int]("inbound-buffer-limit"),
      filePath = java.nio.file.Paths.get(cfg.require[String]("file-path"))
    )

  private[nelson] def readPipeline(cfg: KConfig): PipelineConfig =
    PipelineConfig(
      concurrencyLimit = cfg.require[Int]("concurrency-limit"),
      bufferLimit = cfg.require[Int]("inbound-buffer-limit")
    )

  private[nelson] def readDatacenters(cfg: KConfig,
                                      nomadcfg: NomadConfig,
                                      dockercfg: DockerConfig,
                                      schedulerPool: ScheduledExecutorService,
                                      ec: ExecutionContext,
                                      stg: StoreOp ~> IO,
                                      logger: LoggingOp ~> IO): IO[List[Datacenter]] = {

    def readNomadInfrastructure(kfg: KConfig): Option[Infrastructure.Nomad] = {
      (kfg.lookup[String]("endpoint"),
       kfg.lookup[Duration]("timeout"),
       kfg.lookup[String]("docker.user"),
       kfg.lookup[String]("docker.password"),
       kfg.lookup[String]("docker.host"),
       kfg.lookup[Int]("mhz-per-cpu")
        ).mapN((a,b,c,d,e,f) => {
          val uri = Uri.fromString(a).toOption.yolo(s"nomad.endpoint -- $a -- is an invalid Uri")
          Infrastructure.Nomad(uri,b,c,d,e,f)
        })
    }

    def readKubernetesOutClusterParams(kfg: KConfig): Option[KubernetesMode] =
      kfg.lookup[String]("kubeconfig").map(kubeconfig => KubernetesMode.OutCluster(Paths.get(kubeconfig)))

    def readKubernetesInfrastructure(kfg: KConfig): Option[Infrastructure.Kubernetes] = for {
      inCluster <- kfg.lookup[Boolean]("in-cluster")
      mode      <- if (inCluster) Some(KubernetesMode.InCluster) else readKubernetesOutClusterParams(kfg)
      timeout   <- kfg.lookup[FiniteDuration]("timeout")
    } yield Infrastructure.Kubernetes(mode, timeout)

    def readNomadScheduler(kfg: KConfig): IO[SchedulerOp ~> IO] =
      readNomadInfrastructure(kfg) match {
        case Some(n) => http4sClient(n.timeout).map(client => new scheduler.NomadHttp(nomadcfg, n, client, schedulerPool, ec))
        case None    => IO.raiseError(new IllegalArgumentException("Unable to parse the nomad scheduler configuration"))
      }

    @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NoNeedForMonad"))
    def readDatacenter(id: String, kfg: KConfig): IO[Datacenter] = {
      val proxyCreds =
        (kfg.lookup[String](s"proxy-credentials.username"),
          kfg.lookup[String](s"proxy-credentials.password")
        ).mapN((a,b) => Infrastructure.ProxyCredentials(a,b))

      val dockerClient = InstrumentedDockerClient(dockercfg.connection, new Docker(dockercfg, schedulerPool, ec))

      val lb = readAwsInfrastructure(kfg.subconfig("infrastructure.loadbalancer.aws")).map(cfg => new loadbalancers.Aws(cfg))

      val vault =
        (for {
          token <- kfg.lookup[String]("infrastructure.vault.auth-token")
          endpoint <- kfg.lookup[String]("infrastructure.vault.endpoint")
          timeout <- kfg.lookup[Duration]("infrastructure.vault.timeout")
          prefix = kfg.lookup[String]("infrastructure.vault.auth-role-prefix")
          endpointUri = Uri.fromString(endpoint).valueOr(throw _) // YOLO
        } yield {
          val client = http4sClient(timeout)
          val rawClient = client.map(c => new Http4sVaultClient(Token(token), endpointUri, c, prefix))
          rawClient.map(rc => InstrumentedVaultClient(endpoint, rc))
        }).yolo("We really really need vault. Seriously vault must be configured")

      /*
       * Build the internal consul client. In order to make sure this is actually running,
       * your configuration needs to enable the feature, you must specify a specific endpoint
       * to talk to Consul.  If this field is absent, then StubbedConsulClient will be
       * used in place of the real interpreter.
       */
      def consulRouting: IO[helm.ConsulOp ~> IO] = {
        // if you're using consul, you must specify the timeout and the endpoint.
        // these are non-optional.
        val a = kfg.require[String]("infrastructure.consul.endpoint")
        val b = kfg.require[Duration]("infrastructure.consul.timeout")
        val c = kfg.lookup[String]("infrastructure.consul.acl-token")
        val d = kfg.lookup[String]("infrastructure.consul.username")
        val e = kfg.lookup[String]("infrastructure.consul.password")
        val client = http4sClient(b, 20)

        val http4sConsul = (d,e) match {
          case (None,None) => client.map(consulClient => Http4sConsul.client(Infrastructure.Consul(new URI(a), b, c, None), consulClient))
          case (Some(u),Some(pw)) =>
            client.map(consulClient => Http4sConsul.client(Infrastructure.Consul(new URI(a), b, c,
              Some(Infrastructure.Credentials(u,pw))), consulClient))
          case _ =>
            log.error("If you configure the datacenter to have a consul username, or consul password, it must have both.")
            client.map(consulClient => Http4sConsul.client(Infrastructure.Consul(new URI(a), b, c, None), consulClient))
        }
        http4sConsul.map(consulClient => PrometheusConsul(a, consulClient))
      }

      def withKubectl[A](f: (Kubectl, FiniteDuration) => IO[A]): IO[A] =
        readKubernetesInfrastructure(kfg.subconfig("infrastructure.kubernetes")) match {
          case Some(Infrastructure.Kubernetes(mode, timeout)) =>
            val kubectl = new Kubectl(mode)
            f(kubectl, timeout)
          case None => IO.raiseError(new IllegalArgumentException("both 'in-cluster' and 'timeout' must be specified when using the kubernetes scheduler"))
        }

      val scheduling: IO[SchedulerOp ~> IO] = kfg.lookup[String]("infrastructure.scheduler") match {
        case Some("kubernetes") => {
          withKubectl((kubectl, timeout) =>
            IO.pure(new KubernetesShell(kubectl, timeout, ec, schedulerPool)))
        }
        case Some("nomad") => IO.raiseError(NomadNotImplemented)
        case _ => IO.raiseError(new IllegalArgumentException("At least one scheduler must be defined per datacenter"))
      }

      // however you decide to do routing, you need to account for how you figure out
      // if a given stack is "ready" or is still "warming"
      val routing: IO[(helm.ConsulOp ~> IO, health.HealthCheckOp ~> IO)] =
        kfg.lookup[String]("infrastructure.routing") match {
          case Some("consul") | Some("consul:lighthouse") => for {
            a <- consulRouting
            b <- IO.pure(health.Http4sConsulHealthClient(a))
          } yield (a,b)

          case Some("kubernetes") => for {
            a <- IO.pure(StubbedConsulClient)
            b <- withKubectl((kubectl, timeout) =>
              IO.pure(new KubernetesHealthClient(kubectl, timeout, ec, schedulerPool)))
          } yield (a,b)

          case Some("noop") | None => IO.pure((StubbedConsulClient, health.StubbedHealthClient))
          case Some(unknown) => IO.raiseError(new IllegalArgumentException(s"The specified routing configuration '${unknown}' is not a valid routing sub-system"))
      }

      val interpreters = for {
        sched <- scheduling
        expand <- routing
        (router, healthChecker) = expand
        vv <- vault
      } yield {
        Infrastructure.Interpreters(
          scheduler = sched,
          consul = router,
          vault = vv,
          storage = stg,
          logger = logger,
          docker = dockerClient,
          control = WorkflowControlOp.trans,
          health = healthChecker
        )
      }

      val trafficShift = readTrafficShift(kfg.subconfig("traffic-shift"))

      interpreters.map { interp =>
        Datacenter(
          name = id,
          docker = Infrastructure.Docker(kfg.require[String]("docker-registry")),
          domain = Infrastructure.Domain(kfg.require[String]("domain")),
          defaultTrafficShift = trafficShift,
          proxyCredentials = proxyCreds,
          interpreters = interp,
          loadbalancer = lb,
          policy = readPolicy(kfg.subconfig("policy"))
        )
      }
    }

    val ids: Vector[String] = cfg.env.keys.map(_.toString.split('.')(0)).toVector
    ids.traverse { id => readDatacenter(id, cfg.subconfig(id)) }.map(_.toList)
  }

  def readAwsInfrastructure(kfg: KConfig): Option[Infrastructure.Aws] = {
    import com.amazonaws.regions.Region
    import com.amazonaws.regions.RegionUtils
    import loadbalancers.ElbScheme

    def lookupRegion(k: KConfig): Option[Region] = {
      k.lookup[String]("region").flatMap(name =>
        Option(RegionUtils.getRegion(name))
      )
    }

    /*
     * if no scheme is supplied, then the default is to assume internet-facing
     * loadbalancers, maintaining the existing default functionality.
     */
    def lookupElbScheme(k: KConfig): Option[ElbScheme] =
      k.lookup[Boolean]("use-internal-elb").map(useInternal =>
        if(useInternal) ElbScheme.Internal
        else ElbScheme.External
      )

    def readAvailabilityZone(id: String, kfg: KConfig): Infrastructure.AvailabilityZone = {
      val priv = kfg.require[String]("private-subnet")
      val pub = kfg.require[String]("public-subnet")
      Infrastructure.AvailabilityZone(id,priv,pub)
    }

    def readAvailabilityZones(kfg: KConfig): Set[Infrastructure.AvailabilityZone] = {
      val ids: Vector[String] = kfg.env.keys.map(_.toString.split('.')(0)).toVector
      ids.map(id => readAvailabilityZone(id, kfg.subconfig(id))).toSet
    }

    val zones = readAvailabilityZones(kfg.subconfig("availability-zones"))

    def buildProviderChain(kfg: KConfig): AWSCredentialsProviderChain = {
      val basic = for {
        ak <- kfg.lookup[String]("access-key-id")
        sk <- kfg.lookup[String]("secret-access-key")
      } yield new StaticCredentialsProvider(new BasicAWSCredentials(ak, sk))

      val ec2Discovery = Option(new EC2ContainerCredentialsProviderWrapper())

      new AWSCredentialsProviderChain((basic :: ec2Discovery :: Nil).flatten: _*)
    }

    val creds = buildProviderChain(kfg)

    (lookupRegion(kfg),
     kfg.lookup[String]("launch-configuration-name"),
     kfg.lookup[List[String]]("elb-security-group-names"),
     lookupElbScheme(kfg) orElse Some(ElbScheme.External)
    ).mapN((a,b,c,d) => Infrastructure.Aws(creds,a,b,c.toSet,zones,kfg.lookup[String]("image"),d))
  }

  private def readNomad(cfg: KConfig): NomadConfig =
    NomadConfig(
      applicationPrefix = cfg.lookup[String]("application-prefix"),
      requiredServiceTags = cfg.lookup[List[String]]("required-service-tags")
    )

  private def readDocker(cfg: KConfig): DockerConfig =
    DockerConfig(
      connection = cfg.require[String]("connection"),
      verifyTLS = cfg.require[Boolean]("verify-tls")
    )

  private def readDatabase(cfg: KConfig): DatabaseConfig =
    DatabaseConfig(
      driver     = cfg.require[String]("driver"),
      connection = cfg.require[String]("connection"),
      username = cfg.lookup[String]("username"),
      password = cfg.lookup[String]("password"),
      maxConnections = cfg.lookup[Int]("max-connections")
    )

  private def readSecurity(cfg: KConfig): SecurityConfig =
    SecurityConfig(
      encryptionKeyBase64 = cfg.require[String]("encryption-key"),
      signingKeyBase64 = cfg.require[String]("signature-key"),
      expireLoginAfter = cfg.require[Duration]("expire-login-after"),
      useEnvironmentSession = cfg.require[Boolean]("use-environment-session")
    )

  private def readNetwork(cfg: KConfig): NetworkConfig =
    NetworkConfig(
      bindHost = cfg.require[String]("bind-host"),
      bindPort = cfg.require[Int]("bind-port"),
      externalHost = cfg.require[String]("external-host"),
      externalPort = cfg.require[Int]("external-port"),
      tls = cfg.require[Boolean]("enable-tls"),
      monitoringPort = cfg.require[Int]("monitoring-port"),
      idleTimeout = cfg.require[Duration]("idle-timeout")
    )

  private def readGithub(cfg: KConfig): GithubConfig =
    GithubConfig(
      domain = cfg.lookup[String]("domain").map(Uri.unsafeFromString),
      clientId = cfg.require[String]("client-id"),
      clientSecret = cfg.require[String]("client-secret"),
      redirectUri = cfg.require[String]("redirect-uri"),
      scope = cfg.require[String]("scope"),
      systemAccessToken = AccessToken(cfg.require[String]("access-token")),
      systemUsername = cfg.require[String]("system-username"),
      organizationBlacklist = cfg.lookup[List[String]]("organization-blacklist").getOrElse(Nil),
      organizationAdminList = cfg.lookup[List[String]]("organization-admins").getOrElse(Nil)
    )

  private def readSlack(cfg: KConfig): Option[SlackConfig] = {
    for {
      w <- cfg.lookup[String]("webhook-url").flatMap(raw => Uri.fromString(raw).toOption)
      u <- cfg.lookup[String]("username")
    } yield SlackConfig(w,u)
  }

  private def readEmail(cfg: KConfig): Option[EmailConfig] = {
    import org.apache.commons.mail.DefaultAuthenticator
    for {
      host <- cfg.lookup[String]("host")
      port <- cfg.lookup[Int]("port")
      from <- cfg.lookup[String]("from")
      user <- cfg.lookup[String]("user")
      pass <- cfg.lookup[String]("password")
    } yield EmailConfig(host,port,new DefaultAuthenticator(user,pass),from)
  }

  private def readBannedClients(cfg: KConfig): Option[BannedClientsConfig] = {

    def parse(s: String): Option[BannedClientsConfig.HttpUserAgent] = {
      import BannedClientsConfig.HttpUserAgent
      val splitted = s.split("/")
      val name = splitted.head // yolo, but safe
      val maybeVersion = splitted.drop(1).headOption

      maybeVersion.fold(Option(HttpUserAgent(name, None))) { version =>
        Version.fromString(version).map(version => HttpUserAgent(name, Some(version)))
      }
    }
    cfg.lookup[List[String]]("http-user-agents").map { agents =>
      val httpUserAgents: List[HttpUserAgent] = agents
        .map(x =>
          parse(x).getOrElse(
            throw new Exception(
              "configuration contains invalid user-agent blacklist"))
        )
      BannedClientsConfig(httpUserAgents = httpUserAgents)
    }
  }

  private def readCleanup(cfg: KConfig): CleanupConfig =
    CleanupConfig(
      initialTTL  = cfg.require[Duration]("initial-deployment-time-to-live"),
      extendTTL   = cfg.require[Duration]("extend-deployment-time-to-live"),
      cleanupDelay = cfg.require[FiniteDuration]("cleanup-delay"),
      sweeperDelay = cfg.require[FiniteDuration]("sweeper-delay")
    )

  private def readTrafficShift(cfg: KConfig): Infrastructure.TrafficShift = {
    val ref = cfg.require[String]("policy")
    val policy = TrafficShiftPolicy.fromString(ref).yolo(s"invalid traffic shift policy $ref")
    val dur = cfg.require[FiniteDuration]("duration")
    Infrastructure.TrafficShift(policy, dur)
  }

  private def readExpirationPolicy(cfg: KConfig): ExpirationPolicyConfig = {
    val a = cfg.lookup[String]("default-periodic")
    val aa = a.flatMap(str => ExpirationPolicy.fromString(str)).getOrElse(cleanup.RetainLatest)
    val b = cfg.lookup[String]("default-non-periodic")
    val bb = b.flatMap(str => ExpirationPolicy.fromString(str)).getOrElse(cleanup.RetainActive)
    ExpirationPolicyConfig(
      defaultPeriodic = aa,
      defaultNonPeriodic = bb
    )
  }

  private def readPolicy(cfg: KConfig): PolicyConfig =
    PolicyConfig(
      resourceCredsPath = cfg.lookup[String]("resource-creds-path").getOrElse("nelson/%env%/%resource%/creds/%unit%"),
      pkiPath = cfg.lookup[String]("pki-path")
    )

  private def http4sClient(timeout: Duration, maxTotalConnections: Int = 10, sslContext: Option[SSLContext] = None): IO[Client[IO]] = {
    val config = BlazeClientConfig.defaultConfig.copy(
      requestTimeout = timeout,
      maxTotalConnections = maxTotalConnections,
      sslContext = sslContext
    )
    Http1Client(config)
  }
}
