# Markdown as Syntax

This document explains how ScalaScript uses Markdown constructs as first-class language syntax.

## Philosophy

In most languages, Markdown is documentation — comments that the compiler ignores. In ScalaScript, **Markdown IS the syntax**. The compiler parses and interprets Markdown constructs as semantic elements.

This unification provides:
- Human-readable source that is also valid documentation
- Familiar structure for anyone who knows Markdown
- Natural hierarchical organization via headings
- Seamless mixing of prose, code, and data

## Construct Mappings

### Markdown Host -> Document Content IR

The content layer keeps the existing scope/import/code behavior and uses
the Markdown-hosted document as the first frontend authoring surface. Prose,
lists, links, images, YAML/front-matter, fenced YAML/JSON/TOML data blocks, and
other embedded languages that appear as legitimate Markdown blocks lower into a
shared content IR. Frontend helpers are the first target: they render the
document to toolkit nodes without user-written HTML or manual UI tree
construction. Code-block introspection over the same IR follows as a supporting
metadata API.

````markdown
# Pricing {#pricing route=/pricing layout=marketing}

Simple plans for small teams.

<!-- @meta component=PlanList source=plans data=plans-data -->
## Plans

- Starter: $19
- Pro: $49

```yaml @id=plans-data
plans:
  - id: starter
    price: 19
  - id: pro
    price: 49
```
````

AST/runtime shape:

```text
DocumentContent(
  title = Some("Pricing"),
  sections = List(
    SectionContent(
      id = "pricing",
      attrs = Map("route" -> "/pricing", "layout" -> "marketing"),
      blocks = List(Paragraph(...)),
      children = List(SectionContent(
        id = "plans",
        attrs = Map("component" -> "PlanList", "data" -> "plans-data", ...),
        blocks = List(Embedded(lang = "yaml", kind = StructuredData, data = Some(...)))
      ))
    )
  )
)
```

The first public path is frontend lowering:

```scalascript
[contentComponent, contentToolkitNode, contentToolkitBlock, contentToolkitSection, contentToolkitOptionsWithBindings, contentToolkitOptionsWithComponents](std/ui/content.ssc)
[vstack](std/ui/layout.ssc)
[heading](std/ui/typography.ssc)
[rawText](std/ui/reactive.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)

val planList = contentComponent("PlanList") { ctx =>
  vstack(gap = 4)(
    heading(2, "Plans from component"),
    rawText("id=" + ctx.id),
    rawText("data=" + ctx.data.isDefined.toString)
  )
}

val page = lower(
  contentToolkitNode(contentToolkitOptionsWithComponents([planList])),
  defaultTheme
)
```

`contentToolkitNode()` turns the current Markdown document into a regular
`TkNode` subtree. When one document defines multiple independent frontend
regions, `contentToolkitBlock("id")` selects a block such as
`@id=filters @ui=toolkit` on a fenced YAML block, and
`contentToolkitSection("plans")` selects a heading section by stable id.
Metadata such as `component=PlanList` is resolved only through an explicit
`contentComponent("PlanList")` registry passed in toolkit options. Metadata
such as `data=plans-data` binds the component context to the parsed
YAML/JSON/TOML data block with that id, and code can read the same value through
`contentData("plans-data")`; missing registry entries use the default Markdown
lowering. Code can also query regions directly with `contentSection(id)` and
`contentBlock(id)`, then extract readable text through `contentPlainText(value)`.
`contentMetadata(path)` reads `content:` front-matter metadata by dot path, and
`contentToMarkdown(value)` serializes a selected document, section, or block
back to deterministic semantic Markdown.
`contentBind(value, bindings)` explicitly resolves Markdown `${name}` placeholders
from `ContentValue.MapV` data before these renderers consume the selected
content; toolkit selectors use `contentToolkitOptionsWithBindings(data)` for
the same pre-render step.
Ordinary Markdown links with a `toolkit:` destination, such as
`[Team name](toolkit:textField?signal=teamName&value=ScalaScript%20team)`, are
kept as content rather than imports and lower to toolkit controls for simple
forms. Structured `yaml @ui=toolkit` fences remain the better fit for nested
layout trees.
GFM pipe tables become `ContentBlock.Table` nodes with inline header/cell
content, alignment metadata, and attrs from a preceding `<!-- @meta ... -->`
directive. Toolkit lowering maps them to `TableNode`; low-level `contentView`
maps them to semantic `table` markup.
The low-level `contentView(contentDocument())` renderer remains available when
callers need direct `View` nodes.

