# Show / hide demo

A toggle button that flips visibility of a counter span.  Showcases
`View.ShowSignal` (the reactive conditional sub-tree) and
`EventHandler.ToggleSignal` (the canonical "flip a boolean" handler).

## `.ssc`-level intent

```scala
val visible = ReactiveSignal[Boolean]("visible", true)
val count   = ReactiveSignal[Int]("count", 0)

view:
  h1("Show / hide")
  button(id = "toggle", onClick = toggle(visible))("toggle")
  button(id = "inc",    onClick = increment(count))("+")
  showWhen(visible):
    span(id = "box")(count)
```

When `visible` is `true` the span renders with the live count;
flipping `visible` removes it from the DOM without touching the
counter's value, so re-toggling brings the previous count back.

Built from
[`ShowHideDemo.scala`](../../../frontend-examples/src/main/scala/scalascript/frontend/examples/ShowHideDemo.scala).

## Four flavours of emitted JS

| Backend | How `ShowSignal` lowers | State preservation across toggle |
| --- | --- | --- |
| **custom** | A subscription on the `visible` cell that swaps the placeholder child node                       | The `count` cell lives in the global signal registry; the `<span>` is destroyed/re-created but `count` survives, so the new span renders the live value |
| **react**  | A ternary inside `render()` — `visible ? <span>{count}</span> : ""`; React reconciles on change   | React's `useState` for `count` is hoisted; the ternary's `<span>` mounts/unmounts but the state cell survives the parent's re-render |
| **solid**  | A `createEffect` that wipes-and-rebuilds the conditional region when `visible()` changes          | Solid's signal cells outlive any DOM they were wired to |
| **vue**    | A ternary inside the render arrow; Vue re-runs `render()` when the proxy notices `visible` change | `ref()` cells live on the component instance; mount/unmount of the span doesn't touch them |

Increment the counter several times, toggle to hide, toggle to show
again — the value persists in all four implementations.
