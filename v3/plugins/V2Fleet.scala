package ssc3.plugins

import ssc3.{Module, Plugins, Value}

/** THE EXISTING PLUGIN FLEET, MADE ANSWERABLE BY v3's EXECUTOR.
  *
  * `v2/runtime/std/<name>-plugin/` holds twenty-one providers — ui, content, os, fs, actors, json,
  * http-fast and the rest — and measured on 2026-08-15 they already implement SEVENTEEN of the
  * nineteen host names the corpus refuses. Nothing was missing except a way to call them: v3's
  * executor had no plugin path at all, which is what the whole
  * `host function '…' is not implemented on this lane` bucket was.
  *
  * WHY THIS IS NOT IN `v3/src`. The kernel must build with an empty dependency list (invariant
  * I-1), and this file references `ssc.*` — v2's runtime — so it cannot live there. It is a
  * separate source directory that `v3/ssc3` compiles alongside, exactly as `alphabet/src` already
  * is. The kernel holds the DOOR (`ssc3.Plugins`, a name -> function table with no imports); this
  * holds what comes through it.
  *
  * THE CONVERSION IS THE WHOLE OF THE WORK, and it exists because the two runtimes represent a
  * value differently in one place that matters: v2's `DataV` names its constructor with a STRING
  * and v3's `VData` with an INT index into the module's type table. That is why `Plugins.Fn` takes
  * the `Module` — see there.
  *
  * A NAME IS REGISTERED ONLY IF BOTH LANES CAN ANSWER IT, and that rule is not decoration. Lowering
  * refuses an unimplemented host function uniformly today, so the executor and the bridge agree by
  * refusing together. Letting one lane answer alone is precisely what was tried before and is
  * recorded in `Lower.scala`: v2's plugins answered on the bridge, nothing answered on the
  * executor, ELEVEN corpus cases diverged and FIVE PROGRAMS PRINTED THE WRONG THING. Registration
  * here is an assertion that the bridge answers too — which it does, because the bridge runs the
  * SAME providers through v2's own registry. */
