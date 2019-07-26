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

import nelson.blueprint.Blueprint

import cats.data.{EitherT, Kleisli, NonEmptyList, OptionT, ValidatedNel}
import cats.effect.IO
import cats.implicits._
import nelson.CatsHelpers._
import fs2.async.parallelTraverse
import java.time.Instant
import journal.Logger
import scala.collection.immutable.SortedMap

object Nelson {
  import Datacenter._
  import scala.concurrent.ExecutionContext
  import scala.concurrent.duration._
  import Json._
  import audit._
  import AuditableInstances._
  import nelson.storage.{StoreOp, StoreOpF}
  import Manifest.Versioned
  import health._

  /**
   * NelsonK[U] provides us with a context that accepts a configuration and gives us a cats IO[U]
   * NelsonFK[F[_[_], _], U] provides a context that accepts a configuration and gives us an F[IO, U]
   *    this gives us the flexibility of defining a monadic function that yields FS2 streams of
   *    IO and U as emitted values (e.g. Stream[IO, U], Sink[IO, U])
   */
  type NelsonK[U] = Kleisli[IO, NelsonConfig, U]
  type NelsonFK[F[_[_], _], U] = Kleisli[F[IO, ?], NelsonConfig, U]

  /**
   * a simple lift operation for easy construction of a NelsonFK
   */
  def lift[F[_[_], _], U](f: NelsonConfig => F[IO, U]) = Kleisli[F[IO, ?], NelsonConfig, U](f)

  private val logger = Logger[this.type]

  //////////////////////// GITHUB ////////////////////////////

  /**
   * Create a Nelson session based on the web-flow Github provides. The
   * AccessToken is an opaque, one-time code used to obtain the github token
   * for the user who is currently logged in. This function will only ever work
   * in an OAuth web-flow, as TempoaryAccessCode tokens cannot be obtained apiori
   * or out of band.
   */
  def createSessionFromOAuthCode(code: TempoaryAccessCode): NelsonK[Session] =
    Kleisli[IO, NelsonConfig, AccessToken] { cfg =>
      if(cfg.security.useEnvironmentSession)
        IO {
          logger.warn("Using environment session authentication. Should only be used for development.")
          AccessToken(sys.env("GITHUB_TOKEN"))
        }
      else Github.Request.fetchAccessToken(code).foldMap(cfg.github)
    }.flatMap(token => createSessionFromGithubToken(token))

  /**
   * Given a users Github personal access token - obtained either directly by user input,
   * or via the OAtuh web flow - and lift it into a Github session. This function is
   * distinct from `createSessionFromOAuthCode` because we require a way for non-web
   * clients to obtain a token they can use when calling the Nelson API programatically.
   */
  def createSessionFromGithubToken(githubToken: AccessToken): NelsonK[Session] =
    Kleisli { cfg =>
      for {
        c <- Github.Request.fetchUserData(githubToken).foldMap(cfg.github)
        d  = Instant.now.plusMillis(cfg.security.expireLoginAfter.toMillis)
        e  = c.copy(orgs = c.orgs.filterNot(o => blacklist(cfg.git.organizationBlacklist)(o.slug)))
      } yield Session(expiry = d, github = githubToken, user = e)
    }

  /**
   * Given a session, sync up the users repositories to nelson to make
   * sure we have the latest set of repos and their associated access.
   */
  def syncRepos(session: Session): NelsonK[Unit] = {
    // copy any known hooks into the list received from github
    // to aovid them being overwritten during the persist phase
    def augment(known: List[Repo])(r: Repo): Repo =
      known.find(_.id == r.id)
        .map(k => r.copy(hook = k.hook)).getOrElse(r)

    // find any repos that have been deleted on github
    def diff(nelson: List[Repo], github: List[Repo]): List[Repo] =
      nelson.filterNot(n => github.exists(e => e.id == n.id))

    def deleteRepos(repos: List[Repo]): StoreOpF[Unit] =
      NonEmptyList.fromList(repos).fold(().pure[StoreOpF])(r => StoreOp.deleteRepositories(r))

    def insertRepos(repos: List[Repo]): StoreOpF[Unit] =
      StoreOp.insertOrUpdateRepositories(repos) >> StoreOp.linkRepositoriesToUser(repos,session.user)

    Kleisli { cfg =>
      for {
        nelson <- session.user.orgs.flatTraverse(o => StoreOp.listRepositoriesWithOwner(session.user, o.slug)).foldMap(cfg.storage)
        github <- Github.Request.listUserRepositories(session.github).foldMap(cfg.github)
        repos   = github.filterNot(r => blacklist(cfg.git.organizationBlacklist)(r.slug.owner)).map(augment(nelson)(_))
        delete  = diff(nelson, github)
        _ <- (insertRepos(repos) >> deleteRepos(delete)).foldMap(cfg.storage)
      } yield ()
    }
  }

  //////////////////////// HOOKS ////////////////////////////

  /**
   * Actually setup the webhook both in the nelson database, and also
   * on the remote github repository. The user must have admin privileges
   * on the repo for this to successfully work.
   */
  def createHook(session: Session, slug: Slug): NelsonK[Repo] = {
    def hook(cfg: NelsonConfig): Github.WebHook = {
      val uri = linkTo("/listener")(cfg.network)
      Github.WebHook.create("release" :: "deployment" :: "pull_request" :: Nil, uri)
    }

    def getOrCreate(slug: Slug, hk: Github.WebHook): NelsonK[Github.WebHook] = Kleisli { cfg =>
      val make = Github.Request.createRepoWebhook(slug, hk)(session.github).foldMap(cfg.github)
      Github.Request.fetchRepoWebhooks(slug)(session.github).foldMap(cfg.github).flatMap(list =>
        list.find(_.callback == hk.callback)
          .fold(make)(IO.pure(_))).or(make)
    }

    Kleisli { cfg =>
      for {
        r1  <- storage.StoreOp.findRepository(session.user, slug).foldMap(cfg.storage)
        _   <- log(s"result of findRepository: r1 = $r1")

        // ensure we can access and read the .nelson.yml file
        str <- fetchRawRepoManifest(session.github)(slug).run(cfg)
        mnf <- yaml.ManifestParser.parseIO(str) <*
               log(s"successfully read and load the repo manifest file")

        rhk  = hook(cfg)

        out <- (cfg.auditor.write(rhk, CreateAction, login = session.user.login) *>
                getOrCreate(slug, rhk)(cfg))

        r2  <- (log(s"result of createRepoWebhook: out = $out") *>
                r1.tfold(RepoNotFound(slug))(_.copy(hook = Some(Hook(out.id, out.active)))))

        foo <- (log(s"result of repo copy: r2 = $r2") *>
                storage.StoreOp.insertOrUpdateRepositories(r2 :: Nil).foldMap(cfg.storage) <*
                cfg.auditor.write(r2, CreateAction, login = session.user.login))
        _   <- log(s"result of insertOrUpdateRepositories: foo = $foo")
      } yield r2
    }
  }