The current interpreter `std/content.ssc` lookup surface is:

```scalascript
val doc = contentDocument()
val here = contentCurrentSection()
val pricing = contentSection("pricing")
val controls = contentBlock("filters")
val plansData = contentData("plans-data")
val renderer = contentMetadata("defaultRenderer")
val text = contentPlainText(here)
val markdown = contentToMarkdown(here)
val imported = contentModule("std-money")
val importedSection = contentModuleSection("std-money", "minor-units-integer-count-of-the-smallest-unit-e-g-cents")
```

`contentCurrentSection()` is execution-scoped: a function called from a later
code block sees the caller's current Markdown section, and headingless code
reports an interpreter/runtime error. The low-level `std/content` helpers above
run on interpreter, generated JS, and generated JVM paths.

Pure Markdown import links also create a direct imported content namespace when
the imported module has a `DocumentContent` snapshot. `contentModules()` returns
the duplicate-aware namespace table, `contentModule(namespace)` selects the
imported `DocumentContent`, and `contentModuleSection`,
`contentModuleBlock`, `contentModuleData`, and `contentModuleMetadata` apply
the same lookup semantics to that imported document. Namespaces come from the
imported module's `name:` front-matter value, falling back to the path stem.
The helper imports `std/content.ssc` and `std/ui/content.ssc` are not exposed
as content modules.

The full contract and implementation phases are in
[`../specs/markdown-content-introspection.md`](../specs/markdown-content-introspection.md).

### Headings → Scopes/Namespaces

Headings define the hierarchical structure of your code:

```markdown
# Math                    → Module-level namespace "Math"

Some documentation about the Math module.

## Arithmetic             → Nested scope "Math.Arithmetic"

Basic arithmetic operations.

### Addition              → Further nested "Math.Arithmetic.Addition"
```

**AST Mapping:**
```text
Heading(level=1, text="Math") → Scope(name="Math", parent=root)
Heading(level=2, text="Arithmetic") → Scope(name="Arithmetic", parent=Math)
```

**Scope Rules:**
- Level 1 (`#`) = module/top-level scope
- Each deeper level creates a nested scope
- Definitions are visible within their scope and nested scopes
- Siblings do not see each other's definitions by default

### Links → Imports/References

Markdown links serve as the import system:

```markdown
[std/collections](std/collections)
[contentDocument](std/content.ssc) [contentToolkitNode](std/ui/content.ssc)
```

A paragraph is an import declaration when every non-whitespace inline child is
an import link. Several import links may share one pure Markdown paragraph,
separated by spaces or line breaks; the parser lowers them in source order.

**Import Patterns:**

| Syntax | Meaning |
|--------|---------|
| `[mod](path/to/mod)` | Import module, bind to `mod` |
| `[List](std/collections#List)` | Import specific item |
| `[*](std/collections)` | Wildcard import (all exports) |
| `[List, Map](std/collections)` | Multiple specific imports |
| `[col => collections](std/collections)` | Aliased import |

**Internal References:**

```markdown
See [Arithmetic](#Arithmetic) for basic operations.
```

Links starting with `#` are internal cross-references, not imports.

### Fenced Code Blocks → Typed Expression Units

Code blocks are where computation happens:

````markdown
```scala
def factorial(n: Int): Int =
  if n <= 1 then 1
  else n * factorial(n - 1)
```
````

**Language Tags:**

| Tag | Purpose |
|-----|---------|
| `scala` | Scala-style expressions (primary) |
| `ssc` | ScalaScript (can contain nested markdown) |
| `json` | JSON data literal |
| `yaml` | YAML data literal |

