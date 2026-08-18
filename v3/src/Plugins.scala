package ssc3

/** THE HOST DOOR THE CHARTER ALREADY PROMISED. Invariant I-1 says everything outside the JDK
  * "reaches the language only through `Prim` and the plugin SPI" — and until now v3 had the first
  * and not the second. The plugin SPI was named in the charter, in `Ir.scala`, in `Exec.scala` and
  * in four comments, and implemented nowhere.
  *
  * WHAT THIS IS: a name -> function table and nothing else. It holds no plugins, loads no classes
  * and imports nothing outside `scala.*`, so the kernel's dependency list is unchanged and I-1
  * holds by construction rather than by review. Providers live in `v3/plugins`, a separate source
  * directory that `v3/ssc3` compiles alongside — the same arrangement `alphabet/src` already uses,
  * and for the same reason: the kernel must stay "compile these repo files".
  *
  * IT IS DELIBERATELY THE SHAPE OF `V2PluginRegistry` (`v2/src/Runtime.scala:1281`): a mutable map
  * of `String -> List[Value] => Value`. Two registries that answer the same question in two shapes
  * is how the two lanes would drift, and this file exists to stop the lanes drifting.
  *
  * THE RULE THAT MAKES IT SAFE, and it is not a style point: a name is answerable ONLY IF BOTH
  * LANES CAN ANSWER IT. `Lower` refuses an unimplemented host function at LOWERING, uniformly, so
  * today the executor and the bridge agree by refusing together. Relaxing that for a name only one
  * lane can perform is exactly what was tried before and recorded in `Lower.scala`: v2's plugins
  * answered on the bridge, nothing answered on the executor, ELEVEN corpus cases diverged and FIVE
  * PROGRAMS RAN AND PRINTED THE WRONG THING. So a provider registered here must also be reachable
  * from the bridge, and `registered` is what `Lower` consults before it emits a `Prim` instead of a
  * refusal. */
object Plugins:

  /** THE MODULE IS PART OF THE SIGNATURE, and that is forced by a representation difference rather
    * than chosen for convenience. v2's `DataV` carries its constructor as a STRING tag; v3's
    * `VData` carries an INT — an index into the module's type table. So a provider that returns
    * `Some(x)` or a list cannot be converted without the module in hand, and an SPI of
    * `List[Value] => Value` would have forced an allow-list of "providers that only return
    * scalars" — the kind of list that gets forgotten, and whose forgetting would turn an honest
    * refusal into a run-time crash. */
  type Fn = (Module, List[Value]) => Value

  private val table = collection.mutable.HashMap.empty[String, Fn]

  /** Register a host function under the name an `extern def` declares.
    *
    * IDEMPOTENT BY LAST WRITER, like v2's. A provider loaded twice — two classpath entries, a
    * ServiceLoader that sees a jar and its exploded classes — must not double-register into
    * something that then behaves differently; overwriting keeps the outcome independent of load
    * order for a provider that registers the same function. */
  def register(name: String, fn: Fn): Unit = table(name) = fn

  def lookup(name: String): Option[Fn] = table.get(name)

  /** The names a provider has offered. `Lower` reads THIS to decide whether an `extern def` lowers
    * to a `Prim` or to a positioned refusal, which is why registration has to happen before a
    * program is lowered and not lazily at the first call. */
  def registered: Set[String] = table.keySet.toSet

  /** A METHOD ON A VALUE THE HOST OWNS — the second door, and it is a single function rather than a
    * table because the KEY is not a name the kernel can compute.
    *
    * v2 dispatches `xs.next` on a foreign handle through an interface the handle implements, and
    * `r.exitCode` on host data through a `(tag, method)` table. Both keys are v2's, so a table here
    * would have to mirror v2's tags — two registries answering the same question in two shapes,
    * which is the drift `V2PluginRegistry`'s comment above warns about. One dispatcher keeps the
    * knowledge on the side that has it.
    *
    * `None` means "not mine", and the executor then refuses exactly as it did before — so a value
    * no provider claims still gets v3's own diagnostic rather than a wrapped host error. */
  type MethodFn = (Module, Value, String, List[Value]) => Option[Value]

  private var methods: Option[MethodFn] = None
  def registerMethods(fn: MethodFn): Unit = methods = Some(fn)
  def method(m: Module, recv: Value, name: String, args: List[Value]): Option[Value] =
    methods.flatMap(f => f(m, recv, name, args))

  /** HOW THE HOST PRINTS ITS OWN VALUE — the third door, and the narrowest.
    *
    * A handle is opaque to v3 by design, so rendering one is the owner's business and not a guess
    * this side can make. `jsonRead(…).get("missing")` answers a `JsonBox` carrying `optional` and
    * `present` flags — v2 prints that as `None`, and v3 printed `<handle JsonBox>`, which is a wrong
    * answer rather than a missing feature: the program asked for a value's text and got a
    * description of its container.
    *
    * `None` means "not mine, render it your own way", so nothing changes for a value no provider
    * claims. */
  type ShowFn = (Module, Value) => Option[String]

  private var shower: Option[ShowFn] = None
  def registerShow(fn: ShowFn): Unit = shower = Some(fn)
  def showHost(m: Module, v: Value): Option[String] = shower.flatMap(f => f(m, v))