object V2Fleet:

  /** Install the fleet into both registries. Idempotent: `ServiceLoader` is asked once and the two
    * `register` calls overwrite, so a second call cannot produce a different program. */
  def install(): Unit =
    if !installed then
      installed = true
      ssc.plugin.NativePluginHost.loadAll()
      // `snapshot().handlers` is the enumeration, and using it is what keeps this change out of
      // `v2/src` entirely: the registry already exposes its table as a public map for batch
      // isolation, so no v2 edit is needed to list what the fleet registered.
      ssc.V2PluginRegistry.snapshot().handlers.foreach { (name, fn) =>
        // The tombstone filter belongs on BOTH tables, because json-plugin's `native` helper
        // registers a name into `handlers` AND `globalValues` in one call — so excluding it from
        // the globals alone left it reachable and `json-lookup` still died on the redirect.
        if !selfHosted(name) then Plugins.register(name, (m, args) => call(m, fn, args))
      }
      installGlobals()
      Plugins.registerMethods(methodOn)
      // JVM PACKAGES ARE THE SAME KIND OF DOOR and are installed beside the fleet, so a tree with
      // the fleet has both and a tree without has neither — one switch, one state.
      JvmInterop.install()
      // v2's own renderer, which knows what a provider's handle means — its `Show` has an arm for
      // `ForeignV(nmo: NamedMethodObj)` precisely so a boxed option prints as `None`. Failures
      // decline rather than propagate: a value this cannot convert is not a value whose printing
      // should fail.
      Plugins.registerShow((m, v) =>
        try Some(ssc.Show.show(toV2(m, v))) catch case _: Throwable => None)

  /** THE SECOND TABLE, and the one with the corpus cases behind it.
    *
    * `handlers` is what `register` fills and it is not where a plugin puts a FUNCTION THE PROGRAM
    * CALLS BY NAME. `suspend` — six corpus cases — is `registerGlobal("suspend", 1)` in
    * generator-plugin, and the host wraps that into `Value.ClosV(emptyEnv, arity, env =>
    * Done(fn(env.toList)))` before storing it in `globalValues`. So the value in the table is a v2
    * CLOSURE whose body is the plugin's function, and calling it needs no general v2-closure
    * applier: the env is empty by construction, so applying it is running its body on the argument
    * array and taking the `Done`.
    *
    * ONLY `ClosV` IS BRIDGED, and anything else in that table is left alone rather than guessed at.
    * `registerValue` can store a plain datum under a global name, and a datum is not callable; a
    * bridge that assumed every global was a function would turn a wrong shape into a class-cast
    * crash at run time instead of a refusal at compile time. */
  private def installGlobals(): Unit =
    ssc.V2PluginRegistry.allGlobalNames().foreach { name =>
      if !selfHosted(name) then
        ssc.V2PluginRegistry.lookupGlobal(name) match
          case Some(c: ssc.Value.ClosV) =>
            Plugins.register(name, (m, args) => call(m, as => applyClos(c, as), args))
          // A PLAIN DATUM ANSWERS A ZERO-ARGUMENT CALL, and that is not a relaxation of the rule
          // above but the same rule read from the caller's side. `std/os.ssc` declares
          // `extern def cwd: String`; host-plugin provides it with
          // `registerValue("cwd", StrV(user.dir))`. Whether the provider computed the value once at
          // install or would compute it per call is not observable through that declaration, so a
          // nullary extern and a constant are the same thing to the program — and `std-os-doc-import`
          // was a CRASH for want of this.
          //
          // ARGUMENTS ARE STILL REFUSED. The reason the `ClosV` test was there in the first place
          // stands: a datum is not a function, and passing one arguments is a program error that
          // must say so rather than become a class cast inside the bridge.
          case Some(v) =>
            Plugins.register(name, (m, args) =>
              if args.isEmpty then toV3(m, v)
              else throw ssc3.ExecError(
                "the host value '" + name + "' is not a function — it takes no arguments"))
          case None => ()
    }

  /** A GLOBAL WHOSE ONLY BEHAVIOUR IS TO REDIRECT THE CALLER IS NOT AN IMPLEMENTATION.
    *
    * json-plugin registers `jsonParse` with a body that throws
    * `jsonParse is self-hosted; import std/json.ssc` — a TOMBSTONE, there so v2 can answer "import
    * the module" instead of "unknown name". Bridging it turned `json-lookup` from an honest refusal
    * into a stack trace: measured, +1 DIFF against a control on the same tree, which is the exact
    * trade the DIFF floor exists to refuse.
    *
    * ONE NAME, AND THE CENSUS IS COMPLETE RATHER THAN A GUESS: `grep -rn 'self-hosted'` across every
    * plugin's sources finds exactly one global of this shape in the wired fleet. Re-run that grep
    * when a module is added to `v3/plugin-classpath.sh`; a tombstone cannot be detected by calling
    * it, because calling it is the failure. */
  private def selfHosted(name: String): Boolean = name == "jsonParse"

  /** THE SAME GLOBALS, MADE ANSWERABLE ON THE BRIDGE — called from `V2Cli`, in v2's process.
    *
    * Lowering is SHARED, so once it emits `(prim "coroutineCreate" …)` for a globals-table name both
    * lanes get that IR. v2 resolves such a name as a Global, not a Prim, so `Prims.resolve` looked
    * only in `handlers`, missed, and the bridge died with `unimplemented primitive:
    * coroutineCreate` while the executor answered. Measured, not feared: that is exactly the
    * one-lane-answers divergence this file's header describes, reproduced by my own change before it
    * landed.
    *
    * Copying the globals into `handlers` in the bridge's process closes it, and it belongs HERE
    * rather than in `v2/src` for the same reason as everything else in this file: v2 does not need
    * to know that v3 lowers a global as a prim. */
  def installGlobalsAsHandlers(): Unit =
    ssc.V2PluginRegistry.allGlobalNames().foreach { name =>
      if !selfHosted(name) then
        ssc.V2PluginRegistry.lookupGlobal(name) match
          case Some(c: ssc.Value.ClosV) =>
            ssc.V2PluginRegistry.register(name, args => applyClos(c, args))
          // The same on the bridge, so the two lanes answer a nullary extern alike — lowering is
          // shared and emits one `Prim` for both.
          case Some(v) =>
            ssc.V2PluginRegistry.register(name, args =>
              if args.isEmpty then v
              else throw new IllegalArgumentException(
                "the host value '" + name + "' is not a function"))
          case None => ()
    }

  /** Apply a v2 closure whose environment is empty. The body answers a trampoline step; a plugin
    * global's body is `Done(...)` by construction, and a `More` here would mean the table held a
    * user closure rather than a registered global — refused by name instead of driven, because
    * driving v2's trampoline from here would be v3 running v2's evaluator. */
  private def applyClos(c: ssc.Value.ClosV, args: List[ssc.Value]): ssc.Value =
    c.code(args.toArray) match
      case ssc.Done(v) => v
      case _ => throw ssc3.ExecError(
        "a plugin global did not answer immediately, which this bridge does not drive")

  /** A METHOD ON A HOST-OWNED RECEIVER, resolved through v2's OWN tables rather than a mirror of
    * them here.
    *
    * TWO MECHANISMS, because v2 has two. A foreign HANDLE — `<handle GeneratorValue>` — carries its
    * methods on the object itself through `NamedMethodObj`, which is the same interface
    * `NativePluginHost.invoke` consults for a callback. Host DATA carries them in the registry's
    * `(tag, method)` table, which is what `registerTaggedMethod` fills and what `r.exitCode` on a
    * `ProcessResult` needs.
    *
    * `None` FOR ANYTHING UNCLAIMED, so the executor's own refusal is what a program sees when no
    * provider owns the receiver. Wrapping a miss in a host error would replace a v3 diagnostic that
    * names the lane to retry on with one that names a Java class. */
  private def methodOn(m: Module, recv: Value, name: String, args: List[Value]): Option[Value] =
    val v2recv = toV2(m, recv)
    // EACH ARM BINDS ITS OWN ARGUMENT LIST, and that is not tidiness. v2's TABLES take the receiver
    // as the first argument (`fn(value :: args)` is how its own dispatch calls them) while a
    // handle's MEMBER is an ordinary closure that takes only what the caller wrote. A single
    // `v2recv :: as` for every path gave `Generator.foreach(callback)` two arguments where its
    // `case fn :: Nil` expects one, so it fell into its own error arm and the program printed the
    // provider's complaint instead of doing the work.
    val fn: Option[List[ssc.Value] => ssc.Value] = v2recv match
      // A function the host returned, called back. `apply` is the name `Exec.hostApply` uses.
      case c: ssc.Value.ClosV if name == "apply" => Some(as => applyClosDriven(c, as))
      // The exception shape this adapter itself builds, when the program has no declared
      // `RuntimeException` to carry it — `message` is the prelude's field and `getMessage` its
      // accessor, so both spellings answer the same field.
      case ssc.Value.DataV("RuntimeException", fs)
          if fs.length == 1 && (name == "getMessage" || name == "message") =>
        Some(_ => fs(0))
      // A FIELD IS A METHOD WITH NO ARGUMENTS on this side, and it is a table of its own: a provider
      // that answers a record declares its field names with `registerFieldNames`. Read BY ARITY,
      // because the registry's own comment says the flat map is last-registered-wins.
      case ssc.Value.DataV(tag, fs) if args.isEmpty &&
          ssc.V2PluginRegistry.lookupFieldNames(tag, fs.length).exists(_.contains(name)) =>
        val ix = ssc.V2PluginRegistry.lookupFieldNames(tag, fs.length).get.indexOf(name)
        Some(_ => fs(ix))
      // CALLING host data — v2's `taggedApply`, keyed by the tag alone, and called WITH the receiver
      // exactly as `Runtime`'s own `app` does.
      case ssc.Value.DataV(tag, _) if name == "apply" =>
        ssc.V2PluginRegistry.lookupTaggedApply(tag).map(f => as => f(v2recv :: as))
      case ssc.Value.DataV(tag, _) =>
        ssc.V2PluginRegistry.lookupTaggedMethod(tag, name).map(f => as => f(v2recv :: as))
      // A HANDLE carries its methods on the object, through the same interface
      // `NativePluginHost.invoke` consults. The member is an ordinary closure: NO receiver.
      case ssc.Value.ForeignV(o: ssc.Value.NamedMethodObj) =>
        o.getField(name) match
          case Some(c: ssc.Value.ClosV) => Some(as => applyClosDriven(c, as))
          case _                        => None
      case _ => None
    // A JVM-BACKED DATUM'S FIELD, when v2's tables do not claim the name. The two providers are
    // asked in this order because v2's is the one with registered semantics; the JVM side only
    // knows what `Product` told it.
    fn.map(f => call(m, f, args)).orElse(
      if args.isEmpty then JvmBridge.fieldOn(recv, name) else None)

  /** Like `applyClos`, but for a closure that is NOT a plugin global and may take more than one
    * trampoline step — a `NamedMethodObj`'s member is ordinary v2 code. `Runtime.run` is v2's own
    * driver, so this is v2 running v2 rather than v3 imitating it. */
  private def applyClosDriven(c: ssc.Value.ClosV, args: List[ssc.Value]): ssc.Value =
    ssc.Runtime.run(c.code, if args.isEmpty then c.env else ssc.Runtime.extend(c.env, args.toArray))

  private var installed = false

  /** Invoke a provider and TRANSLATE WHAT IT THROWS, because an exception is part of a host
    * function's contract and not an accident.
    *
    * `std/fs.ssc`'s failure cases are the whole point of `std-fs-failure-raises`: `listDir` on a
    * missing directory is SUPPOSED to raise, and the program catches it. On the bridge that works,
    * because v2 raises `SscThrow` and its own `try` catches it. Here the plugin's Java exception
    * escaped the executor entirely and surfaced as `cannot read '<the .ssc>': NoSuchFileException`
    * — a message about the SOURCE FILE, from a handler that assumes anything thrown came from
    * reading it. The program printed two lines and stopped while the bridge printed seven.
    *
    * So: v2's own `SscThrow` carries a value and maps straight across; anything else is a host
    * failure and becomes a catchable throw carrying its message, which is the shape v3's `try`
    * expects. */
  private def call(m: Module, fn: List[ssc.Value] => ssc.Value, args: List[Value]): Value =
    try toV3(m, fn(args.map(a => toV2(m, a))))
    catch
      case t: ssc.SscThrow  => throw ssc3.ExecThrow(toV3(m, t.value), String.valueOf(t.value))
      case e: ssc3.ExecThrow => throw e
      // AN INTERNAL REFUSAL IS NOT A PROGRAM EXCEPTION and must not be dressed as one. `ExecError`
      // is this bridge saying it cannot convert a shape; turning it into a catchable `ExecThrow`
      // let it surface as a JVM stack trace from a `try` the program never wrote, and would let a
      // program CATCH a limitation of the adapter as though it were a failure of the host call.
      case e: ssc3.ExecError => throw e
      // AN EXCEPTION THE PROGRAM ITSELF RAISED KEEPS ITS OWN IDENTITY. A host function that takes a
      // callback runs v3 code inside it — `generator-callback-user-throw` throws from the callback —
      // and the provider catches that and rethrows with a message of its own
      // (`Generator.foreach(callback)`). Re-labelling the program's exception with the host's is a
      // wrong answer, not a lost detail: the program prints the message it threw. So the cause chain
      // is walked and the original is rethrown unchanged if it is in there.
      case e: Throwable if cause(e).isDefined => throw cause(e).get
      case e: Throwable     =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        // THROWN AS THE SHAPE A PROGRAM CATCHES, not as a bare string. `generator-callback-user-throw`
        // catches a host failure and asks it for `getMessage`; with a `VStr` there is nothing to ask,
        // and the program died with `method 'getMessage' on <the text>`. Built through `toV3` rather
        // than as a literal so it picks up the program's OWN `RuntimeException` when the prelude is
        // in scope — then `getMessage` is the prelude's method and nothing here is involved.
        throw ssc3.ExecThrow(
          toV3(m, ssc.Value.DataV("RuntimeException", collection.immutable.ArraySeq(ssc.Value.StrV(msg)))),
          msg)

  /** The program's own throw, if the host wrapped one. Walks the JVM cause chain and stops at the
    * first exception that came FROM v3 — bounded by the chain's length, and `null`-safe because a
    * cause chain can end in a self-reference on some JDK exceptions. */
  private def cause(t: Throwable): Option[ssc3.ExecThrow] =
    var cur: Throwable = t
    var out: Option[ssc3.ExecThrow] = None
    var guard = 0
    while cur != null && out.isEmpty && guard < 32 do
      cur match
        case x: ssc3.ExecThrow => out = Some(x)
        case _ => ()
      val nxt = cur.getCause
      cur = if nxt eq cur then null else nxt
      guard += 1
    out

  /** v3 -> v2. Total on the shapes a program can pass to a host function.
    *
    * `VChar` becomes v2's `CharV`, not a bare int, because v2's own model is `CharV extends IntV`
    * and a plugin that pattern-matches a Char would otherwise see a number. */
  private def toV2(m: Module, v: Value): ssc.Value = v match
    case Value.VUnit      => ssc.Value.UnitV
    case Value.VBool(b)   => ssc.Value.BoolV(b)
    case Value.VInt(n)    => ssc.Value.IntV(n)
    case Value.VChar(c)   => ssc.Value.CharV(c)
    case Value.VFloat(d)  => ssc.Value.FloatV(d)
    case Value.VStr(s)    => ssc.Value.StrV(s)
    case Value.VBytes(b)  => ssc.Value.BytesV(b.toVector)
    // A CONSTRUCTOR GOES BOTH WAYS, and this direction needs the module for the mirror-image
    // reason the other does: v3 numbers the tag and v2 names it, so the type table is what turns an
    // index back into `Some` or `Cons`. Leaving it out refused `std-process-import` with "a host
    // function was passed VData" while the BRIDGE answered — a lane divergence produced by the very
    // adapter that exists to prevent one.
    case Value.VData(t, fs) =>
      if t < 0 || t >= m.types.length then throw ssc3.ExecError(
        "a host function was passed a constructor this program does not declare")
      ssc.Value.DataV(m.types(t).name, fs.map(f => toV2(m, f)).toIndexedSeq)
    // MAP, SET and ARRAY. Mechanical, and each one was found by a probe rather than by reading the
    // enum: the refusal below names the shape it met, so the surface was discovered one failing
    // program at a time — `VData` first, then `VMap`.
    //
    // v2 has NO array case. `arr.new` answers `ForeignV(ArrayBuffer[Value])`, so what crosses is the
    // handle itself, and a plugin that mutates it mutates the same buffer the program holds — which
    // is what an array IS on that side.
    case Value.VMap(es)   =>
      ssc.Value.MapV.from(es.map((k, v) => (toV2(m, k), toV2(m, v))))
    case Value.VSet(es)   => ssc.Value.SetV.from(es.map(e => toV2(m, e)))
    case Value.VArr(items) =>
      ssc.Value.ForeignV(collection.mutable.ArrayBuffer.from(items.map(i => toV2(m, i))))
    // A HANDLE GOES BACK EXACTLY AS IT CAME. It was never anything but a v2 `ForeignV` — v3 only
    // carried it — so this is an unwrap, not a conversion, and the plugin receives the same object
    // it handed out. Reference identity is the whole contract: `coroutineResume(h, x)` resumes THAT
    // coroutine, not an equal one.
    // A HANDLE THAT IS ITSELF A v2 VALUE goes back AS that value, not wrapped again. A closure
    // returned by a provider arrives here as `ClosV`; re-wrapping it in `ForeignV` would hide it
    // from every v2 site that pattern-matches a function, including `invoke`.
    case Value.VForeign(h: ssc.Value, _) => h
    // AN EXACT DECIMAL, both directions, by its canonical text — the representation both runtimes
    // already agree on. `ssc.Value.DecimalV.apply` re-canonicalises, which is a no-op on text that
    // came from there and a correction on text that did not.
    case Value.VDec(t) => ssc.Value.DecimalV(t)
    case Value.VForeign(h, _) => ssc.Value.ForeignV(h)
    // Back the way it came, by NAME — the tag is what v2 uses, and it is the tag this value was
    // built from, so a handle round-trips through v3 without the type table ever being consulted.
    case Value.VHostData(tag, fs) =>
      ssc.Value.DataV(tag, fs.map(f => toV2(m, f)).toIndexedSeq)
    // A CLOSURE CROSSES AS A v2 CLOSURE WHOSE BODY RE-ENTERS v3. This was refused until now, and the
    // refusal is what actually blocked coroutines: `coroutineCreate(body)` takes a function, and the
    // plugin calls it later through `context.invoke`, which accepts a `ClosV` and drives its `code`.
    // So the wrapper is that `code` — it converts the arguments back, runs v3's own `applyValue`,
    // and converts the answer.
    //
    // ARITY -1 ON PURPOSE. `invoke` checks `clos.arity != args.length` only when the arity is
    // non-negative, and v3's `VClos` carries captured values rather than a remaining arity, so there
    // is no honest number to put here. -1 says "do not check" instead of inventing one that would be
    // wrong for a partially-applied closure.
    //
    // THE HOST MAY CALL THIS ON ANOTHER THREAD — generator-plugin runs a coroutine body on a virtual
    // thread — and that is safe here only because the coroutine protocol hands control over rather
    // than sharing it: exactly one of the two threads is inside the executor at any moment, and the
    // plugin's own lock is what publishes the state between them.
    case Value.VClos(_, _) =>
      ssc.Value.ClosV(ssc.Runtime.emptyEnv, -1,
        env => ssc.Done(toV2(m, ssc3.Exec.applyValue(m, v, env.toList.map(a => toV3(m, a))))))
    case other            => throw ssc3.ExecError(
      "a host function was passed " + other.getClass.getSimpleName +
      ", which this plugin bridge does not convert yet")

  /** v2 -> v3. A `DataV` needs the module: v2 names its constructor, v3 numbers it.
    *
    * AN UNKNOWN CONSTRUCTOR IS REFUSED RATHER THAN GUESSED. A plugin can legitimately return a tag
    * this program never declared — `Some` in a program that imports no `Option` — and inventing an
    * index for it would hand the executor a value whose tag means something else entirely. */
  private def toV3(m: Module, v: ssc.Value): Value = v match
    case ssc.Value.UnitV        => Value.VUnit
    case ssc.Value.BoolV(b)     => Value.VBool(b)
    case c: ssc.Value.CharV     => Value.VChar(c.n.toChar)
    case i: ssc.Value.IntV      => Value.VInt(i.n)
    case ssc.Value.FloatV(d)    => Value.VFloat(d)
    case ssc.Value.StrV(s)      => Value.VStr(s)
    case ssc.Value.BytesV(b)    => Value.VBytes(b.toArray)
    case d: ssc.Value.DecimalV  => Value.VDec(d.text)
    case mp: ssc.Value.MapV =>
      Value.VMap(collection.mutable.ArrayBuffer.from(
        mp.entries.iterator.map((k, v) => (toV3(m, k), toV3(m, v)))))
    case st: ssc.Value.SetV => Value.VSet(st.elems.iterator.map(e => toV3(m, e)).toList)
    // A FOREIGN HANDLE is an array when it holds one — that is v2's only array representation — and
    // anything else foreign has no v3 counterpart and is refused by name rather than smuggled
    // through as an opaque value the executor could not act on.
    case ssc.Value.ForeignV(h: collection.mutable.ArrayBuffer[?]) =>
      Value.VArr(h.iterator.map(x => toV3(m, x.asInstanceOf[ssc.Value])).toArray)
    // ANY OTHER FOREIGN OBJECT IS A HANDLE, carried opaquely. This used to be a refusal, and the
    // refusal was the reason `coroutineCreate` could not answer: its `CoroutineState` has no v3
    // counterpart and needs none, because every operation on it is a call with the handle in
    // argument position. The tag is the JVM class's simple name and is for diagnostics only — it is
    // what makes a later refusal say `<handle CoroutineState>` instead of naming nothing.
    case ssc.Value.ForeignV(h) => Value.VForeign(h, h.getClass.getSimpleName)
    // A CLOSURE COMING BACK. It cannot be a `VClos` — that is an index into a module's function
    // table and this function has none — so it travels as a handle and `Exec.hostApply` calls it.
    case c: ssc.Value.ClosV => Value.VForeign(c, "function")
    case ssc.Value.DataV(tag, fs) =>
      val ix = m.types.indexWhere(t => t.name == tag)
      // NOT A REFUSAL ANY MORE. This threw `a host function returned the constructor 'Yielded',
      // which this program does not declare` — an unpositioned run-time failure, which the corpus
      // ranks CRASH, below the honest refusal it replaced. Measured on the EXECUTOR lane, which the
      // report does not read by default: CRASH 9 -> 15 against a control on the same tree, six
      // honest refusals turned into crashes by my own change. A tag the program never declared is
      // carried by name instead, and prints.
      if ix < 0 then Value.VHostData(tag, fs.map(f => toV3(m, f)).toArray)
      else Value.VData(ix, fs.map(f => toV3(m, f)).toArray)
    case other            => throw ssc3.ExecError(
      "a host function returned " + other.getClass.getSimpleName +
      ", which this plugin bridge does not convert yet")
