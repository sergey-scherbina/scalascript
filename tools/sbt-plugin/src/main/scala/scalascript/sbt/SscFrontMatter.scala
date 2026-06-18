package scalascript.sbt

import sbt._

/** Phase 5 — dependency-resolution wiring (spec `arch-sbt-plugin.md` §3h).
 *
 *  Extracts Maven `dep:` coordinates from the YAML front-matter
 *  `dependencies:` map of `.ssc` source files and maps them to sbt
 *  [[ModuleID]]s, so the plugin can expose them as `libraryDependencies` and
 *  Coursier downloads them onto the JVM (test) classpath alongside the
 *  generated facade.
 *
 *  Deliberately narrow — the standalone plugin build has no YAML library and
 *  no dependency on the ScalaScript core.  It reads the leading `---` … `---`
 *  front-matter block, the `dependencies:` map (block *or* inline-flow form),
 *  and keeps only values that are Maven coordinates, using the same rule as
 *  core's `MavenDepResolver.isMavenCoordinate`:
 *
 *   - `dep:<group>:<artifact>:<version>`      → `group %  artifact % version`  (Java)
 *   - `dep:<group>::<artifact>:<version>`     → `group %% artifact % version`  (Scala-cross)
 *
 *  Non-Maven dependency values (local `.ssc` paths, URLs, `git:` schemes, …)
 *  are ignored here — `ssc build` resolves those; they are not JVM classpath
 *  entries.  Reading happens at project-load (setting evaluation) time, so a
 *  change to a `.ssc` file's `dependencies:` needs an sbt `reload` to take
 *  effect — the same caveat as any file-derived sbt setting.
 */
object SscFrontMatter {

  /** Maven [[ModuleID]]s derived from the front-matter `dependencies:` of all
   *  given `.ssc` files (deduped, order-stable). */
  def mavenDeps(files: Seq[File]): Seq[ModuleID] =
    files
      .flatMap(f => mavenCoords(IO.read(f)))
      .distinct
      .flatMap(toModuleID)

  /** The raw Maven coordinates (`dep:` stripped) declared by one `.ssc`
   *  source's front-matter `dependencies:` map. */
  private[sbt] def mavenCoords(source: String): Seq[String] =
    dependencyValues(source).filter(isMavenCoordinate).map(_.stripPrefix("dep:"))

  /** Mirrors core `MavenDepResolver.isMavenCoordinate`: a `dep:` value whose
   *  body has no `/` and at least three `:`-separated parts. */
  private[sbt] def isMavenCoordinate(raw: String): Boolean = {
    val body = raw.stripPrefix("dep:")
    raw.startsWith("dep:") && !body.contains("/") && body.split(":", -1).length >= 3
  }

  /** `g::a:v` → `g %% a % v` (Scala-cross, `CrossVersion.binary`);
   *  `g:a:v` → `g % a % v` (Java).  `None` for shapes we don't model
   *  (e.g. extra classifier/packaging segments). */
  private[sbt] def toModuleID(coord: String): Option[ModuleID] =
    if (coord.contains("::")) {
      val parts = coord.split("::", 2)
      parts(1).split(":") match {
        case Array(artifact, version) => Some(parts(0) %% artifact % version)
        case _                        => None
      }
    } else {
      coord.split(":") match {
        case Array(group, artifact, version) => Some(group % artifact % version)
        case _                               => None
      }
    }

  // ── narrow front-matter reader ─────────────────────────────────────────────

  /** Unquoted string values of the front-matter `dependencies:` map; empty if
   *  the source has no front-matter or no `dependencies:` key. */
  private[sbt] def dependencyValues(source: String): Seq[String] =
    frontMatter(source).map(dependencyValuesFromBlock).getOrElse(Seq.empty)

  /** Text between the leading `---` and the next `---` line.  The opening `---`
   *  must be the first non-blank, non-shebang line (matches the core parser);
   *  returns `None` if there is no closing `---`. */
  private[sbt] def frontMatter(source: String): Option[String] = {
    val lines   = source.split("\n", -1).toList
    val trimmed = lines.dropWhile(l => l.startsWith("#!") || l.trim.isEmpty)
    trimmed match {
      case head :: tail if head.trim == "---" =>
        val body = tail.takeWhile(_.trim != "---")
        if (body.length == tail.length) None // unterminated block
        else Some(body.mkString("\n"))
      case _ => None
    }
  }

  private def unquote(s: String): String = {
    val t = s.trim
    if (t.length >= 2 &&
        ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
      t.substring(1, t.length - 1)
    else t
  }

  private def dependencyValuesFromBlock(fm: String): Seq[String] = {
    val lines = fm.split("\n", -1).toVector
    val idx   = lines.indexWhere(_.trim.startsWith("dependencies:"))
    if (idx < 0) Seq.empty
    else {
      val depLine    = lines(idx)
      val afterColon = depLine.substring(depLine.indexOf("dependencies:") + "dependencies:".length).trim
      if (afterColon.startsWith("{")) {
        // inline-flow form:  dependencies: { a: "x", b: "y" }
        val inner = afterColon.stripPrefix("{").takeWhile(_ != '}')
        inner.split(",").toSeq.flatMap { kv =>
          val ci = kv.indexOf(':')
          if (ci < 0) None else Some(unquote(kv.substring(ci + 1)))
        }
      } else {
        // block form: subsequent more-indented `key: value` lines
        val depIndent = depLine.takeWhile(_ == ' ').length
        lines
          .drop(idx + 1)
          .takeWhile(l => l.trim.isEmpty || l.takeWhile(_ == ' ').length > depIndent)
          .filter(l => l.trim.nonEmpty && l.contains(":"))
          .map(l => unquote(l.substring(l.indexOf(':') + 1)))
      }
    }
  }
}
