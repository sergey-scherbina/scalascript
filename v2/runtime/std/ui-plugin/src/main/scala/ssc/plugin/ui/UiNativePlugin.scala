package ssc.plugin.ui

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.IdentityHashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import ssc.{Done, NativeUiSites, Runtime, Show, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Versioned portable NativeUi values plus the deterministic JVM/static lane. */
final class UiNativePlugin extends NativePlugin:
  def id: String = "55-ui"

  private final class SignalCell(
      val id: String,
      val scope: String,
      val kind: String,
      val declaredDefault: Value,
      val writable: Boolean):
    var current: Value = declaredDefault
    var dirty = false
    var dynamicRead: () => Value = () => current
    var afterWrite: Value => Unit = _ => ()

  private type OwnerPath = Vector[(String, String)]
  private final case class OwnerKey(rootId: String, path: OwnerPath)
  private final case class ScopeKey(rootId: String, scope: String)
  private final case class SignalKey(scope: ScopeKey, id: String)
  private val rootId = "root"
  private val signals = mutable.LinkedHashMap.empty[SignalKey, SignalCell]
  private val storage = mutable.LinkedHashMap.empty[String, String]
  private val scopes = mutable.ArrayBuffer("root")
  private val ownerScopes = mutable.LinkedHashMap.empty[OwnerKey, Set[ScopeKey]]
  private val provisionalScopes = mutable.ArrayBuffer.empty[mutable.LinkedHashSet[String]]
  private val componentOwners = IdentityHashMap[AnyRef, OwnerPath]()
  private val componentBindingCollectors = mutable.ArrayBuffer.empty[IdentityHashMap[AnyRef, java.lang.Boolean]]
  private val rootComponentOccurrences = mutable.LinkedHashMap.empty[(OwnerPath, String), Int]
  private val componentOccurrenceFrames = mutable.ArrayBuffer.empty[mutable.LinkedHashMap[(OwnerPath, String), Int]]
  private val siteOccurrences = mutable.LinkedHashMap.empty[(OwnerPath, String), Int]
  private var currentOwnerPath = Vector.empty[(String, String)]
  private var renderDepth = 0
  private var appleContext = false
  private var registeredRoot: Option[(Value, Value, Value)] = None // tree, config, source

  private final case class RuntimeSnapshot(
      signalEntries: Vector[(SignalKey, SignalCell)],
      signalState: Vector[(SignalCell, Value, Boolean)],
      owners: Vector[(OwnerKey, Set[ScopeKey])],
      ownerPath: OwnerPath,
      scopeStack: Vector[String],
      provisional: Vector[mutable.LinkedHashSet[String]],
      componentBindings: Vector[(AnyRef, OwnerPath)])

  private def snapshotRuntime(): RuntimeSnapshot =
    RuntimeSnapshot(
      signals.toVector,
      signals.valuesIterator.map(cell => (cell, cell.current, cell.dirty)).toVector,
      ownerScopes.toVector,
      currentOwnerPath,
      scopes.toVector,
      provisionalScopes.toVector,
      componentOwners.entrySet().asScala.iterator.map(entry => entry.getKey -> entry.getValue).toVector)

  private def restoreRuntime(snapshot: RuntimeSnapshot): Unit =
    signals.clear()
    signals ++= snapshot.signalEntries
    snapshot.signalState.foreach { case (cell, current, dirty) =>
      cell.current = current
      cell.dirty = dirty
    }
    ownerScopes.clear()
    ownerScopes ++= snapshot.owners
    currentOwnerPath = snapshot.ownerPath
    provisionalScopes.clear()
    provisionalScopes ++= snapshot.provisional
    componentOwners.clear()
    snapshot.componentBindings.foreach { case (value, owner) => componentOwners.put(value, owner) }
    scopes.clear()
    scopes ++= snapshot.scopeStack

  private def disposeUnownedScopes(): Unit =
    val referenced = ownerScopes.valuesIterator.flatten.toSet + ScopeKey(rootId, "root")
    signals.keysIterator.filterNot(key => referenced(key.scope)).toList.foreach(signals.remove)

  private def resetEvaluationState(): Unit =
    appleContext = false
    registeredRoot = None
    signals.clear()
    ownerScopes.clear()
    provisionalScopes.clear()
    componentOwners.clear()
    rootComponentOccurrences.clear()
    componentOccurrenceFrames.clear()
    componentBindingCollectors.clear()
    siteOccurrences.clear()
    currentOwnerPath = Vector.empty
    renderDepth = 0
    scopes.clear()
    scopes += "root"

  private def pruneComponentBindings(
      prefix: OwnerPath,
      retained: IdentityHashMap[AnyRef, java.lang.Boolean] = null): Unit =
    componentOwners.entrySet().asScala.iterator.collect {
      case entry if entry.getValue.startsWith(prefix) &&
          (retained == null || !retained.containsKey(entry.getKey)) => entry.getKey
    }.toVector.foreach(componentOwners.remove)

  private[ui] def componentBindingCount: Int = componentOwners.size()

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def register(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    register(context, name)(fn)

  private def sourceRef(operation: String): Value =
    Value.DataV("NativeUiSourceRef", Vector(
      Value.StrV("<entry>"), Value.IntV(0), Value.IntV(0), Value.StrV(operation)))

  private def siteNative(context: NativePluginContext, name: String)(
      fn: (String, Value, List[Value]) => Value): Unit =
    register(context, name)(args => fn(s"manual:$name", sourceRef(name), args))
    register(context, NativeUiSites.internalName(name)) { args =>
      args match
        case Value.StrV(site) :: source :: rest => fn(site, source, rest)
        case _ => throw new RuntimeException(s"${NativeUiSites.internalName(name)} requires site and source metadata")
    }

  private def sourceNative(context: NativePluginContext, name: String)(
      fn: (Value, List[Value]) => Value): Unit =
    register(context, name)(args => fn(sourceRef(name), args))
    internalSourceNative(context, name)(fn)

  private def internalSourceNative(context: NativePluginContext, name: String)(
      fn: (Value, List[Value]) => Value): Unit =
    register(context, NativeUiSites.internalName(name)) { args =>
      args match
        case source :: rest => fn(source, rest)
        case _ => throw new RuntimeException(s"${NativeUiSites.internalName(name)} requires source metadata")
    }

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def integer(args: List[Value], index: Int, operation: String): Long = args.lift(index) match
    case Some(Value.IntV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be Int")

  private def unlist(value: Value, operation: String): List[Value] =
    val out = mutable.ListBuffer.empty[Value]
    var current = value
    var done = false
    while !done do current match
      case Value.DataV("Cons", Seq(head, tail)) => out += head; current = tail
      case Value.DataV("Nil", _) => done = true
      case _ => throw new RuntimeException(s"$operation expected a valid List")
    out.toList

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def metadata(entries: (String, Value)*): Value.MapV =
    Value.MapV.from(entries.map { case (key, value) => Value.StrV(key) -> value })

  private def signalFields(value: Value, operation: String): IndexedSeq[Value] = value match
    case Value.DataV("NativeUiSignal", fields) if fields.length == 6 => fields
    case _ => throw new RuntimeException(s"$operation argument must be NativeUiSignal")

  private def readSignal(value: Value, operation: String): Value =
    val read = signalFields(value, operation)(3).asInstanceOf[Value.ClosV]
    Runtime.run(read.code, read.env)

  private def writeSignal(value: Value, next: Value, operation: String): Unit =
    val write = signalFields(value, operation)(4).asInstanceOf[Value.ClosV]
    Runtime.run(write.code, Runtime.extend(write.env, Array(next)))

  private def signalId(value: Value, operation: String): String = signalFields(value, operation)(0) match
    case Value.StrV(id) => id
    case _ => throw new RuntimeException(s"$operation signal id must be String")

  private def makeSignal(
      id: String,
      kind: String,
      declaredDefault: Value,
      signalMetadata: Value,
      writable: Boolean = true,
      configure: SignalCell => Unit = _ => ()): Value =
    val scope = scopes.last
    val portableDefault = NativeUiPortable.canonical(declaredDefault, s"NativeUiSignal[$id].default")
    val key = SignalKey(ScopeKey(rootId, scope), id)
    val cell = signals.get(key) match
      case Some(existing) =>
        if existing.kind != kind || !NativeUiPortable.portableEquals(existing.declaredDefault, portableDefault) then
          throw new RuntimeException(s"duplicate native UI signal '$id' in scope '$scope' has conflicting kind/default")
        existing
      case None =>
        val created = SignalCell(id, scope, kind, portableDefault, writable)
        configure(created)
        signals(key) = created
        created
    val read = closure(0)(_ => NativeUiPortable.canonical(cell.dynamicRead(), s"NativeUiSignal[$id].read"))
    val write = closure(1) {
      case next :: Nil if cell.writable =>
        val portable = NativeUiPortable.canonical(next, s"NativeUiSignal[$id].write")
        val firstSeedWrite = cell.kind == "seed" && !cell.dirty
        if firstSeedWrite || !NativeUiPortable.portableEquals(cell.current, portable) then
          cell.current = portable
          cell.dirty = true
          cell.afterWrite(portable)
        Value.UnitV
      case _ :: Nil => throw new RuntimeException(s"native UI signal '$id' is read-only")
      case _ => throw new RuntimeException(s"native UI signal '$id' write(value)")
    }
    Value.DataV("NativeUiSignal", Vector(
      Value.StrV(id), Value.StrV(scope), Value.StrV(kind), read, write,
      NativeUiPortable.canonical(signalMetadata, s"NativeUiSignal[$id].metadata")))

  private def event(kind: String, target: Value, payload: Value, meta: Value = metadata()): Value =
    Value.DataV("NativeUiEvent", Vector(
      Value.StrV(kind),
      NativeUiPortable.canonical(target, s"NativeUiEvent[$kind].target"),
      NativeUiPortable.canonical(payload, s"NativeUiEvent[$kind].payload"),
      NativeUiPortable.canonical(meta, s"NativeUiEvent[$kind].metadata")))

  private def success(kind: String, target: Value, payload: Value): Value =
    Value.DataV("NativeUiSuccessEffect", Vector(
      Value.StrV(kind),
      NativeUiPortable.canonical(target, s"NativeUiSuccessEffect[$kind].target"),
      NativeUiPortable.canonical(payload, s"NativeUiSuccessEffect[$kind].payload")))

  private def fetchRequest(method: String, url: Value, body: Value, headers: Value): Value =
    signalFields(headers, "fetch headers")
    Value.DataV("NativeUiFetchRequest", Vector(
      Value.StrV(method),
      NativeUiPortable.canonical(url, "NativeUiFetchRequest.urlSource"),
      NativeUiPortable.canonical(body, "NativeUiFetchRequest.bodySource"),
      NativeUiPortable.canonical(headers, "NativeUiFetchRequest.headersSource")))

  private def fetchAction(
      site: String,
      method: String,
      url: Value,
      body: Value,
      effects: Value,
      headers: Value,
      capture: Value = Value.UnitV,
      clear: Value = Value.UnitV): Value =
    val status = NativeUiPortable.stringMap(
      metadata("clearTarget" -> clear), s"NativeUiFetchAction[$site].status")
    Value.DataV("NativeUiFetchAction", Vector(
      Value.StrV(site), fetchRequest(method, url, body, headers),
      NativeUiPortable.canonical(effects, s"NativeUiFetchAction[$site].onSuccess"),
      NativeUiPortable.canonical(capture, s"NativeUiFetchAction[$site].captureTarget"), status))

  private def mobileCss(value: String): Boolean =
    value.matches("@media\\(max-width:[0-9]+px\\)\\{body,p,label,span\\{font-size:[0-9]+px!important\\}h1\\{font-size:[0-9]+px!important\\}h2\\{font-size:[0-9]+px!important\\}h3\\{font-size:[0-9]+px!important\\}h4,h5,h6\\{font-size:[0-9]+px!important\\}button\\{font-size:[0-9]+px!important;padding:[0-9]+px [0-9]+px!important;border-radius:[0-9]+px!important\\}input\\[type=text\\],input\\[type=email\\],input\\[type=password\\]\\{font-size:[0-9]+px!important;padding:[0-9]+px!important;border-radius:[0-9]+px!important\\}\\}")

  private def unsupported(feature: String, source: Value, detail: String): Value =
    Value.DataV("NativeUiUnsupported", Vector(
      Value.StrV(feature),
      NativeUiPortable.canonical(source, s"NativeUiUnsupported[$feature].sourceRef"),
      Value.StrV(detail)))

  private def registerRoot(tree: Value, config: Value, source: Value): Unit =
    registeredRoot match
      case Some((_, previous, previousSource)) =>
        val message = s"native UI program registered multiple roots: ${Show.show(previous)} at ${Show.show(previousSource)} and ${Show.show(config)} at ${Show.show(source)}"
        resetEvaluationState()
        throw new RuntimeException(message)
      case None => registeredRoot = Some((tree, config, source))

  private def scalar(value: Value): String = value match
    case Value.StrV(text) => text
    case Value.IntV(number) => number.toString
    case Value.BigV(number) => number.toString
    case Value.FloatV(number) => number.toString
    case Value.DecimalV(text) => text
    case Value.BoolV(boolean) => boolean.toString
    case Value.UnitV => ""
    case signal @ Value.DataV("NativeUiSignal", _) => scalar(readSignal(signal, "render signal"))
    case other => Show.show(other)

  private def escapeText(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
  private def escapeAttribute(text: String): String =
    escapeText(text).replace("\"", "&quot;").replace("'", "&#39;")
  private val validTag = "[A-Za-z][A-Za-z0-9:-]*".r
  private val voidTags = Set("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

  private def nextComponentOccurrence(scope: String): Int =
    val counters = componentOccurrenceFrames.lastOption.getOrElse(rootComponentOccurrences)
    val key = currentOwnerPath -> scope
    val occurrence = counters.getOrElse(key, 0)
    counters(key) = occurrence + 1
    occurrence

  private def nextSiteOccurrence(site: String): Int =
    val key = currentOwnerPath -> site
    val occurrence = siteOccurrences.getOrElse(key, 0)
    siteOccurrences(key) = occurrence + 1
    occurrence

  private def renderKeyed(
      site: String,
      occurrence: Int,
      itemsSignal: Value,
      keyClosure: Value.ClosV,
      renderClosure: Value.ClosV): String =
    val snapshot = snapshotRuntime()
    val parent = currentOwnerPath
    try
      val items = unlist(readSignal(itemsSignal, "NativeUiForKeyed items"), "NativeUiForKeyed items")
      val keyed = items.map { item =>
        Runtime.run(keyClosure.code, Runtime.extend(keyClosure.env, Array(item))) match
          case Value.StrV(key) => key -> item
          case _ => throw new RuntimeException("NativeUiForKeyed key must be String")
      }
      val seenKeys = mutable.LinkedHashSet.empty[String]
      keyed.foreach { case (key, _) =>
        if !seenKeys.add(key) then
          throw new RuntimeException(s"duplicate NativeUiForKeyed key '$key' at site $site")
      }

      val ownerSegment = s"for:$site#$occurrence"
      val rendered = keyed.map { case (key, item) =>
        val owner = parent :+ (ownerSegment -> key)
        val collected = mutable.LinkedHashSet.empty[String]
        val retainedBindings = IdentityHashMap[AnyRef, java.lang.Boolean]()
        currentOwnerPath = owner
        provisionalScopes += collected
        componentOccurrenceFrames += mutable.LinkedHashMap.empty
        componentBindingCollectors += retainedBindings
        val html =
          try render(Runtime.run(renderClosure.code, Runtime.extend(renderClosure.env, Array(item))))
          finally
            componentBindingCollectors.remove(componentBindingCollectors.length - 1)
            componentOccurrenceFrames.remove(componentOccurrenceFrames.length - 1)
            provisionalScopes.remove(provisionalScopes.length - 1)
            currentOwnerPath = parent
        ownerScopes(OwnerKey(rootId, owner)) = collected.iterator.map(ScopeKey(rootId, _)).toSet
        pruneComponentBindings(owner, retainedBindings)
        html
      }

      val liveKeys = keyed.iterator.map(_._1).toSet
      ownerScopes.keysIterator.filter { owner =>
        val path = owner.path
        path.length > parent.length && path.startsWith(parent) &&
          path(parent.length)._1 == ownerSegment && !liveKeys(path(parent.length)._2)
      }.toList.foreach(ownerScopes.remove)
      componentOwners.entrySet().asScala.iterator.collect {
        case entry
            if entry.getValue.length > parent.length && entry.getValue.startsWith(parent) &&
              entry.getValue.apply(parent.length)._1 == ownerSegment &&
              !liveKeys(entry.getValue.apply(parent.length)._2) => entry.getKey
      }.toVector.foreach(componentOwners.remove)
      disposeUnownedScopes()
      rendered.mkString
    catch
      case error: Throwable =>
        restoreRuntime(snapshot)
        throw error
    finally currentOwnerPath = parent

  private def render(value: Value): String =
    val topLevel = renderDepth == 0
    if topLevel then siteOccurrences.clear()
    renderDepth += 1
    val previousOwner = currentOwnerPath
    Option(componentOwners.get(value.asInstanceOf[AnyRef])).foreach(currentOwnerPath = _)
    try renderValue(value)
    finally
      currentOwnerPath = previousOwner
      renderDepth -= 1
      if topLevel then siteOccurrences.clear()

  private def renderValue(value: Value): String = value match
    case Value.DataV("NativeUiText", Seq(Value.StrV(text))) => escapeText(text)
    case Value.DataV("NativeUiSignalText", Seq(signal)) => escapeText(scalar(signal))
    case Value.DataV("NativeUiShow", Seq(condition, whenTrue, whenFalse)) =>
      readSignal(condition, "NativeUiShow") match
        case Value.BoolV(true) => render(whenTrue)
        case _ => render(whenFalse)
    case Value.DataV("NativeUiFragment", Seq(children)) => unlist(children, "fragment").map(render).mkString
    case Value.DataV("NativeUiTrustedHtml", Seq(_, Value.StrV(source))) => source
    case Value.DataV("NativeUiElement", Seq(_, Value.StrV(tag), attrs: Value.MapV, _, children)) =>
      if !validTag.matches(tag) then throw new RuntimeException(s"invalid native UI tag: $tag")
      val renderedAttrs = attrs.entries.iterator.collect {
        case (Value.StrV(key), Value.BoolV(false) | Value.UnitV) => None
        case (Value.StrV(key), Value.BoolV(true)) => Some(key -> key)
        case (Value.StrV(key), attrValue) => Some(key -> scalar(attrValue))
      }.flatten.toList.sortBy(_._1).map { case (key, attrValue) =>
        s" ${escapeAttribute(key)}=\"${escapeAttribute(attrValue)}\""
      }.mkString
      val open = s"<$tag$renderedAttrs>"
      if voidTags(tag.toLowerCase) then open
      else open + unlist(children, "element children").map(render).mkString + s"</$tag>"
    case Value.DataV("NativeUiForKeyed", Seq(Value.StrV(site), items, keyClosure: Value.ClosV, renderClosure: Value.ClosV)) =>
      renderKeyed(site, nextSiteOccurrence(site), items, keyClosure, renderClosure)
    case Value.DataV("NativeUiUnsupported", Seq(Value.StrV(feature), _, Value.StrV(detail))) =>
      throw new RuntimeException(s"unsupported native UI feature '$feature': $detail")
    case _ => throw new RuntimeException(s"native UI emit expected ABI-v1 View, got ${Show.show(value)}")

  def install(context: NativePluginContext): Unit =
    val fields = List(
      "NativeUiAbi" -> Vector("version", "root", "config"),
      "NativeUiRootConfig" -> Vector("operation", "outDir", "port", "extraCss"),
      "NativeUiSignal" -> Vector("id", "scope", "kind", "read", "write", "metadata"),
      "NativeUiText" -> Vector("text"),
      "NativeUiSignalText" -> Vector("signal"),
      "NativeUiShow" -> Vector("condition", "whenTrue", "whenFalse"),
      "NativeUiFragment" -> Vector("children"),
      "NativeUiElement" -> Vector("siteId", "tag", "attrs", "events", "children"),
      "NativeUiForKeyed" -> Vector("siteId", "items", "keyClosure", "renderClosure"),
      "NativeUiTrustedHtml" -> Vector("siteId", "source"),
      "NativeUiDataTable" -> Vector("siteId", "source", "columns", "actions", "rowKeyPath"),
      "NativeUiUnsupported" -> Vector("feature", "sourceRef", "detail"),
      "NativeUiSourceRef" -> Vector("file", "line", "column", "operation"),
      "NativeUiEvent" -> Vector("kind", "target", "payload", "metadata"),
      "NativeUiFetchRequest" -> Vector("method", "urlSource", "bodySource", "headersSource"),
      "NativeUiFetchAction" -> Vector("siteId", "request", "onSuccess", "captureTarget", "status"),
      "NativeUiSuccessEffect" -> Vector("kind", "target", "payload"),
      "NativeUiFormBody" -> Vector("fields"),
      "NativeUiSignalMetaSeed" -> Vector("source"),
      "NativeUiSignalMetaComputed" -> Vector("compute"),
      "NativeUiSignalMetaEquality" -> Vector("source", "expected"),
      "NativeUiSignalMetaHash" -> Vector.empty,
      "NativeUiSignalMetaFetch" -> Vector("urlSource", "refresh", "headers", "phase", "error"),
      "NativeUiSignalMetaOnline" -> Vector.empty,
      "NativeUiSignalMetaPersisted" -> Vector("storageKey"),
      "NativeUiTableSource" -> Vector("kind", "value", "rowsPath"),
      "NativeUiColumn" -> Vector("kind", "title", "fieldPath", "align", "options"),
      "NativeUiRowAction" -> Vector("kind", "label", "request", "payload", "refresh", "options"),
      "NativeUiRowPayload" -> Vector("kind", "names"))
    fields.foreach(context.registerFields)

    context.registerTaggedApply("NativeUiSignal") { case signal :: Nil => readSignal(signal, "NativeUiSignal.apply"); case _ => throw new RuntimeException("NativeUiSignal.apply()") }
    List("apply", "get").foreach { name =>
      context.registerTaggedMethod("NativeUiSignal", name) { case signal :: Nil => readSignal(signal, s"NativeUiSignal.$name"); case _ => throw new RuntimeException(s"NativeUiSignal.$name()") }
    }
    context.registerTaggedMethod("NativeUiSignal", "set") { case signal :: value :: Nil => writeSignal(signal, value, "NativeUiSignal.set"); Value.UnitV; case _ => throw new RuntimeException("NativeUiSignal.set(value)") }
    context.registerTaggedMethod("NativeUiSignal", "id") {
      case signal :: Nil => Value.StrV(signalId(signal, "NativeUiSignal.id"))
      case _ => throw new RuntimeException("NativeUiSignal.id()")
    }
    context.registerTaggedMethod("NativeUiSignal", "update") {
      case signal :: (fn: Value.ClosV) :: Nil =>
        val next = Runtime.run(fn.code, Runtime.extend(fn.env, Array(readSignal(signal, "NativeUiSignal.update"))))
        writeSignal(signal, next, "NativeUiSignal.update"); Value.UnitV
      case _ => throw new RuntimeException("NativeUiSignal.update(fn)")
    }

    native(context, "signal") { args =>
      if args.length != 2 then throw new RuntimeException("signal(name, default)")
      makeSignal(text(args, 0, "signal"), "mutable", args(1), metadata())
    }
    native(context, "seedSignal") { args =>
      if args.length != 2 then throw new RuntimeException("seedSignal(name, source)")
      val source = args(1); signalFields(source, "seedSignal source")
      makeSignal(text(args, 0, "seedSignal"), "seed", readSignal(source, "seedSignal source"),
        Value.DataV("NativeUiSignalMetaSeed", Vector(source)), configure = cell => {
          cell.dynamicRead = () => if cell.dirty then cell.current else readSignal(source, "seedSignal source")
        })
    }
    siteNative(context, "computedSignal") { (site, _, args) => args match
      case (compute: Value.ClosV) :: Nil =>
        val initial = Runtime.run(compute.code, compute.env)
        makeSignal(s"__computed__$site", "computed", initial,
          Value.DataV("NativeUiSignalMetaComputed", Vector(compute)), writable = false,
          configure = cell => cell.dynamicRead = () => Runtime.run(compute.code, compute.env))
      case _ => throw new RuntimeException("computedSignal(callback)")
    }
    siteNative(context, "eqSignal") { (site, _, args) => args match
      case source :: expected :: Nil =>
        signalFields(source, "eqSignal source")
        makeSignal(s"__equality__$site", "equality", Value.BoolV(NativeUiPortable.portableEquals(readSignal(source, "eqSignal"), expected)),
          Value.DataV("NativeUiSignalMetaEquality", Vector(source, expected)), writable = false,
          configure = cell => cell.dynamicRead = () => Value.BoolV(NativeUiPortable.portableEquals(readSignal(source, "eqSignal"), expected)))
      case _ => throw new RuntimeException("eqSignal(signal, value)")
    }
    native(context, "hashSignal") { args =>
      if args.nonEmpty then throw new RuntimeException("hashSignal()")
      makeSignal("__hash__", "hash", Value.StrV(""), Value.DataV("NativeUiSignalMetaHash", Vector.empty))
    }

    val emptyHeaders = makeSignal("__empty_headers__", "computed", Value.StrV(""),
      Value.DataV("NativeUiSignalMetaComputed", Vector(closure(0)(_ => Value.StrV("")))), writable = false)
    val emptyHeadersKey = SignalKey(ScopeKey(rootId, "root"), "__empty_headers__")
    val emptyHeadersCell = signals(emptyHeadersKey)
    def registerEmptyHeadersForRoot(): Unit =
      signals(emptyHeadersKey) = emptyHeadersCell
    context.registerValue("emptyHeaders", emptyHeaders)

    def fetchSignal(site: String, name: String, url: Value, refresh: Value, headers: Value): Value =
      signalFields(refresh, "fetch refresh"); signalFields(headers, "fetch headers")
      val phase = makeSignal(s"${name}__phase", "mutable", Value.StrV("idle"), metadata())
      val error = makeSignal(s"${name}__error", "mutable", Value.StrV(""), metadata())
      makeSignal(name, "fetch", Value.StrV(""),
        Value.DataV("NativeUiSignalMetaFetch", Vector(url, refresh, headers, phase, error)), writable = false)

    siteNative(context, "fetchUrlSignal") { (site, _, args) =>
      if args.length != 3 && args.length != 4 then throw new RuntimeException("fetchUrlSignal(name, url, refresh[, headers])")
      fetchSignal(site, text(args, 0, "fetchUrlSignal"), Value.StrV(text(args, 1, "fetchUrlSignal")), args(2), args.lift(3).getOrElse(emptyHeaders))
    }
    siteNative(context, "fetchUrlSignalTo") { (site, _, args) =>
      if args.length != 3 && args.length != 4 then throw new RuntimeException("fetchUrlSignalTo(name, urlSignal, refresh[, headers])")
      signalFields(args(1), "fetchUrlSignalTo url")
      fetchSignal(site, text(args, 0, "fetchUrlSignalTo"), args(1), args(2), args.lift(3).getOrElse(emptyHeaders))
    }

    native(context, "textNode") { args => Value.DataV("NativeUiText", Vector(Value.StrV(text(args, 0, "textNode")))) }
    native(context, "signalText") { case signal :: Nil => signalFields(signal, "signalText"); Value.DataV("NativeUiSignalText", Vector(signal)); case _ => throw new RuntimeException("signalText(signal)") }
    native(context, "showSignal") { case condition :: whenTrue :: whenFalse :: Nil => signalFields(condition, "showSignal"); Value.DataV("NativeUiShow", Vector(condition, NativeUiPortable.canonical(whenTrue, "NativeUiShow.whenTrue"), NativeUiPortable.canonical(whenFalse, "NativeUiShow.whenFalse"))); case _ => throw new RuntimeException("showSignal(condition, whenTrue, whenFalse)") }
    native(context, "fragment") { case children :: Nil => unlist(children, "fragment"); Value.DataV("NativeUiFragment", Vector(NativeUiPortable.canonical(children, "NativeUiFragment.children"))); case _ => throw new RuntimeException("fragment(children)") }
    siteNative(context, "element") { (site, source, args) => args match
      case Value.StrV(tag) :: attrsRaw :: eventsRaw :: childrenRaw :: Nil =>
        val attrs = NativeUiPortable.stringMap(attrsRaw, "NativeUiElement.attrs")
        val events = NativeUiPortable.stringMap(eventsRaw, "NativeUiElement.events")
        val children = NativeUiPortable.canonical(childrenRaw, "NativeUiElement.children")
        val raw = attrs.entries.get(Value.StrV("data-ssc-raw-html"))
        raw match
          case Some(Value.StrV(html))
              if tag == "span" &&
                attrs.entries.size == 2 &&
                attrs.entries.get(Value.StrV("style")).contains(Value.StrV("display:contents")) &&
                events.entries.isEmpty &&
                unlist(children, "rawHtml children").isEmpty =>
            Value.DataV("NativeUiTrustedHtml", Vector(Value.StrV(site), Value.StrV(html)))
          case Some(_) => unsupported("rawHtml sentinel", source, "malformed data-ssc-raw-html element")
          case None => Value.DataV("NativeUiElement", Vector(Value.StrV(site), Value.StrV(tag), attrs, events, children))
      case _ => throw new RuntimeException("element(tag, attrs, events, children)")
    }
    siteNative(context, "forKeyedView") { (site, _, args) => args match
      case items :: (key: Value.ClosV) :: (renderFn: Value.ClosV) :: Nil =>
        signalFields(items, "forKeyedView items")
        Value.DataV("NativeUiForKeyed", Vector(
          Value.StrV(site),
          NativeUiPortable.canonical(items, s"NativeUiForKeyed[$site].items"),
          NativeUiPortable.canonical(key, s"NativeUiForKeyed[$site].keyClosure"),
          NativeUiPortable.canonical(renderFn, s"NativeUiForKeyed[$site].renderClosure")))
      case _ => throw new RuntimeException("forKeyedView(items, key, render)")
    }

    native(context, "setSignal") { case signal :: value :: Nil => signalFields(signal, "setSignal"); event("set", signal, value); case _ => throw new RuntimeException("setSignal(signal, value)") }
    native(context, "inputChange") { case signal :: Nil => signalFields(signal, "inputChange"); event("input", signal, Value.UnitV); case _ => throw new RuntimeException("inputChange(signal)") }
    native(context, "toggleSignal") { case signal :: Nil => signalFields(signal, "toggleSignal"); event("toggle", signal, Value.UnitV); case _ => throw new RuntimeException("toggleSignal(signal)") }
    native(context, "incSignal") { case signal :: Nil => signalFields(signal, "incSignal"); event("increment", signal, Value.IntV(1)); case _ => throw new RuntimeException("incSignal(signal)") }

    native(context, "onBumpTick") { case signal :: Nil => signalFields(signal, "onBumpTick"); success("bumpTick", signal, Value.UnitV); case _ => throw new RuntimeException("onBumpTick(signal)") }
    native(context, "onSetSignal") { case signal :: value :: Nil => signalFields(signal, "onSetSignal"); success("setSignal", signal, value); case _ => throw new RuntimeException("onSetSignal(signal, value)") }
    native(context, "onNavigate") { case Value.StrV(path) :: Nil => success("navigate", Value.UnitV, Value.StrV(path)); case _ => throw new RuntimeException("onNavigate(path)") }
    native(context, "onOpenJson") { case Value.StrV(template) :: Value.StrV(field) :: Nil => success("openJson", Value.StrV(template), Value.StrV(field)); case _ => throw new RuntimeException("onOpenJson(template, field)") }
    native(context, "formBody") { case fields :: Nil => Value.DataV("NativeUiFormBody", Vector(NativeUiPortable.canonical(fields, "NativeUiFormBody.fields"))); case _ => throw new RuntimeException("formBody(fields)") }

    def tickEffects(tick: Value): Value = { signalFields(tick, "fetch success tick"); list(List(success("bumpTick", tick, Value.UnitV))) }
    siteNative(context, "fetchAction") { (site, _, args) => if args.length == 4 || args.length == 5 then fetchAction(site, text(args, 0, "fetchAction"), Value.StrV(text(args, 1, "fetchAction")), args(2), tickEffects(args(3)), args.lift(4).getOrElse(emptyHeaders)) else throw new RuntimeException("fetchAction(method, url, body, tick[, headers])") }
    siteNative(context, "fetchActionTo") { (site, _, args) => if args.length == 4 || args.length == 5 then { signalFields(args(1), "fetchActionTo url"); fetchAction(site, text(args, 0, "fetchActionTo"), args(1), args(2), tickEffects(args(3)), args.lift(4).getOrElse(emptyHeaders)) } else throw new RuntimeException("fetchActionTo(method, urlSignal, body, tick[, headers])") }
    siteNative(context, "fetchActionClear") { (site, _, args) => if args.length == 4 || args.length == 5 then fetchAction(site, text(args, 0, "fetchActionClear"), Value.StrV(text(args, 1, "fetchActionClear")), args(2), tickEffects(args(3)), args.lift(4).getOrElse(emptyHeaders), clear = args(2)) else throw new RuntimeException("fetchActionClear(method, url, body, tick[, headers])") }
    siteNative(context, "fetchCaptureAction") { (site, _, args) => if args.length == 5 || args.length == 6 then fetchAction(site, text(args, 0, "fetchCaptureAction"), Value.StrV(text(args, 1, "fetchCaptureAction")), args(2), tickEffects(args(4)), args.lift(5).getOrElse(emptyHeaders), capture = args(3)) else throw new RuntimeException("fetchCaptureAction(method, url, body, into, tick[, headers])") }
    siteNative(context, "fetchActionWith") { (site, _, args) => if args.length == 4 || args.length == 5 then fetchAction(site, text(args, 0, "fetchActionWith"), Value.StrV(text(args, 1, "fetchActionWith")), args(2), args(3), args.lift(4).getOrElse(emptyHeaders)) else throw new RuntimeException("fetchActionWith(method, url, body, effects[, headers])") }

    native(context, "staticRowsSource") { case rows :: Nil =>
      val portableRows = unlist(rows, "NativeUiTableSource.rows").zipWithIndex.map { case (row, index) =>
        NativeUiPortable.stringMap(row, s"NativeUiTableSource.rows[$index]")
      }
      Value.DataV("NativeUiTableSource", Vector(Value.StrV("static"), list(portableRows), Value.StrV("")))
      case _ => throw new RuntimeException("staticRowsSource(rows)") }
    native(context, "signalRowsSource") { case signal :: Nil => signalFields(signal, "signalRowsSource"); Value.DataV("NativeUiTableSource", Vector(Value.StrV("signal"), signal, Value.StrV(""))); case _ => throw new RuntimeException("signalRowsSource(signal)") }
    native(context, "fetchRowsSource") { case signal :: Value.StrV(path) :: Nil => signalFields(signal, "fetchRowsSource"); Value.DataV("NativeUiTableSource", Vector(Value.StrV("fetch"), signal, Value.StrV(path))); case _ => throw new RuntimeException("fetchRowsSource(signal, rowsPath)") }
    def validDottedName(name: String): Boolean =
      name.nonEmpty && name.split("\\.", -1).forall(_.nonEmpty)
    def rowPayload(value: Value, operation: String): Value =
      val descriptor = value match
        case Value.StrV(name) =>
          Value.DataV("NativeUiRowPayload", Vector(Value.StrV("field"), list(List(Value.StrV(name)))))
        case data @ Value.DataV("NativeUiRowPayload", fields) if fields.length == 2 => data
        case _ => throw new RuntimeException(s"$operation payload must be String or NativeUiRowPayload")
      descriptor match
        case Value.DataV("NativeUiRowPayload", Seq(Value.StrV(kind), rawNames)) =>
          val names = unlist(rawNames, s"$operation payload names").map {
            case Value.StrV(name) if validDottedName(name) => name
            case _ => throw new RuntimeException(s"$operation payload names must be non-empty dotted Strings")
          }
          val valid = kind match
            case "field" => names.length == 1
            case "wholeRow" => names.isEmpty
            case "fields" => names.nonEmpty && names.distinct.length == names.length
            case _ => false
          if !valid then throw new RuntimeException(s"$operation payload descriptor is malformed")
          Value.DataV("NativeUiRowPayload", Vector(Value.StrV(kind), list(names.map(Value.StrV.apply))))
        case _ => throw new RuntimeException(s"$operation payload descriptor is malformed")

    native(context, "fieldPayload") {
      case Value.StrV(name) :: Nil => rowPayload(Value.StrV(name), "fieldPayload")
      case _ => throw new RuntimeException("fieldPayload(name)")
    }
    native(context, "wholeRowPayload") {
      case Nil => rowPayload(
        Value.DataV("NativeUiRowPayload", Vector(Value.StrV("wholeRow"), list(Nil))),
        "wholeRowPayload")
      case _ => throw new RuntimeException("wholeRowPayload()")
    }
    native(context, "fieldsPayload") {
      case names :: Nil => rowPayload(
        Value.DataV("NativeUiRowPayload", Vector(Value.StrV("fields"), names)),
        "fieldsPayload")
      case _ => throw new RuntimeException("fieldsPayload(names)")
    }

    def column(kind: String, title: Value, fieldPath: Value, align: Value, options: Value): Value =
      val operation = s"${kind}Column"
      val fields = List(title, fieldPath, align)
      Value.DataV("NativeUiColumn", Vector(
        Value.StrV(kind),
        Value.StrV(text(fields, 0, operation)),
        Value.StrV(text(fields, 1, operation)),
        Value.StrV(text(fields, 2, operation)),
        NativeUiPortable.stringMap(options, s"NativeUiColumn[$kind].options")))
    native(context, "fieldColumn") { args => if args.length >= 2 && args.length <= 4 then column("text", args(0), args(1), args.lift(2).getOrElse(Value.StrV("")), metadata("editAction" -> args.lift(3).getOrElse(Value.UnitV))) else throw new RuntimeException("fieldColumn(title, fieldPath[, align, edit])") }
    native(context, "dateColumn") { args => if args.length >= 2 && args.length <= 4 then column("date", args(0), args(1), args.lift(2).getOrElse(Value.StrV("")), metadata("format" -> args.lift(3).getOrElse(Value.StrV("")))) else throw new RuntimeException("dateColumn(title, fieldPath[, align, format])") }
    native(context, "moneyColumn") { args => if args.length >= 2 && args.length <= 5 then column("money", args(0), args(1), args.lift(2).getOrElse(Value.StrV("")), metadata("currency" -> args.lift(3).getOrElse(Value.StrV("USD")), "locale" -> args.lift(4).getOrElse(Value.StrV("")))) else throw new RuntimeException("moneyColumn(title, fieldPath[, align, currency, locale])") }
    native(context, "statusColumn") { args => if args.length >= 2 && args.length <= 4 then column("status", args(0), args(1), args.lift(2).getOrElse(Value.StrV("")), metadata("colorMap" -> args.lift(3).getOrElse(Value.UnitV))) else throw new RuntimeException("statusColumn(title, fieldPath[, align, colors])") }
    native(context, "linkColumn") { args => if args.length >= 2 && args.length <= 4 then column("link", args(0), args(1), args.lift(2).getOrElse(Value.StrV("")), metadata("urlTemplate" -> args.lift(3).getOrElse(Value.StrV("")))) else throw new RuntimeException("linkColumn(title, fieldPath[, align, url])") }
    native(context, "stackedColumn") { args => if args.length == 3 || args.length == 4 then column("stacked", args(0), args(1), args.lift(3).getOrElse(Value.StrV("")), metadata("subFieldPath" -> args(2))) else throw new RuntimeException("stackedColumn(title, fieldPath, subFieldPath[, align])") }

    def rowAction(kind: String, label: String, request: Value, payload: Value, refresh: Value, options: Value): Value =
      val checkedPayload = rowPayload(payload, s"row $kind action")
      Value.DataV("NativeUiRowAction", Vector(
        Value.StrV(kind), Value.StrV(label),
        NativeUiPortable.canonical(request, s"NativeUiRowAction[$kind].request"),
        NativeUiPortable.canonical(checkedPayload, s"NativeUiRowAction[$kind].payload"),
        NativeUiPortable.canonical(refresh, s"NativeUiRowAction[$kind].refresh"),
        NativeUiPortable.stringMap(options, s"NativeUiRowAction[$kind].options")))
    native(context, "rowDeleteAction") { args =>
      if args.length == 3 || args.length == 4 then
        val payload = rowPayload(Value.StrV(text(args, 1, "rowDeleteAction")), "rowDeleteAction")
        rowAction("delete", "Delete", fetchRequest("POST", Value.StrV(text(args, 0, "rowDeleteAction")), payload, args.lift(3).getOrElse(emptyHeaders)), payload, args(2), metadata())
      else throw new RuntimeException("rowDeleteAction(url, idField, tick[, headers])")
    }
    native(context, "rowPostAction") { args => if args.length == 5 || args.length == 6 then rowAction("post", text(args, 0, "rowPostAction"), fetchRequest(text(args, 1, "rowPostAction"), Value.StrV(text(args, 2, "rowPostAction")), Value.UnitV, args.lift(5).getOrElse(emptyHeaders)), rowPayload(args(3), "rowPostAction"), args(4), metadata()) else throw new RuntimeException("rowPostAction(label, method, url, payload, tick[, headers])") }
    native(context, "rowLinkAction") { case Value.StrV(label) :: signal :: Value.StrV(field) :: Nil => signalFields(signal, "rowLinkAction"); rowAction("link", label, Value.UnitV, rowPayload(Value.StrV(field), "rowLinkAction"), Value.UnitV, metadata("signal" -> signal)); case _ => throw new RuntimeException("rowLinkAction(label, signal, fieldPath)") }
    native(context, "rowEditAction") { args => if args.length == 4 || args.length == 5 then rowAction("edit", "Edit", fetchRequest(text(args, 0, "rowEditAction"), Value.StrV(text(args, 1, "rowEditAction")), Value.UnitV, args.lift(4).getOrElse(emptyHeaders)), rowPayload(Value.StrV(text(args, 2, "rowEditAction")), "rowEditAction"), args(3), metadata()) else throw new RuntimeException("rowEditAction(method, url, idField, tick[, headers])") }
    siteNative(context, "dataTableView") { (site, _, args) => args match
      case source :: columns :: actions :: Nil =>
        Value.DataV("NativeUiDataTable", Vector(Value.StrV(site), NativeUiPortable.canonical(source, "NativeUiDataTable.source"), NativeUiPortable.canonical(columns, "NativeUiDataTable.columns"), NativeUiPortable.canonical(actions, "NativeUiDataTable.actions"), Value.StrV("id")))
      case source :: columns :: actions :: Value.StrV(rowKeyPath) :: Nil =>
        Value.DataV("NativeUiDataTable", Vector(Value.StrV(site), NativeUiPortable.canonical(source, "NativeUiDataTable.source"), NativeUiPortable.canonical(columns, "NativeUiDataTable.columns"), NativeUiPortable.canonical(actions, "NativeUiDataTable.actions"), Value.StrV(if rowKeyPath.isEmpty then "id" else rowKeyPath)))
      case _ => throw new RuntimeException("dataTableView(source, columns, actions[, rowKeyPath])")
    }

    native(context, "localStorageGet") { case Value.StrV(key) :: Nil => storage.get(key).fold[Value](Value.DataV("None", Vector.empty))(value => Value.DataV("Some", Vector(Value.StrV(value)))); case _ => throw new RuntimeException("localStorageGet(key)") }
    native(context, "localStorageSet") { case Value.StrV(key) :: Value.StrV(value) :: Nil => storage(key) = value; Value.UnitV; case _ => throw new RuntimeException("localStorageSet(key, value)") }
    native(context, "localStorageRemove") { case Value.StrV(key) :: Nil => storage.remove(key); Value.UnitV; case _ => throw new RuntimeException("localStorageRemove(key)") }
    native(context, "onlineSignal") { case Nil => makeSignal("__online__", "online", Value.BoolV(true), Value.DataV("NativeUiSignalMetaOnline", Vector.empty), writable = false); case _ => throw new RuntimeException("onlineSignal()") }
    native(context, "persistedSignal") { case Value.StrV(name) :: Value.StrV(default) :: Nil =>
      val initial = Value.StrV(storage.getOrElse(name, default))
      makeSignal(name, "persisted", initial, Value.DataV("NativeUiSignalMetaPersisted", Vector(Value.StrV(name))), configure = cell => cell.afterWrite = {
        case Value.StrV(value) => storage(name) = value
        case other => throw new RuntimeException(s"persisted signal '$name' requires String, got ${Show.show(other)}")
      }); case _ => throw new RuntimeException("persistedSignal(name, default)") }

    native(context, "componentScope") { case Value.StrV(scope) :: (body: Value.ClosV) :: Nil =>
      val occurrence = nextComponentOccurrence(scope)
      val previousOwner = currentOwnerPath
      val componentOwner = previousOwner :+ (s"component:$scope" -> occurrence.toString)
      currentOwnerPath = componentOwner
      scopes += scope
      val result =
        try Runtime.run(body.code, body.env)
        finally
          scopes.remove(scopes.length - 1)
          currentOwnerPath = previousOwner
      val owner = OwnerKey(rootId, componentOwner)
      val owned = ownerScopes.getOrElse(owner, Set.empty)
      ownerScopes(owner) = owned + ScopeKey(rootId, scope)
      result match
        case data: Value.DataV =>
          componentOwners.put(data, componentOwner)
          componentBindingCollectors.foreach(_.put(data, java.lang.Boolean.TRUE))
        case _ => ()
      result
      case _ => throw new RuntimeException("componentScope(scopeId, bodyThunk)") }

    native(context, "__nativeUiBeginApple") { case Nil =>
      resetEvaluationState()
      appleContext = true
      registerEmptyHeadersForRoot()
      Value.UnitV
      case _ => throw new RuntimeException("__nativeUiBeginApple()") }
    native(context, "__nativeUiAbortApple") { case Nil =>
      resetEvaluationState()
      Value.UnitV
      case _ => throw new RuntimeException("__nativeUiAbortApple()") }
    native(context, "__nativeUiTakeRoot") { case Nil =>
      try registeredRoot match
        case Some((tree, config, _)) =>
          Value.DataV("NativeUiAbi", Vector(Value.IntV(1), tree, config))
        case None =>
          throw new RuntimeException("native UI program did not register a root; call emit(...) or serve(...) exactly once")
      finally resetEvaluationState()
      case _ => throw new RuntimeException("__nativeUiTakeRoot()") }

    sourceNative(context, "emit") { (source, args) => args match
      case tree :: Value.StrV(outDir) :: Nil if appleContext =>
        val config = Value.DataV("NativeUiRootConfig", Vector(Value.StrV("emit"), Value.StrV(outDir), Value.IntV(0), Value.StrV("")))
        registerRoot(NativeUiPortable.canonical(tree, "NativeUiAbi.root"), config, source); Value.UnitV
      case tree :: Value.StrV(outDir) :: Nil =>
        val output = Path.of(outDir).resolve("index.html")
        Files.createDirectories(output.getParent)
        Files.writeString(output, s"<!doctype html>\n${render(tree)}\n", StandardCharsets.UTF_8)
        Value.UnitV
      case _ => throw new RuntimeException("emit(tree, outDir)")
    }
    // Public `serve(port)` belongs to the HTTP provider. Imported std.ui.serve
    // calls carry provenance and are rewritten to this reserved ABI global.
    internalSourceNative(context, "serve") { (source, args) =>
      if args.length != 2 && args.length != 3 then throw new RuntimeException("serve(tree, port[, extraCss])")
      val tree = args.head; val port = integer(args, 1, "serve"); val css = args.lift(2).map { case Value.StrV(value) => value; case _ => throw new RuntimeException("serve extraCss must be String") }.getOrElse("")
      if appleContext then
        val root = if css.isEmpty || mobileCss(css) then NativeUiPortable.canonical(tree, "NativeUiAbi.root") else unsupported("root extraCss", source, "only std/ui mobileOverrideCss is supported")
        val config = Value.DataV("NativeUiRootConfig", Vector(Value.StrV("serve"), Value.StrV(""), Value.IntV(port), Value.StrV(css)))
        registerRoot(root, config, source); Value.UnitV
      else throw new RuntimeException("native JVM serve is unavailable; use emit or an HTTP server backend")
    }