  /**
   * Remove the webhook from the repository.
   */
  def deleteHook(session: Session, slug: Slug): NelsonK[Unit] =
    Kleisli { cfg =>
      for {
        rep <- storage.StoreOp.findRepository(session.user, slug).foldMap(cfg.storage)
        rrr <- rep.tfold(RepoNotFound(slug))(identity)
        id  <- rrr.hook.tfold(UnexpectedMissingHook(slug))(_.id)
        _   <- cfg.auditor.write(rrr, DeleteAction, login = session.user.login)
        _   <- Github.Request.deleteRepoWebhook(slug, id)(session.github).foldMap(cfg.github)
        .or(IO.unit)
        upd  = Repo(rrr.id, rrr.slug, rrr.access, None)
        _   <- storage.StoreOp.insertOrUpdateRepositories(upd :: Nil).foldMap(cfg.storage)
      } yield ()
    }

  //////////////////////// REPOSITORIES ////////////////////////////

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NoNeedForMonad"))
  def listRepositories(session: Session, owner: Option[String]): NelsonK[List[Repo]] =
    Kleisli { cfg =>
      for {
        _   <- log(s"figuring out which database query to make, given '$owner'")
        out <- owner match {
          case Some(o) =>
            for {
              _ <- log("listing repositories with owner")
              t <- storage.StoreOp.listRepositoriesWithOwner(session.user, o).foldMap(cfg.storage)
            } yield t
          case None    =>
            for {
              _ <- log(s"listing repositories with active hooks for '${session.user}'")
              t <- storage.StoreOp.listRepositoriesWithActiveHooks(session.user).foldMap(cfg.storage)
            } yield t
        }
      } yield out.sortBy(_.slug.toString)
    }

  /**
   * Actually reach out to the repository in question using the github content
   * API and fetch the manifest file, if it exists.
   *
   * This function ensures that the file exists, that its content can be loaded
   * into a Manifest instance, and validates that it cab be deployed
   *
   * Anything else is just not cricket.
   */
  def fetchRepoManifestAndValidateDeployable(slug: Slug, ref: Github.Reference = Github.Branch("master")): NelsonK[ValidatedNel[NelsonError, Manifest]] = {
    for {
      cfg <- config
      token = cfg.git.systemAccessToken
      raw <- fetchRawRepoManifest(token)(slug, ref)
      mnf <- Kleisli.liftF(ManifestValidator.parseManifestAndValidate(raw, cfg))
    } yield mnf
  }

  def fetchRawRepoManifest(token: AccessToken)(slug: Slug, ref: Github.Reference = Github.Branch("master")): NelsonK[String] =
    Kleisli { cfg =>
      for {
        _   <- log(s"about to fetch ${cfg.manifest.filename} from ${slug}@${ref.toString} repository")
        raw <- Github.Request.fetchFileFromRepository(slug, cfg.manifest.filename, ref)(token).foldMap(cfg.github)
        dec <- raw.tfold(ProblematicRepoManifest(slug))(_.decoded)
      } yield dec
    }

  /*
   * Reaches out to github and retrieves the manifest for a specific release,
   * validates and then saturates with deployables.
   */
  def getVersionedManifestForRelease(r: Released): NelsonK[Manifest @@ Versioned] = {
    for  {
      _   <- config
      g   <- fetchGithubDeployment(r.referenceId, r.slug)
      v   <- fetchRepoManifestAndValidateDeployable(r.slug, g.ref)
      m   <- Kleisli.liftF(v.fold(e => IO.raiseError(MultipleValidationErrors(e)), m => IO.pure(m)))
      // take the deployable and modify the manifest to incorporate the release info
      ms  <- Kleisli.liftF(Manifest.saturateManifest(m)(g))
    } yield ms
  }

  private def fetchGithubDeployment(referenceId: Long, slug: Slug): NelsonK[Github.Deployment] = {
    Kleisli[IO, NelsonConfig, Option[Github.Deployment]] { cfg =>
      val t = cfg.git.systemAccessToken
      // NOTE(timperrett): im not wild about this, but there's simply no meaningful default that
      // can sensibly be applied here, so we're just bailing out.
      Github.Request.getDeployment(slug, referenceId)(t)
        .foldMap(cfg.github)
        .ensure(MissingDeploymentReference(referenceId, slug))(_.nonEmpty)
        .retryExponentially(2.seconds, limit = 3)(cfg.pools.schedulingPool, cfg.pools.defaultExecutor)
    }.map(_.get)
  }

  def deploy(actions: List[Manifest.Action]): NelsonK[Unit] =
    Kleisli(cfg => actions.traverse_(a => cfg.queue.enqueue1(a)))

