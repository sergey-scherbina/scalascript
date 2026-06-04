package scalascript.cli

/** Renders the `Commands:` section of `ssc help` from the CommandRegistry, so
 *  the command list is single-source: a registered CliCommand with a summary
 *  appears automatically. See specs/cli-command-spi.md. */
object Help:

  /** Display order for category buckets; anything else sorts to the end. */
  val categoryOrder: List[String] = List(
    "Run & develop",
    "Build, bundle & package",
    "Emit & transpile",
    "Separate compilation (v2.0)",
    "Check & inspect",
    "Diagnostics",
    "Dependencies & plugins",
    "Services & tooling",
    "Help",
  )

  /** The grouped, registry-derived command listing (no surrounding prose). */
  def renderCommands(): String =
    val visible = CommandRegistry.all.filterNot(_.hidden)
    val byCat   = visible.groupBy(_.category)
    val ordered = categoryOrder.filter(byCat.contains) ++
                  byCat.keys.filterNot(categoryOrder.contains).toList.sorted
    val sb = StringBuilder()
    for cat <- ordered do
      sb.append(s"\n$cat:\n")
      val cmds = byCat(cat).sortBy(_.name)
      for c <- cmds do
        sb.append(f"  ${c.name}%-18s ${c.summary}\n")
        for d <- c.details do
          sb.append(s"                     $d\n")
    sb.toString
