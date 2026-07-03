package scalascript.interpreter

type Env = Map[String, Value]

/** A `Map[String, Value]` specialised for the interpreter's call-env hot
 *  path: a small "frame" of local bindings (params and self-ref) sitting
 *  on top of a `parent` map (closure captures). Three variants:
 *
 *  - `FrameMap1` — one slot, two direct fields (single allocation; the
 *    typical 1-arg lambda / 1-param function case).
 *  - `FrameMap2` — two slots, four direct fields (two-param case).
 *  - `FrameMapN` — three-or-more, parallel arrays.
 *
 *  Lookup is `name == slot ? value : parent.get(name)` — for one or two
 *  bindings this beats `HashMap.get`'s hash + bucket walk and avoids the
 *  HashMap2/3 allocation `closure.updated(name, value)` would do.
 *
 *  Mutation ops (`updated`, `removed`, `iterator`) flatten to an ordinary
 *  Map; they're rare in eval. */
sealed abstract class FrameMap
    extends scala.collection.immutable.AbstractMap[String, Value]:
  protected def parentMap: Map[String, Value]
  private[interpreter] def parent: Map[String, Value] = parentMap
  protected def flat: Map[String, Value]
  override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
    flat.updated(key, value)
  override def removed(key: String): Map[String, Value] =
    flat.removed(key)
  /** Append only the local slots (not the parent chain) to `b`, filtering out
   *  entries whose value matches the same key in `globals`.  Used by lambda
   *  closure construction to avoid iterating the entire globals map. */
  private[interpreter] def appendLocalTo(
    b: scala.collection.mutable.Growable[(String, Value)],
    globals: scala.collection.mutable.Map[String, Value]
  ): Unit
  /** HashMap overload: uses `b(k) = v` (no Tuple2) instead of `b += (k -> v)`. */
  private[interpreter] def appendLocalTo(
    b: scala.collection.mutable.HashMap[String, Value],
    globals: scala.collection.mutable.HashMap[String, Value]
  ): Unit

final class FrameMap1(n1: String, v1: Value, parent: Map[String, Value])
    extends FrameMap:
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    if key == n1 then Some(v1) else parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    if key == n1 then v1 else parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    key == n1 || parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    Iterator.single(n1 -> v1) ++ parent.iterator.filterNot(_._1 == n1)
  override protected def flat: Map[String, Value] =
    parent.updated(n1, v1)
  override def foreachEntry[U](f2: (String, Value) => U): Unit =
    parent.foreachEntry(f2)
    f2(n1, v1)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b += (n1 -> v1)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b(n1) = v1

/** Mutable single-slot frame for hot iteration callbacks (e.g. `xs.foreach(f)`).
 *  Identical to `FrameMap1` except `v1` is reassignable, so one instance is reused
 *  across every element of a sequence instead of allocating a `FrameMap1` per call.
 *
 *  SAFE ONLY while the callback body returns `Pure`: a `Pure` value can retain the
 *  env solely through a `FunV.closure`, and closures are always built as a fresh
 *  by-value snapshot (never the live frame — see `Term.Function` eval), so mutating
 *  `v1` for the next element cannot corrupt an already-produced value. If the body
 *  yields a non-`Pure` `Computation` (which may close over this frame), the caller
 *  must stop reusing the frame and leave `v1` untouched. */
final class ReusableFrame1(n1: String, parent: Map[String, Value]) extends FrameMap:
  private[interpreter] var v1: Value = null
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    if key == n1 then Some(v1) else parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    if key == n1 then v1 else parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    key == n1 || parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    Iterator.single(n1 -> v1) ++ parent.iterator.filterNot(_._1 == n1)
  override protected def flat: Map[String, Value] =
    parent.updated(n1, v1)
  override def foreachEntry[U](f2: (String, Value) => U): Unit =
    parent.foreachEntry(f2)
    f2(n1, v1)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b += (n1 -> v1)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b(n1) = v1

/** Mutable two-slot frame for hot fold callbacks (e.g. `xs.foldLeft(z)(f)`).
 *  Two-slot analogue of `ReusableFrame1`: one instance is reused across every
 *  element of a fold instead of allocating a `FrameMap2` per call. Same `Pure`-only
 *  safety contract — a non-`Pure` body result must stop reuse and leave the slots
 *  untouched (see `ReusableFrame1` for the full argument). */
