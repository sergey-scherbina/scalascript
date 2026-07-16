package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression for busi-v1-lane-runtime-regressions: a nullary enum case named
 *  `None` in an imported module must not replace ScalaScript's built-in Option
 *  singleton. The enum case remains available through its companion.
 *
 *  This is deliberately multi-file. The production failure appears while the
 *  Personal Vault domain is evaluated as an imported module; a single snippet
 *  would not protect the module-loader boundary that exposed the collision. */
class EnumBuiltinCollisionTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parseFile(dir / entry))
    ps.flush()
    buf.toString.trim

  test("imported enum None keeps qualified case access without shadowing Option.None"):
    val dir = os.temp.dir(prefix = "ssc-enum-builtin-collision-")
    try
      os.write(dir / "vault_domain.ssc",
        """---
          |name: vault_domain
          |exports:
          |  - DataClass
          |  - missingLabel
          |  - enumNoneLabel
          |---
          |# Vault domain
          |
          |```scalascript
          |enum DataClass:
          |  case None, Pii, Sensitive, Secret
          |
          |def missingLabel(): Option[String] = None
          |def enumNoneLabel(): String = DataClass.None match
          |  case DataClass.None => "enum-none"
          |  case _ => "other"
          |```
          |""".stripMargin)
      os.write(dir / "main.ssc",
        """# Main
          |
          |[DataClass, missingLabel, enumNoneLabel](vault_domain.ssc)
          |
          |```scalascript
          |println(missingLabel().isEmpty)
          |println(enumNoneLabel())
          |println(DataClass.values.length)
          |```
          |""".stripMargin)

      assert(run(dir, "main.ssc") == "true\nenum-none\n4")
    finally os.remove.all(dir)
