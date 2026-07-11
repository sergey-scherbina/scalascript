package ssc.plugin.httpfast

import org.scalatest.funsuite.AnyFunSuite

class RouterTest extends AnyFunSuite:

  private def router(entries: (String, String)*): Router[String] =
    val r = new Router[String]
    entries.foreach { case (mp, h) =>
      val Array(m, p) = mp.split(" ", 2)
      r.add(m, p, h)
    }
    r.freeze()
    r

  test("literal routes match exactly on method + path") {
    val r = router("GET /" -> "root", "GET /health" -> "health", "POST /users" -> "create")
    assert(r.find("GET", "/").map(_.handler).contains("root"))
    assert(r.find("GET", "/health").map(_.handler).contains("health"))
    assert(r.find("POST", "/users").map(_.handler).contains("create"))
    assert(r.find("GET", "/users").isEmpty) // wrong method
    assert(r.find("GET", "/missing").isEmpty)
  }

  test(":param segments are captured") {
    val r = router("GET /users/:id" -> "show", "GET /users/:id/posts/:pid" -> "post")
    val m = r.find("GET", "/users/42").get
    assert(m.handler == "show")
    assert(m.params == Map("id" -> "42"))
    val m2 = r.find("GET", "/users/7/posts/99").get
    assert(m2.handler == "post")
    assert(m2.params == Map("id" -> "7", "pid" -> "99"))
  }

  test("a literal beats a :param at the same position (specificity)") {
    val r = router("GET /users/me" -> "self", "GET /users/:id" -> "byId")
    assert(r.find("GET", "/users/me").map(_.handler).contains("self"))
    assert(r.find("GET", "/users/123").map(_.handler).contains("byId"))
  }

  test("trailing * matches the remaining segments including none") {
    val r = router("GET /static/*" -> "assets")
    assert(r.find("GET", "/static/css/app.css").map(_.handler).contains("assets"))
    assert(r.find("GET", "/static").map(_.handler).contains("assets"))
    assert(r.find("GET", "/other/x").isEmpty)
  }

  test("a more specific route wins over a wildcard") {
    val r = router("GET /api/*" -> "wild", "GET /api/ping" -> "ping")
    assert(r.find("GET", "/api/ping").map(_.handler).contains("ping"))
    assert(r.find("GET", "/api/other").map(_.handler).contains("wild"))
  }

  test("hasPath / allowedMethods distinguish 404 from 405") {
    val r = router("GET /users/:id" -> "show", "DELETE /users/:id" -> "del")
    assert(r.find("POST", "/users/5").isEmpty)
    assert(r.hasPath("/users/5"))
    assert(r.allowedMethods("/users/5") == Set("GET", "DELETE"))
    assert(!r.hasPath("/nope"))
  }

  test("registration after freeze is rejected") {
    val r = router("GET /" -> "root")
    assertThrows[IllegalStateException](r.add("GET", "/late", "x"))
  }

  test("splitPath drops empty + leading/trailing slashes") {
    assert(Router.splitPath("/") == Vector.empty)
    assert(Router.splitPath("/a/b") == Vector("a", "b"))
    assert(Router.splitPath("/a//b/") == Vector("a", "b"))
    assert(Router.splitPath("a/b") == Vector("a", "b"))
  }
