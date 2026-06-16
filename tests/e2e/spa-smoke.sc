#!/usr/bin/env -S scala-cli --server=false
//> using toolkit 0.9.2

// Browser-SPA smoke harness.
//
// 1. Runs `ssc emit-spa examples/spa-demo.ssc` and captures the HTML.
// 2. Structural checks: <!doctype, <title>, exactly one <script>, the
//    JsRuntimeBrowserPatch sentinel `function _spaDispatch`, and route
//    registrations for /, /about, /contact.
// 3. `node --check` on the embedded JS to catch syntax regressions.
// 4. If jsdom is importable from `node`, drives the rendered DOM: asserts
//    the initial dispatch lands on Home, a synthetic click on /about
//    navigates and renders, popstate goes back, and /contact navigates.
//    When jsdom isn't installed the runtime test is skipped (not failed)
//    so the harness still passes on a bare checkout.

import java.nio.file.Files

val root        = os.pwd
val example     = root / "examples" / "spa-demo.ssc"
val binDir      = root / "bin"
val compiler    = root / "compiler"

def emitSpa(): String =
  val bin = binDir / "ssc"
  val proc =
    if os.exists(bin) then os.proc(bin.toString, "emit-spa", example.toString)
    else os.proc("scala-cli", "run", "--server=false", compiler.toString, "--", "emit-spa", example.toString)
  proc.call(cwd = root, check = true, stderr = os.Pipe).out.text()

case class Check(name: String, ok: Boolean, detail: String = "")

println(s"\n${"=" * 60}")
println(s"  SPA smoke — emit-spa + node --check (+ jsdom if available)")
println(s"${"=" * 60}\n")

println("→ emit-spa: generating HTML…")
val html = emitSpa()
println(s"   ✓ ${html.length} bytes")

// ─── Structural checks ───────────────────────────────────────────────

val structural = List(
  Check("doctype present",          html.startsWith("<!doctype html>")),
  Check("<title> populated",        html.contains("<title>spa-demo</title>")),
  Check("exactly one </script>",    html.split("</script>", -1).length == 2),
  Check("SPA overlay sentinel",     html.contains("function _spaDispatch")),
  Check("Node serve still present", html.contains("require('http')")),  // dead code but emitted
  Check("route GET /",              html.contains("\"GET\", \"/\"")),
  Check("route /about",             html.contains("\"GET\", \"/about\"")),
  Check("route /contact",           html.contains("\"GET\", \"/contact\"")),
)

println()
for c <- structural do
  if c.ok then println(f"✓ ${c.name}%-32s")
  else
    println(f"✗ ${c.name}%-32s ${c.detail}")
    System.err.println(s"FAIL: structural check ${c.name}")
    System.exit(1)

// ─── Syntax check via node --check ───────────────────────────────────

val script =
  val start = html.indexOf("<script>") + "<script>".length
  val end   = html.indexOf("</script>", start)
  html.substring(start, end)

val tmpJs = os.temp(script, suffix = ".js")
try
  println("\n→ node --check on embedded JS…")
  val res = os.proc("node", "--check", tmpJs.toString).call(check = false, stderr = os.Pipe)
  if res.exitCode == 0 then println("   ✓ no syntax errors")
  else
    System.err.println(s"FAIL: node --check rejected the JS:\n${res.err.text()}")
    System.exit(1)
finally os.remove(tmpJs)

// ─── jsdom runtime test (optional) ───────────────────────────────────

println("\n→ jsdom runtime test (skipped if jsdom not available)…")

// jsdom can be local or global; `npm root -g` is the canonical global path.
val globalNodeModules =
  os.proc("npm", "root", "-g").call(check = false).out.text().trim
val nodeEnv = Map("NODE_PATH" -> globalNodeModules)
val probe = os.proc("node", "-e",
  "try { require('jsdom'); process.stdout.write('OK'); } catch (e) { process.stdout.write('NO'); }"
).call(check = false, env = nodeEnv)
val jsdomAvailable = probe.out.text().trim == "OK"

if !jsdomAvailable then
  println("   ⚠ jsdom not installed; skipping runtime test")
  println("     (install:  `npm install -g jsdom`  —  picked up via NODE_PATH)")
  println("\nAll structural + syntax checks passed.")
else
  val tmpHtml   = os.temp(html, suffix = ".html")
  val runnerJs  = os.temp(suffix = ".js")
  os.write.over(runnerJs, s"""
    |const fs   = require('fs');
    |const { JSDOM, VirtualConsole } = require('jsdom');
    |const html = fs.readFileSync(${'"'}$tmpHtml${'"'}, 'utf-8');
    |const vc = new VirtualConsole();  vc.forwardTo(console);
    |const dom = new JSDOM(html, {
    |  runScripts: 'dangerously',
    |  url: 'http://localhost/',
    |  virtualConsole: vc,
    |});
    |const { window } = dom;
    |const { document } = window;
    |const results = [];
    |function step(label, action, delayMs, check) {
    |  return new Promise((resolve, reject) => {
    |    if (action) action();
    |    setTimeout(() => {
    |      try { results.push({ label, ...check(document, window) }); resolve(); }
    |      catch (e) { reject(new Error(`${'$'}{label}: ${'$'}{e.message}`)); }
    |    }, delayMs);
    |  });
    |}
    |(async () => {
    |  // Initial dispatch happens during script execution; give it a tick.
    |  await step('initial',           null, 100, (d, w) => ({ path: w.location.pathname, h1: d.querySelector('h1')?.textContent }));
    |  await step('click /about',      () => [...document.querySelectorAll('a')].find(a => a.getAttribute('href') === '/about').click(), 50, (d, w) => ({ path: w.location.pathname, h1: d.querySelector('h1')?.textContent }));
    |  await step('back to /',         () => { window.history.back(); window.dispatchEvent(new window.Event('popstate')); }, 50, (d, w) => ({ path: w.location.pathname, h1: d.querySelector('h1')?.textContent }));
    |  await step('click /contact',    () => [...document.querySelectorAll('a')].find(a => a.getAttribute('href') === '/contact').click(), 50, (d, w) => ({ path: w.location.pathname, h1: d.querySelector('h1')?.textContent }));
    |  process.stdout.write(JSON.stringify(results));
    |  process.exit(0);
    |})().catch(e => { console.error(e.message); process.exit(1); });
    |""".stripMargin)
  try
    val res = os.proc("node", runnerJs.toString).call(env = nodeEnv, check = false)
    if res.exitCode != 0 then
      System.err.println(s"FAIL: jsdom runtime test:\n${res.err.text()}")
      System.exit(1)
    val out = res.out.text().trim
    // Parse last line as JSON-ish array of { label, path, h1 }
    val expected = List(
      ("initial",        "/",         "Home"),
      ("click /about",   "/about",    "About"),
      ("back to /",      "/",         "Home"),
      ("click /contact", "/contact",  "Contact"),
    )
    println()
    for (label, expPath, expH1) <- expected do
      val pathOk = out.contains(s""""path":"$expPath"""")
      val h1Ok   = out.contains(s""""h1":"$expH1"""")
      if pathOk && h1Ok then println(f"✓ $label%-20s path=$expPath h1=$expH1")
      else
        println(f"✗ $label%-20s expected path=$expPath h1=$expH1  got: $out")
        System.err.println(s"FAIL: $label")
        System.exit(1)
    println("\nAll structural + syntax + jsdom runtime checks passed.")
  finally
    os.remove(tmpHtml)
    os.remove(runnerJs)
