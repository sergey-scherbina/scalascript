package scalascript.compiler.plugin.pwa

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginNative, PluginValue, PluginError, PluginContext}

object PwaIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("pwa") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String) =>
          registerPwaRoutes(ctx, name, name, "", "#ffffff", "#ffffff", "standalone", "/", Nil, Nil)
        case List(name: String, shortName: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, "", "#ffffff", "#ffffff", "standalone", "/", Nil, Nil)
        case List(name: String, shortName: String, description: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, "#ffffff", "#ffffff", "standalone", "/", Nil, Nil)
        case List(name: String, shortName: String, description: String, themeColor: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, "#ffffff", "standalone", "/", Nil, Nil)
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, "standalone", "/", Nil, Nil)
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, "/", Nil, Nil)
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String, startUrl: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, startUrl, Nil, Nil)
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String, startUrl: String, PluginValue.Lst(iconVals)) =>
          val icons = iconVals.collect { case PluginValue.Str(s) => s }
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, startUrl, icons, Nil)
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String, startUrl: String, PluginValue.Lst(iconVals), PluginValue.Lst(precacheVals)) =>
          val icons    = iconVals.collect   { case PluginValue.Str(s) => s }
          val precache = precacheVals.collect { case PluginValue.Str(s) => s }
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, startUrl, icons, precache)
        case _ => PluginError.raise("pwa(name[, shortName, description, themeColor, backgroundColor, display, startUrl, icons, precache])")
    },
  )

  private def mkResponse(contentType: String, body: String): PluginValue =
    PluginValue.instance("Response", Map(
      "status"  -> PluginValue.int(200),
      "headers" -> PluginValue.mapOf(Map(
        (PluginValue.string("Content-Type"): PluginValue) -> (PluginValue.string(contentType): PluginValue)
      )),
      "body"    -> PluginValue.string(body)
    ))

  private def registerPwaRoutes(
      ctx: PluginContext,
      name:     String,
      short:    String,
      desc:     String,
      theme:    String,
      bg:       String,
      display:  String,
      start:    String,
      icons:    List[String],
      precache: List[String],
  ): PluginValue =
    val manifest = buildManifest(name, short, desc, theme, bg, display, start, icons)
    val swJs     = buildServiceWorker(precache)

    ctx.registerRoute("GET", "/manifest.json",
      PluginValue.nativeFn("pwa.manifest", _ =>
        mkResponse("application/manifest+json", manifest))
    )

    ctx.registerRoute("GET", "/sw.js",
      PluginValue.nativeFn("pwa.sw", _ =>
        mkResponse("application/javascript", swJs))
    )

    PluginValue.unit

  private def jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def buildManifest(
      name: String, short: String, desc: String,
      theme: String, bg: String, display: String,
      start: String, icons: List[String],
  ): String =
    val iconsJson =
      if icons.isEmpty then "[]"
      else
        val entries = icons.map { url =>
          val size = if url.contains("512") then "512x512"
                     else if url.contains("192") then "192x192"
                     else "any"
          s"""    {"src":${jsonStr(url)},"sizes":${jsonStr(size)},"type":"image/png"}"""
        }
        "[\n" + entries.mkString(",\n") + "\n  ]"

    s"""{
  "name":${jsonStr(name)},
  "short_name":${jsonStr(short)},
  "description":${jsonStr(desc)},
  "theme_color":${jsonStr(theme)},
  "background_color":${jsonStr(bg)},
  "display":${jsonStr(display)},
  "start_url":${jsonStr(start)},
  "icons":$iconsJson
}"""

  private def buildServiceWorker(precache: List[String]): String =
    val urlsJson = precache.map(u => jsonStr(u)).mkString("[", ", ", "]")
    s"""const CACHE = "ssc-pwa-v1";
const PRECACHE = $urlsJson;

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(PRECACHE)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request).then(cached => cached || fetch(e.request))
  );
});
"""
