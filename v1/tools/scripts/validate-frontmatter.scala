#!/usr/bin/env -S scala-cli shebang

//> using scala 3.3
//> using toolkit 0.9.2
//> using file ../../lang/yaml/src/main/scala/scalascript/parser/SimpleYaml.scala

import scalascript.parser.SimpleYaml
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Success, Failure}

@main def validateFrontmatter(): Unit =
  val root = os.pwd
  val sscFiles = os.walk(root).filter(_.ext == "ssc").toList

  if sscFiles.isEmpty then
    println("No .ssc files found")
    System.exit(0)

  var allValid = true

  for file <- sscFiles do
    val relativePath = file.relativeTo(root)
    extractFrontmatter(file) match
      case None =>
        println(s"✓ $relativePath (no front-matter)")
      case Some(Left(error)) =>
        allValid = false
        println(s"❌ $relativePath")
        println(s"   YAML parse error: $error")
      case Some(Right(data)) =>
        validateSchema(data) match
          case Nil =>
            println(s"✓ $relativePath")
          case errors =>
            allValid = false
            println(s"❌ $relativePath")
            errors.foreach(e => println(s"   $e"))

  if !allValid then System.exit(1)

def extractFrontmatter(file: os.Path): Option[Either[String, Map[String, Any]]] =
  val lines = os.read.lines(file).toList

  if lines.isEmpty || lines.head.trim != "---" then
    return None

  val endIndex = lines.tail.indexWhere(_.trim == "---")
  if endIndex == -1 then
    return None

  val yamlContent = lines.slice(1, endIndex + 1).mkString("\n")

  Try {
    val data = SimpleYaml.load[java.util.Map[String, Any]](yamlContent)
    if data == null then Map.empty[String, Any]
    else data.asScala.toMap
  } match
    case Success(data) => Some(Right(data))
    case Failure(e) => Some(Left(e.getMessage))

def validateSchema(data: Map[String, Any]): List[String] =
  val errors = collection.mutable.ListBuffer[String]()

  // `imports:` LOADS NOTHING, ON EVERY FRONT — refuse it rather than let a file believe otherwise.
  //
  // v2/BUGS.md `frontmatter-imports-is-not-an-import-on-any-front` is itself a CORRECTION: the
  // entry first said the key "is followed by v1 and by nothing in the tower" and proposed teaching
  // the tower to read it. v1 does not read it either. What settled it was a control the first
  // probe could not run — the first subject's `KV` is supplied AMBIENTLY by a plugin, so it
  // resolved with or without any import and could not discriminate. Only a name that NO plugin
  // supplies asks the question, and there the answer is `[ERROR] Undefined: mk`.
  //
  // So the key is metadata everywhere; `[names](path.ssc)` is the import spelling and there is no
  // other. A file carrying `imports:` believes it is importing and is not, and nothing says so —
  // which is why this is a refusal and not a warning.
  //
  // PREVENTION, NOT REPAIR: measured 2026-08-31, ZERO tracked `.ssc` files carry the key. The probe
  // was validated before that zero was believed — the same scan finds 1003 files with `name:` and
  // 301 with `backends:`, so it does read front-matter, and a zero from an unvalidated instrument
  // would have been worth nothing.
  if data.contains("imports") then
    errors += "'imports' is not an import: no front reads this key, on any lane. Use the " +
              "[names](path.ssc) spelling in the body instead — a file carrying this key believes " +
              "it is importing and is not (v2/BUGS.md frontmatter-imports-is-not-an-import-on-any-front)"

  // Validate 'name' field — must be a string; no pattern enforced
  data.get("name").foreach {
    case _: String => // any string value is fine
    case other =>
      errors += s"'name' must be a string (got: ${other.getClass.getSimpleName})"
  }

  // Validate 'version' field
  data.get("version").foreach {
    case s: String =>
      val semverPattern = """^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?(\+[a-zA-Z0-9.]+)?$""".r
      if semverPattern.findFirstIn(s).isEmpty then
        errors += s"'version' must be a valid semver (got: $s)"
    case other =>
      errors += s"'version' must be a string (got: ${other.getClass.getSimpleName})"
  }

  // Validate 'exports' field
  data.get("exports").foreach {
    case list: java.util.List[?] =>
      list.asScala.zipWithIndex.foreach {
        case (s: String, _) =>
          if !s.matches("^[A-Za-z_][A-Za-z0-9_]*$") then
            errors += s"export '$s' must be a valid identifier"
        case (other, idx) =>
          errors += s"exports[$idx] must be a string"
      }
    case other =>
      errors += s"'exports' must be a list (got: ${other.getClass.getSimpleName})"
  }

  // Validate 'targets' field
  data.get("targets").foreach {
    case list: java.util.List[?] =>
      val validTargets = Set("jvm", "js", "wasm", "native")
      list.asScala.foreach {
        case s: String if !validTargets.contains(s) =>
          errors += s"invalid target '$s' (valid: ${validTargets.mkString(", ")})"
        case _: String => // ok
        case other =>
          errors += s"target must be a string (got: ${other.getClass.getSimpleName})"
      }
    case other =>
      errors += s"'targets' must be a list (got: ${other.getClass.getSimpleName})"
  }

  errors.toList
