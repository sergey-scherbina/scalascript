package scalascript.codegen

/** Per-host library packaging (Task B, `specs/polyglot-libraries.md` §4 / §6 Phase 2).
 *
 *  Packages a ScalaScript feature's emitted JS runtime as a **standalone npm (ESM) package** with
 *  a curated, stable public API + TypeScript types — no `.ssc` source or ScalaScript build
 *  dependency at the consumer's edge. The first feature is **optics** (Lens / Optional / Traversal
 *  / Prism), chosen because it is pure (zero effects, zero host coupling).
 *
 *  The package re-exports the runtime `_make*` factories under stable public names, bundles only
 *  the Option/Map helpers the optics functions reference (`_None`/`_Some`/`_isMap`, with `_isMap`
 *  narrowed to native JS `Map` at the library edge), and ships a hand-curated `optics.d.ts`. The
 *  `.d.ts` is the **frozen public API signature** — the golden test asserts it verbatim so a
 *  codegen change can't silently break the published surface. */
object JsLibPackager:

  /** Minimal Option + Map prelude the optics runtime depends on, narrowed to native JS values
   *  for the standalone package (the ScalaScript HAMT is an internal detail, not part of the
   *  library boundary). */
  private val opticsPrelude: String =
    """// Option + Map helpers (the subset the optics runtime references).
      |const _None = { _type: '_None' };
      |function _Some(v) { return { _type: '_Some', value: v }; }
      |function _isMap(x) { return x instanceof Map; }
      |""".stripMargin

  /** Public step-builders + re-exports appended after the runtime. */
  private val opticsExports: String =
    """// Step builders — compose paths for Optional / Traversal.
      |function field(name) { return name; }
      |function index(i) { return { kind: 'index', i }; }
      |function at(key) { return { kind: 'at', key }; }
      |const some = '__some__';
      |const each = '__each__';
      |
      |export {
      |  _makeLens as makeLens,
      |  _makeOptional as makeOptional,
      |  _makeTraversal as makeTraversal,
      |  _makePrism as makePrism,
      |  _Some as Some,
      |  _None as None,
      |  field, index, at, some, each,
      |};
      |""".stripMargin

  /** The ESM module: prelude + the verbatim optics runtime + public exports. */
  def opticsIndexMjs: String =
    "// @scalascript/optics — standalone ESM library (generated; no ScalaScript runtime dependency).\n" +
    "// Lens / Optional / Traversal / Prism. Source of truth: scalascript.codegen.JsRuntimeOptics.\n\n" +
    opticsPrelude + JsRuntimeOptics.stripPrefix("\n") + "\n" + opticsExports

  /** The frozen public API signature (the golden). */
  def opticsDts: String =
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

  /** `package.json` with a stable, curated export surface. */
  def opticsPackageJson(version: String): String =
    s"""{
       |  "name": "@scalascript/optics",
       |  "version": "$version",
       |  "description": "Composable optics (Lens/Optional/Traversal/Prism), generated from the ScalaScript optics runtime. No runtime dependency.",
       |  "type": "module",
       |  "main": "./index.mjs",
       |  "module": "./index.mjs",
       |  "types": "./optics.d.ts",
       |  "exports": {
       |    ".": {
       |      "types": "./optics.d.ts",
       |      "import": "./index.mjs"
       |    }
       |  },
       |  "sideEffects": false,
       |  "license": "Apache-2.0"
       |}
       |""".stripMargin

  /** The complete npm package as `filename -> content`. */
  def opticsNpmPackage(version: String): Map[String, String] = Map(
    "package.json" -> opticsPackageJson(version),
    "index.mjs"    -> opticsIndexMjs,
    "optics.d.ts"  -> opticsDts,
  )
