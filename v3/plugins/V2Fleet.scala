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
        Plugins.register(name, (m, args) => call(m, fn, args))
      }

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
      case e: Throwable     =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        throw ssc3.ExecThrow(Value.VStr(msg), msg)

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
    case mp: ssc.Value.MapV =>
      Value.VMap(collection.mutable.ArrayBuffer.from(
        mp.entries.iterator.map((k, v) => (toV3(m, k), toV3(m, v)))))
    case st: ssc.Value.SetV => Value.VSet(st.elems.iterator.map(e => toV3(m, e)).toList)
    // A FOREIGN HANDLE is an array when it holds one — that is v2's only array representation — and
    // anything else foreign has no v3 counterpart and is refused by name rather than smuggled
    // through as an opaque value the executor could not act on.
    case ssc.Value.ForeignV(h: collection.mutable.ArrayBuffer[?]) =>
      Value.VArr(h.iterator.map(x => toV3(m, x.asInstanceOf[ssc.Value])).toArray)
    case ssc.Value.DataV(tag, fs) =>
      val ix = m.types.indexWhere(t => t.name == tag)
      if ix < 0 then throw ssc3.ExecError(
        "a host function returned the constructor '" + tag + "', which this program does not declare")
      Value.VData(ix, fs.map(f => toV3(m, f)).toArray)
    case other            => throw ssc3.ExecError(
      "a host function returned " + other.getClass.getSimpleName +
      ", which this plugin bridge does not convert yet")
