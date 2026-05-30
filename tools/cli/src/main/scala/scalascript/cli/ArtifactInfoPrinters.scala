package scalascript.cli

import scalascript.artifact.{ArtifactIO, JvmArtifactIO, JsArtifactIO}

/** Pretty-printers backing `ssc info <artifact>` for each artifact format
 *  (`.scim` / `.scir` / `.scjvm` / `.scjs`) plus the shared section-hash
 *  dump used by `--sections`.
 *
 *  Extracted verbatim from `Main.scala` (cli-main-helper-split-p2) — the
 *  `info` command imports `ArtifactInfoPrinters.*` so the call sites stay
 *  unqualified and behave identically. */
object ArtifactInfoPrinters:

  def printScimInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
    ArtifactIO.readInterface(json) match
      case Left(err) =>
        System.err.println(s"info: $err")
        System.exit(1)
      case Right(iface) =>
        if jsonMode then println(ArtifactIO.writeInterface(iface))
        else
          println(s"file: $path")
          println(s"format: .scim (module interface)")
          println(s"magic: ${iface.magic}")
          println(s"abiVersion: ${iface.abiVersion}")
          println(s"moduleId: ${iface.moduleName.getOrElse("<unnamed>")}")
          println(s"package: ${if iface.pkg.isEmpty then "<none>" else iface.pkg.mkString(".")}")
          println(s"moduleVersion: ${iface.moduleVersion.getOrElse("<none>")}")
          println(s"sourceHash: ${iface.sourceHash}")
          println(s"size: $fileSize bytes")
          println(s"exports: ${iface.exports.length}")
          iface.exports.foreach { e =>
            println(s"  - ${e.kind} ${e.name}: ${e.tpe}")
          }
          if iface.externDefs.nonEmpty then
            println(s"externDefs: ${iface.externDefs.length}")
            iface.externDefs.foreach { e =>
              println(s"  - extern ${e.name}: ${e.tpe}")
            }
          println(s"instances: ${iface.instances.length}")
          iface.instances.foreach { i =>
            println(s"  - ${i.typeclass}[${i.typeParam}] via ${i.witnessName} (${i.fqn})")
          }
          println(s"capabilities: ${iface.capabilities.length}")
          iface.capabilities.foreach { c =>
            println(s"  - ${c.name}")
          }
          if iface.dependencies.nonEmpty then
            println(s"dependencies: ${iface.dependencies.size}")
            iface.dependencies.toList.sortBy(_._1).foreach { (alias, target) =>
              println(s"  - $alias → $target")
            }
          if sectionsMode then
            printSectionHashes(iface.sectionHashes)

  /** Pretty-print the `sectionHashes` map (shared across .scim/.scir/.scjvm/.scjs).
   *
   *  Entries are emitted in iteration order — which is insertion order
   *  for the `LinkedHashMap`-derived map persisted by
   *  `InterfaceExtractor.computeSectionHashes` and any subsequent
   *  `.copy(sectionHashes = ...)` calls.  When the field is empty (pre-
   *  Phase-3 artifact or `--section-cache` was off at write time) the line
   *  reports "sectionHashes: 0 (none — section cache off or pre-Phase-3)"
   *  so users diagnose missing data, not just absence.
   *
   *  v2.0 Phase 3 — `ssc info <artifact> --sections` extension. */
  def printSectionHashes(map: Map[String, String]): Unit =
    if map.isEmpty then
      println("sectionHashes: 0 (none — section cache off or pre-Phase-3 artifact)")
    else
      println(s"sectionHashes: ${map.size}")
      map.toList.foreach { case (id, h) =>
        println(s"  - $id: $h")
      }

  def printScirInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
    // Decode the artifact directly so we have access to the sectionHashes map
    // for `--sections`; the legacy `readIr` returns a tuple that drops it.
    val artEither =
      scala.util.Try(upickle.default.read[scalascript.ir.ModuleIrArtifact](json)).toEither.left.map { e =>
        s"Failed to parse .scir artifact: ${e.getMessage}"
      }
    artEither match
      case Left(err) =>
        System.err.println(s"info: $err")
        System.exit(1)
      case Right(art) =>
        if jsonMode then
          // Round-trip through writeIr so the canonical pretty form is
          // emitted (matches pre-Phase-3 behaviour).
          val nm = scala.util.Try(upickle.default.read[scalascript.ir.NormalizedModule](art.body)).getOrElse(
            scalascript.ir.NormalizedModule(manifest = None, sections = Nil))
          println(ArtifactIO.writeIr(nm, art.pkg, art.moduleName, art.sourceHash, art.sectionHashes))
        else
          // Body byte size — `art.body` is the embedded JSON string.
          val bodyBytes = art.body.length
          val sectionCount =
            scala.util.Try(upickle.default.read[scalascript.ir.NormalizedModule](art.body).sections.length).getOrElse(0)
          println(s"file: $path")
          println(s"format: .scir (module IR artifact)")
          println(s"magic: ${art.magic}")
          println(s"abiVersion: ${art.abiVersion}")
          println(s"moduleId: ${art.moduleName.getOrElse("<unnamed>")}")
          println(s"package: ${if art.pkg.isEmpty then "<none>" else art.pkg.mkString(".")}")
          println(s"sourceHash: ${art.sourceHash}")
          println(s"size: $fileSize bytes")
          println(s"sections: $sectionCount")
          println(s"bodyBytes: $bodyBytes")
          if sectionsMode then
            printSectionHashes(art.sectionHashes)

  def printScjvmInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
    JvmArtifactIO.readJvm(json) match
      case Left(err) =>
        System.err.println(s"info: $err")
        System.exit(1)
      case Right(art) =>
        if jsonMode then println(JvmArtifactIO.writeJvm(art))
        else
          println(s"file: $path")
          println(s"format: .scjvm (JVM-backend cached source)")
          println(s"magic: ${art.magic}")
          println(s"abiVersion: ${art.abiVersion}")
          println(s"moduleId: ${art.moduleId}")
          println(s"package: ${if art.pkg.isEmpty then "<none>" else art.pkg.mkString(".")}")
          println(s"moduleName: ${art.moduleName.getOrElse("<unnamed>")}")
          println(s"sourceHash: ${art.sourceHash}")
          println(s"size: $fileSize bytes")
          println(s"scalaSourceBytes: ${art.scalaSource.length}")
          println(s"imports: ${art.imports.length}")
          art.imports.foreach { imp => println(s"  - $imp") }
          if sectionsMode then
            printSectionHashes(art.sectionHashes)

  def printScjsInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
    JsArtifactIO.readJs(json) match
      case Left(err) =>
        System.err.println(s"info: $err")
        System.exit(1)
      case Right(art) =>
        if jsonMode then println(JsArtifactIO.writeJs(art))
        else
          println(s"file: $path")
          println(s"format: .scjs (JS-backend cached source)")
          println(s"magic: ${art.magic}")
          println(s"abiVersion: ${art.abiVersion}")
          println(s"moduleId: ${art.moduleId}")
          println(s"package: ${if art.pkg.isEmpty then "<none>" else art.pkg.mkString(".")}")
          println(s"moduleName: ${art.moduleName.getOrElse("<unnamed>")}")
          println(s"sourceHash: ${art.sourceHash}")
          println(s"size: $fileSize bytes")
          println(s"jsSourceBytes: ${art.jsSource.length}")
          println(s"imports: ${art.imports.length}")
          art.imports.foreach { imp => println(s"  - $imp") }
          if sectionsMode then
            printSectionHashes(art.sectionHashes)
