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

import java.net.URI

import journal.Logger
import java.time.Instant

import scala.util.control.NonFatal
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.{Sink, Process}
import scalaz._
import Scalaz._
import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService, ThreadFactory}

import alerts.{Overhaul, Promtool, rewriteRules}

object Nelson {
  import Datacenter._
  import scala.concurrent.duration._
  import Json._
  import audit._
  import AuditableInstances._
  import nelson.storage.{run => runs, StoreOp, StoreOpF}
  import Manifest.Versioned

  /**
   * NelsonK[U] provides us with a context that accepts a configuration and gives us a scalaz Task[U]
   * NelsonFK[F[_[_], _], U] provides a context that accepts a configuration and gives us an F[Task, U]
   *    this gives us the flexibility of defining a monadic function that yields scalaz streams of 
   *    Task and U as emitted values (e.g. Process[Task, U], Sink[Task, U]) 
   */
  type NelsonK[U] = Kleisli[Task, NelsonConfig, U]
  type NelsonFK[F[_[_], _], U] = Kleisli[F[Task, ?], NelsonConfig, U]

  type StorageK[U] = Kleisli[Task, storage.StoreOp ~> Task, U]

  /**
   * a simple lift operation for easy construction of a NelsonFK
   */
  def lift[F[_[_], _], U](f: NelsonConfig => F[Task, U]) = Kleisli[F[Task, ?], NelsonConfig, U](f)

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
    Kleisli[Task, NelsonConfig, AccessToken] { cfg =>
      if(cfg.security.useEnvironmentSession)
        Task.delay {
          logger.warn("Using environment session authentication. Should only be used for development.")
          AccessToken(sys.env("GITHUB_TOKEN"))
        }
      else Github.Request.fetchAccessToken(code).runWith(cfg.github)
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
        c <- Github.Request.fetchUserData(githubToken).runWith(cfg.github)
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
      repos.toNel.cata(r => StoreOp.deleteRepositories(r), ().point[StoreOpF])

    def insertRepos(repos: List[Repo]): StoreOpF[Unit] =
      StoreOp.insertOrUpdateRepositories(repos) >> StoreOp.linkRepositoriesToUser(repos,session.user)

    Kleisli { cfg =>
      for {
        nelson <- storage.run(cfg.storage, session.user.orgs.traverseM(o =>
                    StoreOp.listRepositoriesWithOwner(session.user, o.slug)))
        github <- Github.Request.listUserRepositories(session.github).runWith(cfg.github)
        repos   = github.filterNot(r => blacklist(cfg.git.organizationBlacklist)(r.slug.owner)).map(augment(nelson)(_))
        delete  = diff(nelson, github)

        _ <- storage.run(cfg.storage, insertRepos(repos) >> deleteRepos(delete))
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
      Github.WebHook.create("release" :: Nil, uri)
    }

    def getOrCreate(slug: Slug, hk: Github.WebHook): NelsonK[Github.WebHook] = Kleisli { cfg =>
      val make = Github.Request.createRepoWebhook(slug, hk)(session.github).runWith(cfg.github)
      Github.Request.fetchRepoWebhooks(slug)(session.github).runWith(cfg.github).flatMap(list =>
        list.find(_.callback == hk.callback)
          .fold(make)(Task.now(_))).or(make)
    }

    Kleisli { cfg =>
      for {
        r1  <- storage.run(cfg.storage,storage.StoreOp.findRepository(session.user, slug))
        _   <- log(s"result of findRepository: r1 = $r1")

        // ensure we can access and read the .nelson.yml file
        str <- fetchRawRepoManifest(session.github)(slug).run(cfg)
        mnf <- yaml.ManifestParser.parseTask(str) <*
               log(s"sucsessfully read and load the repo manifest file")

        rhk  = hook(cfg)

        out <- (cfg.auditor.write(rhk, CreateAction, login = session.user.login) *>
                getOrCreate(slug, rhk)(cfg))

        r2  <- (log(s"result of createRepoWebhook: out = $out") *>
                r1.tfold(RepoNotFound(slug))(_.copy(hook = Some(Hook(out.id, out.active)))))

        foo <- (log(s"result of repo copy: r2 = $r2") *>
                storage.run(cfg.storage, storage.StoreOp.insertOrUpdateRepositories(r2 :: Nil)) <*
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
        rep <- storage.run(cfg.storage, storage.StoreOp.findRepository(session.user, slug))
        rrr <- rep.tfold(RepoNotFound(slug))(identity)
        id  <- rrr.hook.tfold(UnexpectedMissingHook(slug))(_.id)
        _   <- cfg.auditor.write(rrr, DeleteAction, login = session.user.login)
        _   <- Github.Request.deleteRepoWebhook(slug, id)(session.github).runWith(cfg.github)
        .or(Task.now(()))
        upd  = Repo(rrr.id, rrr.slug, rrr.access, None)
        _   <- storage.run(cfg.storage, storage.StoreOp.insertOrUpdateRepositories(upd :: Nil))
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
              t <- storage.run(cfg.storage, storage.StoreOp.listRepositoriesWithOwner(session.user, o))
            } yield t
          case None    =>
            for {
              _ <- log(s"listing repositories with active hooks for '${session.user}'")
              t <- storage.run(cfg.storage, storage.StoreOp.listRepositoriesWithActiveHooks(session.user))
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
  def fetchRepoManifestAndValidateDeployable(slug: Slug, tagOrBranch: String = "master"): NelsonK[ValidationNel[NelsonError, Manifest]] = {
    for {
      cfg <- config
      token = cfg.git.systemAccessToken
      raw <- fetchRawRepoManifest(token)(slug, tagOrBranch)
      mnf <- ManifestValidator.parseManifestAndValidate(raw, cfg).liftKleisli
    } yield mnf
  }

  def fetchRawRepoManifest(token: AccessToken)(slug: Slug, tagOrBranch: String = "master"): NelsonK[String] =
    Kleisli { cfg =>
      for {
        _   <- log(s"about to fetch ${cfg.manifest.filename} from ${slug}:${tagOrBranch} repository")
        raw <- Github.Request.fetchFileFromRepository(slug, cfg.manifest.filename, tagOrBranch)(token).runWith(cfg.github)
        dec <- raw.tfold(ProblematicRepoManifest(slug))(_.decoded)
      } yield dec
    }

  /*
   * Reaches out to github and retrieves the manifest for a specific release,
   * validates and then saturates with deployables.
   */
  def getVersionedManifestForRelease(r: Released): NelsonK[Manifest @@ Versioned] = {
    val e = Github.ReleaseEvent(r.releaseId, r.slug, 0)
    for  {
      cfg <- config
      g   <- fetchRelease(e)
      v   <- fetchRepoManifestAndValidateDeployable(e.slug, g.tagName)
      m   <- v.fold(e => Task.fail(MultipleValidationErrors(e)), m => Task.now(m)).liftKleisli
      ms  <- Manifest.saturateManifest(m)(g).liftKleisli
    } yield ms
  }

  private def fetchRelease(e: Github.ReleaseEvent): NelsonK[Github.Release] = Kleisli { cfg =>
    val t = cfg.git.systemAccessToken

    Github.Request.fetchRelease(e.slug, e.id)(t).runWith(cfg.github)
      .ensure(MissingReleaseAssets(e))(_.assets.nonEmpty)
      .retryExponentially(2.seconds, 3)
  }

  def deploy(actions: List[Manifest.Action]): NelsonK[Unit] =
    Kleisli(cfg => actions.traverse_(cfg.queue.enqueueOne))

  /**
   * Invoked when the inbound webhook from Github arrives, notifying Nelson
   * that a new deployment needs to take place.
   */
  def handleRelease(e: Github.ReleaseEvent): NelsonK[Unit] = {
    import Manifest.{Namespace,Plan,UnitDef,Loadbalancer,Action}
    import Actionable._

    // convert units in the manifest to action.
    // filter out all units that are not in the provided namespace (ns)
    def unitActions(m: Manifest @@ Versioned, ns: NamespaceName, dcs: Seq[Datacenter]): List[Action] = {
      val unitFilter: (Datacenter,Namespace,Plan,UnitDef) => Boolean =
        (_,namespace,_,_) => namespace.name == ns

       Manifest.unitActions(m, dcs, unitFilter)
    }

    Kleisli { cfg =>
      for {
        r  <- fetchRelease(e).run(cfg)
        v  <- (log(s"fetched full release from github: $r") *>
                fetchRepoManifestAndValidateDeployable(e.slug, r.tagName).run(cfg))
        m  <-  v.fold(e => Task.fail(MultipleErrors(e)), m => Task.now(m))

        hm <- (log(s"received manifest from github: $m")
              *> storage.run(cfg.storage, storage.StoreOp.createRelease(e.repositoryId, r))
              *> cfg.auditor.write(r, CreateAction, Option(r.id))
              *> log(s"created release in response to release ${r.id}")
              *> Manifest.saturateManifest(m)(r))

        _  <- (storeManifest(hm, e.repositoryId).run(cfg)
              *> log("stored the release manifest in the database"))

        _ <- deploy(unitActions(hm, cfg.defaultNamespace, cfg.datacenters)).run(cfg)
      } yield ()
    }
  }

  def storeManifest(m: Manifest @@ Versioned, repoId: ID): NelsonK[Unit] = {
    Kleisli { cfg =>
      val mnf = Manifest.Versioned.unwrap(m)
      storage.run(cfg.storage,
        mnf.units.traverse_(u => StoreOp.addUnit(Versioned(u), repoId)) >>
        mnf.loadbalancers.traverse_(lb => StoreOp.insertLoadbalancerIfAbsent(Versioned(lb), repoId).map(_ => ()))
      )
    }
  }

  /**
   * list releases this repository has had. Feels a little hack to be doing
   * the jiggery pokery with ==>> but whatever, its better than using Map.
   */
  def listRepositoryReleases(s: Slug): NelsonK[Released ==>> List[ReleasedDeployment]] =
    Kleisli { cfg =>
      storage.run(cfg.storage, storage.StoreOp.listRecentReleasesForRepository(s))
    }

  /**
   * list recent releases, regardless of repository.
   */
  def listReleases(limit: Option[Int]): NelsonK[Released ==>> List[ReleasedDeployment]] =
    Kleisli { cfg =>
      for {
        a <- (limit.fold(Task.now(50))(l =>
                if(l > 50) Task.fail(ExceededLimitRange(l))
                else Task.now(l)))
        b <- storage.run(cfg.storage, storage.StoreOp.listReleases(a))
      } yield b
    }

  /**
   * Given the ID of a particular release, show the release information and any associated deployments
   */
  def getRelease(id: Long): NelsonK[Released ==>> List[ReleasedDeployment]] =
    Kleisli(cfg => storage.run(cfg.storage, storage.StoreOp.findRelease(id)))

  /**
   * Given a repo slug and a specific unit, figure out what the state of the latest released version is,
   * for the specified unit only. This is primarily used to power the SVG badging.
   *
   * TIM: REALLY NEEDS CACHING TO SCALE; *PROBALLY* TOO EXPENSIVE RIGHT NOW.
   */
  def getStatusOfReleaseUnit(s: Slug, u: UnitName): NelsonK[Option[DeploymentStatus]] =
    Kleisli { cfg =>
      cfg.caches.stackStatusCache.get((s.owner, s.repository, u))
        .fold(storage.run(cfg.storage, storage.StoreOp.findLastReleaseDeploymentStatus(s,u))
          )(x => Task.now(Option(x)))
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
      storage.run(cfg.storage, prog)
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
    list.traverse_(dc => storage.run(cfg.storage, storage.StoreOp.createDatacenter(dc)))
  }

  /*
   * This is a bootstrapping function - each instance of Nelson has a default
   * namespace defined in the Nelson configuration. This function creates
   * the default namespace for each datacenter if it doesn't already exist
   *
   * This function should only ever be called at bootup
   */
  def createDefaultNamespaceIfAbsent(dcs: List[Datacenter], ns: NamespaceName): NelsonK[Unit] =
    Kleisli(cfg =>
      storage.run(cfg.storage,
        dcs.traverse(dc => getOrCreateNamespace(dc.name, ns))).map(_ => ()))

  /**
   * List all the datacenters Nelson is currently aware of, and return
   * the namespaces associated with that datacenter.
   */
  def listDatacenters: NelsonK[Map[Datacenter, Set[Namespace]]] = Kleisli { cfg =>
    Nondeterminism[Task].gatherUnordered(
      cfg.datacenters.map(d =>
        storage.run(cfg.storage,
                    storage.StoreOp.listNamespacesForDatacenter(d.name).map(d -> _)))).map(_.toMap)
  }

  /**
   * Fetch a specific datacenter based on its name, along with the namespaces
   * that are avalible in that specific datacenter (if any).
   */
  def fetchDatacenterByName(name: String): NelsonK[Option[(Datacenter, Set[Namespace])]] =
    Kleisli { cfg =>
      cfg.datacenters.find(_.name.trim.toLowerCase == name.trim.toLowerCase).traverse(dc =>
        storage.run(cfg.storage, storage.StoreOp.listNamespacesForDatacenter(dc.name).map(dc -> _))
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
      datacenters.traverseM(dc => listDatacenterDeployments(dc,ns,status,unit).run(cfg).map(_.map(x => (dc,x._1,x._2, x._3))))
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
    val empty: Task[List[(Namespace,Deployment,DeploymentStatus)]] = List.empty.point[Task]

    def findNsByName(set: Set[Namespace]): NelsonK[List[(Namespace,Deployment,DeploymentStatus)]] = Kleisli { cfg =>
      val namespaces = set.toList.filter(x => ns.toList.exists(_ == x.name)) // only query for namepaces in this dc
      namespaces.traverseM { n =>
        storage.run(cfg.storage, storage.StoreOp.listDeploymentsForNamespaceByStatus(n.id, status, unit))
          .map((s: Set[(Deployment,DeploymentStatus)]) => s.toList.map(pair => (n,pair._1,pair._2)))
      }
    }

    for {
      a <- fetchDatacenterByName(dcName)
      b <- a.fold[NelsonK[List[(Namespace,Deployment,DeploymentStatus)]]](Kleisli(_ => empty)){
        case (dc,set) => findNsByName(set)
      }
    } yield b
  }

  def getOrCreateNamespace(dc: DatacenterRef, ns: NamespaceName): StoreOpF[ID] =
    StoreOp.getNamespace(dc, ns).flatMap(_.cata(
      some = n => n.id.point[StoreOpF],
      none = StoreOp.createNamespace(dc, ns)))

  /*
   * Creates namespace hierarchy starting at the root and working downward.
   * If a namespace already exists, it's a noop and continues to the next
   * in hierarchy.
   */
  def recursiveCreateNamespace(dc: DatacenterRef, ns: NamespaceName): NelsonK[Unit] =
    Kleisli { cfg =>
      for {
         opt <- cfg.datacenters.find(_.name.trim.toLowerCase == dc.trim.toLowerCase).point[Task]
         _   <- opt.tfold(UnknownDatacenter(dc))(identity)
         _   <- storage.run(cfg.storage, ns.hierarchy.traverse(n => getOrCreateNamespace(dc, n)))
      } yield ()
    }

  /*
   * Creates namespace hierarchy only if root namespace already exists.
   */
  def recursiveCreateSubordinateNamespace(dc: DatacenterRef, ns: NamespaceName): NelsonK[Unit] =
    Kleisli { cfg =>
      for {
        root <- storage.run(cfg.storage, StoreOp.getNamespace(dc, ns.root))
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

    val empty: Task[List[(Namespace,RoutingGraph)]] = List.empty.point[Task]
    for {
      a <- fetchDatacenterByName(dc)
      b <- a.fold[NelsonK[List[(Namespace, RoutingGraph)]]](Kleisli(_ => empty)){
        case (dc,set) =>

          val namespaces =
            if (ns.isEmpty) set.toList // if no ns supplied use them all
            else set.toList.filter(n => ns.exists(_ == n.name))

          Kleisli(cfg => storage.run(cfg.storage, getRoutingGraphByNamespaces(namespaces)))
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
    import Scalaz._
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
      t <- Kleisli[Task, NelsonConfig, Option[(Namespace,Datacenter.Deployment,RoutingGraph)]]{ config =>
        storage.run(config.storage, getDplWithNs.run)
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
    health: List[ConsulHealthStatus],
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
      } yield (dep,exp,st,dep.namespace)).run

      (for {
        a   <- OptionT(storage.run(cfg.storage, query))
        (dep, exp, status, ns) = a
        dc  <- OptionT(Task.now(cfg.datacenters.find(_.name == ns.datacenter)))
        sum <- OptionT(scheduler.run(dc.interpreters.scheduler, scheduler.SchedulerOp.summary(dc, dep.stackName)))
        h   <- OptionT(helm.run(dc.consul,
                  helm.ConsulOp.healthCheckJson[ConsulHealthStatus](dep.stackName.toString)).map(_.toOption))
      } yield RuntimeSummary(sum, h, status, exp)).run
    }
  }

  final case class CommitUnit(unitName: UnitName, version: Version, target: NamespaceName)

  /*
   * Commit unit to namespace given a github release event by deploying it into the given datacenters
   */
  def commit(un: UnitName, ns: NamespaceName, dcs: List[Datacenter], m: Manifest @@ Versioned): NelsonK[Unit] = {
    import Manifest.{Namespace,Plan,UnitDef,Action}

    // fiter out everything that doesn't belong to this unit / namespace / datacenter
    val unitFilter: (Datacenter,Namespace,Plan,UnitDef) => Boolean =
      (dc,namespace,_,unit) => dcs.exists(_ == dc) && namespace.name == ns && unit.name == un

    Kleisli { cfg =>
      for {
        v <- Task.delay(ManifestValidator.validateOnCommit(un, ns, Versioned.unwrap(m)))
        _ <- v.fold(e => Task.fail(MultipleValidationErrors(e)), m => Task.now(m))
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
        a  <- runs(cfg.storage, StoreOp.findReleasedByUnitNameAndVersion(un, v))
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
    def validateDeployment(d: Deployment): Task[Unit] =
      if (d.workflow == "manual")
        Task.fail(DeploymentCommitFailed("can't redeploy a manual deployment"))
      else Task.now(())

    Kleisli { cfg =>
      for {
        a   <- runs(cfg.storage, storage.StoreOp.getDeploymentByGuid(deploymentGuid))
        d   <- a.tfold(MissingDeployment(deploymentGuid))(identity)
        _   <- validateDeployment(d)
        ns   = d.namespace
        dct <- cfg.datacenters.find(_.name == ns.datacenter).point[Task]
        dc  <- dct.tfold(MisconfiguredDatacenter(ns.datacenter, s"couldn't be found"))(identity)
        rm  <- runs(cfg.storage, storage.StoreOp.findReleaseByDeploymentGuid(d.guid))
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
        a <- storage.run(config.storage, storage.StoreOp.getDeploymentByGuid(guid))
        d <- a.tfold(MissingDeployment(guid))(identity(_))
        ns = d.namespace
        dc <- config.datacenters.find(_.name == ns.datacenter).fold[Task[Datacenter]](Task.fail(MisconfiguredDatacenter(ns.datacenter, "couldn't be found")))(Task.now)
        status <- storage.run(config.storage, storage.StoreOp.listDeploymentStatuses(d.id))
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
      datacenters.traverseM(dc => listDatacenterUnitsByStatus(dc,ns,status).run(cfg)
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
      namespaces.traverseM(n =>
        storage.run(cfg.storage, storage.StoreOp.listUnitsByStatus(n.id, status)
          .map(s => s.toList.map(x => (n,x._1,x._2)))))
    }

    val empty: Task[List[(Namespace,GUID,ServiceName)]] = List.empty.point[Task]

    for {
      a <- fetchDatacenterByName(dc)
      b <- a.fold[NelsonK[List[(Namespace, GUID, ServiceName)]]](Kleisli(_ => empty)){
        case (dc,set) => findNsByName(set)
      }
    } yield b
  }

  /*
   * Returns the latest expiration for deployment
   */
  def findDeploymentExpiration(guid: GUID): NelsonK[Option[Instant]] =
    Kleisli(cfg => runs(cfg.storage, StoreOp.findDeploymentExpirationByGuid(guid)))

  /**
   * Create a manual deployment. That is to say, let Nelson know about something
   * that was not deployed via Nelson (e.g. databases and other ops-infrastructure)
   */
  def createManualDeployment(s: Session, m: Datacenter.ManualDeployment): NelsonK[GUID] = Kleisli { cfg =>

    def validateNamespace(ns: String): Task[NamespaceName] =
       NamespaceName.fromString(ns).fold(
         e => Task.fail(ManualDeployFailed(e.getMessage)),
         ns =>
          if (!ns.isRoot)
            Task.fail(ManualDeployFailed(s"namespace ${ns.asString} is not a root namespace"))
          else Task.now(ns))

    val ttl = cfg.cleanup.initialTTL.toSeconds
    val exp = Instant.now.plusSeconds(ttl)

    for {
      dc   <- cfg.datacenters.find(_.name == m.datacenter)
                 .fold[Task[Datacenter]](Task.fail(ManualDeployFailed(s"datacenter ${m.datacenter} couldn't be found")))(Task.now)
       ns   <- validateNamespace(m.namespace)
       guid <- storage.run(cfg.storage, StoreOp.createManualDeployment(
               dc,
               ns,
               m.serviceType,
               m.version,
               m.hash,
               m.description,
               m.port,
               exp)) <* cfg.auditor.write(m, CreateAction, login = s.user.login)
     } yield guid
  }

  def getDeploymentsByDatacenter(dc: Datacenter, f: Namespace => StoreOpF[List[Deployment]]): StoreOpF[Set[Deployment]] = {
    for {
      ns <- StoreOp.listNamespacesForDatacenter(dc.name).map(_.toList)
      ds <- ns.traverseM(ns => f(ns))
    } yield ds.toSet
  }

  /**
   * Deprecates all deloyments for a service / feature version accross all datacenters and namepaces
   */
  def deprecateService(sn: Datacenter.ServiceName): NelsonK[Unit] = Kleisli { cfg =>

    val ready = NonEmptyList(DeploymentStatus.Ready) // only deprecate deployments in the ready state

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
    storage.run(cfg.storage, prog)
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
    storage.run(cfg.storage, prog)
  }

  /*
   * Starts to reverse an in progress traffic shift given the to deployment's guid
   */
  def reverseTrafficShift(guid: GUID): NelsonK[Datacenter.TrafficShift] = {

    def validate(ts: TrafficShift): String \/ TrafficShift =
      if (ts.to.guid != guid) -\/(s"deployment ($guid) is not the target for latest traffic shift")
      else if (ts.reverse.isDefined) -\/("can't reverse a traffic shift that's already been reversed")
      else if (!ts.inProgress(Instant.now)) -\/("can't reverse a traffic shift that is currently not in progress")
      else \/-(ts)

    def reverse(ts: TrafficShift): StoreOpF[String \/ ID] = {
      val prog = for {
        id <- OptionT(StoreOp.reverseTrafficShift(ts.to.id, Instant.now))
                .toRight("unable to start traffic shift reverse")
      } yield id

      prog.run
    }

    Kleisli { cfg =>

      val prog = for {
        dep <- OptionT(StoreOp.getDeploymentByGuid(guid))
                .toRight(s"deployment with guid $guid not found")
        ts  <- OptionT(StoreOp.getTrafficShiftForServiceName(dep.nsid, dep.unit.serviceName))
                .toRight(s"unable to find traffic shift for to deployment ${dep.stackName}")
        _   <- EitherT(validate(ts).point[StoreOpF])
        _   <- EitherT(reverse(ts))
      } yield ts

      storage.run(cfg.storage, prog.run)
       .flatMap(_.fold(e => Task.fail(InvalidTrafficShiftReverse(e)), r => Task.now(r)))
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
    Kleisli(cfg => runs(cfg.storage, deprecated.deploymentsWithDeprecatedDependencies(dc)))
  }

  def listAuditEvents(
    limit: Long, offset: Long, releaseId: Option[Long],
    action: Option[String], category: Option[String]): NelsonK[List[AuditLog]] =
    Kleisli { cfg =>
      storage.run(cfg.storage,
                  releaseId.fold(storage.StoreOp.listAuditLog(limit, offset, action, category))(
                    storage.StoreOp.listAuditLogByReleaseId(limit, offset, _)))
    }

  def fetchWorkflowLog(guid: GUID, offset: Int): NelsonK[Option[(Int, List[String])]] = {
    val empty: Task[Option[(Int,List[String])]] = Task.now(Option.empty[(Int,List[String])])
    for {
      cfg <- config
      a <- storage.run(cfg.storage, StoreOp.getDeploymentByGuid(guid)).liftKleisli
      b <- a.fold(empty)(dpl => cfg.workflowLogger.read(dpl.id, offset).map(l => Some(offset -> l))).liftKleisli
    } yield b
  }

  //////////////////////// LOADBALANCERS /////////////////////////////
  import Datacenter.LoadbalancerDeployment

  def getLoadbalancerByGUID(guid: GUID): NelsonK[Option[LoadbalancerDeployment]] =
    Kleisli(cfg => storage.run(cfg.storage, StoreOp.getLoadbalancerDeploymentByGUID(guid)))

  // Launches loadbalancer with name and major version into the given datacenter and namespace
  def commitLoadbalancer(name: String, v: Int, dcName: DatacenterRef, ns: NamespaceName): NelsonK[Unit] = {
    import Manifest.{Loadbalancer,Plan,Namespace,Action}

    val mv = MajorVersion(v)

    // fiter out everything that doesn't belong to this loadbalancer / namespace / datacenter
    val lbFilter: (Datacenter,Namespace,Plan,Loadbalancer) => Boolean =
      (dc,namespace,_,lb) => dcName == dc.name && namespace.name == ns && lb.name == name

    def validateAndDeployActions(acts: List[Action], m: Manifest @@ Versioned, cfg: NelsonConfig): Task[Unit] = {
      if (acts.isEmpty)
        Task.fail(DeploymentCommitFailed(
          s"Manifest does not reference loadbalancer ${name} ${mv} in namespace ${ns}"))
      else
        deploy(acts).run(cfg)
    }

    Kleisli { cfg =>
      for {
        lbo <- storage.run(cfg.storage, StoreOp.getLoadbalancer(name, mv))
        lb  <- lbo.tfold(DeploymentCommitFailed(s"couldn't find loadbalancer: $name $mv"))(identity)
        rr  <- storage.run(cfg.storage, StoreOp.getLatestReleaseForLoadbalancer(name, mv))
        r   <- rr.tfold(DeploymentCommitFailed(s"couldn't find latest release for loadblancer $name $mv"))(identity)
        ms  <- getVersionedManifestForRelease(r).run(cfg)
        act  = Manifest.loadbalancerActions(ms, cfg.datacenters, lbFilter)
        _ <- validateAndDeployActions(act, ms, cfg)
      } yield ()
    }
  }

  // Delete loadbalancer deployment: First delete loadbalancer in the datacenter, next
  // delete loadbalancer from nelson's internal h2 tables
  def deleteLoadbalancerDeployment(guid: GUID): NelsonK[Unit] = {
    Kleisli { cfg =>
      for {
        a  <- storage.run(cfg.storage, StoreOp.getLoadbalancerDeploymentByGUID(guid))
        lb <- a.tfold(LoadbalancerNotFound(guid))(identity)
        ns <- storage.run(cfg.storage, StoreOp.getNamespaceByID(lb.nsid))
        dc <- cfg.datacenters.find(_.name == ns.datacenter)
                .fold[Task[Datacenter]](Task.fail(MisconfiguredDatacenter(ns.datacenter, "couldn't be found")))(Task.now)
        _  <- dc.loadbalancer.traverse(trans =>
                loadbalancers.run(trans,  loadbalancers.delete(lb,dc,ns)))
        _  <- storage.run(cfg.storage, StoreOp.deleteLoadbalancerDeployment(lb.id))
        _  <- helm.run(dc.consul, loadbalancers.deleteLoadbalancerConfigFromConsul(lb))
      } yield ()
    }
  }

  // Lists all loadbalancer deployments in a given set of datacenters and namespaces
  def listLoadbalancers(dcs: List[String], ns: NonEmptyList[NamespaceName]): NelsonK[List[(DatacenterRef,Namespace,LoadbalancerDeployment)]] = {
    Kleisli { cfg =>
      val datacenters = if (dcs.isEmpty) cfg.datacenters.map(_.name) else dcs
      datacenters.traverseM(dc => listLoadbalancerDeployments(dc,ns).run(cfg).map(_.map(x => (dc,x._1,x._2))))
    }
  }

  def listLoadbalancerDeployments(dc: DatacenterRef, ns: NonEmptyList[NamespaceName]): NelsonK[List[(Namespace,LoadbalancerDeployment)]] = {
    val empty: Task[List[(Namespace,LoadbalancerDeployment)]] = List.empty.point[Task]

    def findNsByName(set: Set[Namespace]): NelsonK[List[(Namespace,LoadbalancerDeployment)]] = Kleisli { cfg =>
      val namespaces = set.toList.filter(x => ns.toList.exists(_ == x.name)) // only query for namepaces in this dc
      namespaces.traverseM(n =>
        storage.run(cfg.storage, storage.StoreOp.listLoadbalancerDeploymentsForNamespace(n.id)
          .map(s => s.toList.map(n -> _))))
    }

    for {
      a <- fetchDatacenterByName(dc)
      b <- a.fold[NelsonK[List[(Namespace, LoadbalancerDeployment)]]](Kleisli(_ => empty)){
        case (dc,set) => findNsByName(set)
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
      storage.run( cfg.storage, (for {
        lb <- OptionT(storage.StoreOp.getLoadbalancerDeploymentByGUID(guid))
        ns <- OptionT(storage.StoreOp.getNamespaceByID(lb.nsid).map(Option(_)))
        od <- OptionT(RoutingTable.routingGraph(ns).map(Option(_)))
      } yield {
        LoadbalancerSummary(ns, lb, getOuts(od, lb))
      }).run)
    }
  }

  //////////////////////// INTERNALS ////////////////////////////

  protected val config: NelsonK[NelsonConfig] = Kleisli.ask

  private def blacklist(owners: List[String])(owner: String): Boolean =
    owners.exists(_.trim.toLowerCase == owner.trim.toLowerCase)

  private def log(msg: String): Task[Unit] =
    Task.delay(logger.info(msg))
}
