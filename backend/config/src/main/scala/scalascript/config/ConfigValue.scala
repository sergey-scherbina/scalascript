package scalascript.config

import scala.collection.immutable.{List => IList, Map => IMap}

/** Unified value tree for all config formats (YAML, JSON, HOCON). */
enum ConfigValue:
  case Str(value: String)
  case Num(value: Double)
  case Bool(value: Boolean)
  case Null
  case Lst(values: IList[ConfigValue])
  case Map(entries: IMap[String, ConfigValue])

object ConfigValue:

  val empty: ConfigValue = Map(IMap.empty)

  /** Build a [[ConfigValue]] tree from JVM system properties matching any of the given prefixes.
   *  Both `ssc.` and `scalascript.` are canonical aliases — keys from later prefixes in the
   *  list win on conflict, so pass the canonical name last:
   *  {{{
   *    fromSystemProperties("ssc", "scalascript")   // scalascript.* wins over ssc.*
   *  }}}
   *  `-Dscalascript.frontend=vue` → `Map("frontend" -> Str("vue"))`.
   *  Dotted sub-keys after the prefix become nested maps:
   *  `-Dscalascript.server.port=9090` → `Map("server" -> Map("port" -> Str("9090")))`. */
  def fromSystemProperties(prefixes: String*): ConfigValue =
    val ps = if prefixes.isEmpty then Seq("ssc", "scalascript") else prefixes
    import scala.jdk.CollectionConverters.*
    val props = System.getProperties.asScala
    ps.foldLeft(ConfigValue.empty) { (acc, prefix) =>
      val p = prefix + "."
      props
        .collect { case (k: String, v: String) if k.startsWith(p) => k.stripPrefix(p) -> v }
        .foldLeft(acc) { case (a, (key, value)) => a.set(key, Str(value)) }
    }

  /** Lift a YamlParser-parsed raw value into ConfigValue. */
  def from(raw: Any): ConfigValue =
    import scala.jdk.CollectionConverters.*
    raw match
      case null                      => Null
      case s: String                 => Str(s)
      case n: java.lang.Number       => Num(n.doubleValue)
      case b: java.lang.Boolean      => Bool(b.booleanValue)
      case l: java.util.List[?]      => Lst(l.asScala.map(from).toList)
      case m: java.util.Map[?, ?]    => Map(m.asScala.map((k, v) => k.toString -> from(v)).toMap)
      case _                         => Str(raw.toString)

  extension (cv: ConfigValue)

    def getString: Option[String] = cv match
      case Str(s)  => Some(s)
      case Num(n)  => Some(if n == n.toLong then n.toLong.toString else n.toString)
      case Bool(b) => Some(b.toString)
      case Null    => Some("")
      case _       => None

    def getInt: Option[Int] = cv match
      case Num(n) => Some(n.toInt)
      case Str(s) => s.toIntOption
      case _      => None

    def getDouble: Option[Double] = cv match
      case Num(n) => Some(n)
      case Str(s) => s.toDoubleOption
      case _      => None

    def getBool: Option[Boolean] = cv match
      case Bool(b) => Some(b)
      case Str(s)  => s.toBooleanOption
      case _       => None

    def getList: Option[IList[ConfigValue]] = cv match
      case Lst(vs) => Some(vs)
      case _       => None

    def getMap: Option[IMap[String, ConfigValue]] = cv match
      case Map(m) => Some(m)
      case _      => None

    /** Look up a dotted path, e.g. "server.port". */
    def get(path: String): Option[ConfigValue] =
      path.split('.').toList match
        case Nil        => Some(cv)
        case h :: Nil   =>
          cv match
            case Map(m) => m.get(h)
            case _      => None
        case h :: rest  =>
          cv match
            case Map(m) => m.get(h).flatMap(_.get(rest.mkString(".")))
            case _      => None

    /** Deep merge: `other` wins on key conflicts; maps are merged recursively. */
    def deepMerge(other: ConfigValue): ConfigValue = (cv, other) match
      case (Map(a), Map(b)) =>
        Map(b.foldLeft(a) { case (acc, (k, v)) =>
          acc.updated(k, acc.get(k).fold(v)(_.deepMerge(v)))
        })
      case _ => other

    /** Set a value at a dotted path, creating intermediate maps as needed. */
    def set(path: String, value: ConfigValue): ConfigValue =
      path.split('.').toList match
        case Nil       => value
        case h :: Nil  =>
          cv match
            case Map(m) => Map(m.updated(h, value))
            case _      => Map(IMap(h -> value))
        case h :: rest =>
          val existing = cv match
            case Map(m) => m.getOrElse(h, ConfigValue.empty)
            case _      => ConfigValue.empty
          val nested = existing.set(rest.mkString("."), value)
          cv match
            case Map(m) => Map(m.updated(h, nested))
            case _      => Map(IMap(h -> nested))
