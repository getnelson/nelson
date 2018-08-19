package nelson.blueprint

import cats.Eq

import org.fusesource.scalate.{Template => STemplate, TemplateEngine, TemplateSource}
import org.fusesource.scalate.util.{Resource, ResourceLoader}

final class Template private(private val template: STemplate, override val toString: String) {
  def render(substitutes: Map[String, String]): String =
    // We get away with empty string here because we turn off
    // caching in the template engine
    Template.engine.layout("", template, substitutes)
}

object Template {
  /** Create a template from a raw string. */
  def load(id: String, templateString: String): Template = {
    // NOTE: If TemplateEngine has caching on, it will use the id
    // as the cache key. We also need to tack on '.mustache' here
    // since Scalate uses the assumed extension to determine
    // which backend to use :fire:
    val source = TemplateSource.fromText(s"${id}.mustache", templateString)
    val template = engine.load(source)
    new Template(template, templateString)
  }

  private val engine = {
    val te = new TemplateEngine()
    te.allowReload  = false
    te.allowCaching = false
    te
  }

  implicit val nelsonBlueprintTemplateInstances: Eq[Template] = new Eq[Template] {
    def eqv(x: Template, y: Template): Boolean = x.toString == y.toString
  }
}

// TODO: Fetch from database?
class TemplateResourceLoader extends ResourceLoader {
  def resource(uri: String): Option[Resource] = ???
}
