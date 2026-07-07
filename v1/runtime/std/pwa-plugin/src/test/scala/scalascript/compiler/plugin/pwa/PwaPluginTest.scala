package scalascript.compiler.plugin.pwa

import org.scalatest.funsuite.AnyFunSuite

/** tkv2-pwa-adopt (specs/ssc-toolkit-v2.md): the generated manifest and
 *  service worker must carry the offline-first extensions — maskable icon,
 *  cache versioning, network-first routes, offline fallback page. Unit-tests
 *  the generators directly (the `requires:`-based plugin lazy-load is broken
 *  on main — see BUGS.md `plugin-lazyload-extern-imports`; the .ssc-level
 *  drive lives in tests/conformance/tkv2-pwa.ssc, enable once that is fixed). */
class PwaPluginTest extends AnyFunSuite:

  test("manifest: icons sized, maskable entry, theme/start carried"):
    val m = PwaIntrinsics.buildManifest(
      name = "Busi Test", short = "busi", desc = "offline-first test",
      theme = "#0b0d10", bg = "#0b0d10", display = "standalone",
      start = "/react",
      icons = List("/icons/icon-192.png", "/icons/icon-512.png"),
      maskableIcon = "/icons/icon-512.png")
    assert(m.contains(""""theme_color":"#0b0d10""""))
    assert(m.contains(""""start_url":"/react""""))
    assert(m.contains(""""sizes":"192x192""""))
    assert(m.contains(""""sizes":"512x512""""))
    assert(m.contains(""""purpose":"maskable""""))

  test("manifest: no maskable entry when not configured"):
    val m = PwaIntrinsics.buildManifest(
      name = "X", short = "X", desc = "", theme = "#fff", bg = "#fff",
      display = "standalone", start = "/", icons = List("/i-192.png"),
      maskableIcon = "")
    assert(!m.contains("maskable"))

  test("sw: cache version, network-first list, offline fallback"):
    val sw = PwaIntrinsics.buildServiceWorker(
      precache = List("/react"), cacheVersion = "v8",
      networkFirst = List("/api/home", "/react"),
      offlineHtml = "<h1>offline</h1>")
    assert(sw.contains("""const CACHE = "ssc-pwa-v8""""))
    assert(sw.contains("""NETWORK_FIRST = ["/api/home", "/react"]"""))
    assert(sw.contains("<h1>offline</h1>"))
    assert(sw.contains("mode === 'navigate'"))
    assert(sw.contains("caches.match(path)"))

  test("sw: defaults keep the old cache-first behaviour"):
    val sw = PwaIntrinsics.buildServiceWorker(
      precache = List("/", "/styles.css"), cacheVersion = "v1",
      networkFirst = Nil, offlineHtml = "")
    assert(sw.contains("""const CACHE = "ssc-pwa-v1""""))
    assert(sw.contains("NETWORK_FIRST = []"))
    assert(sw.contains("caches.match(e.request)"))
