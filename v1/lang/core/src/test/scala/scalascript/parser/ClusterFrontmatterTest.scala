package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.SsccFormat
import scalascript.transform.{Denormalize, Normalize}

class ClusterFrontmatterTest extends AnyFunSuite:

  private def withFrontmatter(yaml: String, body: String = "") =
    Parser.parse(
      s"""|---
          |$yaml
          |---
          |
          |# Test
          |$body
          |""".stripMargin
    )

  test("cluster front matter parses typed cluster metadata"):
    val mod = withFrontmatter(
      """cluster:
        |  name: demo
        |  nodeId: api-1
        |  role: server
        |  bind: 0.0.0.0:9100
        |  advertiseUrl: ws://api-1:9100/_ssc-actors
        |  seedNodes:
        |    - ws://api-1:9100/_ssc-actors
        |    - ws://worker-1:9200/_ssc-actors
        |  authToken: ${env:SSC_CLUSTER_TOKEN}
        |  placement:
        |    defaultTimeoutMs: 10000
        |  wire:
        |    enabled: true
        |    format: cbor""".stripMargin
    )

    val cluster = mod.manifest.flatMap(_.cluster).get
    assert(cluster.name.contains("demo"))
    assert(cluster.nodeId.contains("api-1"))
    assert(cluster.role.contains("server"))
    assert(cluster.bind.contains("0.0.0.0:9100"))
    assert(cluster.advertiseUrl.contains("ws://api-1:9100/_ssc-actors"))
    assert(cluster.seedNodes == List("ws://api-1:9100/_ssc-actors", "ws://worker-1:9200/_ssc-actors"))
    assert(cluster.authToken.contains("${env:SSC_CLUSTER_TOKEN}"))
    assert(cluster.placement("defaultTimeoutMs") == "10000")
    assert(cluster.wire("format") == "cbor")

  test("registry front matter parses remote handlers, sources, and behaviors"):
    val mod = withFrontmatter(
      """remoteHandlers:
        |  users.get:
        |    function: getUser
        |    path: /api/v1/users/:id
        |    request: UserId
        |    response: User
        |remoteSources:
        |  streams.orders.events:
        |    source: orderEvents
        |    params: OrderFilter
        |    item: OrderEvent
        |remoteBehaviors:
        |  workers.thumbnail:
        |    behavior: thumbnailWorker
        |    args: ThumbnailWorkerArgs""".stripMargin,
      """
        |```scala
        |case class UserId(value: String)
        |case class User(name: String)
        |case class OrderFilter(account: String)
        |case class OrderEvent(id: String)
        |case class ThumbnailWorkerArgs(path: String)
        |def getUser(id: UserId): User = ???
        |def orderEvents(filter: OrderFilter): List[OrderEvent] = List()
        |def thumbnailWorker(args: ThumbnailWorkerArgs): Unit = ()
        |```
        |""".stripMargin
    )

    val mf = mod.manifest.get
    assert(mf.remoteHandlers.map(h => (h.name, h.function, h.path, h.requestType, h.responseType)) ==
      List(("users.get", "getUser", Some("/api/v1/users/:id"), Some("UserId"), Some("User"))))
    assert(mf.remoteSources.map(s => (s.name, s.source, s.paramsType, s.itemType)) ==
      List(("streams.orders.events", "orderEvents", Some("OrderFilter"), Some("OrderEvent"))))
    assert(mf.remoteBehaviors.map(b => (b.name, b.behavior, b.argsType)) ==
      List(("workers.thumbnail", "thumbnailWorker", Some("ThumbnailWorkerArgs"))))

  test("cluster and registry metadata survive Normalize/Denormalize and SsccFormat"):
    val mod = withFrontmatter(
      """cluster:
        |  name: demo
        |  seedNodes: [ws://api-1:9100/_ssc-actors]
        |remoteHandlers:
        |  users.get:
        |    function: getUser
        |    request: UserId
        |    response: User""".stripMargin,
      """
        |```scala
        |case class UserId(value: String)
        |case class User(name: String)
        |def getUser(id: UserId): User = ???
        |```
        |""".stripMargin
    )

    val roundTripped = Denormalize(Normalize(mod))
    assert(roundTripped.manifest.flatMap(_.cluster).flatMap(_.name).contains("demo"))
    assert(roundTripped.manifest.get.remoteHandlers.head.name == "users.get")

    val sscc = SsccFormat.read(SsccFormat.write(mod)).toOption.get
    assert(sscc.manifest.flatMap(_.cluster).flatMap(_.name).contains("demo"))
    assert(sscc.manifest.get.remoteHandlers.head.function == "getUser")

  test("registry front matter rejects missing local definitions"):
    val err = intercept[RuntimeException] {
      withFrontmatter(
        """remoteHandlers:
          |  users.get:
          |    function: missingGetUser
          |    request: UserId
          |    response: User""".stripMargin
      )
    }
    assert(err.getMessage.contains("remoteHandlers.users.get references missing local definition 'missingGetUser'"))

  test("registry front matter rejects missing request or response types"):
    val err = intercept[RuntimeException] {
      withFrontmatter(
        """remoteHandlers:
          |  users.get:
          |    function: getUser
          |    request: MissingUserId
          |    response: User""".stripMargin,
        """
          |```scala
          |case class User(name: String)
          |def getUser(id: Any): User = ???
          |```
          |""".stripMargin
      )
    }
    assert(err.getMessage.contains("remoteHandlers.users.get.request references missing local type 'MissingUserId'"))

  test("source cluster block lowers into cluster metadata"):
    val mod = Parser.parse(
      """# Demo
        |
        |cluster Demo:
        |  nodes = 3
        |  seedDiscovery = SeedResolver.k8sHeadlessService("ssc-demo")
        |  leaderElection = Raft
        |  authTokenFrom = K8sSecret("ssc-cluster-token", key = "token")
        |  heartbeat(intervalMs = 5000, deadAfterMs = 40000)
        |  quorum(2)
        |
        |```scala
        |val x = 1
        |```
        |""".stripMargin
    )

    val cluster = mod.manifest.flatMap(_.cluster).get
    assert(cluster.name.contains("Demo"))
    assert(cluster.nodes.contains(3))
    assert(cluster.seedDiscovery.contains("""SeedResolver.k8sHeadlessService("ssc-demo")"""))
    assert(cluster.leaderElection.contains("Raft"))
    assert(cluster.authTokenFrom.contains("""K8sSecret("ssc-cluster-token", key = "token")"""))
    assert(cluster.heartbeat == Map("intervalMs" -> "5000", "deadAfterMs" -> "40000"))
    assert(cluster.quorum.contains(2))

  test("remote annotation lowers into remoteHandlers metadata"):
    val mod = Parser.parse(
      """# Remote
        |
        |```scala
        |case class UserId(value: String)
        |case class User(name: String)
        |
        |@remote(name = "users.get", path = "/api/v1/users/:id")
        |def getUser(id: UserId): User = User(id.value)
        |```
        |""".stripMargin
    )

    val h = mod.manifest.get.remoteHandlers.head
    assert((h.name, h.function, h.path, h.requestType, h.responseType) ==
      ("users.get", "getUser", Some("/api/v1/users/:id"), Some("UserId"), Some("User")))

    val remoteClient = mod.manifest.get.apiClients.find(_.name == "RemoteRpc").get
    assert(remoteClient.endpoints.map(e => (e.name, e.method, e.path, e.requestType, e.responseType)) ==
      List(("usersGet", "POST", "/api/v1/users/:id", "UserId", "User")))

  test("remote def sugar lowers into remoteHandlers metadata"):
    val mod = Parser.parse(
      """# Remote
        |
        |```scala
        |remote def echo(value: String): String = "echo:" + value
        |```
        |""".stripMargin
    )

    val h = mod.manifest.get.remoteHandlers.head
    assert((h.name, h.function, h.requestType, h.responseType) ==
      ("echo", "echo", Some("String"), Some("String")))

  test("remoteHandlers with paths derive typed RemoteRpc client metadata"):
    val mod = Parser.parse(
      """---
        |remoteHandlers:
        |  demo.echo:
        |    function: echo
        |    path: /rpc/echo
        |    request: String
        |    response: String
        |---
        |
        |# Remote
        |
        |```scala
        |def echo(value: String): String = value
        |```
        |""".stripMargin
    )

    val clients = mod.manifest.get.apiClients
    assert(clients.map(_.name) == List("RemoteRpc"))
    assert(clients.head.endpoints.map(e => (e.name, e.method, e.path, e.requestType, e.responseType)) ==
      List(("demoEcho", "POST", "/rpc/echo", "String", "String")))
