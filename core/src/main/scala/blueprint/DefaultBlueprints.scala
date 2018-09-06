package nelson
package blueprint

import cats.effect.IO

import fs2.{io, text}

object DefaultBlueprints {
  private def templateFromClasspath(path: String): IO[Template] =
    io.readInputStream(IO(getClass.getClassLoader.getResourceAsStream(path)), 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .compile
      .toList
      .map(lines => Template.load(s"nelson-default-${path}", lines.mkString("\n")))

  object canopus {
    val service = templateFromClasspath("nelson/canopus_service.mustache")
    val cronJob = templateFromClasspath("nelson/canopus_cron_job.mustache")
    val job = templateFromClasspath("nelson/canopus_job.mustache")
  }
}