final class ReusableFrame2(n1: String, n2: String, parent: Map[String, Value]) extends FrameMap:
  private[interpreter] var v1: Value = null
  private[interpreter] var v2: Value = null
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    if key == n1 then Some(v1)
    else if key == n2 then Some(v2)
    else parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    if key == n1 then v1
    else if key == n2 then v2
    else parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    key == n1 || key == n2 || parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    Iterator(n1 -> v1, n2 -> v2) ++ parent.iterator.filterNot { case (k, _) =>
      k == n1 || k == n2
    }
  override protected def flat: Map[String, Value] =
    parent.updated(n1, v1).updated(n2, v2)
  override def foreachEntry[U](f2: (String, Value) => U): Unit =
    parent.foreachEntry(f2)
    f2(n1, v1); f2(n2, v2)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b += (n1 -> v1)
    if globals.getOrElse(n2, null) != v2 then b += (n2 -> v2)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b(n1) = v1
    if globals.getOrElse(n2, null) != v2 then b(n2) = v2

final class FrameMap2(
  n1: String, v1: Value,
  n2: String, v2: Value,
  parent: Map[String, Value]
) extends FrameMap:
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    if key == n1 then Some(v1)
    else if key == n2 then Some(v2)
    else parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    if key == n1 then v1
    else if key == n2 then v2
    else parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    key == n1 || key == n2 || parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    Iterator(n1 -> v1, n2 -> v2) ++ parent.iterator.filterNot { case (k, _) =>
      k == n1 || k == n2
    }
  override protected def flat: Map[String, Value] =
    parent.updated(n1, v1).updated(n2, v2)
  override def foreachEntry[U](f2: (String, Value) => U): Unit =
    parent.foreachEntry(f2)
    f2(n1, v1); f2(n2, v2)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b += (n1 -> v1)
    if globals.getOrElse(n2, null) != v2 then b += (n2 -> v2)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b(n1) = v1
    if globals.getOrElse(n2, null) != v2 then b(n2) = v2

final class FrameMapN(
  slots: Array[String],
  vals:  Array[Value],
  parent: Map[String, Value]
) extends FrameMap:
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    var i = 0
    while i < slots.length do
      if slots(i) == key then return Some(vals(i))
      i += 1
    parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    var i = 0
    while i < slots.length do
      if slots(i) == key then return vals(i)
      i += 1
    parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    var i = 0
    while i < slots.length do
      if slots(i) == key then return true
      i += 1
    parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    val localKeys = slots.toSet
    slots.iterator.zip(vals.iterator) ++
      parent.iterator.filterNot { case (k, _) => localKeys.contains(k) }
  override protected def flat: Map[String, Value] =
    val b = scala.collection.mutable.HashMap.empty[String, Value]
    parent.foreachEntry { (k, v) => b(k) = v }
    var i = 0
    while i < slots.length do
      b(slots(i)) = vals(i)
      i += 1
    b.toMap
  override def foreachEntry[U](f2: (String, Value) => U): Unit =
    parent.foreachEntry(f2)
    var i = 0
    while i < slots.length do
      f2(slots(i), vals(i))
      i += 1
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    var i = 0
    while i < slots.length do
      if globals.getOrElse(slots(i), null) != vals(i) then b += (slots(i) -> vals(i))
      i += 1
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    var i = 0
    while i < slots.length do
      if globals.getOrElse(slots(i), null) != vals(i) then b(slots(i)) = vals(i)
      i += 1