  /**
   * Invoked when the inbound webhook from Github arrives, notifying Nelson
   * that a new deployment needs to take place.
   */
  def handleDeployment(e: Github.DeploymentEvent): NelsonK[Unit] = {
    import Manifest.{Namespace,Plan,UnitDef,Action}

    // convert units in the manifest to action.
    // filter out all units that are not in the provided namespace (ns)
    def unitActions(m: Manifest @@ Versioned, ns: NamespaceName, dcs: Seq[Datacenter]): List[Action] = {
      val unitFilter: (Datacenter,Namespace,Plan,UnitDef) => Boolean =
        (_,namespace,_,_) => namespace.name == ns

      Manifest.unitActions(m, dcs, unitFilter)
    }

    Kleisli { cfg =>
      for {
        v  <- fetchRepoManifestAndValidateDeployable(e.slug, e.deployment.ref).run(cfg)
        m  <- v.fold(err => IO.raiseError(MultipleErrors(err)), m => IO.pure(m))

        hm <- (log(s"received manifest from github: $m")
              *> storage.StoreOp.createRelease(e).foldMap(cfg.storage)
              *> cfg.auditor.write(e.deployment, CreateAction, Option(e.deployment.id))
              *> log(s"created release in response to release ${e.deployment.id}")
              *> Manifest.saturateManifest(m)(e.deployment))

        _  <- (storeManifest(hm, e.repositoryId).run(cfg)
              *> log("stored the release manifest in the database"))

        _ <- deploy(unitActions(hm, cfg.defaultNamespace, cfg.datacenters)).run(cfg)
      } yield ()
    }
  }

  def storeManifest(m: Manifest @@ Versioned, repoId: ID): NelsonK[Unit] = {
    Kleisli { cfg =>
      val mnf = Manifest.Versioned.unwrap(m)
      (mnf.units.traverse_(u => StoreOp.addUnit(Versioned(u), repoId)) >>
       mnf.loadbalancers.traverse_(lb => StoreOp.insertLoadbalancerIfAbsent(Versioned(lb), repoId).map(_ => ()))
      ).foldMap(cfg.storage)
    }
  }

  /**
   * list releases this repository has had.
   */
  def listRepositoryReleases(s: Slug): NelsonK[SortedMap[Released, List[ReleasedDeployment]]] =
    Kleisli { cfg =>
      storage.StoreOp.listRecentReleasesForRepository(s).foldMap(cfg.storage)
    }

  /**
   * list recent releases, regardless of repository.
   */
  def listReleases(limit: Option[Int]): NelsonK[SortedMap[Released, List[ReleasedDeployment]]] =
    Kleisli { cfg =>
      for {
        a <- (limit.fold(IO.pure(50))(l =>
                if(l > 50) IO.raiseError(ExceededLimitRange(l))
                else IO.pure(l)))
        b <- storage.StoreOp.listReleases(a).foldMap(cfg.storage)
      } yield b
    }

  /**
   * Given the ID of a particular release, show the release information and any associated deployments
   */
  def getRelease(id: Long): NelsonK[SortedMap[Released, List[ReleasedDeployment]]] =
    Kleisli(cfg => storage.StoreOp.findRelease(id).foldMap(cfg.storage))

  /**
   * Given a repo slug and a specific unit, figure out what the state of the latest released version is,
   * for the specified unit only. This is primarily used to power the SVG badging.
   *
   * TIM: REALLY NEEDS CACHING TO SCALE; *PROBALLY* TOO EXPENSIVE RIGHT NOW.
   */
  def getStatusOfReleaseUnit(s: Slug, u: UnitName): NelsonK[Option[DeploymentStatus]] =
    Kleisli { cfg =>
      cfg.caches.stackStatusCache.get((s.owner, s.repository, u))
        .fold(storage.StoreOp.findLastReleaseDeploymentStatus(s,u).foldMap(cfg.storage)
          )(x => IO.pure(Option(x)))
    }

  //////////////////////// BLUEPRINTS ////////////////////////////

  def listBlueprints: NelsonK[List[Blueprint]] =
    Kleisli { cfg =>
      StoreOp.listBlueprints.foldMap(cfg.storage)
    }

  def fetchBlueprint(name: String, revision: Blueprint.Revision): NelsonK[Option[Blueprint]] =
    Kleisli { cfg =>
      StoreOp.findBlueprint(name, revision).foldMap(cfg.storage)
    }

  /*
   * NOTE: to prevent totally invalid templates being specified (i.e. things that make the
   * template parser choke) we defensivly try to proof the inbound blueprint which should
   * raise an error in the event the blueprint is junk.
   */
  def createBlueprint(name: String, description: Option[String], sha: Sha256, template: String): NelsonK[Option[Blueprint]] =
    proofBlueprint(template).flatMap(_ => Kleisli { cfg =>
      (for {
        _ <- StoreOp.insertBlueprint(name, description, sha, template)
        b <- StoreOp.findBlueprint(name, Blueprint.Revision.HEAD)
      } yield b).foldMap(cfg.storage)
    })

  def proofBlueprint(templateContent: String): NelsonK[String] = {
    import blueprint.{Template, EnvValue, Render}
    import Manifest._
    Kleisli { cfg =>
      val unitName = "example"
      val version = Version(1, 4, 54)
      val image = docker.Docker.Image(unitName, Some(version.toFeatureVersion.toString), None)
      val unit = UnitDef(unitName, "",
          Map("database" -> FeatureVersion(1,2)),
          Set.empty,
          Alerting.empty,
          Some(Ports(Port("default", 8080, "tcp"), Nil)),
          Some(Deployable(unitName, version, Deployable.Container(image.toString))),
          Set("some-tag")
      )
      val proofingData: Map[String, EnvValue] =
        Render.makeEnv(
          image,
          cfg.datacenters.head,
          NamespaceName("dev"),
          unit,
          version,
          Manifest.Plan.default,
          randomAlphaNumeric(8)
        )
      // NOTE: using random identifier here simply so that
      // even if template engine has caching engaged, we
      // always get a fresh result (cache busting FTW).
      // This is only ever desirable for proofing purposes.
      val template = Template.load(randomAlphaNumeric(12), templateContent)
      IO(template.render(proofingData))
    }
  }

  //////////////////////// DATACENTERS ////////////////////////////

  final case class RecentStatistics(statusCounts: List[(String,Int)], mostDeployed: List[(String,Int)], leastDeployed: List[(String,Int)])

