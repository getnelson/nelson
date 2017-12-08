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

import java.nio.file.{Path, Paths}
import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService, ThreadFactory}

import journal.Logger
import nelson.BannedClientsConfig.HttpUserAgent
import nelson.cleanup.ExpirationPolicy
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze._
import storage.StoreOp
import logging.{WorkflowLogger,LoggingOp}
import audit.{Auditor,AuditEvent}
import notifications.{SlackHttp,SlackOp,EmailOp,EmailServer}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scalaz.Scalaz._
import scalaz.concurrent.Strategy
import scalaz.~>
import docker.Docker
import scheduler.SchedulerOp
import vault._
import vault.http4s._

/**
 *
 */
final case class GithubConfig(
  domain: Option[String],
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

  val oauth =
    "https://"+ domain.fold("github.com")(identity)

  val api =
    "https://"+ domain.fold("api.github.com")(_+"/api/v3")

  val tokenEndpoint =
    s"${oauth}/login/oauth/access_token"

  val loginEndpoint =
    s"${oauth}/login/oauth/authorize?client_id=${clientId}&redirect_uri=${encodeURI(redirectUri)}&scope=${scope}"

  val userEndpoint =
    s"${api}/user"

  val userOrgsEndpoint =
    s"${userEndpoint}/orgs"

  def orgEndpoint(login: String) =
    s"${api}/orgs/${login}"

  def repoEndpoint(page: Int = 1) =
    s"${api}/user/repos?affiliation=owner,organization_member&visibility=all&direction=asc&page=${page}"

  def webhookEndpoint(slug: Slug) =
    s"${api}/repos/${slug}/hooks"

  def contentsEndpoint(slug: Slug, path: String) =
    s"${api}/repos/${slug}/contents/${path}"

  def releaseEndpoint(slug: Slug, releaseId: Long) =
    s"${api}/repos/${slug}/releases/${releaseId}"

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
  monitoringPort: Int
)

final case class DatabaseConfig(
  driver: String,
  connection: String,
  username: Option[String],
  password: Option[String],
  maxConnections: Option[Int]
)

import crypto.{AuthEnv, TokenAuthenticator}
import scodec.bits.ByteVector
import scalaz.concurrent.Task

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
    getNextNonce = Task.delay(crypto.Nonce.fromSecureRandom(rng))
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
  consulTemplateImage: String,
  vaultAddress: Option[String]
)

final case class WorkflowLoggerConfig(
  bufferLimit: Int,
  filePath: java.nio.file.Path
)

