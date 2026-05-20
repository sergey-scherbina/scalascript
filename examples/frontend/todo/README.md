# Todo list demo

A reactive list rendered via `View.ForSignal`, with add / clear
buttons driven by `EventHandler.PushSignalLiteral` and
`EventHandler.ClearSignalList`.

## `.ssc`-level intent

```scala
val todos = ReactiveSignalList[String]("todos", Seq("write some ScalaScript"))

view:
  h1("Todo list")
  button(id = "add",   onClick = push(todos, "new item"))("add")
  button(id = "clear", onClick = clearList(todos))("clear")
  ul(id = "list"):
    forEach(todos):
      li(_)                            // implicit "render each item as <li>String(item)</li>"
```

Built from
[`TodoListDemo.scala`](../../../frontend-examples/src/main/scala/scalascript/frontend/examples/TodoListDemo.scala).

### Per-item template scope

A2e renders each item as `<tag attrs>String(item)</tag>` — currently
just enough for the canonical todo demo.  Rich per-item templates
(nested elements, per-item events, conditional sub-children) need
either compile-time inlining of a `T => View` template or a runtime
view DSL on the JS side; both are deferred to a follow-up phase.

## Four flavours of emitted JS

| Backend | How `ForSignal` lowers | Reconciliation strategy |
| --- | --- | --- |
| **custom** | `__ssc_lists['todos']` cell + a subscription on the `<ul>` that wipes + re-builds children on every change | Wipe-and-rebuild (no keyed reconciliation yet) |
| **react**  | `array.map(item => createElement(tag, { key }, item))` inside `render()`                                  | React's diff handles reuse via array keys |
| **solid**  | `createEffect` that wipes + re-builds the `<ul>` children when the list signal fires                      | Wipe-and-rebuild (no native `<For>` yet — solid-js JSX runtime out of scope here) |
| **vue**    | `this.todos.map(item => h(tag, { key }, item))` inside the render arrow                                   | Vue's VDOM diff handles reuse via keys |

Click "add" three times then "clear": every backend ends with an
empty list; click "add" again and the first new item appears at
position 0.
