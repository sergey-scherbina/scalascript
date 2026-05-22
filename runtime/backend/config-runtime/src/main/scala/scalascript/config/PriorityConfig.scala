package scalascript.config

/** Declarative priority override.
 *
 *  Front-matter:
 *  {{{
 *  config:
 *    priority: [env, frontmatter, files, blocks]
 *  }}}
 *
 *  Code:
 *  {{{
 *  val loader = ConfigLoader(...).withPriority(List(Priority.Frontmatter, Priority.Files, Priority.Blocks))
 *  }}}
 */
object PriorityConfig:

  /** Parse a `config.priority` list from the already-parsed front-matter ConfigValue.
   *  Returns `None` if not specified (use `Priority.DefaultOrder`). */
  def fromConfigValue(cv: ConfigValue): Option[List[Priority]] =
    cv.get("config.priority") match
      case Some(ConfigValue.Lst(items)) =>
        val priorities = items.flatMap {
          case ConfigValue.Str(s) => parsePriority(s)
          case _                  => None
        }
        if priorities.nonEmpty then Some(priorities) else None
      case _ => None

  private def parsePriority(s: String): Option[Priority] = s.toLowerCase.trim match
    case "blocks" | "block" | "fenced"               => Some(Priority.Blocks)
    case "files"  | "file"  | "external"              => Some(Priority.Files)
    case "frontmatter" | "front-matter" | "fm"        => Some(Priority.Frontmatter)
    case _                                             => None
