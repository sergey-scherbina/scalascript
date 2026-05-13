#!/usr/bin/env -S scala-cli shebang

//> using scala 3.3
//> using toolkit default
//> using dep org.yaml:snakeyaml:2.2

import org.yaml.snakeyaml.Yaml
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
    val yaml = Yaml()
    val data = yaml.load[java.util.Map[String, Any]](yamlContent)
    if data == null then Map.empty[String, Any]
    else data.asScala.toMap
  } match
    case Success(data) => Some(Right(data))
    case Failure(e) => Some(Left(e.getMessage))

def validateSchema(data: Map[String, Any]): List[String] =
  val errors = collection.mutable.ListBuffer[String]()

  // Validate 'name' field
  data.get("name").foreach {
    case s: String =>
      if !s.matches("^[a-z][a-z0-9-]*$") then
        errors += s"'name' must match pattern ^[a-z][a-z0-9-]*$$ (got: $s)"
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
