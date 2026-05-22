#!/usr/bin/env scala-cli
//> using toolkit 0.9.2

// Cross-backend REST smoke harness.
//
// Boots examples/rest-api.ssc on http://localhost:8765 through each of the
// three backends in turn (interpreter, JVM-compiled, JS via Node), issues
// an identical sequence of HTTP requests against it, and verifies that
// every backend returns the same (status, body) for each step. Any drift
// between the three runtimes is reported and the script exits non-zero.

import java.net.{URI, HttpURLConnection}

val root     = os.pwd
val example  = root / "examples" / "rest-api.ssc"
val binDir   = root / "bin"
val compiler = root / "compiler"

// Prefer the installed bin/ launchers when present (faster cold-start —
// they shell out to a pre-built binary or cached scala-cli compile).
// Fall back to `scala-cli run compiler -- <subcommand>` so the harness
// also works in fresh checkouts and git worktrees where bin/ is absent.
def cmdFor(subcommand: String, launcher: String, postPipe: String = ""): List[String] =
  val bin = binDir / launcher
  if os.exists(bin) then List(bin.toString, example.toString)
  else
    // Fallback: run the compiler directly via scala-cli. For the JS backend
    // we additionally pipe emit-js output through node, mirroring what
    // bin/jssc does in a single shell command.
    val core = s"scala-cli run ${compiler.toString} -- $subcommand ${example.toString}"
    val full = if postPipe.isEmpty then core else s"$core | $postPipe"
    List("bash", "-c", full)

val port = 8765
val base = s"http://localhost:$port"

case class Backend(label: String, cmd: List[String])

val backends = List(
  Backend("ssc-int", cmdFor("run",     "ssc")),
  Backend("ssc-jvm", cmdFor("compile", "sscc")),
  Backend("ssc-js",  cmdFor("emit-js", "jssc", postPipe = "node")),
)

// ─── HTTP client ───────────────────────────────────────────────────────

case class Hit(status: Int, body: String)

def request(
    method:      String,
    path:        String,
    body:        Option[String]  = None,
    contentType: Option[String]  = None
): Hit =
  val c = URI.create(s"$base$path").toURL.openConnection.asInstanceOf[HttpURLConnection]
  c.setRequestMethod(method)
  c.setConnectTimeout(2000)
  c.setReadTimeout(2000)
  body.foreach { b =>
    c.setDoOutput(true)
    contentType.foreach(c.setRequestProperty("Content-Type", _))
    val bytes = b.getBytes("UTF-8")
    c.getOutputStream.write(bytes)
    c.getOutputStream.close()
  }
  val status = c.getResponseCode
  val stream =
    if status >= 400 then Option(c.getErrorStream)
    else Option(c.getInputStream)
  val text = stream.map(s => scala.io.Source.fromInputStream(s, "UTF-8").mkString)
    .getOrElse("")
  c.disconnect()
  Hit(status, text)

// ─── Test sequence ─────────────────────────────────────────────────────

case class Step(name: String, run: () => Hit, normaliser: String => String = identity)

val steps = List(
  Step("GET /api/todos (initial)",       () => request("GET",    "/api/todos")),
  Step("POST /api/todos (raw body)",     () => request("POST",   "/api/todos", Some("Buy bread"))),
  Step("GET /api/todos (after POST)",    () => request("GET",    "/api/todos")),
  Step("POST /api/todos (form)",         () => request("POST",   "/api/todos",
    Some("item=Mail+letter"), Some("application/x-www-form-urlencoded"))),
  Step("GET /api/todos (after form POST)", () => request("GET",  "/api/todos")),
  Step("DELETE /api/todos/0",            () => request("DELETE", "/api/todos/0")),
  Step("GET /api/todos (after DELETE)",  () => request("GET",    "/api/todos")),
  // Whitespace differs between backends' HTML interpolation so just sanity-check
  // markers rather than do exact diff.
  Step(
    "GET / (HTML)", () => request("GET", "/"),
    body => {
      val hasTitle = body.contains("<title>Todos</title>")
      val hasCount = body.contains("Todos (")
      val hasItems = body.contains("<small>")
      s"hasTitle=$hasTitle, hasCount=$hasCount, hasItems=$hasItems"
    }
  ),
  // The interpreter's serve mode falls through to static .ssc rendering when
  // no route matches (so it serves a 404 HTML page); JVM and JS backends are
  // pure REST runtimes and return a plain "Not Found: <path>" body.  The
  // shared invariant is just the 404 status — normalise the body away.
  Step("GET /no-such-route",            () => request("GET", "/no-such-route"),
    _ => "(404 body — backend-specific)"),
)

