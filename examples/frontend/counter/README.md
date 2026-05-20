# Counter demo

The smallest non-trivial reactive UI: one `Int` signal, three
buttons (-, +, reset), one display span.

## `.ssc`-level intent

```scala
val count = ReactiveSignal[Int]("count", 0)

view:
  h1("Counter")
  button(id = "dec",   onClick = decrement(count, by = 1))("-")
  span(id = "display")(count)              // live-bound text node
  button(id = "inc",   onClick = increment(count, by = 1))("+")
  button(id = "reset", onClick = setSignal(count, 0))("reset")
```

The actual IR construction lives in
[`CounterDemo.scala`](../../../frontend-examples/src/main/scala/scalascript/frontend/examples/CounterDemo.scala);
this README captures the user-facing shape it's modelling.

## Four flavours of emitted JS

| Backend | Reactivity model | What `count` lowers to | How clicks flow |
| --- | --- | --- | --- |
| **custom** | Subscribe-and-patch (Solid-style)  | A cell in `__ssc_signals['count']` + a Set of subscribers | The `+` listener reads the cell, adds 1, calls `__setSignal` which notifies the SignalText subscriber, which patches `textContent` on the display span |
| **react**  | Re-render whole component on change | `const [count, setCount] = useState(0)`                  | The `+` listener calls `setCount(c => c + 1)`; React reconciles the new tree against the old and patches the changed `<span>` text |
| **solid**  | Fine-grained subscriptions          | `const [count, setCount] = createSignal(0)`              | The `+` listener calls `setCount(c => c + 1)`; Solid's `createEffect` re-runs the textContent assignment for the affected node only |
| **vue**    | Proxy-based dep tracking            | `const count = ref(0)` in `setup()`                      | The `+` listener calls `this.count += 1`; the proxy notifies its render dependency and Vue re-runs the render function |

All four end up at the same observed DOM behaviour — the difference is
purely the JS the user (or their bundler) has to ship.

Run `sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"`
then open `target/frontend-examples/counter/<backend>/index.html` in a
browser to see each in action.
