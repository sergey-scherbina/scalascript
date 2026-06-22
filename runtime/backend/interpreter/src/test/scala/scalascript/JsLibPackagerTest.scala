package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JsLibPackager

/** Golden + runtime tests for the JS optics npm-package packager (Task B,
 *  `specs/polyglot-libraries.md` §4 / §6 Phase 2 — publish the pure optics feature as a
 *  standalone host library). The `optics.d.ts` is asserted **verbatim** — it is the frozen
 *  public API signature, so a codegen change can't silently break the published surface. An
 *  `assume(node)`-gated smoke writes the generated package to a temp dir and exercises all four
 *  optics through real Node ESM `import`, proving the bundle works without any ScalaScript dep. */
class JsLibPackagerTest extends AnyFunSuite with Matchers:

  test("npm package has exactly the three standard files"):
    JsLibPackager.opticsNpmPackage("0.1.0").keySet shouldBe
      Set("package.json", "index.mjs", "optics.d.ts")

  test("package.json exposes a stable ESM + types surface"):
    val pj = JsLibPackager.opticsPackageJson("1.2.3")
    pj should include (""""name": "@scalascript/optics"""")
    pj should include (""""version": "1.2.3"""")
    pj should include (""""type": "module"""")
    pj should include (""""types": "./optics.d.ts"""")
    pj should include (""""import": "./index.mjs"""")

  test("index.mjs bundles the optics runtime + its Option/Map prelude and re-exports public names"):
    val mjs = JsLibPackager.opticsIndexMjs
    // bundled runtime + its only external deps
    mjs should include ("function _makeLens(")
    mjs should include ("function _makeOptional(")
    mjs should include ("function _makeTraversal(")
    mjs should include ("function _makePrism(")
    mjs should include ("const _None = { _type: '_None' };")
    mjs should include ("function _isMap(x) { return x instanceof Map; }")
    // stable public export names (no leading underscore leaks)
    mjs should include ("_makeLens as makeLens")
    mjs should include ("_makeOptional as makeOptional")
    mjs should include ("_makeTraversal as makeTraversal")
    mjs should include ("_makePrism as makePrism")
    // no ScalaScript-internal HAMT leaks at the library edge
    mjs should not include "_HAMT"

  test("optics.d.ts matches the frozen public API signature (golden)"):
    JsLibPackager.opticsDts shouldBe ExpectedDts

  test("Node smoke: the generated package's four optics work via real ESM import"):
    assume(nodeAvailable, "node not on PATH — skipping ESM runtime smoke")
    val dir = java.nio.file.Files.createTempDirectory("ssc-optics-npm").toFile
    try
      JsLibPackager.opticsNpmPackage("0.1.0").foreach { (name, content) =>
        java.nio.file.Files.writeString(new java.io.File(dir, name).toPath, content)
      }
      val script =
        """import { makeLens, makeOptional, makeTraversal, makePrism, field, index, each } from './index.mjs';
          |const l = makeLens(['a','b']);
          |console.log(l.get({a:{b:5}}));
          |console.log(JSON.stringify(l.set({a:{b:5}}, 9)));
          |console.log(JSON.stringify(l.modify({a:{b:5}}, x=>x+1)));
          |const o = makeOptional([field('a'), index(0)]);
          |console.log(JSON.stringify(o.getOption({a:[10,20]})));
          |console.log(o.getOption({a:[]})._type);
          |const t = makeTraversal([field('xs'), each]);
          |console.log(JSON.stringify(t.getAll({xs:[1,2,3]})));
          |console.log(JSON.stringify(t.modify({xs:[1,2,3]}, x=>x*2)));
          |const p = makePrism('Circle');
          |console.log(p.getOption({_type:'Circle', r:1})._type);
          |console.log(p.getOption({_type:'Square'})._type);
          |""".stripMargin
      java.nio.file.Files.writeString(new java.io.File(dir, "smoke.mjs").toPath, script)
      val out = new StringBuilder
      val logger = scala.sys.process.ProcessLogger(line => out.append(line).append('\n'), _ => ())
      val code = scala.sys.process.Process(Seq("node", "smoke.mjs"), dir).!(logger)
      code shouldBe 0
      out.toString.trim shouldBe
        Seq(
          "5",
          """{"a":{"b":9}}""",
          """{"a":{"b":6}}""",
          """{"_type":"_Some","value":10}""",
          "_None",
          "[1,2,3]",
          """{"xs":[2,4,6]}""",
          "_Some",
          "_None",
        ).mkString("\n")
    finally
      Option(dir.listFiles).foreach(_.foreach(_.delete()))
      dir.delete()

  private def nodeAvailable: Boolean =
    try scala.sys.process.Process(Seq("node", "--version")).!(scala.sys.process.ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Throwable => false

  private val ExpectedDts: String =
    """// @scalascript/optics — TypeScript declarations (generated). The frozen public API surface.
      |
      |export type Step = string | { kind: 'index'; i: number } | { kind: 'at'; key: unknown };
      |export type Option<A> = { _type: '_Some'; value: A } | { _type: '_None' };
      |
      |export interface Lens<S, A> {
      |  readonly _type: 'Lens';
      |  get(s: S): A;
      |  set(s: S, v: A): S;
      |  modify(s: S, f: (a: A) => A): S;
      |  andThen(other: Lens<A, any> | Optional<A, any> | Traversal<A, any>): Lens<S, any> | Optional<S, any> | Traversal<S, any>;
      |}
      |
      |export interface Optional<S, A> {
      |  readonly _type: 'Optional';
      |  getOption(s: S): Option<A>;
      |  set(s: S, v: A): S;
      |  modify(s: S, f: (a: A) => A): S;
      |  andThen(other: Optional<A, any> | Traversal<A, any>): Optional<S, any> | Traversal<S, any>;
      |}
      |
      |export interface Traversal<S, A> {
      |  readonly _type: 'Traversal';
      |  getAll(s: S): A[];
      |  modify(s: S, f: (a: A) => A): S;
      |  set(s: S, v: A): S;
      |  andThen(other: Traversal<A, any>): Traversal<S, any>;
      |}
      |
      |export interface Prism<S, A> {
      |  readonly _type: 'Prism';
      |  getOption(s: S): Option<A>;
      |  reverseGet(a: A): S;
      |  set(s: S, v: A): S;
      |  modify(s: S, f: (a: A) => A): S;
      |  andThen(other: Prism<A, any>): Prism<S, any>;
      |}
      |
      |export function makeLens<S = any, A = any>(path: string[]): Lens<S, A>;
      |export function makeOptional<S = any, A = any>(steps: Step[]): Optional<S, A>;
      |export function makeTraversal<S = any, A = any>(steps: Step[]): Traversal<S, A>;
      |export function makePrism<S = any, A = any>(variant: string): Prism<S, A>;
      |
      |export function field(name: string): string;
      |export function index(i: number): { kind: 'index'; i: number };
      |export function at(key: unknown): { kind: 'at'; key: unknown };
      |export const some: '__some__';
      |export const each: '__each__';
      |
      |export function Some<A>(value: A): Option<A>;
      |export const None: Option<never>;
      |""".stripMargin
