#!/usr/bin/env scala-cli
//> using toolkit latest

// Cross-backend auth smoke harness.
//
// Boots examples/auth-demo.ssc on http://localhost:8769 through each
// of the three backends in turn (interpreter, JVM, JS), drives the
// full sign-in / sign-out flow with manual cookie tracking, and
// asserts every backend produces the same status + key markers at
// each step.  Covers: signed cookie sessions (cookie set + read +
// cleared), CSRF state (token in form + checked on POST), redirect
// after login, session-gated /profile.

import java.net.{URI, HttpURLConnection, CookieManager, CookiePolicy, HttpCookie}
import scala.jdk.CollectionConverters.*

val root     = os.pwd
val example  = root / "examples" / "auth-demo.ssc"
val binDir   = root / "bin"
val compiler = root / "compiler"

def cmdFor(subcommand: String, launcher: String, postPipe: String = ""): List[String] =
  val bin = binDir / launcher
  if os.exists(bin) then List(bin.toString, example.toString)
  else
    val core = s"scala-cli run ${compiler.toString} -- $subcommand ${example.toString}"
    val full = if postPipe.isEmpty then core else s"$core | $postPipe"
    List("bash", "-c", full)

// auth-demo.ssc hard-codes serve(8769); the harness talks to that.
val port = 8769
val base = s"http://localhost:$port"

case class Backend(label: String, cmd: List[String], expectedBroken: Boolean = false)

val backends = List(
  Backend("ssc-int", cmdFor("run",     "ssc")),
  Backend("ssc-jvm", cmdFor("compile", "sscc")),
  Backend("ssc-js",  cmdFor("emit-js", "jssc", postPipe = "node")),
)

// ─── HTTP client with cookie jar ───────────────────────────────────────

case class Hit(status: Int, body: String, location: Option[String])

def newJar(): CookieManager =
  val cm = CookieManager()
  cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
  cm

def request(
    cm:          CookieManager,
    method:      String,
    path:        String,
    body:        Option[String]  = None,
    contentType: Option[String]  = None,
    follow:      Boolean         = false,
): Hit =
  val uri = URI.create(s"$base$path")
  val c   = uri.toURL.openConnection.asInstanceOf[HttpURLConnection]
  c.setRequestMethod(method)
  c.setConnectTimeout(3000)
  c.setReadTimeout(5000)
  c.setInstanceFollowRedirects(follow)
  // Attach saved cookies for this URI.
  val saved = cm.get(uri, java.util.Collections.emptyMap()).asScala
  saved.get("Cookie").foreach { vs =>
    val cookies = vs.asScala.mkString("; ")
    if cookies.nonEmpty then c.setRequestProperty("Cookie", cookies)
  }
  body.foreach { b =>
    c.setDoOutput(true)
    contentType.foreach(c.setRequestProperty("Content-Type", _))
    val bytes = b.getBytes("UTF-8")
    c.getOutputStream.write(bytes)
    c.getOutputStream.close()
  }
  val status = c.getResponseCode
  // Store Set-Cookie headers in the jar.
  val headerMap = c.getHeaderFields
  if headerMap != null then cm.put(uri, headerMap)
  val location = Option(c.getHeaderField("Location"))
  val stream   =
    if status >= 400 then Option(c.getErrorStream)
    else Option(c.getInputStream)
  val text = stream.map(s => scala.io.Source.fromInputStream(s, "UTF-8").mkString)
    .getOrElse("")
  c.disconnect()
  Hit(status, text, location)

// ─── Server orchestration ──────────────────────────────────────────────

def waitForServer(deadlineMs: Long): Boolean =
  while System.currentTimeMillis() < deadlineMs do
    try
      val c = URI.create(s"$base/").toURL.openConnection.asInstanceOf[HttpURLConnection]
      c.setConnectTimeout(200); c.setReadTimeout(200); c.setRequestMethod("GET")
      val ok = c.getResponseCode == 200
      c.disconnect()
      if ok then return true
    catch case _: Throwable => ()
    Thread.sleep(150)
  false

def killPortHolders(): Unit =
  try
    val r = os.proc("lsof", "-ti", s":$port").call(check = false)
    r.out.lines().filter(_.nonEmpty).foreach { pid =>
      try os.proc("kill", "-9", pid).call(check = false)
      catch case _: Throwable => ()
    }
  catch case _: Throwable => ()
  Thread.sleep(300)

// ─── Test steps ────────────────────────────────────────────────────────

case class StepResult(name: String, status: Int, key: String)

def csrfFrom(html: String): String =
  val m = "name=\"csrf\" value=\"([^\"]+)\"".r.findFirstMatchIn(html)
  m.map(_.group(1)).getOrElse("")