**AST Mapping:**
```text
FencedCodeBlock(lang="scala", content="...") → CodeUnit(lang=Scala, ast=...)
```

**Code blocks are expressions**, not statements. They return a value:

````markdown
The answer is:

```scala
val x = 21
x * 2
```
````

The block above evaluates to `42`.

### Inline Code with Interpolation

Inline code with `${}` evaluates expressions:

```markdown
The factorial of 5 is `${factorial(5)}`.
```

**Parsing:**
1. Detect inline code: `` `...` ``
2. Check for `${...}` pattern
3. Parse inner expression
4. Evaluate and convert to string

### Lists → Data Structures

Lists can represent structured data:

```markdown
## Configuration

- debug: true
- port: 8080
- hosts:
  - localhost
  - example.com
```

When typed contextually, this becomes:

```scala
val config: Map[String, Any] = ...
```

**Unordered lists** (`-`, `*`, `+`) typically map to `List` or `Set`.

**Ordered lists** (`1.`, `2.`) map to indexed collections.

### YAML Front-Matter → Module Manifest

The YAML block at file start is the module manifest:

```yaml
---
name: my-module
version: 1.0.0
dependencies:
  std/collections: ^1.0.0
exports:
  - myFunction
  - MyType
---
```

**AST Mapping:**
```text
FrontMatter(yaml="...") → ModuleManifest(
  name: "my-module",
  version: SemVer(1, 0, 0),
  dependencies: [...],
  exports: [...]
)
```

### HTML Comments → Compiler Directives

HTML comments can contain compiler directives:

```markdown
<!-- @suppress("unused") -->
<!-- @deprecated("Use newFunction instead") -->
```

Standard HTML comments are ignored:

```markdown
<!-- This is just a comment, ignored by compiler -->
```

## Worked Example

Here's a complete ScalaScript file demonstrating all constructs:

````markdown
---
name: geometry
version: 1.0.0
exports:
  - Circle
  - Rectangle
  - area
---

# Geometry

A module for geometric calculations.

[math](std/math)

## Shapes

### Circle

A circle defined by its radius.

```scala
case class Circle(radius: Double)
```

### Rectangle

A rectangle defined by width and height.

```scala
case class Rectangle(width: Double, height: Double)
```

## Calculations

Functions for computing properties of shapes.

```scala
def area(shape: Circle | Rectangle): Double = shape match
  case Circle(r) => math.Pi * r * r
  case Rectangle(w, h) => w * h
```

## Examples

A circle with radius 5 has area `${area(Circle(5))}`.

A 3x4 rectangle has area `${area(Rectangle(3, 4))}`.
````

**AST Structure:**
```text
Module(name="geometry", version="1.0.0")
├── Import(path="std/math", binding="math")
├── Scope(name="Shapes")
│   ├── Scope(name="Circle")
│   │   └── ClassDef(Circle(radius: Double))
│   └── Scope(name="Rectangle")
│       └── ClassDef(Rectangle(width: Double, height: Double))
├── Scope(name="Calculations")
│   └── DefDef(area(...))
└── Scope(name="Examples")
    ├── Interpolation(area(Circle(5)))
    └── Interpolation(area(Rectangle(3, 4)))
```

## Edge Cases

### Heading Levels

Skipping levels is allowed but discouraged:

```markdown
# Top
### Skipped Level 2  <!-- Still valid, creates implicit level-2 -->
```

### Code Block Nesting

With `ssc` tag, you can nest markdown inside code:

````markdown
```ssc
# Nested Section

```scala
val x = 1
```
```
````

### Ambiguous Links

Links that look like imports but aren't:

```markdown
Read [the docs](https://example.com) before continuing.  <!-- prose, not import -->
[List](#List)                                         <!-- internal ref, not import -->
```

Import detection rules:
1. The whole paragraph is pure imports: only links plus Markdown whitespace.
2. Each import link destination is non-empty and does not start with `#`.
3. URL, dependency, registry, and local paths are all valid when used in a pure
   import paragraph.
