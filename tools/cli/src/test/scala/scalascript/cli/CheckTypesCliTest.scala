package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CheckTypesCliTest extends AnyFunSuite with Matchers:

  private def captureStdout(body: => Unit): String =
    val baos = new java.io.ByteArrayOutputStream()
    val ps   = new java.io.PrintStream(baos)
    Console.withOut(ps)(body)
    ps.flush()
    baos.toString("UTF-8")

  private def withSscFile(content: String)(body: os.Path => Unit): Unit =
    val dir  = os.temp.dir(prefix = "ssc-check-types-")
    val file = dir / "test.ssc"
    try
      os.write(file, content.stripMargin)
      body(file)
    finally os.remove.all(dir)

  test("check-types prints inventory table for module with declared route evidence"):
    withSscFile(
      """|---
         |apiClients:
         |  Users:
         |    endpoints:
         |      - name: getUser
         |        method: GET
         |        path: /users/:id
         |        request: Unit
         |        response: User
         |---
         |
         |# Users
         |"""
    ) { file =>
      val cmd = CheckTypesCmd()
      val out = captureStdout { val _ = cmd.runResult(List(file.toString)) }
      out should include ("Route evidence:")
      out should include ("api endpoints:")
      out should include ("1 declared, 0 unknown")
      out should include ("GraphQL evidence:")
      assert(out.contains("All routes and GraphQL types have declared types.") ||
             out.contains("All routes have declared types."))
    }

  test("check-types exits 0 (success) when all routes declared"):
    withSscFile(
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: listItems
         |        method: GET
         |        path: /items
         |        request: Unit
         |        response: Item
         |---
         |
         |# Items
         |"""
    ) { file =>
      val cmd    = CheckTypesCmd()
      val result = cmd.runResult(List(file.toString))
      result.isSuccess shouldBe true
    }

  test("check-types exits 1 (failure) when some routes have unknown evidence"):
    withSscFile(
      """|---
         |apiClients:
         |  Orders:
         |    endpoints:
         |      - name: listOrders
         |        method: GET
         |        path: /orders
         |        request: Any
         |        response: Any
         |---
         |
         |# Orders
         |"""
    ) { file =>
      val cmd    = CheckTypesCmd()
      val result = cmd.runResult(List(file.toString))
      result.isSuccess shouldBe false
    }

  test("check-types prints unknown count summary for Any-evidence module"):
    withSscFile(
      """|---
         |apiClients:
         |  Misc:
         |    endpoints:
         |      - name: doThing
         |        method: POST
         |        path: /thing
         |        request: Any
         |        response: Any
         |---
         |
         |# Misc
         |"""
    ) { file =>
      val cmd = CheckTypesCmd()
      val out = captureStdout { val _ = cmd.runResult(List(file.toString)) }
      assert(out.contains("0 declared, 1 unknown") || out.contains("1 declared, 1 unknown"))
      assert(out.contains("route have unknown types.") || out.contains("routes have unknown types."))
    }

  test("check-types includes Symbol evidence section"):
    withSscFile(
      """|---
         |name: test
         |---
         |
         |# Code
         |
         |```scalascript
         |val x = 42
         |```
         |"""
    ) { file =>
      val cmd = CheckTypesCmd()
      val out = captureStdout { val _ = cmd.runResult(List(file.toString)) }
      out should include ("Symbol evidence (Any-typed exports):")
      out should include ("unknown:")
    }

  test("check-types prints GraphQL evidence section with all-declared SDL"):
    withSscFile(
      """|---
         |name: gql-test
         |---
         |
         |# Schema
         |
         |```graphql
         |type Query { id: ID!, name: String }
         |```
         |"""
    ) { file =>
      val cmd = CheckTypesCmd()
      val out = captureStdout { val _ = cmd.runResult(List(file.toString)) }
      out should include ("GraphQL evidence:")
      out should include ("object/interface/input types:")
    }

  test("check-types exits 1 when GraphQL block has unknown field type"):
    withSscFile(
      """|---
         |name: gql-unknown
         |---
         |
         |# Schema
         |
         |```graphql
         |type Query { ghost: UnknownType }
         |```
         |"""
    ) { file =>
      val cmd    = CheckTypesCmd()
      val result = cmd.runResult(List(file.toString))
      result.isSuccess shouldBe false
    }

  test("check-types is registered in CommandRegistry"):
    CommandRegistry.lookup("check-types") shouldBe defined
