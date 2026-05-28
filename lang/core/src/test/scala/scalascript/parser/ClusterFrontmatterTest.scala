package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.SsccFormat
import scalascript.transform.{Denormalize, Normalize}

class ClusterFrontmatterTest extends AnyFunSuite:

  private def withFrontmatter(yaml: String) =
    Parser.parse(
      s"""|---
          |$yaml
          |---
          |
          |# Test
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
        |    args: ThumbnailWorkerArgs""".stripMargin
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
        |    response: User""".stripMargin
    )

    val roundTripped = Denormalize(Normalize(mod))
    assert(roundTripped.manifest.flatMap(_.cluster).flatMap(_.name).contains("demo"))
    assert(roundTripped.manifest.get.remoteHandlers.head.name == "users.get")

    val sscc = SsccFormat.read(SsccFormat.write(mod)).toOption.get
    assert(sscc.manifest.flatMap(_.cluster).flatMap(_.name).contains("demo"))
    assert(sscc.manifest.get.remoteHandlers.head.function == "getUser")
