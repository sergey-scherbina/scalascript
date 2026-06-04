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

### Markdown Body -> Document Content IR (planned)

The next planned layer keeps the existing scope/import/code behavior and also
exposes the non-code Markdown body as typed content. This lets code blocks read
document content as metadata, and lets frontend helpers render prose, lists,
links, images, and future tables without user-written HTML generation.

```markdown
# Pricing {#pricing route=/pricing layout=marketing}

Simple plans for small teams.

<!-- @meta component=PlanList source=plans -->
## Plans

- Starter: $19
- Pro: $49
```

Planned AST/runtime shape:

```text
DocumentContent(
  title = Some("Pricing"),
  sections = List(
    SectionContent(
      id = "pricing",
      attrs = Map("route" -> "/pricing", "layout" -> "marketing"),
      blocks = List(Paragraph(...)),
      children = List(SectionContent(id = "plans", attrs = Map("component" -> "PlanList", ...)))
    )
  )
)
```

The public API is planned under `std/content.ssc`:

```scalascript
val doc = contentDocument()
val pricing = contentSection("pricing")
val current = contentCurrentSection()
```

Frontend lowering is planned separately under `std/ui/content.ssc`:

```scalascript
val page = contentView(contentDocument())
```

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
```

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
[Click here](https://example.com)  <!-- External URL, not import -->
[See docs](./docs/readme.md)       <!-- Relative file, not module -->
[List](#List)                      <!-- Internal ref, not import -->
```

Import detection rules:
1. No protocol prefix (`http://`, `https://`)
2. No file extension
3. Doesn't start with `#`