  /**
   * Fetches statistics about what happened over the last N days, where
   * `N` is defined by the `whence` instance of `java.time.Instant`.
   */
  def recentActivityStatistics: NelsonK[RecentStatistics] = {
    val whence = Instant.now.minus(7, java.time.temporal.ChronoUnit.DAYS)
    val zero: Map[String,Int] = DeploymentStatus.all.map(_.toString -> 0).toMap
    Kleisli { cfg =>
      val prog = for {
        statusCounts <- StoreOp.countDeploymentsByStatus(whence).map(agg => ((zero ++ agg.toMap).toList))
        mostDeployed <- StoreOp.getMostAndLeastDeployed(whence, 10, "Desc")
        leastDeployed <- StoreOp.getMostAndLeastDeployed(whence, 10, "Asc")
      }
      yield RecentStatistics(statusCounts, mostDeployed, leastDeployed)
      prog.foldMap(cfg.storage)
    }
  }

  /**
   * This is a bootstrapping function - definitions of datacenters come
   * from the Nelson configuration, and the database is a read-only reference
   * of that, simply so we can provide forigen key constraints when doing
   * queries.
   *
   * This function should only ever be called at bootup.
   */
  def createDatacenters(list: List[Datacenter]): NelsonK[Unit] = Kleisli { cfg =>
    list.traverse_(dc => storage.StoreOp.createDatacenter(dc).foldMap(cfg.storage))
  }

  /*
   * This is a bootstrapping function - each instance of Nelson has a default
   * namespace defined in the Nelson configuration. This function creates
   * the default namespace for each datacenter if it doesn't already exist
   *
   * This function should only ever be called at bootup
   */
  def createDefaultNamespaceIfAbsent(dcs: List[Datacenter], ns: NamespaceName): NelsonK[Unit] =
    Kleisli(cfg => dcs.traverse(dc => getOrCreateNamespace(dc.name, ns)).foldMap(cfg.storage).map(_ => ()))

  /**
   * List all the datacenters Nelson is currently aware of, and return
   * the namespaces associated with that datacenter.
   */
  def listDatacenters(implicit ec: ExecutionContext): NelsonK[Map[Datacenter, Set[Namespace]]] = Kleisli { cfg =>
    parallelTraverse(cfg.datacenters) { d =>
      storage.StoreOp.listNamespacesForDatacenter(d.name).map(d -> _).foldMap(cfg.storage)
    }.map(_.toMap)
  }

  /**
   * Fetch a specific datacenter based on its name, along with the namespaces
   * that are avalible in that specific datacenter (if any).
   */
  def fetchDatacenterByName(name: String): NelsonK[Option[(Datacenter, Set[Namespace])]] =
    Kleisli { cfg =>
      cfg.datacenters.find(_.name.trim.toLowerCase == name.trim.toLowerCase).traverse(dc =>
        storage.StoreOp.listNamespacesForDatacenter(dc.name).map(dc -> _).foldMap(cfg.storage)
      )
    }

  /*
   * Given a list of datacenters and namespaces figure out deployments by DeploymentStatus
   */
  def listDeployments(
    dcs: List[String],
    ns: NonEmptyList[NamespaceName],
    status: NonEmptyList[DeploymentStatus],
    unit: Option[UnitName]
  ): NelsonK[List[(DatacenterRef,Namespace,Deployment,DeploymentStatus)]] = {
    Kleisli { cfg =>
      val datacenters = if (dcs.isEmpty) cfg.datacenters.map(_.name) else dcs
      datacenters.flatTraverse(dc => listDatacenterDeployments(dc,ns,status,unit).run(cfg).map(_.map(x => (dc,x._1,x._2, x._3))))
    }
  }

  /**
   * Given a specific datacenter and a list of namespaces figure out deployments by DeploymentStatus.
   * TIM: this probally wont scale, but its likley ok for the moment.
   */
  def listDatacenterDeployments(
    dcName: String,
    ns: NonEmptyList[NamespaceName],
    status: NonEmptyList[DeploymentStatus],
    unit: Option[UnitName]
  ): NelsonK[List[(Namespace,Deployment,DeploymentStatus)]] = {
    val empty: IO[List[(Namespace,Deployment,DeploymentStatus)]] = List.empty.pure[IO]

    def findNsByName(set: Set[Namespace]): NelsonK[List[(Namespace,Deployment,DeploymentStatus)]] = Kleisli { cfg =>
      val namespaces = set.toList.filter(x => ns.toList.exists(_ == x.name)) // only query for namepaces in this dc
      namespaces.flatTraverse { n =>
        storage.StoreOp.listDeploymentsForNamespaceByStatus(n.id, status, unit).foldMap(cfg.storage)
          .map((s: Set[(Deployment,DeploymentStatus)]) => s.toList.map(pair => (n,pair._1,pair._2)))
      }
    }

    for {
      a <- fetchDatacenterByName(dcName)
      b <- a.fold[NelsonK[List[(Namespace,Deployment,DeploymentStatus)]]](Kleisli(_ => empty)){
        case (_,set) => findNsByName(set)
      }
    } yield b
  }

  def getOrCreateNamespace(dc: DatacenterRef, ns: NamespaceName): StoreOpF[ID] =
    StoreOp.getNamespace(dc, ns).flatMap(_.fold(StoreOp.createNamespace(dc, ns)) { n =>
      n.id.pure[StoreOpF]
    })

  /*
   * Creates namespace hierarchy starting at the root and working downward.
   * If a namespace already exists, it's a noop and continues to the next
   * in hierarchy.
   */
  def recursiveCreateNamespace(dc: DatacenterRef, ns: NamespaceName): NelsonK[Unit] =
    Kleisli { cfg =>
      for {
         opt <- cfg.datacenters.find(_.name.trim.toLowerCase == dc.trim.toLowerCase).pure[IO]
         _   <- opt.tfold(UnknownDatacenter(dc))(identity)
         _   <- ns.hierarchy.traverse(n => getOrCreateNamespace(dc, n)).foldMap(cfg.storage)
      } yield ()
    }

  /*
   * Creates namespace hierarchy only if root namespace already exists.
   */
  def recursiveCreateSubordinateNamespace(dc: DatacenterRef, ns: NamespaceName): NelsonK[Unit] =
    Kleisli { cfg =>
      for {
        root <- StoreOp.getNamespace(dc, ns.root).foldMap(cfg.storage)
        _    <- root.tfold(NamespaceCreateFailed(s"root namespace (${ns.root.asString}) does not exist"))(identity)
        _    <- recursiveCreateNamespace(dc, ns).run(cfg)
      } yield ()
    }