object FrameMap:
  def one(name: String, value: Value, parent: Map[String, Value]): FrameMap =
    new FrameMap1(name, value, parent)
  def two(n1: String, v1: Value, n2: String, v2: Value, parent: Map[String, Value]): FrameMap =
    new FrameMap2(n1, v1, n2, v2, parent)
  def of(names: Array[String], vals: Array[Value], parent: Map[String, Value]): FrameMap =
    new FrameMapN(names, vals, parent)
  /** Overlay `fields` on top of `parent` without allocating a merged HashMap.
   *  Used by the typeMethods dispatch path to inject instance fields into a
   *  method's closure without calling `fn.closure ++ fields`. */
  def fromMap(fields: Map[String, Value], parent: Map[String, Value]): Map[String, Value] =
    fields.size match
      case 0 => parent
      case 1 =>
        var k0: String = null
        var v0: Value = null
        fields.foreachEntry { (k, v) => k0 = k; v0 = v }
        new FrameMap1(k0, v0, parent)
      case 2 =>
        var k0: String = null
        var v0: Value = null
        var k1: String = null
        var v1: Value = null
        fields.foreachEntry { (k, v) =>
          if k0 == null then { k0 = k; v0 = v }
          else { k1 = k; v1 = v }
        }
        new FrameMap2(k0, v0, k1, v1, parent)
      case _ =>
        val keys = new Array[String](fields.size)
        val vals = new Array[Value](fields.size)
        var i = 0
        fields.foreachEntry { (k, v) => keys(i) = k; vals(i) = v; i += 1 }
        new FrameMapN(keys, vals, parent)

  /** Build a frame from parallel name/value arrays (as stored in `InstanceV.fieldNames` /
   *  `InstanceV.fieldsArr`).  Avoids iterating a Map when the caller already has arrays. */
  def fromArrays(names: Array[String], vals: Array[Value], parent: Map[String, Value]): Map[String, Value] =
    names.length match
      case 0 => parent
      case 1 => new FrameMap1(names(0), vals(0), parent)
      case 2 => new FrameMap2(names(0), vals(0), names(1), vals(1), parent)
      case _ => new FrameMapN(names, vals, parent)

  /** Like fromMap but also inlines an extra (selfName, selfRef) slot so the
   *  self-reference for a named class method is available without an additional
   *  Map.updated call on the parent (which for MutableEnvView is a full copy). */
  def fromMapWithSelf(
    fields: Map[String, Value], selfName: String, selfRef: Value,
    parent: Map[String, Value]
  ): Map[String, Value] =
    fields.size match
      case 0 =>
        new FrameMap1(selfName, selfRef, parent)
      case 1 =>
        var k0: String = null
        var v0: Value = null
        fields.foreachEntry { (k, v) => k0 = k; v0 = v }
        new FrameMap2(k0, v0, selfName, selfRef, parent)
      case _ =>
        val n = fields.size + 1
        val keys = new Array[String](n)
        val vals = new Array[Value](n)
        var i = 0
        fields.foreachEntry { (k, v) => keys(i) = k; vals(i) = v; i += 1 }
        keys(n - 1) = selfName
        vals(n - 1) = selfRef
        new FrameMapN(keys, vals, parent)

/** Presents a `scala.collection.mutable.Map` as an immutable `Map[String, Value]`
 *  without copying it.  Used by `BlockRuntime.evalBlock` to avoid the
 *  `local.toMap` allocation on every statement.
 *
 *  Mutation ops (`updated`, `removed`) flatten to a copied Map — they are
 *  rare in the eval path (only when a `val/var` binding is used as a key). */
final class MutableEnvView(m: scala.collection.mutable.Map[String, Value])
    extends scala.collection.immutable.AbstractMap[String, Value]:
  private[interpreter] val underlying: scala.collection.mutable.Map[String, Value] = m
  override def get(key: String): Option[Value]     = m.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 = m.getOrElse(key, default)
  override def contains(key: String): Boolean      = m.contains(key)
  override def iterator: Iterator[(String, Value)] = m.iterator
  // Delegate to the backing map's foreachEntry. The default AbstractMap impl
  // routes through `iterator`, boxing a Tuple2 per entry; `mutable.HashMap`
  // overrides foreachEntry to walk its node table directly with no Tuple2. This
  // matters on the closure-capture path (a literal closure built inside a loop
  // walks its whole env every iteration to find genuine captures).
  override def foreachEntry[U](f: (String, Value) => U): Unit = m.foreachEntry(f)
  override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
    (m.toMap: Map[String, Value]).updated(key, value).asInstanceOf[Map[String, V1]]
  override def removed(key: String): Map[String, Value] = m.toMap.removed(key)

