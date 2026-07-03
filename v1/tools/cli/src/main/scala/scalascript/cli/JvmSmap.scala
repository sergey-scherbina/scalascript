package scalascript.cli

/** Minimal JSR-45 SMAP (Source Map for Other Languages) builder.
 *
 *  Generates the textual SMAP payload that gets stuffed into each `.class`
 *  file's `SourceDebugExtension` attribute via [[JvmSmapInjector]].  The JVM
 *  decodes the attribute at stack-trace time and rewrites the `LineNumberTable`
 *  reference from the synthetic "compiled-Scala" line numbers back to the
 *  user-facing `.ssc` line numbers — so a runtime exception in user code
 *  shows `at a_sc.boom(a.ssc:10)` instead of `at a_sc.boom(a_sc.scala:147)`.
 *
 *  ### Format (JSR-45, simplified for our single-stratum case)
 *
 *  Our user-code emit only ever produces ONE stratum — the synthetic Scala
 *  source compiled out of the `.ssc` — so the SMAP is short:
 *
 *  {{{
 *  SMAP
 *  <moduleId>_sc.scala
 *  SSC
 *  *S SSC
 *  *F
 *  + 1 <moduleId>.ssc
 *  <moduleId>.ssc
 *  *L
 *  <gen-line>#1:<orig-line>
 *  <gen-line>#1:<orig-line>
 *  ...
 *  *E
 *  }}}
 *
 *  Header semantics (see the JSR-45 spec §5):
 *
 *   - Line 1: literal `SMAP`.
 *   - Line 2: name of the file the .class's `SourceFile` attribute would
 *     otherwise refer to — i.e. the compiled Scala source name.  The JVM
 *     uses this when no stratum matches; we still write a sensible value.
 *   - Line 3: `SSC` — the default stratum's name.  This is the language we
 *     want stack traces resolved against.
 *
 *  Section semantics:
 *
 *   - `*S SSC` — declares the SSC stratum.  All `*F` / `*L` sections until
 *     the next `*S` / `*E` belong to it.
 *   - `*F` — file table.  We emit one entry: `+ 1 <name.ssc>` (`+` = absolute
 *     path follows; `1` = our file id; `<name.ssc>` = display name).  The
 *     second line repeats the absolute or relative path — for our single-
 *     file case it's the same string.
 *   - `*L` — line section.  Each line is
 *     `<input-start-line>[,<input-line-count>]#<file-id>[,<output-line-count>]:<output-start-line>`
 *     We emit the simplest form `<gen-line>#1:<orig-line>` (no input/output
 *     line counts ⇒ defaults to 1 each).  The mappings are sorted by
 *     generated line so a JVM with naive linear scan stays fast.
 *   - `*E` — end of section.
 *
 *  ### Backward compatibility
 *
 *  When [[build]] is called with an empty `genLineToOrig` map, the returned
 *  SMAP still has all the headers and the `*L` section is empty.  The JVM
 *  accepts this and falls through to the regular `LineNumberTable`
 *  resolution — i.e. no source-map effect, but the attribute is still
 *  syntactically valid.  This lets the linker unconditionally inject SMAP
 *  on every class without special-casing empty maps.
 *
 *  v2.0 Phase 4 (Option A) — SMAP injection via `SourceDebugExtension`. */
object JvmSmap:

  /** Build a JSR-45 SMAP string from a line mapping.
   *
   *  @param moduleId      Display id of the module — used to derive the
   *                       compiled-source filename in the header.  e.g.
   *                       `"a"` ⇒ `"a_sc.scala"` (matching the wrapper
   *                       object name the in-process driver / scala-cli `.sc`
   *                       script wrapper produces).
   *  @param sscFileName   Display name of the original .ssc file — appears
   *                       in stack frames after SMAP resolution.  Typically
   *                       `"<moduleId>.ssc"`.
   *  @param genLineToOrig Mapping from generated Scala line (1-based, as
   *                       the bytecode's `LineNumberTable` sees it) to the
   *                       original .ssc line (1-based, what we want to
   *                       show the user).  Empty map ⇒ empty `*L` section.
   *  @return              Newline-terminated SMAP text ready to be packed
   *                       as the `SourceDebugExtension` attribute body. */
  def build(
      moduleId:      String,
      sscFileName:   String,
      genLineToOrig: Map[Int, Int]
  ): String =
    val sb = new StringBuilder
    // Header — JSR-45 §5.2.  The first non-SMAP line names the "default"
    // SourceFile that the SMAP overrides.  We use `<moduleId>_sc.scala`
    // because that's the wrapper-class source name JvmBytecode emits.
    val compiledName = s"${moduleId}_sc.scala"
    sb.append("SMAP\n")
    sb.append(compiledName).append('\n')
    sb.append("SSC\n")
    sb.append("*S SSC\n")
    // File section — one entry, id 1.  `+ 1 <display>` then `<absolute>`.
    // We don't have an absolute path; using the display name twice is
    // explicitly allowed by the spec when the file isn't on disk yet.
    sb.append("*F\n")
    sb.append("+ 1 ").append(sscFileName).append('\n')
    sb.append(sscFileName).append('\n')
    // Line section — sorted ascending by generated line so consumers can
    // binary-search.  Format: `<gen>#1:<orig>`.
    sb.append("*L\n")
    val sorted = genLineToOrig.toList.sortBy(_._1)
    for (genLine, origLine) <- sorted do
      sb.append(genLine).append("#1:").append(origLine).append('\n')
    sb.append("*E\n")
    sb.toString
