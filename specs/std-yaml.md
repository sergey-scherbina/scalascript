# `std.yaml` — YAML Parse / Stringify

Status: **planned**.  Tracked as `std-yaml` milestone in SPRINT.md.
Related: [`specs/std-fs-os.md`](std-fs-os.md), [`specs/backend-specific-blocks.md`](backend-specific-blocks.md).

---

## 1. Motivation

`.ssc` user code cannot call `parseYaml(s)` or convert values to YAML today.
`SimpleYaml` already covers the common 90% of real YAML but only returns internal
Java types — not ScalaScript `Value`s.  This spec adds a cross-backend
`std.yaml` module exposing parse and stringify to user code.

---

## 2. API

```scalascript
// ── Core functions ────────────────────────────────────────────────────────────
extern def parseYaml(s: String): YamlValue
extern def toYaml(v: YamlValue): String

// ── YamlValue ADT ─────────────────────────────────────────────────────────────
sealed trait YamlValue

case class YStr(value: String)                 extends YamlValue
case class YNum(value: Double)                 extends YamlValue
case class YBool(value: Boolean)               extends YamlValue
case object YNull                              extends YamlValue
case class YArr(items: List[YamlValue])        extends YamlValue
case class YObj(fields: Map[String, YamlValue]) extends YamlValue
```

### 2.1 Accessor helpers

```scalascript
extension (v: YamlValue)
  def str:  Option[String]             = v match { case YStr(s)  => Some(s); case _ => None }
  def num:  Option[Double]             = v match { case YNum(n)  => Some(n); case _ => None }
  def bool: Option[Boolean]            = v match { case YBool(b) => Some(b); case _ => None }
  def arr:  Option[List[YamlValue]]    = v match { case YArr(a)  => Some(a); case _ => None }
  def obj:  Option[Map[String,YamlValue]] = v match { case YObj(m) => Some(m); case _ => None }
  def isNull: Boolean                  = v == YNull
  def apply(key: String): YamlValue   = v match { case YObj(m) => m.getOrElse(key, YNull); case _ => YNull }
  def apply(idx: Int): YamlValue      = v match { case YArr(a) => if idx >= 0 && idx < a.length then a(idx) else YNull; case _ => YNull }
```

---

## 3. Supported YAML subset

The subset mirrors what `SimpleYaml` already parses (used for front-matter):

| Feature | Supported |
|---------|-----------|
| Block mappings `key: value` | ✓ |
| Block sequences `- item` | ✓ |
| Flow mappings `{k: v}` | ✓ |
| Flow sequences `[a, b]` | ✓ |
| Single-quoted strings `'...'` | ✓ |
| Double-quoted strings `"..."` (escape sequences) | ✓ |
| Null: `null`, `~`, empty | ✓ |
| Booleans: `true`/`false` (case-insensitive) | ✓ |
| Integers | ✓ (stored as `YNum`) |
| Doubles | ✓ |
| Comments `#` | ✓ |
| Literal block scalars `\|` | ✓ |
| Folded block scalars `>` | ✓ |
| Anchors `&foo` / aliases `*foo` | ✗ out of scope |
| Merge keys `<<: *base` | ✗ out of scope |
| Multi-document `---` separator | ✗ out of scope |
| YAML 1.2 typed tags `!!timestamp` | ✗ out of scope |

---

## 4. Serialization format (`toYaml`)

Output is block-style YAML, suitable for human reading and round-tripping:

- `YObj` → block mapping, keys alphabetically sorted, indented 2 spaces
- `YArr` → block sequence with `- ` prefix
- `YStr` → quoted only when necessary (contains `:`, `#`, `[`, `{`, leading/trailing space, or looks like a scalar keyword)
- `YNum` → integer if `.0` suffix would be misleading, else decimal
- `YBool` → `true` / `false`
- `YNull` → `null`

Round-trip invariant: `parseYaml(toYaml(v)) == v` for all `YamlValue` trees.

---

## 5. Backend implementations

| Backend | `parseYaml` | `toYaml` |
|---------|-------------|----------|
| Interpreter / JVM | `SimpleYaml.load` → `Value` converter | pure-Scala block-style serializer |
| JS / Node | inline pure-JS parser (matches SimpleYaml subset) | pure-JS block serializer |
| Browser | same JS impl | same JS impl |
| Rust | `serde_yaml` crate (optional dep) or inline parser | `serde_yaml::to_string` |

---

## 6. `yaml` fenced blocks (Phase 4)

A `yaml` fenced block today produces `EmbeddedKind.StructuredData` accessible only
via the content API (`doc.sections[n].blocks[m].data`).  Phase 4 adds a ScalaScript-visible
binding: the block's content is parsed as `YamlValue` and bound to a `val` named
`<sectionId>_yaml` in the section's scope, analogous to how `html`/`css` blocks bind
`<sectionId>.html` as a `String`.

```markdown
# Config

```yaml
host: localhost
port: 8080
```

```scalascript
// config_yaml is now a YamlValue in scope:
val host = config_yaml("host").str.getOrElse("localhost")
val port = config_yaml("port").num.map(_.toInt).getOrElse(8080)
```
```

---

## 7. Implementation plan

| Phase | Task | Commit |
|-------|------|--------|
| 1 | spec (this file) | `spec: std-yaml` |
| 2 | `yaml-plugin`: JVM `parseYaml`/`toYaml`, `Value` converter, serializer | `feat(yaml-plugin): JVM backend` |
| 3 | `JsRuntimeYaml.scala`: JS parse+serialize preamble | `feat(jsgen): std.yaml JS/Node preamble` |
| 4 | `runtime/std/yaml.ssc` + fenced-block wiring + example | `feat(std): std.yaml stdlib module` |

---

## 8. Non-goals

- Anchors & aliases — require a stateful second pass; deferred.
- Merge keys — depend on anchors; deferred.
- Multi-document streams — separate use case; deferred.
- Full YAML 1.2 type tags — deferred; most real-world YAML doesn't need them.
- Streaming parse — synchronous only for now.
- Schema validation — separate milestone.
