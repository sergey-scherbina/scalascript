package scalascript.config

/** Emits Scala code / files to make config values available in JVM-compiled output.
 *
 *  Three strategies:
 *  - `Embedded`         — `val __ssc_config: Map[String, Any] = Map(...)` in the generated `.sc`
 *  - `ApplicationConf`  — write TypesafeConfig-compatible `application.conf` alongside `.sc`
 *  - `CompanionObject`  — generate `object AppConfig { val port: Int = 8080; ... }`
 */
object ScalaConfigEmitter:

  enum Strategy:
    case Embedded
    case ApplicationConf
    case CompanionObject

  object Strategy:
    def fromString(s: String): Strategy = s.toLowerCase.trim match
      case "application.conf" | "hocon" | "typesafe" => ApplicationConf
      case "object" | "companion" | "companion-object" => CompanionObject
      case _                                            => Embedded

    def fromConfigValue(cv: ConfigValue): Strategy =
      cv.get("config.scala-output").flatMap(_.getString)
        .fold(Embedded)(fromString)

  /** Emit Scala source code (preamble) for the given strategy. */
  def emitPreamble(cv: ConfigValue, strategy: Strategy): String = strategy match
    case Strategy.Embedded        => emitEmbedded(cv)
    case Strategy.ApplicationConf => ""   // config goes to a file; preamble is empty
    case Strategy.CompanionObject => emitCompanionObject(cv)

  /** Emit `val __ssc_config = Map(...)` embedded in generated Scala code. */
  private def emitEmbedded(cv: ConfigValue): String =
    val entries = flattenToScala(cv, "")
    val mapEntries = entries.map { case (k, v) => s""""$k" -> $v""" }.mkString(",\n  ")
    s"""private val __ssc_config: Map[String, Any] = Map(
       |  $mapEntries
       |)
       |private def __ssc_cfg[A](key: String): Option[A] =
       |  __ssc_config.get(key).map(_.asInstanceOf[A])
       |""".stripMargin

  /** Emit a companion object with typed fields for each top-level key. */
  private def emitCompanionObject(cv: ConfigValue): String =
    val fields = cv match
      case ConfigValue.Map(m) =>
        m.toList.flatMap { case (k, v) =>
          v match
            case ConfigValue.Map(_) => None   // skip nested — too complex for Phase 10
            case leaf =>
              val (scalaType, scalaVal) = scalaLiteral(leaf)
              Some(s"  val ${sanitizeName(k)}: $scalaType = $scalaVal")
        }
      case _ => Nil
    s"""object AppConfig:
       |${fields.mkString("\n")}
       |""".stripMargin

  /** Write a TypesafeConfig `application.conf` HOCON file. */
  def writeApplicationConf(cv: ConfigValue, outputDir: java.nio.file.Path): Unit =
    val hocon = toHocon(cv, "")
    java.nio.file.Files.writeString(outputDir.resolve("application.conf"), hocon)

  /** Render ConfigValue as HOCON text. */
  def toHocon(cv: ConfigValue, indent: String): String = cv match
    case ConfigValue.Str(s)   => s""""${s.replace("\"", "\\\"")}""""
    case ConfigValue.Num(n)   => if n == n.toLong then n.toLong.toString else n.toString
    case ConfigValue.Bool(b)  => b.toString
    case ConfigValue.Null     => "null"
    case ConfigValue.Lst(vs)  => vs.map(v => s"$indent  ${toHocon(v, indent)}").mkString("[\n", "\n", s"\n$indent]")
    case ConfigValue.Map(m)   =>
      m.map { case (k, v) =>
        v match
          case ConfigValue.Map(_) => s"$k {\n${toHocon(v, indent + "  ")}\n$indent}"
          case _                  => s"$k = ${toHocon(v, indent)}"
      }.mkString(s"\n$indent")

  private def flattenToScala(cv: ConfigValue, prefix: String): List[(String, String)] =
    cv match
      case ConfigValue.Map(m) =>
        m.toList.flatMap { case (k, v) =>
          val path = if prefix.isEmpty then k else s"$prefix.$k"
          flattenToScala(v, path)
        }
      case other =>
        List((prefix, scalaLiteral(other)._2))

  private def scalaLiteral(cv: ConfigValue): (String, String) = cv match
    case ConfigValue.Str(s)  => ("String",  s""""${s.replace("\"", "\\\"")}"""")
    case ConfigValue.Num(n)  =>
      if n == n.toInt then ("Int", n.toInt.toString)
      else ("Double", n.toString)
    case ConfigValue.Bool(b) => ("Boolean", b.toString)
    case ConfigValue.Null    => ("Any", "null")
    case _                   => ("Any", "null")

  private def sanitizeName(s: String): String =
    val id = s.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("^([0-9])", "_$1")
    if scala.util.Try(id.charAt(0)).map(_.isLetter).getOrElse(false) then id else s"_$id"