  /**
   * Gets routing graphs for a given datacenter and list of namespaces. If namespaces is not provided then
   * default to all namespaces
   */
  def getRoutingGraphs(dc: DatacenterRef, ns: List[NamespaceName]): NelsonK[List[(Namespace,routing.RoutingGraph)]] = {
    import routing.{RoutingTable,RoutingGraph}

    def getRoutingGraphByNamespaces(namespaces: List[Namespace]): StoreOpF[List[(Namespace,RoutingGraph)]] =
      namespaces.traverse(n => RoutingTable.routingGraph(n).map(n -> _))

    val empty: IO[List[(Namespace,RoutingGraph)]] = List.empty.pure[IO]
    for {
      a <- fetchDatacenterByName(dc)
      b <- a.fold[NelsonK[List[(Namespace, RoutingGraph)]]](Kleisli(_ => empty)){
        case (_,set) =>

          val namespaces =
            if (ns.isEmpty) set.toList // if no ns supplied use them all
            else set.toList.filter(n => ns.exists(_ == n.name))

          Kleisli(cfg => getRoutingGraphByNamespaces(namespaces).foldMap(cfg.storage))
      }
    } yield b
  }

  //////////////////////// DEPLOYMENTS ////////////////////////////


  final case class StackSummary(
    namespace: Datacenter.Namespace,
    deployment: Datacenter.Deployment,
    statuses: List[(DeploymentStatus, Option[String], Instant)],
    expiration: Instant,
    inboundDependencies: Vector[(routing.RoutePath, routing.RoutingNode)],
    outboundDependencies: Vector[(routing.RoutePath, routing.RoutingNode)]
  )

  /*
   * Get all the information we know about a specific deployment
   */
  def fetchDeployment(guid: GUID): NelsonK[Option[StackSummary]] = {
    import routing.{RoutingNode,RoutingGraph,RoutePath,RoutingTable}

    def getDplWithNs: OptionT[StoreOpF,(Namespace,Datacenter.Deployment,RoutingGraph)] =
      for {
        a <- OptionT(StoreOp.getDeploymentByGuid(guid))
        b <- OptionT(RoutingTable.routingGraph(a.namespace).map(Option(_)))
      } yield (a.namespace,a,b)

    // a deployment can only depend on another deployment's default port
    def filterDefault(rp: RoutePath): Boolean =
      rp.portName == "default"

    def getInsAndOuts(g: RoutingGraph, d: Datacenter.Deployment) =
      if (g.contains(RoutingNode(d))) {
        val ins = g.ins(RoutingNode(d)).filter(x => filterDefault(x._1))
        val outs = g.outs(RoutingNode(d)).filter(x => filterDefault(x._1))
        (ins, outs)
      }
      else (Vector(),Vector())

    for {
      s <- fetchDeploymentStatuses(guid)
      e <- findDeploymentExpiration(guid)
      t <- Kleisli[IO, NelsonConfig, Option[(Namespace,Datacenter.Deployment,RoutingGraph)]]{ config =>
        getDplWithNs.value.foldMap(config.storage)
      }
    } yield for {
        a <- t
        b <- e
        (ns, dpl, gph) = a
      } yield {
        val (ins, outs) = getInsAndOuts(gph,dpl)
        StackSummary(ns, dpl, s, b,
          inboundDependencies = ins,
          outboundDependencies = outs)
      }
  }

  final case class RuntimeSummary(
    deployment: scheduler.DeploymentSummary,
    health: List[HealthStatus],
    currentStatus: DeploymentStatus,
    expiresAt: Instant
  )

  /*
   * Reaches out to the scheduler (i.e. Nomad) and consul to get
   * runtime specifics about the deployment
   */
  def getRuntimeSummary(guid: GUID): NelsonK[Option[RuntimeSummary]] = {
    Kleisli { cfg =>
      val query = (for {
        dep <- OptionT(StoreOp.getDeploymentByGuid(guid))
        exp <- OptionT(StoreOp.findDeploymentExpiration(dep.id))
        st  <- OptionT(StoreOp.getDeploymentStatus(dep.id))
      } yield (dep,exp,st,dep.namespace)).value

      (for {
        a   <- OptionT(query.foldMap(cfg.storage))
        (dep, exp, status, ns) = a
        dc  <- OptionT(IO.pure(cfg.datacenters.find(_.name == ns.datacenter)))
        sum <- OptionT(scheduler.SchedulerOp.summary(dc, ns.name, dep.stackName).foldMap(dc.interpreters.scheduler))
        h   <- OptionT(HealthCheckOp.health(dc, ns.name, dep.stackName).foldMap(dc.health).map(h => Some(h): Option[List[HealthStatus]]))
      } yield RuntimeSummary(sum, h, status, exp)).value
    }
  }

  final case class CommitUnit(unitName: UnitName, version: Version, target: NamespaceName)

  /*
   * Commit unit to namespace given a github release event by deploying it into the given datacenters
   */
  def commit(un: UnitName, ns: NamespaceName, dcs: List[Datacenter], m: Manifest @@ Versioned): NelsonK[Unit] = {
    import Manifest.{Namespace,Plan,UnitDef}

    // fiter out everything that doesn't belong to this unit / namespace / datacenter
    val unitFilter: (Datacenter,Namespace,Plan,UnitDef) => Boolean =
      (dc,namespace,_,unit) => dcs.exists(_ == dc) && namespace.name == ns && unit.name == un

    Kleisli { cfg =>
      for {
        v <- IO(ManifestValidator.validateOnCommit(un, ns, Versioned.unwrap(m)))
        _ <- v.fold(e => IO.raiseError(MultipleValidationErrors(e)), m => IO.pure(m))
        a  = Manifest.unitActions(m, dcs, unitFilter)
        _ <- deploy(a).run(cfg)
      } yield ()
    }
  }