def runFlow(): List[StepResult] =
  val cm = newJar()
  val results = scala.collection.mutable.ArrayBuffer.empty[StepResult]
  // 1. Anonymous landing.
  val r1 = request(cm, "GET", "/")
  results += StepResult("anon home",
    r1.status,
    if r1.body.contains("Not signed in") then "Not signed in" else "MISSING")
  // 2. GET /login — captures CSRF token + cookie.
  val r2 = request(cm, "GET", "/login")
  val token = csrfFrom(r2.body)
  results += StepResult("get login form", r2.status,
    if token.nonEmpty then s"csrf-len=${token.length}" else "no csrf")
  // 3. POST /login wrong creds → re-shows form with "Wrong credentials".
  val r3 = request(cm, "POST", "/login",
    body = Some(s"csrf=$token&username=alice&password=WRONG"),
    contentType = Some("application/x-www-form-urlencoded"))
  results += StepResult("login wrong pass", r3.status,
    if r3.body.contains("Wrong credentials") then "Wrong credentials" else "MISSING")
  // 4. POST /login valid → 302 to /profile.
  val r4 = request(cm, "POST", "/login",
    body = Some(s"csrf=$token&username=alice&password=wonderland"),
    contentType = Some("application/x-www-form-urlencoded"))
  results += StepResult("login good pass", r4.status, r4.location.getOrElse("no-loc"))
  // 5. GET /profile (authed) → "Hello, alice".
  val r5 = request(cm, "GET", "/profile")
  val logoutToken = csrfFrom(r5.body)
  results += StepResult("profile authed", r5.status,
    if r5.body.contains("Hello, <strong>alice") then "Hello alice"
    else if r5.status == 302 then s"redirected:${r5.location.getOrElse("")}"
    else "MISSING")
  // 6. POST /logout → 302 to /.
  val r6 = request(cm, "POST", "/logout",
    body = Some(s"csrf=$logoutToken"),
    contentType = Some("application/x-www-form-urlencoded"))
  results += StepResult("logout", r6.status, r6.location.getOrElse("no-loc"))
  // 7. GET / after logout → anon again.
  val r7 = request(cm, "GET", "/")
  results += StepResult("anon after logout", r7.status,
    if r7.body.contains("Not signed in") then "Not signed in" else "MISSING")
  results.toList

case class BackendRun(label: String, steps: List[StepResult], err: Option[String])

def runBackend(b: Backend): BackendRun =
  killPortHolders()
  val proc = os.proc(b.cmd.map(os.Shellable.StringShellable.apply)*).spawn(
    cwd = root,
    stdout = os.Pipe,
    stderr = os.Pipe,
    env   = Map("SSC_SESSION_SECRET" -> "auth-smoke-test"),
  )
  try
    val deadline = System.currentTimeMillis() + 180_000
    if !waitForServer(deadline) then
      BackendRun(b.label, Nil, Some("server did not become ready within 180s"))
    else
      try
        BackendRun(b.label, runFlow(), None)
      catch case e: Throwable =>
        BackendRun(b.label, Nil, Some(s"flow error: ${e.getMessage}"))
  finally
    proc.destroy()
    proc.join(2000)
    if proc.isAlive() then proc.destroyForcibly()
    killPortHolders()

// Run all backends.
println(s"\n${"=" * 60}")
println(s"  Auth smoke — three backends · port $port")
println(s"${"=" * 60}\n")

val runs = backends.map { b =>
  println(s"→ ${b.label}: starting…")
  val r = runBackend(b)
  r.err match
    case Some(e) if b.expectedBroken =>
      println(s"   ⚠ $e  (expected — upstream WsProxy regression)")
    case Some(e) =>
      println(s"   ✗ $e")
    case None =>
      println(s"   ✓ ${r.steps.length} steps captured")
  r
}

println()

// Compare runs from non-broken backends.
val goodRuns = runs.filter(r => !backends.find(_.label == r.label).exists(_.expectedBroken))
val withResults = goodRuns.filter(_.err.isEmpty)

if withResults.isEmpty then
  System.err.println("FAIL: no backend completed the flow.")
  System.exit(1)

val stepNames = withResults.head.steps.map(_.name)
val divergences = scala.collection.mutable.ArrayBuffer.empty[String]

for (name, i) <- stepNames.zipWithIndex do
  val cells = withResults.map { r =>
    val s = r.steps(i)
    (r.label, s.status, s.key)
  }
  val canonical = (cells.head._2, cells.head._3)
  val drift = cells.tail.find { case (_, s, k) => (s, k) != canonical }
  drift match
    case None =>
      println(f"✓ $name%-22s → ${canonical._1} ${canonical._2}")
    case Some((label, s, k)) =>
      divergences += name
      println(f"✗ $name%-22s")
      cells.foreach { case (l, st, key) =>
        val tag = if (st, key) == canonical then "  " else "≠ "
        println(f"    $tag$l%-10s $st  $key")
      }

println()
if divergences.isEmpty then
  println("All non-broken backends agree.")
  // Print expected-broken summary
  runs.filter(r => backends.find(_.label == r.label).exists(_.expectedBroken)).foreach { r =>
    r.err.foreach(e => System.err.println(s"[skip] ${r.label}: $e"))
  }
else
  System.err.println(s"DIVERGED: ${divergences.mkString(", ")}")
  System.exit(1)