// ─── Process orchestration ─────────────────────────────────────────────

def waitForServer(deadlineMs: Long): Boolean =
  while System.currentTimeMillis() < deadlineMs do
    try
      val c = URI.create(s"$base/api/todos").toURL.openConnection.asInstanceOf[HttpURLConnection]
      c.setConnectTimeout(200); c.setReadTimeout(200); c.setRequestMethod("GET")
      val ok = c.getResponseCode == 200
      c.disconnect()
      if ok then return true
    catch case _: Throwable => ()
    Thread.sleep(150)
  false

case class BackendRun(label: String, results: List[(String, Hit, String)], err: Option[String])

/** Kill anything holding the test port. `proc.destroy()` only signals the
 *  immediate child; the bin/ launchers wrap scala-cli / node in shell, which
 *  can leave the actual HTTP listener orphaned and bind-leaked across
 *  iterations.  This belt-and-braces sweep removes any stragglers between
 *  runs. */
def killPortHolders(): Unit =
  try
    val r = os.proc("lsof", "-ti", s":$port").call(check = false)
    r.out.lines().filter(_.nonEmpty).foreach { pid =>
      try os.proc("kill", "-9", pid).call(check = false)
      catch case _: Throwable => ()
    }
  catch case _: Throwable => ()
  Thread.sleep(300)

def runBackend(b: Backend): BackendRun =
  killPortHolders()
  val proc = os.proc(b.cmd.map(os.Shellable.StringShellable.apply)*).spawn(
    cwd = root,
    stdout = os.Pipe,
    stderr = os.Pipe
  )
  try
    val deadline = System.currentTimeMillis() + 180_000  // sscc cold-compile + interp warm-up can be slow
    if !waitForServer(deadline) then
      BackendRun(b.label, Nil, Some("server did not become ready within 180s"))
    else
      val results = steps.map { s =>
        val h = s.run()
        (s.name, h, s.normaliser(h.body))
      }
      BackendRun(b.label, results, None)
  finally
    proc.destroy()
    proc.join(2000)
    if proc.isAlive() then proc.destroyForcibly()
    killPortHolders()

// ─── Run all backends, diff results ────────────────────────────────────

println(s"\n${"=" * 60}")
println(s"  REST smoke — three backends · port $port")
println(s"${"=" * 60}\n")

val runs = backends.map { b =>
  println(s"→ ${b.label}: starting…")
  val r = runBackend(b)
  r.err match
    case Some(e) => println(s"   ✗ $e")
    case None    => println(s"   ✓ ${r.results.length} steps captured")
  r
}

println()

val ok = runs.forall(_.err.isEmpty)
if !ok then
  System.err.println("FAIL: one or more backends did not run.")
  System.exit(1)

// For each step, compare the (status, normalised body) tuple across backends.
val divergences = scala.collection.mutable.ArrayBuffer.empty[String]

for (step, i) <- steps.zipWithIndex do
  val cells = runs.map { r =>
    val (_, hit, norm) = r.results(i)
    (r.label, hit.status, norm)
  }
  val canonical = (cells.head._2, cells.head._3)
  val drift = cells.tail.find { case (_, s, n) => (s, n) != canonical }
  drift match
    case None => println(f"✓ ${step.name}%-32s → ${canonical._1} ${canonical._2.take(60)}")
    case Some((label, s, n)) =>
      divergences += step.name
      println(f"✗ ${step.name}%-32s")
      cells.foreach { case (l, st, body) =>
        val tag = if (st, body) == canonical then "  " else "≠ "
        println(f"    $tag$l%-10s $st  ${body.take(80)}")
      }

println()
if divergences.isEmpty then
  println("All three backends agree.")
else
  System.err.println(s"DIVERGED: ${divergences.mkString(", ")}")
  System.exit(1)