final case class SlackConfig(
  webhook: String,
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
import dispatch.Http
import scalaz.stream.async.boundedQueue
import scalaz.stream.async.mutable.Queue

final case class Pools(defaultPool: ExecutorService,
                       serverPool: ExecutorService,
                       schedulingPool: ScheduledExecutorService) {
  val defaultExecutor = Strategy.Executor(defaultPool)
  val defaultEC: ExecutionContext = ExecutionContext.fromExecutor(defaultPool)
  val serverExecutor = Strategy.Executor(serverPool)
  val schedulingExecutor: Strategy = Strategy.Executor(schedulingPool)
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

    val defaultExecutor: Strategy =
      Strategy.Executor(defaultPool)

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
  git: Github.GithubOp ~> Task,
  storage: StoreOp ~> Task,
  slack: Option[SlackOp ~> Task],
  email: Option[EmailOp ~> Task]
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
  cleanupDelay: Duration,
  sweeperDelay: Duration
)

final case class DeploymentMonitorConfig(
  delay: Duration
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
  domains: List[Domain],
  pipeline: PipelineConfig,
  audit: AuditConfig,
  template: TemplateConfig,
  http: Http,
  pools: Pools,
  interpreters: Interpreters,
  workflowLogger: WorkflowLogger,
  bannedClients: Option[BannedClientsConfig],
  ui: UIConfig,
  proxyPortWhitelist: Option[ProxyPortWhitelist],
  defaultNamespace: NamespaceName,
  expirationPolicy: ExpirationPolicyConfig,
  discoveryDelay: Duration
){

  val log = Logger[NelsonConfig.type]

  lazy val storage = interpreters.storage

  lazy val github = interpreters.git

  lazy val slack = interpreters.slack

  lazy val email = interpreters.email

  lazy val queue: Queue[Manifest.Action] =
    boundedQueue(pipeline.bufferLimit)(pools.defaultExecutor)

  lazy val auditQueue: Queue[AuditEvent[_]] =
    boundedQueue(audit.bufferLimit)(pools.defaultExecutor)

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

  def domain(dc: String): Task[Domain] =
    domains.find(_.name == dc).fold[Task[Domain]](Task.fail(MisconfiguredDomain(dc, s"Domain not configured.")))(Task.now)
}

import knobs.{Config => KConfig}
import doobie.imports._

object Config {

  private[this] val log = Logger[Config.type]

  def readConfig(cfg: KConfig, http: Http, xa: DatabaseConfig => Transactor[Task]): NelsonConfig = {
    // TIM: Don't turn this on for any deployed version; it will dump all the credentials
    // into the log, so be careful.
    // log.debug("configured with the following knobs:")
    // log.debug(cfg.toString)

    val timeout = cfg.require[Duration]("nelson.timeout")

    val http0: Http = http.configure(
      _.setAllowPoolingConnection(true)
      .setConnectionTimeoutInMs(timeout.toMillis.toInt))

    val pools = Pools.default

    val nomadcfg = readNomad(cfg.subconfig("nelson.nomad"))

    val gitcfg = readGithub(cfg.subconfig("nelson.github"))
    val git = new Github.GithubHttp(gitcfg, http0)

    val workflowConf = readWorkflowLogger(cfg.subconfig("nelson.workflow-logger"))
    val workflowlogger = new WorkflowLogger(
      boundedQueue[(ID,String)](workflowConf.bufferLimit)(pools.defaultExecutor),
      workflowConf.filePath)

    val databasecfg = readDatabase(cfg.subconfig("nelson.database"))
    val storage = new nelson.storage.H2Storage(xa(databasecfg))

    val slack = readSlack(cfg.subconfig("nelson.slack")).map(new SlackHttp(_, http))

    val email = readEmail(cfg.subconfig("nelson.email")).map(new EmailServer(_))

    val cleanup = readCleanup(cfg.subconfig("nelson.cleanup"))

    val deploymentMonitor = cfg.require[Duration]("nelson.readiness-delay")

    val discoveryDelay = cfg.require[Duration]("nelson.discovery-delay")

    val dockercfg = readDocker(cfg.subconfig("nelson.docker"))

    val whitelist = cfg.lookup[List[Int]]("nelson.proxy-port-whitelist").map(ProxyPortWhitelist)

    val nsStr = cfg.require[String]("nelson.default-namespace")
    val defaultNS = NamespaceName.fromString(nsStr).toOption.yolo(s"unable to parse $nsStr into a namespace")

    val expirationPolicy = readExpirationPolicy(cfg.subconfig("nelson.expiration-policy"))

    val manifestcfg = cfg.require[String]("nelson.manifest-filename")

    NelsonConfig(
      git               = gitcfg,
      network           = readNetwork(cfg.subconfig("nelson.network")),
      security          = readSecurity(cfg.subconfig("nelson.security")),
      database          = databasecfg,
      dockercfg         = dockercfg,
      nomadcfg          = nomadcfg,
      manifest          = ManifestConfig(manifestcfg),
      timeout           = timeout,
      cleanup           = cleanup,
      deploymentMonitor = DeploymentMonitorConfig(deploymentMonitor),
      domains       = readDomains(
        cfg = cfg.subconfig("nelson.domains"),
        nomadcfg = nomadcfg,
        dockercfg = dockercfg,
        ec = pools.defaultEC,
        exec = pools.defaultExecutor,
        stg = storage,
        logger = workflowlogger),
      pipeline           = readPipeline(cfg.subconfig("nelson.pipeline")),
      audit              = readAudit(cfg.subconfig("nelson.audit")),
      template           = readTemplate(cfg),
      http               = http,
      pools              = pools,
      interpreters       = Interpreters(git,storage,slack,email),
      workflowLogger     = workflowlogger,
      bannedClients      = readBannedClients(cfg.subconfig("nelson.banned-clients")),
      ui                 = readUI(cfg.subconfig("nelson.ui")),
      proxyPortWhitelist = whitelist,
      defaultNamespace   = defaultNS,
      expirationPolicy   = expirationPolicy,
      discoveryDelay     = discoveryDelay
    )
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
    val dcCfg = cfg.subconfig("nelson.domains")
    val firstDcId = dcCfg.env.keys.toVector.sorted.headOption.flatMap(_.toString.split('.').headOption)
    val vaultAddress = firstDcId.flatMap(dcCfg.subconfig(_).lookup[String]("infrastructure.vault.endpoint"))

    val tCfg = cfg.subconfig("nelson.template")
    TemplateConfig(
      tempDir = Paths.get(tCfg.require[String]("temp-dir")),
      memoryMegabytes = tCfg.require[Int]("memory-mb"),
      cpuPeriod = tCfg.require[Int]("cpu-period"),
      cpuQuota = tCfg.require[Int]("cpu-quota"),
      timeout = tCfg.require[FiniteDuration]("timeout"),
      consulTemplateImage = tCfg.require[String]("consul-template-image"),
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

  private[nelson] def readDomains(cfg: KConfig,
                                      nomadcfg: NomadConfig,
                                      dockercfg: DockerConfig,
                                      ec: ExecutionContext,
                                      exec: Strategy,
                                      stg: StoreOp ~> Task,
                                      logger: LoggingOp ~> Task): List[Domain] = {

    def readNomadInfrastructure(kfg: KConfig): Option[Infrastructure.Nomad] = {
      def readSplunk: Option[Infrastructure.SplunkConfig] =
        (kfg.lookup[String]("docker.splunk-url") |@| kfg.lookup[String]("docker.splunk-token")
          )((x,y) => Infrastructure.SplunkConfig(x,y))

      def readLoggingImage: Option[Docker.Image] =
        kfg.lookup[String]("logging-sidecar")
          .flatMap(a => docker.Docker.Image.fromString(a).toOption)

      (kfg.lookup[String]("endpoint") |@|
       kfg.lookup[Duration]("timeout") |@|
       kfg.lookup[String]("docker.user") |@|
       kfg.lookup[String]("docker.password") |@|
       kfg.lookup[String]("docker.host") |@|
       kfg.lookup[Int]("mhz-per-cpu")
        )((a,b,c,d,e,g) => {
          val splunk = readSplunk
          val loggingSidecar = readLoggingImage
          val uri = org.http4s.Uri.fromString(a).toOption.yolo(s"nomad.endpoint -- $a -- is an invalid Uri")
          Infrastructure.Nomad(uri,b,c,d,e,loggingSidecar,g,splunk)
        })
    }

    /*
     * Domains currently only support one scheduler
     */
    def readScheduler(kfg: KConfig, proxy: Option[Infrastructure.ProxyCredentials]): Option[SchedulerOp ~> Task] =
      readNomadInfrastructure(kfg.subconfig("nomad"))
        .map(n => new scheduler.NomadHttp(nomadcfg, n, http4sClient(n.timeout)))

    @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NoNeedForMonad"))
    def readDomain(id: String, kfg: KConfig): Domain = {
      val proxyCreds =
        (kfg.lookup[String](s"proxy-credentials.username") |@|
          kfg.lookup[String](s"proxy-credentials.password")
        )((a,b) => Infrastructure.ProxyCredentials(a,b))

      val consul = {
        val a = kfg.require[String]("infrastructure.consul.endpoint")
        val b = kfg.require[Duration]("infrastructure.consul.timeout")
        val c = kfg.lookup[String]("infrastructure.consul.acl-token")
        val d = kfg.lookup[String]("infrastructure.consul.username")
        val e = kfg.lookup[String]("infrastructure.consul.password")
        val client = http4sClient(b, 20)
        val http4sConsul = (d,e) match {
          case (None,None) => Http4sConsul.client(Infrastructure.Consul(new URI(a), b, c, None), client)
          case (Some(u),Some(pw)) => Http4sConsul.client(Infrastructure.Consul(new URI(a), b, c,
            Some(Infrastructure.Credentials(u,pw))), client)
          case _ =>
            log.error("If you configure the domain to have a consul username, or consul password, it must have both.")
            Http4sConsul.client(Infrastructure.Consul(new URI(a), b, c, None), client)
        }
        PrometheusConsul(a, http4sConsul)
      }

      val dockerClient = InstrumentedDockerClient(dockercfg.connection, new Docker(dockercfg))

      val lb = readAwsInfrastructure(kfg.subconfig("infrastructure.loadbalancer.aws")).map(cfg => new loadbalancers.Aws(cfg))

      val vault =
        (for {
          token <- kfg.lookup[String]("infrastructure.vault.auth-token")
          endpoint <- kfg.lookup[String]("infrastructure.vault.endpoint")
          timeout <- kfg.lookup[Duration]("infrastructure.vault.timeout")
          endpointUri = Uri.fromString(endpoint).valueOr(throw _) // YOLO
        } yield {
          val client = http4sClient(timeout)
          val rawClient = new Http4sVaultClient(Token(token), endpointUri, client)
          InstrumentedVaultClient(endpoint, rawClient)
        }).yolo("We really really need vault.  Seriously vault must be configured")

      val sched = readScheduler(kfg.subconfig("infrastructure.scheduler"), proxyCreds)
        .yolo("At least one scheduler must be defined per domain")

      val interpreters = Infrastructure.Interpreters(
        scheduler = sched,
        consul = consul,
        vault = vault,
        storage = stg,
        logger = logger,
        docker = dockerClient,
        control = WorkflowControlOp.trans)

      val trafficShift = readTrafficShift(kfg.subconfig("traffic-shift"))

      Domain(
        name = id,
        docker = Infrastructure.Docker(kfg.require[String]("docker-registry")),
        domain = Infrastructure.Domain(kfg.require[String]("domain")),
        defaultTrafficShift = trafficShift,
        proxyCredentials = proxyCreds,
        interpreters = interpreters,
        loadbalancer = lb,
        policy = readPolicy(kfg.subconfig("policy"))
      )
    }

    val ids: Vector[String] = cfg.env.keys.map(_.toString.split('.')(0)).toVector
    ids.map { id => readDomain(id, cfg.subconfig(id)) }.toList
  }

  def readAwsInfrastructure(kfg: KConfig): Option[Infrastructure.Aws] = {
    import com.amazonaws.regions.Region
    import com.amazonaws.regions.RegionUtils

    def lookupRegion(k: KConfig): Option[Region] = {
      k.lookup[String]("region").flatMap(name =>
        Option(RegionUtils.getRegion(name))
      )
    }

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

    (kfg.lookup[String]("access-key-id") |@|
     kfg.lookup[String]("secret-access-key") |@|
     lookupRegion(kfg) |@|
     kfg.lookup[String]("launch-configuration-name") |@|
     kfg.lookup[List[String]]("elb-security-group-names") |@|
     kfg.lookup[String]("image")
    )((a,b,c,d,e,f) => Infrastructure.Aws(a,b,c,d,e.toSet,zones,f))
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
      monitoringPort = cfg.require[Int]("monitoring-port")
    )

  private def readGithub(cfg: KConfig): GithubConfig =
    GithubConfig(
      domain = cfg.lookup[String]("domain"),
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
      w <- cfg.lookup[String]("webhook-url")
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

      maybeVersion.cata(
        none = Option(HttpUserAgent(name, None)),
        some = version =>
        Version.fromString(version)
          .map(version => HttpUserAgent(name, Some(version)))
      )
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
      cleanupDelay = cfg.require[Duration]("cleanup-delay"),
      sweeperDelay = cfg.require[Duration]("sweeper-delay")
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

  private def http4sClient(timeout: Duration, maxTotalConnections: Int = 10): Client = {
    val config = BlazeClientConfig.defaultConfig.copy(
      requestTimeout = timeout)
    PooledHttp1Client(maxTotalConnections = maxTotalConnections, config = config)
  }
}
