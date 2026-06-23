package scalascript.plugin.api

/** Version of the STABLE plugin API surface (`scalascript-plugin-api`), independent of the backend
 *  `SpiVersion`.  A plugin built against this API declares the version it compiled against; the host
 *  checks compatibility at load via [[isCompatible]].
 *
 *  Bump per SemVer relative to the previous stable release:
 *    - PATCH: doc/impl-only change, no surface change.
 *    - MINOR: purely ADDITIVE new surface (new method/extractor/cap) — older plugins still work.
 *    - MAJOR: any REMOVAL or signature change to the stable surface — older plugins may break.
 *
 *  `1.0.0` — first stable release.  The surface is frozen after the Phase 3 migration (all
 *  value-surface plugins depend only on this module).  `PluginApiSignatureTest` snapshots the
 *  binary surface; any change fails the build until this version is bumped and the golden updated. */
object PluginApiVersion:
  val Current: String = "1.0.0"

  /** True if a plugin built against `pluginVersion` is compatible with this host's [[Current]].
   *  SemVer rule: same MAJOR, and host MINOR >= plugin MINOR (the host is a superset of older
   *  minors, since MINOR bumps are additive).  Malformed versions are incompatible. */
  def isCompatible(pluginVersion: String): Boolean =
    (parse(pluginVersion), parse(Current)) match
      case (Some((pMaj, pMin, _)), Some((hMaj, hMin, _))) => pMaj == hMaj && hMin >= pMin
      case _                                              => false

  private def parse(v: String): Option[(Int, Int, Int)] =
    v.split('.') match
      case Array(a, b, c) =>
        try Some((a.toInt, b.toInt, c.toInt)) catch case _: NumberFormatException => None
      case _ => None