  /*
   * Commit a unit / version to a namespace by deploying it into the datacenter
   */
  def commit(un: UnitName, v: Version, ns: NamespaceName): NelsonK[Unit] = {
    Kleisli { cfg =>
      for {
        a  <- StoreOp.findReleasedByUnitNameAndVersion(un, v).foldMap(cfg.storage)
        b  <- a.tfold(DeploymentCommitFailed(
                s"could not find release by unit name: $un and version: $v"))(identity)
        m  <- getVersionedManifestForRelease(b).run(cfg)
        _  <- commit(un, ns, cfg.datacenters, m).run(cfg)
      } yield ()
    }
  }

  /*
   * Redeploys Deployment specified by deploymentGuid
   */
  def redeploy(deploymentGuid: GUID): NelsonK[Unit] = {
    // can't redeploy a manual deployment
    def validateDeployment(d: Deployment): IO[Unit] =
      if (d.workflow == "manual")
        IO.raiseError(DeploymentCommitFailed("can't redeploy a manual deployment"))
      else IO.unit

    Kleisli { cfg =>
      for {
        a   <- storage.StoreOp.getDeploymentByGuid(deploymentGuid).foldMap(cfg.storage)
        d   <- a.tfold(MissingDeployment(deploymentGuid))(identity)
        _   <- validateDeployment(d)
        ns   = d.namespace
        dct <- cfg.datacenters.find(_.name == ns.datacenter).pure[IO]
        dc  <- dct.tfold(MisconfiguredDatacenter(ns.datacenter, s"couldn't be found"))(identity)
        rm  <- storage.StoreOp.findReleaseByDeploymentGuid(d.guid).foldMap(cfg.storage)
        r   <- rm.tfold(MissingReleaseForDeployment(deploymentGuid))(identity)
        m   <- getVersionedManifestForRelease(r._1).run(cfg)
        _   <- commit(d.unit.name, ns.name, List(dc), m).run(cfg)
      } yield ()
    }
  }

  /**
   * Given a deployment GUID, list the deployment statuses
   */
  def fetchDeploymentStatuses(guid: GUID): NelsonK[List[(DeploymentStatus, Option[String], Instant)]] =
    Kleisli { config =>
      for {
        a <- storage.StoreOp.getDeploymentByGuid(guid).foldMap(config.storage)
        d <- a.tfold(MissingDeployment(guid))(identity(_))
        ns = d.namespace
        _ <- config.datacenters.find(_.name == ns.datacenter).fold[IO[Datacenter]](IO.raiseError(MisconfiguredDatacenter(ns.datacenter, "couldn't be found")))(IO.pure)
        status <- storage.StoreOp.listDeploymentStatuses(d.id).foldMap(config.storage)
      } yield status
    }

  /*
   * Given a list of datacenters and namespaces figure out units by DeploymentStatus
   */
  def listUnitsByStatus(
    dcs: List[DatacenterRef],
    ns: NonEmptyList[NamespaceName],
    status: NonEmptyList[DeploymentStatus]
  ): NelsonK[List[(DatacenterRef,Namespace,GUID,ServiceName)]] =
    Kleisli { cfg =>
      val datacenters = if (dcs.isEmpty) cfg.datacenters.map(_.name) else dcs
      datacenters.flatTraverse(dc => listDatacenterUnitsByStatus(dc,ns,status).run(cfg)
        .map(_.map(x => (dc,x._1,x._2,x._3))))
    }

  /*
   * Given a specific datacenter and a list of namespaces figure out units by DeploymentStatus.
   */
  def listDatacenterUnitsByStatus(
    dc: String,
    ns: NonEmptyList[NamespaceName],
    status: NonEmptyList[DeploymentStatus]
  ): NelsonK[List[(Namespace,GUID,ServiceName)]] = {

    def findNsByName(set: Set[Namespace]): NelsonK[List[(Namespace,GUID,ServiceName)]] = Kleisli { cfg =>
      val namespaces = set.toList.filter(x => ns.toList.exists(_ == x.name)) // only query for namepaces in this dc
      namespaces.flatTraverse(n =>
        storage.StoreOp.listUnitsByStatus(n.id, status)
          .map(s => s.toList.map(x => (n,x._1,x._2))).foldMap(cfg.storage))
    }

    val empty: IO[List[(Namespace,GUID,ServiceName)]] = List.empty.pure[IO]

    for {
      a <- fetchDatacenterByName(dc)
      b <- a.fold[NelsonK[List[(Namespace, GUID, ServiceName)]]](Kleisli(_ => empty)){
        case (_,set) => findNsByName(set)
      }
    } yield b
  }

  /*
   * Returns the latest expiration for deployment
   */
  def findDeploymentExpiration(guid: GUID): NelsonK[Option[Instant]] =
    Kleisli(cfg => StoreOp.findDeploymentExpirationByGuid(guid).foldMap(cfg.storage))

  /**
   * Create a manual deployment. That is to say, let Nelson know about something
   * that was not deployed via Nelson (e.g. databases and other ops-infrastructure)
   */
  def createManualDeployment(s: Session, m: Datacenter.ManualDeployment): NelsonK[GUID] = Kleisli { cfg =>

    def validateNamespace(ns: String): IO[NamespaceName] =
       NamespaceName.fromString(ns).fold(
         e => IO.raiseError(ManualDeployFailed(e.getMessage)),
         ns =>
          if (!ns.isRoot)
            IO.raiseError(ManualDeployFailed(s"namespace ${ns.asString} is not a root namespace"))
          else IO.pure(ns))

    val ttl = cfg.cleanup.initialTTL.toSeconds
    val exp = Instant.now.plusSeconds(ttl)

    for {
      dc   <- cfg.datacenters.find(_.name == m.datacenter)
                 .fold[IO[Datacenter]](IO.raiseError(ManualDeployFailed(s"datacenter ${m.datacenter} couldn't be found")))(IO.pure)
       ns   <- validateNamespace(m.namespace)
       guid <- StoreOp.createManualDeployment(
               dc,
               ns,
               m.serviceType,
               m.version,
               m.hash,
               m.description,
               m.port,
               exp).foldMap(cfg.storage) <* cfg.auditor.write(m, CreateAction, login = s.user.login)
     } yield guid
  }

  def getDeploymentsByDatacenter(dc: Datacenter, f: Namespace => StoreOpF[List[Deployment]]): StoreOpF[Set[Deployment]] = {
    for {
      ns <- StoreOp.listNamespacesForDatacenter(dc.name).map(_.toList)
      ds <- ns.flatTraverse(ns => f(ns))
    } yield ds.toSet
  }

