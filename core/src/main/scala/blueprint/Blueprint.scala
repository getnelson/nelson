package nelson.blueprint

import cats.Eq

import org.fusesource.scalate.{Template, TemplateEngine, TemplateSource}

final class Blueprint private(private val template: Template, override val toString: String) {
  def render(substitutes: Map[String, String]): String =
    // We get away with empty string here because we turn off
    // caching in the template engine
    Blueprint.engine.layout("", template, substitutes)
}

object Blueprint {
  /** Create a template from a raw string. */
  def load(id: String, templateString: String): Blueprint = {
    // NOTE: If TemplateEngine has caching on, it will use the id
    // as the cache key. We also need to tack on '.mustache' here
    // since Scalate uses the assumed extension to determine
    // which backend to use :fire:
    val source = TemplateSource.fromText(s"${id}.mustache", templateString)
    val template = engine.load(source)
    new Blueprint(template, templateString)
  }

  private val engine = {
    val te = new TemplateEngine()
    te.allowReload  = false
    te.allowCaching = false
    te
  }

  implicit val nelsonBlueprintBlueprintInstances: Eq[Blueprint] = new Eq[Blueprint] {
    def eqv(x: Blueprint, y: Blueprint): Boolean = x.toString == y.toString
  }
}
