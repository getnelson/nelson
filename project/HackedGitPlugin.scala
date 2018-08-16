package verizon.build

import java.io.File
import sys.process.{ Process, ProcessBuilder, ProcessLogger }
import sbtrelease.{Git,VcsCompanion,ReleasePlugin}, ReleasePlugin.autoImport.releaseVcs
import sbt._, Keys._

object HackedGitPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = RigPlugin
  override lazy val projectSettings = Seq(
    releaseVcs := {
      Some(verizon.build.HackedGit.mkVcs(baseDirectory.value))
    }
  )
}

object HackedGit extends VcsCompanion {
  protected val markerDirectory = ".git"

  def mkVcs(baseDir: File) = new HackedGit(baseDir)
}

class HackedGit(baseDir: File) extends Git(baseDir){ self =>
  // only push tags, becase you end up with errors like:
  // hint: Updates were rejected because the tip of your current branch is behind
  // hint: its remote counterpart. Integrate the remote changes (e.g.
  // hint: 'git pull ...') before pushing again.```
  override def pushChanges = self.pushTags
  private def pushTags = cmd("push", "--tags", trackingRemote)
}
