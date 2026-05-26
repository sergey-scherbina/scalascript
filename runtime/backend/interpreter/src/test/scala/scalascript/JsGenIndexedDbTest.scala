package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JsRuntime}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

class JsGenIndexedDbTest extends AnyFunSuite:

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(js: String): String =
    val tmp = java.io.File.createTempFile("ssc-indexeddb-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  private val source =
    """# IndexedDB
      |
      |```scalascript
      |case class Draft(id: String, title: String, done: Boolean)
      |
      |val drafts = IndexedDb.store[Draft]("drafts", "test-db")
      |awaitClient(drafts.clear())
      |awaitClient(drafts.put(Draft("d1", "Plan", false)))
      |awaitClient(drafts.put(Draft("d2", "Ship", true)))
      |
      |val loadedOpt = awaitClient(drafts.get("d1"))
      |val loaded = loadedOpt.get
      |val rows = awaitClient(drafts.all())
      |val keys = awaitClient(drafts.keys())
      |awaitClient(drafts.remove("d2"))
      |val afterRemove = awaitClient(drafts.get("d2"))
      |
      |println(loaded.title + ":" + rows.size + ":" + keys.head + ":" + afterRemove.isEmpty)
      |```
      |""".stripMargin

  test("JS codegen lowers IndexedDb.store type argument to runtime type name"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("""IndexedDb.store("drafts", "Draft", "test-db")"""))
    assert(code.contains("const drafts = IndexedDb.store"))
    assert(code.contains("await _dispatch(drafts, 'put'"))

  test("JS runtime stores typed objects through IndexedDb fallback"):
    assume(hasNode, "node not available")
    val js = JsRuntime + "\n" + JsGen.generate(Parser.parse(source))

    assert(runJs(js) == "Plan:2:d1:true")

  private val syncSource =
    """# Sync
      |
      |```scalascript
      |case class Draft(id: String, title: String, done: Boolean)
      |
      |val drafts = IndexedDb.store[Draft]("drafts", "sync-db")
      |awaitClient(drafts.clear())
      |awaitClient(drafts.put(Draft("d1", "Local", false)))
      |awaitClient(Sync.push[Draft]("drafts", "sync-db"))
      |awaitClient(Sync.pull[Draft]("drafts", "sync-db"))
      |val rows = awaitClient(drafts.all())
      |println(rows.size)
      |```
      |""".stripMargin

  test("JS codegen lowers Sync pull/push type arguments to runtime type names"):
    val code = JsGen.generate(Parser.parse(syncSource))

    assert(code.contains("""Sync.push("drafts", "Draft", "sync-db")"""))
    assert(code.contains("""Sync.pull("drafts", "Draft", "sync-db")"""))

  test("JS runtime sync helpers push local entries and pull remote changes"):
    assume(hasNode, "node not available")
    val fetchStub =
      """|globalThis.__sscSyncPosts = [];
         |globalThis.fetch = async function(url, init) {
         |  if (String(url).includes("/push")) {
         |    const body = JSON.parse(init.body);
         |    globalThis.__sscSyncPosts.push(body);
         |    if (body.mutations.length !== 1 || body.mutations[0].value.title !== "Local") throw new Error("bad push payload");
         |    return { ok: true, json: async () => ({ results: [{ key: "d1", version: 1, deleted: false }], conflicts: [] }) };
         |  }
         |  if (String(url).includes("/changes")) {
         |    return { ok: true, json: async () => ({
         |      changes: [{ key: "d2", version: 2, updatedAt: "2026-05-26T00:00:00Z", deleted: false, value: { id: "d2", title: "Remote", done: true } }],
         |      nextCursor: 2
         |    }) };
         |  }
         |  throw new Error("unexpected url " + url);
         |};
         |""".stripMargin
    val js = JsRuntime + "\n" + fetchStub + "\n" + JsGen.generate(Parser.parse(syncSource))

    assert(runJs(js) == "2")

  private val queueSource =
    """# Sync Queue
      |
      |```scalascript
      |case class Draft(id: String, title: String, done: Boolean)
      |
      |val drafts = IndexedDb.store[Draft]("drafts", "queue-db")
      |awaitClient(drafts.clear())
      |awaitClient(Sync.put[Draft]("drafts", Draft("d1", "Queued", false), "queue-db"))
      |awaitClient(Sync.remove[Draft]("drafts", "d2", "queue-db"))
      |val before = Sync.pending("drafts", "queue-db").size
      |awaitClient(Sync.push[Draft]("drafts", "queue-db"))
      |val after = Sync.pending("drafts", "queue-db").size
      |println(before.toString + ":" + after.toString)
      |```
      |""".stripMargin

  test("JS codegen lowers Sync put/remove type arguments to runtime type names"):
    val code = JsGen.generate(Parser.parse(queueSource))

    assert(code.contains("""Sync.put("drafts", "Draft", _call(Draft, "d1", "Queued", false), "queue-db")"""))
    assert(code.contains("""Sync.remove("drafts", "Draft", "d2", "queue-db")"""))

  test("JS runtime keeps durable sync mutations until push acknowledges them"):
    assume(hasNode, "node not available")
    val fetchStub =
      """|globalThis.fetch = async function(url, init) {
         |  const body = JSON.parse(init.body);
         |  if (body.mutations.length !== 2) throw new Error("expected queued mutations");
         |  if (body.mutations[0].key !== "d1" || body.mutations[0].deleted !== false) throw new Error("bad put mutation");
         |  if (body.mutations[1].key !== "d2" || body.mutations[1].deleted !== true) throw new Error("bad delete mutation");
         |  return { ok: true, json: async () => ({ results: [
         |    { key: "d1", version: 1, deleted: false },
         |    { key: "d2", version: 2, deleted: true }
         |  ], conflicts: [] }) };
         |};
         |""".stripMargin
    val js = JsRuntime + "\n" + fetchStub + "\n" + JsGen.generate(Parser.parse(queueSource))

    assert(runJs(js) == "2:0")