  /**
   * Deprecates all deloyments for a service / feature version accross all datacenters and namepaces
   */
  def deprecateService(sn: Datacenter.ServiceName): NelsonK[Unit] = Kleisli { cfg =>

    val ready = NonEmptyList.of(DeploymentStatus.Ready) // only deprecate deployments in the ready state

    def deprecateDeployment(d: Deployment): storage.StoreOpF[Unit] =
      storage.StoreOp.createDeploymentStatus(d.id, DeploymentStatus.Deprecated, None)

    // gets all deployments for a given unit name / feature version which are ready
    val getDeployments: (Datacenter.Namespace) => StoreOpF[List[Deployment]] =
      (ns) => StoreOp.listDeploymentsForUnitByStatus(ns.id, sn.serviceType, ready)
        .map(_.toList.filter(_.unit.version.toFeatureVersion == sn.version))

    def deprecateByDatacenter(dc: Datacenter): StoreOpF[Unit] =
     for {
        ds <- getDeploymentsByDatacenter(dc, getDeployments)
        _  <- ds.toList.traverse(d => deprecateDeployment(d))
      } yield ()

    val prog = cfg.datacenters.traverse_(dc => deprecateByDatacenter(dc))
    prog.foldMap(cfg.storage)
  }

  /**
   * Expires all deloyments for a service accross all datacenters and namepaces
   */
  def expireService(sn: Datacenter.ServiceName): NelsonK[Unit] = Kleisli { cfg =>
    def expireDeployment(d: Deployment): storage.StoreOpF[Unit] = {
      val exp = Instant.now()
      storage.StoreOp.createDeploymentExpiration(d.id, exp).void
    }

    // gets all deployments for a given unit name / feature version
    val getDeployments: (Datacenter.Namespace) => StoreOpF[List[Deployment]] =
      (ns) => StoreOp.listDeploymentsForUnitByStatus(ns.id, sn.serviceType, DeploymentStatus.nel)
        .map(_.toList.filter(_.unit.version.toFeatureVersion == sn.version))

    def expireByDatacenter(dc: Datacenter): StoreOpF[Unit] =
     for {
        ds <- getDeploymentsByDatacenter(dc, getDeployments)
        _  <- ds.toList.traverse(d => expireDeployment(d))
      } yield ()

    val prog = cfg.datacenters.traverse_(dc => expireByDatacenter(dc))
    prog.foldMap(cfg.storage)
  }

  /*
   * Starts to reverse an in progress traffic shift given the to deployment's guid
   */
  def reverseTrafficShift(guid: GUID): NelsonK[Datacenter.TrafficShift] = {

    def validate(ts: TrafficShift): Either[String, TrafficShift] =
      if (ts.to.guid != guid) Left(s"deployment ($guid) is not the target for latest traffic shift")
      else if (ts.reverse.isDefined) Left("can't reverse a traffic shift that's already been reversed")
      else if (!ts.inProgress(Instant.now)) Left("can't reverse a traffic shift that is currently not in progress")
      else Right(ts)

    def reverse(ts: TrafficShift): StoreOpF[Either[String, ID]] = {
      val prog = OptionT(StoreOp.reverseTrafficShift(ts.to.id, Instant.now)).toRight("unsable to start traffic shift reverse")
      prog.value
    }

    Kleisli { cfg =>

      val prog = for {
        dep <- OptionT(StoreOp.getDeploymentByGuid(guid)).toRight(s"deployment with guid $guid not found")
        ts  <- OptionT(StoreOp.getTrafficShiftForServiceName(dep.nsid, dep.unit.serviceName))
                .toRight(s"unable to find traffic shift for to deployment ${dep.stackName}")
        _   <- EitherT(validate(ts).pure[StoreOpF])
        _   <- EitherT(reverse(ts))
      } yield ts

      prog.value.foldMap(cfg.storage)
       .flatMap(_.fold(e => IO.raiseError(InvalidTrafficShiftReverse(e)), r => IO.pure(r)))
    }
  }

  /**
   * Lists all deployments accross datacenters and namspaces that depend on
   * a deployment that is deprecated.
   */
  def listDeploymentsWithDeprecatedDependencies: NelsonK[Vector[DependencyEdge]] = {
    for {
      cfg <- config
      dcs <- cfg.datacenters.toVector.traverse(dc => listDeploymentsWithDeprecatedDependencies(dc))
    } yield dcs.flatten
  }

  /**
   * Like listDeploymentsWithDeprecatedDependencies but for a single Datacenter
   */
  def listDeploymentsWithDeprecatedDependencies(dc: Datacenter): NelsonK[Vector[DependencyEdge]] = {
    import nelson.routing.deprecated
    Kleisli(cfg => deprecated.deploymentsWithDeprecatedDependencies(dc).foldMap(cfg.storage))
  }

  def listAuditEvents(
    limit: Long, offset: Long, releaseId: Option[Long],
    action: Option[String], category: Option[String]): NelsonK[List[AuditLog]] =
    Kleisli { cfg => releaseId.fold(storage.StoreOp.listAuditLog(limit, offset, action, category))(
      storage.StoreOp.listAuditLogByReleaseId(limit, offset, _)).foldMap(cfg.storage)
    }

  def fetchWorkflowLog(guid: GUID, offset: Int): NelsonK[Option[(Int, List[String])]] = {
    val empty: IO[Option[(Int,List[String])]] = IO.pure(Option.empty[(Int,List[String])])
    for {
      cfg <- config
      a <- Kleisli.liftF(StoreOp.getDeploymentByGuid(guid).foldMap(cfg.storage))
      b <- Kleisli.liftF(a.fold(empty)(dpl => cfg.workflowLogger.read(dpl.id, offset).map(l => Some(offset -> l))))
    } yield b
  }

  //////////////////////// LOADBALANCERS /////////////////////////////
  import Datacenter.LoadbalancerDeployment

  def getLoadbalancerByGUID(guid: GUID): NelsonK[Option[LoadbalancerDeployment]] =
    Kleisli(cfg => StoreOp.getLoadbalancerDeploymentByGUID(guid).foldMap(cfg.storage))

