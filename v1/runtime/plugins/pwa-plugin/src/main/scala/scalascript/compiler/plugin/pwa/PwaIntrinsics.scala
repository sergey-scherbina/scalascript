package scalascript.compiler.plugin.pwa

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginNative, PluginValue, PluginError, PluginContext}

object PwaIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("pwa") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String) =>
          registerPwaRoutes(ctx, name, name, "", "#ffffff", "#ffffff", "standalone", "/", Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, "", "#ffffff", "#ffffff", "standalone", "/", Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, "#ffffff", "#ffffff", "standalone", "/", Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String, themeColor: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, "#ffffff", "standalone", "/", Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, "standalone", "/", Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, "/", Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String, startUrl: String) =>
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, startUrl, Nil, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String, startUrl: String, PluginValue.Lst(iconVals)) =>
          val icons = iconVals.collect { case PluginValue.Str(s) => s }
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, startUrl, icons, Nil, "v1", Nil, "", "")
        case List(name: String, shortName: String, description: String, themeColor: String, bgColor: String, display: String, startUrl: String, PluginValue.Lst(iconVals), PluginValue.Lst(precacheVals)) =>
          val icons    = iconVals.collect   { case PluginValue.Str(s) => s }
          val precache = precacheVals.collect { case PluginValue.Str(s) => s }
          registerPwaRoutes(ctx, name, if shortName.isEmpty then name else shortName, description, themeColor, bgColor, display, startUrl, icons, precache, "v1", Nil, "", "")
        // tkv2-pwa-adopt: + cacheVersion, networkFirst, offlineHtml, maskableIcon
        case name :: rest if name.isInstanceOf[String] && rest.length >= 9 && rest.length <= 12 =>
          val n = name.asInstanceOf[String]
          def str(i: Int, d: String): String = if i < rest.length then rest(i) match { case s: String => s; case PluginValue.Str(s) => s; case _ => d } else d
          def lst(i: Int): List[String] = if i < rest.length then rest(i) match { case PluginValue.Lst(vs) => vs.collect { case PluginValue.Str(s) => s }; case _ => Nil } else Nil
          val short = str(0, ""); val desc = str(1, ""); val theme = str(2, "#ffffff"); val bg = str(3, "#ffffff")
          val display = str(4, "standalone"); val start = str(5, "/")
          registerPwaRoutes(ctx, n, if short.isEmpty then n else short, desc, theme, bg, display, start,
            lst(6), lst(7), str(8, "v1"), lst(9), str(10, ""), str(11, ""))
        case _ => PluginError.raise("pwa(name[, shortName, description, themeColor, backgroundColor, display, startUrl, icons, precache, cacheVersion, networkFirst, offlineHtml, maskableIcon])")
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
      cacheVersion: String,
      networkFirst: List[String],
      offlineHtml:  String,
      maskableIcon: String,
  ): PluginValue =
    val manifest = buildManifest(name, short, desc, theme, bg, display, start, icons, maskableIcon)
    val swJs     = buildServiceWorker(precache, cacheVersion, networkFirst, offlineHtml)

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

  private[pwa] def buildManifest(
      name: String, short: String, desc: String,
      theme: String, bg: String, display: String,
      start: String, icons: List[String],
      maskableIcon: String,
  ): String =
    val iconsJson =
      if icons.isEmpty && maskableIcon.isEmpty then "[]"
      else
        val maskEntry =
          if maskableIcon.isEmpty then Nil
          else List(s"""    {"src":${jsonStr(maskableIcon)},"sizes":"512x512","type":"image/png","purpose":"maskable"}""")
        val entries = icons.map { url =>
          val size = if url.contains("512") then "512x512"
                     else if url.contains("192") then "192x192"
                     else "any"
          s"""    {"src":${jsonStr(url)},"sizes":${jsonStr(size)},"type":"image/png"}"""
        }
        "[\n" + (entries ++ maskEntry).mkString(",\n") + "\n  ]"

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

  private[pwa] def buildServiceWorker(
      precache: List[String],
      cacheVersion: String,
      networkFirst: List[String],
      offlineHtml: String,
  ): String =
    val urlsJson = precache.map(u => jsonStr(u)).mkString("[", ", ", "]")
    val nfJson   = networkFirst.map(u => jsonStr(u)).mkString("[", ", ", "]")
    val offline  = jsonStr(offlineHtml)
    s"""const CACHE = "ssc-pwa-${cacheVersion}";
const PRECACHE = $urlsJson;
const NETWORK_FIRST = $nfJson;
const OFFLINE_HTML = $offline;

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

function putCache(key, response) {
  if (response && response.ok) caches.open(CACHE).then(c => c.put(key, response.clone()));
  return response;
}

function offlineFallback(request) {
  if (request.mode === 'navigate' && OFFLINE_HTML !== "")
    return new Response(OFFLINE_HTML, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  return Response.error();
}

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  const path = new URL(e.request.url).pathname;
  // network-first paths: fresh when online, cached copy when offline,
  // offline page for navigations with no cached copy.
  if (NETWORK_FIRST.some(p => path === p || path.startsWith(p))) {
    e.respondWith(
      fetch(e.request).then(r => putCache(path, r))
        .catch(() => caches.match(path).then(r => r || offlineFallback(e.request)))
    );
    return;
  }
  e.respondWith(
    caches.match(e.request).then(cached => cached ||
      fetch(e.request).catch(() => offlineFallback(e.request)))
  );
});
"""
