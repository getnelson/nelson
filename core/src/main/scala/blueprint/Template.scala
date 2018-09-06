package nelson.blueprint

import cats.Eq

import org.fusesource.scalate.{Template => STemplate, TemplateEngine, TemplateSource}

final class Template private(private val template: STemplate, override val toString: String) {
  def render(env: Map[String, EnvValue]): String =
    // We get away with empty string here because we turn off
    // caching in the template engine
    Template.engine.layout("", template, Template.envToMap(env.toList, Map.empty))
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

  @annotation.tailrec
  private def envToMap(envs: List[(String, EnvValue)], acc: Map[String, Any]): Map[String, Any] = envs match {
    case Nil => acc
    case (key, value) :: tail => envToMap(tail, acc + ((key, extractValue(value))))
  }

  import EnvValue._
  private def extractValue(env: EnvValue): Any = env match { // YOLO
    case StringValue(v) => v
    case ListValue(v) => v.map(extractValue)
    case MapValue(v) => v.map { case (k, v) => (k, extractValue(v)) }
  }
}

sealed abstract class EnvValue extends Product with Serializable

object EnvValue {
  final case class StringValue(value: String) extends EnvValue
  final case class ListValue(value: List[EnvValue]) extends EnvValue
  final case class MapValue(value: Map[String, EnvValue]) extends EnvValue
}