  // Launches loadbalancer with name and major version into the given datacenter and namespace
  def commitLoadbalancer(name: String, v: Int, dcName: DatacenterRef, ns: NamespaceName): NelsonK[Unit] = {
    import Manifest.{Loadbalancer,Plan,Namespace,Action}

    val mv = MajorVersion(v)

    // fiter out everything that doesn't belong to this loadbalancer / namespace / datacenter
    val lbFilter: (Datacenter,Namespace,Plan,Loadbalancer) => Boolean =
      (dc,namespace,_,lb) => dcName == dc.name && namespace.name == ns && lb.name == name

    def validateAndDeployActions(acts: List[Action], cfg: NelsonConfig): IO[Unit] = {
      if (acts.isEmpty)
        IO.raiseError(DeploymentCommitFailed(
          s"Manifest does not reference loadbalancer ${name} ${mv} in namespace ${ns}"))
      else
        deploy(acts).run(cfg)
    }

    Kleisli { cfg =>
      for {
        lbo <- StoreOp.getLoadbalancer(name, mv).foldMap(cfg.storage)
        _  <- lbo.tfold(DeploymentCommitFailed(s"couldn't find loadbalancer: $name $mv"))(identity)
        rr  <- StoreOp.getLatestReleaseForLoadbalancer(name, mv).foldMap(cfg.storage)
        r   <- rr.tfold(DeploymentCommitFailed(s"couldn't find latest release for loadblancer $name $mv"))(identity)
        ms  <- getVersionedManifestForRelease(r).run(cfg)
        act  = Manifest.loadbalancerActions(ms, cfg.datacenters, lbFilter)
        _ <- validateAndDeployActions(act, cfg)
      } yield ()
    }
  }

  // Delete loadbalancer deployment: First delete loadbalancer in the datacenter, next
  // delete loadbalancer from nelson's internal h2 tables
  def deleteLoadbalancerDeployment(guid: GUID): NelsonK[Unit] = {
    Kleisli { cfg =>
      for {
        a  <- StoreOp.getLoadbalancerDeploymentByGUID(guid).foldMap(cfg.storage)
        lb <- a.tfold(LoadbalancerNotFound(guid))(identity)
        ns <- StoreOp.getNamespaceByID(lb.nsid).foldMap(cfg.storage)
        dc <- cfg.datacenters.find(_.name == ns.datacenter)
                .fold[IO[Datacenter]](IO.raiseError(MisconfiguredDatacenter(ns.datacenter, "couldn't be found")))(IO.pure)
        _  <- dc.loadbalancer.traverse(trans => loadbalancers.delete(lb,dc,ns).foldMap(trans))
        _  <- StoreOp.deleteLoadbalancerDeployment(lb.id).foldMap(cfg.storage)
        _  <- helm.run(dc.consul, loadbalancers.deleteLoadbalancerConfigFromConsul(lb))
      } yield ()
    }
  }

  // Lists all loadbalancer deployments in a given set of datacenters and namespaces
  def listLoadbalancers(dcs: List[String], ns: NonEmptyList[NamespaceName]): NelsonK[List[(DatacenterRef,Namespace,LoadbalancerDeployment)]] = {
    Kleisli { cfg =>
      val datacenters = if (dcs.isEmpty) cfg.datacenters.map(_.name) else dcs
      datacenters.flatTraverse(dc => listLoadbalancerDeployments(dc,ns).run(cfg).map(_.map(x => (dc,x._1,x._2))))
    }
  }

  def listLoadbalancerDeployments(dc: DatacenterRef, ns: NonEmptyList[NamespaceName]): NelsonK[List[(Namespace,LoadbalancerDeployment)]] = {
    val empty: IO[List[(Namespace,LoadbalancerDeployment)]] = List.empty.pure[IO]

    def findNsByName(set: Set[Namespace]): NelsonK[List[(Namespace,LoadbalancerDeployment)]] = Kleisli { cfg =>
      val namespaces = set.toList.filter(x => ns.toList.exists(_ == x.name)) // only query for namepaces in this dc
      namespaces.flatTraverse(n =>
        storage.StoreOp.listLoadbalancerDeploymentsForNamespace(n.id)
          .map(s => s.toList.map(n -> _)).foldMap(cfg.storage))
    }

    for {
      a <- fetchDatacenterByName(dc)
      b <- a.fold[NelsonK[List[(Namespace, LoadbalancerDeployment)]]](Kleisli(_ => empty)){
        case (_,set) => findNsByName(set)
      }
    } yield b
  }

  final case class LoadbalancerSummary(
    namespace: Datacenter.Namespace,
    loadbalancer: Datacenter.LoadbalancerDeployment,
    outboundDependencies: Vector[routing.RoutingNode]
  )

  def fetchLoadbalancerDeployment(guid: GUID): NelsonK[Option[LoadbalancerSummary]] = {
    import routing.{RoutingNode,RoutingGraph,RoutingTable}

    def getOuts(g: RoutingGraph, lb: Datacenter.LoadbalancerDeployment): Vector[routing.RoutingNode] =
      if (g.contains(RoutingNode(lb))) g.outs(RoutingNode(lb)).map(_._2) else Vector.empty[routing.RoutingNode]

    Kleisli { cfg =>
      (for {
        lb <- OptionT(storage.StoreOp.getLoadbalancerDeploymentByGUID(guid))
        ns <- OptionT(storage.StoreOp.getNamespaceByID(lb.nsid).map(Option(_)))
        od <- OptionT(RoutingTable.routingGraph(ns).map(Option(_)))
      } yield {
        LoadbalancerSummary(ns, lb, getOuts(od, lb))
      }).value.foldMap(cfg.storage)
    }
  }

  //////////////////////// INTERNALS ////////////////////////////

  protected val config: NelsonK[NelsonConfig] = Kleisli.ask

  private def blacklist(owners: List[String])(owner: String): Boolean =
    owners.exists(_.trim.toLowerCase == owner.trim.toLowerCase)

  private def log(msg: String): IO[Unit] =
    IO(logger.info(msg))
}
