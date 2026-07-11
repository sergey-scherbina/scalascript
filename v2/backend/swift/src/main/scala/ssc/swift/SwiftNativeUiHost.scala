package ssc.swift

private[swift] object SwiftNativeUiHost:
  val source: String = """// Generated ScalaScript v2 portable NativeUi host. No SwiftUI dependency.
import Foundation

private final class NativeUiSignalCell {
    let id: String
    let scope: String
    let kind: String
    let declaredDefault: SscValue
    let writable: Bool
    var current: SscValue
    var dirty = false
    var dynamicRead: () throws -> SscValue
    var afterWrite: (SscValue) throws -> Void = { _ in }

    init(id: String, scope: String, kind: String, declaredDefault: SscValue, writable: Bool) {
        self.id = id
        self.scope = scope
        self.kind = kind
        self.declaredDefault = declaredDefault
        self.writable = writable
        self.current = declaredDefault
        self.dynamicRead = { declaredDefault }
    }

    func snapshot() -> NativeUiSignalState {
        NativeUiSignalState(current: current, dirty: dirty, dynamicRead: dynamicRead, afterWrite: afterWrite)
    }

    func restore(_ state: NativeUiSignalState) {
        current = state.current
        dirty = state.dirty
        dynamicRead = state.dynamicRead
        afterWrite = state.afterWrite
    }
}

private struct NativeUiSignalState {
    let current: SscValue
    let dirty: Bool
    let dynamicRead: () throws -> SscValue
    let afterWrite: (SscValue) throws -> Void
}

private struct NativeUiPair: Hashable {
    let left: ObjectIdentifier
    let right: ObjectIdentifier
}

struct NativeUiKeyedEntryValue {
    let id: String
    let ownerPath: String
    let value: SscValue
}

private struct NativeUiForOwnerHint {
    let fields: SscFields
    let path: String
}

struct NativeUiKeyedResult {
    let entries: [NativeUiKeyedEntryValue]
    let disposedSignalKeys: [String]
}

private struct NativeUiHostSnapshot {
    let signals: [String: NativeUiSignalCell]
    let signalStates: [String: NativeUiSignalState]
    let storage: [String: String]
    let scopes: [String]
    let ownerScopes: [String: Set<String>]
    let scopeSignals: [String: Set<String>]
    let keyedOwners: [String: Set<String>]
    let siteSources: [String: SscValue]
    let signalSources: [String: SscValue]
    let forOwnerHints: [ObjectIdentifier: NativeUiForOwnerHint]
    let currentSiteOccurrences: [String: Int]
    let currentOwnerPath: String
    let currentOwnerScopes: Set<String>
}

final class NativeUiSession {
    let root: SscValue
    private let host: NativeUiHost
    private let runtime: SscRuntimeSession

    init(root: SscValue, host: NativeUiHost, runtime: SscRuntimeSession) {
        self.root = root
        self.host = host
        self.runtime = runtime
    }

    func dispose() { host.abort() }

    func invoke(_ closure: SscClosure, _ arguments: [SscValue]) throws -> SscValue {
        try runtime.invoke(closure, arguments)
    }

    func observe(
        onRead: @escaping (String, String) throws -> Void,
        onReadEnd: @escaping (String, String) -> Void,
        onWrite: @escaping (String, String) -> Void,
        onBatchBegin: @escaping () -> Void,
        onBatchCommit: @escaping () -> Void,
        onBatchRollback: @escaping () -> Void
    ) {
        host.onRead = onRead
        host.onReadEnd = onReadEnd
        host.onWrite = onWrite
        host.onBatchBegin = onBatchBegin
        host.onBatchCommit = onBatchCommit
        host.onBatchRollback = onBatchRollback
    }

    func read(_ signal: SscValue) throws -> SscValue { try host.readSignal(signal, "NativeUiSession.read") }
    func write(_ signal: SscValue, _ value: SscValue) throws { try host.writeSignal(signal, value, "NativeUiSession.write") }
    func equal(_ left: SscValue, _ right: SscValue) -> Bool { nativeUiEqual(left, right) }
    func sourceRef(_ siteId: String) -> SscValue? { host.sourceRef(siteId) }
    func sourceRef(for signal: SscValue) -> SscValue? { host.sourceRef(for: signal) }
    func ownerPath(for node: SscValue) -> String? { host.ownerPath(for: node) }
    func ownerHintCount() -> Int { host.ownerHintCount() }
    func reconcileKeyed(
        parentOwnerPath: String,
        siteId: String,
        items: [SscValue],
        key: SscClosure,
        render: SscClosure
    ) throws -> NativeUiKeyedResult {
        try host.reconcileKeyed(
            parentOwnerPath: parentOwnerPath,
            siteId: siteId,
            items: items,
            key: key,
            render: render
        )
    }
    deinit { host.abort() }
}

final class NativeUiHost: SscRuntimeExtension {
    private var invoke: ((SscClosure, [SscValue]) throws -> SscValue)?
    private var signals: [String: NativeUiSignalCell] = [:]
    private var storage: [String: String] = [:]
    private var scopes: [String] = ["root"]
    private var root: (SscValue, SscValue, SscValue)?
    private var active = false
    private var emptyHeaders: SscValue = .unit
    private var ownerScopes: [String: Set<String>] = [:]
    private var scopeSignals: [String: Set<String>] = ["root": []]
    private var keyedOwners: [String: Set<String>] = [:]
    private var siteSources: [String: SscValue] = [:]
    private var signalSources: [String: SscValue] = [:]
    private var forOwnerHints: [ObjectIdentifier: NativeUiForOwnerHint] = [:]
    private var ownerHintCollectors: [Set<ObjectIdentifier>] = []
    private var currentSiteOccurrences: [String: Int] = [:]
    private var currentOwnerPath = "root"
    private var currentOwnerScopes = Set<String>()
    var onRead: ((String, String) throws -> Void)?
    var onReadEnd: ((String, String) -> Void)?
    var onWrite: ((String, String) -> Void)?
    var onBatchBegin: (() -> Void)?
    var onBatchCommit: (() -> Void)?
    var onBatchRollback: (() -> Void)?

    func bind(_ invoke: @escaping (SscClosure, [SscValue]) throws -> SscValue) {
        self.invoke = invoke
    }

    func begin() throws {
        active = true
        root = nil
        signals.removeAll(keepingCapacity: true)
        scopes = ["root"]
        ownerScopes.removeAll(keepingCapacity: true)
        scopeSignals = ["root": []]
        keyedOwners.removeAll(keepingCapacity: true)
        siteSources.removeAll(keepingCapacity: true)
        signalSources.removeAll(keepingCapacity: true)
        forOwnerHints.removeAll(keepingCapacity: true)
        ownerHintCollectors.removeAll(keepingCapacity: true)
        currentSiteOccurrences.removeAll(keepingCapacity: true)
        currentOwnerPath = "root"
        currentOwnerScopes.removeAll(keepingCapacity: true)
        emptyHeaders = try makeSignal(
            id: "__empty_headers__", kind: "computed", declaredDefault: .string(""),
            metadata: try nativeUiData("NativeUiSignalMetaComputed", [.closure(SscClosure(arity: 0) { _ in .string("") })]),
            writable: false)
    }

    func abort() {
        active = false
        root = nil
        signals.removeAll(keepingCapacity: true)
        scopes = ["root"]
        ownerScopes.removeAll(keepingCapacity: true)
        scopeSignals = ["root": []]
        keyedOwners.removeAll(keepingCapacity: true)
        siteSources.removeAll(keepingCapacity: true)
        signalSources.removeAll(keepingCapacity: true)
        forOwnerHints.removeAll(keepingCapacity: true)
        ownerHintCollectors.removeAll(keepingCapacity: true)
        currentSiteOccurrences.removeAll(keepingCapacity: true)
        currentOwnerPath = "root"
        currentOwnerScopes.removeAll(keepingCapacity: true)
        emptyHeaders = .unit
        onRead = nil
        onReadEnd = nil
        onWrite = nil
        onBatchBegin = nil
        onBatchCommit = nil
        onBatchRollback = nil
    }

    func evaluate(_ program: SscProgram) throws -> NativeUiSession {
        try begin()
        let runtime = SscRuntime.session(program, nativeUiHost: self)
        switch runtime.evaluate() {
        case .success:
            do { return NativeUiSession(root: try takeRoot(), host: self, runtime: runtime) }
            catch { abort(); throw error }
        case let .failure(error):
            abort()
            throw error
        }
    }

    func takeRoot() throws -> SscValue {
        guard let (tree, config, _) = root else {
            abort()
            throw SscRuntimeFailure(description: "native UI program did not register a root; call emit(...) or serve(...) exactly once")
        }
        let result = try nativeUiData("NativeUiAbi", [.int(1), tree, config])
        ownerScopes["root"] = currentOwnerScopes
        active = false
        root = nil
        scopes = ["root"]
        return result
    }

    func globals() throws -> [String: SscValue] {
        if case .unit = emptyHeaders { try begin() }
        var values: [String: SscValue] = [:]
        func native(_ name: String, _ body: @escaping ([SscValue]) throws -> SscValue) {
            values[name] = .closure(SscClosure(arity: -1, native: body))
        }
        func site(_ name: String, _ body: @escaping (String, SscValue, [SscValue]) throws -> SscValue) {
            native(name) { [weak self] args in
                let siteId = "manual:\(name)", source = try nativeUiSource(name)
                self!.siteSources[siteId] = source
                let result = try body(siteId, source, args)
                try self!.bindSignalSource(result, source: source)
                if name == "forKeyedView" { try self!.bindForOwner(result, siteId: siteId) }
                return result
            }
            native("__ssc_nativeui_v1.\(name)") { [weak self] args in
                guard args.count >= 2, case let .string(site) = args[0] else {
                    throw SscRuntimeFailure(description: "__ssc_nativeui_v1.\(name) requires site and source metadata")
                }
                self!.siteSources[site] = args[1]
                let result = try body(site, args[1], Array(args.dropFirst(2)))
                try self!.bindSignalSource(result, source: args[1])
                if name == "forKeyedView" { try self!.bindForOwner(result, siteId: site) }
                return result
            }
        }
        func sourced(_ name: String, _ body: @escaping (SscValue, [SscValue]) throws -> SscValue) {
            native(name) { args in try body(try nativeUiSource(name), args) }
            native("__ssc_nativeui_v1.\(name)") { args in
                guard let source = args.first else { throw SscRuntimeFailure(description: "__ssc_nativeui_v1.\(name) requires source metadata") }
                return try body(source, Array(args.dropFirst()))
            }
        }

        native("__nativeUiBeginApple") { [weak self] args in
            try nativeUiRequire(args.isEmpty, "__nativeUiBeginApple()")
            try self!.begin(); return .unit
        }
        native("__nativeUiAbortApple") { [weak self] args in
            try nativeUiRequire(args.isEmpty, "__nativeUiAbortApple()")
            self!.abort(); return .unit
        }
        native("__nativeUiTakeRoot") { [weak self] args in
            try nativeUiRequire(args.isEmpty, "__nativeUiTakeRoot()")
            return try self!.takeRoot()
        }

        native("signal") { [weak self] args in
            try nativeUiRequire(args.count == 2, "signal(name, default)")
            return try self!.makeSignal(id: try nativeUiString(args[0], "signal name"), kind: "mutable", declaredDefault: args[1], metadata: nativeUiMap())
        }
        native("seedSignal") { [weak self] args in
            try nativeUiRequire(args.count == 2, "seedSignal(name, source)")
            let source = args[1]
            _ = try nativeUiSignalFields(source, "seedSignal source")
            let signal = try self!.makeSignal(
                id: try nativeUiString(args[0], "seedSignal name"), kind: "seed",
                declaredDefault: try self!.readSignal(source, "seedSignal source"),
                metadata: try nativeUiData("NativeUiSignalMetaSeed", [source]))
            let fields = try nativeUiSignalFields(signal, "seedSignal")
            let key = self!.signalKey(scope: try nativeUiString(fields[1], "seed scope"), id: try nativeUiString(fields[0], "seed id"))
            let cell = self!.signals[key]!
            cell.dynamicRead = { [weak self, weak cell] in
                guard let self, let cell else { throw SscRuntimeFailure(description: "seed signal released") }
                if cell.dirty { return cell.current }
                return try self.readSignal(source, "seedSignal source")
            }
            return signal
        }
        site("computedSignal") { [weak self] siteId, _, args in
            try nativeUiRequire(args.count == 1, "computedSignal(callback)")
            let compute = try nativeUiClosure(args[0], "computedSignal callback")
            let initial = try self!.call(compute, [])
            let signal = try self!.makeSignal(
                id: "__computed__\(siteId)", kind: "computed", declaredDefault: initial,
                metadata: try nativeUiData("NativeUiSignalMetaComputed", [.closure(compute)]), writable: false)
            let fields = try nativeUiSignalFields(signal, "computedSignal")
            let key = self!.signalKey(scope: try nativeUiString(fields[1], "computed scope"), id: try nativeUiString(fields[0], "computed id"))
            self!.signals[key]!.dynamicRead = { [weak self] in try self!.call(compute, []) }
            return signal
        }
        site("eqSignal") { [weak self] siteId, _, args in
            try nativeUiRequire(args.count == 2, "eqSignal(signal, value)")
            _ = try nativeUiSignalFields(args[0], "eqSignal source")
            let source = args[0], expected = args[1]
            let signal = try self!.makeSignal(
                id: "__equality__\(siteId)", kind: "equality",
                declaredDefault: .bool(nativeUiEqual(try self!.readSignal(source, "eqSignal"), expected)),
                metadata: try nativeUiData("NativeUiSignalMetaEquality", [source, expected]), writable: false)
            let fields = try nativeUiSignalFields(signal, "eqSignal")
            let key = self!.signalKey(scope: try nativeUiString(fields[1], "equality scope"), id: try nativeUiString(fields[0], "equality id"))
            self!.signals[key]!.dynamicRead = { [weak self] in .bool(nativeUiEqual(try self!.readSignal(source, "eqSignal"), expected)) }
            return signal
        }
        native("hashSignal") { [weak self] args in
            try nativeUiRequire(args.isEmpty, "hashSignal()")
            return try self!.makeSignal(id: "__hash__", kind: "hash", declaredDefault: .string(""), metadata: try nativeUiData("NativeUiSignalMetaHash", []))
        }
        values["emptyHeaders"] = emptyHeaders

        func fetchSignal(_ name: String, _ url: SscValue, _ refresh: SscValue, _ headers: SscValue) throws -> SscValue {
            _ = try nativeUiSignalFields(refresh, "fetch refresh")
            _ = try nativeUiSignalFields(headers, "fetch headers")
            let phase = try self.makeSignal(id: "\(name)__phase", kind: "mutable", declaredDefault: .string("idle"), metadata: nativeUiMap())
            let error = try self.makeSignal(id: "\(name)__error", kind: "mutable", declaredDefault: .string(""), metadata: nativeUiMap())
            return try self.makeSignal(
                id: name, kind: "fetch", declaredDefault: .string(""),
                metadata: try nativeUiData("NativeUiSignalMetaFetch", [url, refresh, headers, phase, error]), writable: false)
        }
        site("fetchUrlSignal") { siteId, _, args in
            try nativeUiRequire(args.count == 3 || args.count == 4, "fetchUrlSignal(name, url, refresh[, headers])")
            return try fetchSignal(try nativeUiString(args[0], "fetch name"), .string(try nativeUiString(args[1], "fetch url")), args[2], args.count == 4 ? args[3] : self.emptyHeaders)
        }
        site("fetchUrlSignalTo") { siteId, _, args in
            try nativeUiRequire(args.count == 3 || args.count == 4, "fetchUrlSignalTo(name, urlSignal, refresh[, headers])")
            _ = try nativeUiSignalFields(args[1], "fetch URL signal")
            return try fetchSignal(try nativeUiString(args[0], "fetch name"), args[1], args[2], args.count == 4 ? args[3] : self.emptyHeaders)
        }

        native("textNode") { args in
            try nativeUiRequire(args.count == 1, "textNode(text)")
            return try nativeUiData("NativeUiText", [.string(try nativeUiString(args[0], "textNode"))])
        }
        native("signalText") { args in
            try nativeUiRequire(args.count == 1, "signalText(signal)")
            _ = try nativeUiSignalFields(args[0], "signalText")
            return try nativeUiData("NativeUiSignalText", [args[0]])
        }
        native("showSignal") { args in
            try nativeUiRequire(args.count == 3, "showSignal(condition, whenTrue, whenFalse)")
            _ = try nativeUiSignalFields(args[0], "showSignal")
            try nativeUiEnsurePortable(args[1], "NativeUiShow.whenTrue")
            try nativeUiEnsurePortable(args[2], "NativeUiShow.whenFalse")
            return try nativeUiData("NativeUiShow", args)
        }
        native("fragment") { args in
            try nativeUiRequire(args.count == 1, "fragment(children)")
            _ = try nativeUiList(args[0], "fragment children")
            try nativeUiEnsurePortable(args[0], "NativeUiFragment.children")
            return try nativeUiData("NativeUiFragment", [args[0]])
        }
        site("element") { siteId, source, args in
            try nativeUiRequire(args.count == 4, "element(tag, attrs, events, children)")
            let tag = try nativeUiString(args[0], "element tag")
            let attrs = try nativeUiStringMap(args[1], "NativeUiElement.attrs")
            let events = try nativeUiStringMap(args[2], "NativeUiElement.events")
            let children = args[3]
            _ = try nativeUiList(children, "element children")
            try nativeUiEnsurePortable(children, "NativeUiElement.children")
            if let raw = attrs.get(.string("data-ssc-raw-html")) {
                if tag == "span", attrs.entries.count == 2,
                   nativeUiEqual(attrs.get(.string("style")) ?? .unit, .string("display:contents")),
                   events.entries.isEmpty, try nativeUiList(children, "rawHtml children").isEmpty,
                   case let .string(html) = raw {
                    return try nativeUiData("NativeUiTrustedHtml", [.string(siteId), .string(html)])
                }
                return try nativeUiData("NativeUiUnsupported", [.string("rawHtml sentinel"), source, .string("malformed data-ssc-raw-html element")])
            }
            return try nativeUiData("NativeUiElement", [.string(siteId), .string(tag), .map(attrs), .map(events), children])
        }
        site("forKeyedView") { siteId, _, args in
            try nativeUiRequire(args.count == 3, "forKeyedView(items, key, render)")
            _ = try nativeUiSignalFields(args[0], "forKeyedView items")
            _ = try nativeUiClosure(args[1], "forKeyedView key")
            _ = try nativeUiClosure(args[2], "forKeyedView render")
            return try nativeUiData("NativeUiForKeyed", [.string(siteId), args[0], args[1], args[2]])
        }

        native("setSignal") { args in try nativeUiRequire(args.count == 2, "setSignal(signal, value)"); return try nativeUiEvent("set", args[0], args[1]) }
        native("inputChange") { args in try nativeUiRequire(args.count == 1, "inputChange(signal)"); return try nativeUiEvent("input", args[0], .unit) }
        native("toggleSignal") { args in try nativeUiRequire(args.count == 1, "toggleSignal(signal)"); return try nativeUiEvent("toggle", args[0], .unit) }
        native("incSignal") { args in try nativeUiRequire(args.count == 1, "incSignal(signal)"); return try nativeUiEvent("increment", args[0], .int(1)) }

        native("onBumpTick") { args in try nativeUiRequire(args.count == 1, "onBumpTick(signal)"); return try nativeUiSuccess("bumpTick", args[0], .unit) }
        native("onSetSignal") { args in try nativeUiRequire(args.count == 2, "onSetSignal(signal, value)"); return try nativeUiSuccess("setSignal", args[0], args[1]) }
        native("onNavigate") { args in try nativeUiRequire(args.count == 1, "onNavigate(path)"); return try nativeUiSuccess("navigate", .unit, .string(try nativeUiString(args[0], "onNavigate path"))) }
        native("onOpenJson") { args in
            try nativeUiRequire(args.count == 2, "onOpenJson(template, field)")
            return try nativeUiSuccess("openJson", .string(try nativeUiString(args[0], "onOpenJson template")), .string(try nativeUiString(args[1], "onOpenJson field")))
        }
        native("formBody") { args in try nativeUiRequire(args.count == 1, "formBody(fields)"); try nativeUiEnsurePortable(args[0], "NativeUiFormBody.fields"); return try nativeUiData("NativeUiFormBody", args) }

        func tickEffects(_ tick: SscValue) throws -> SscValue {
            _ = try nativeUiSignalFields(tick, "fetch success tick")
            return try nativeUiListValue([try nativeUiSuccess("bumpTick", tick, .unit)])
        }
        func request(_ method: String, _ url: SscValue, _ body: SscValue, _ headers: SscValue) throws -> SscValue {
            _ = try nativeUiSignalFields(headers, "fetch headers")
            try [url, body, headers].enumerated().forEach { try nativeUiEnsurePortable($0.element, "NativeUiFetchRequest[\($0.offset)]") }
            return try nativeUiData("NativeUiFetchRequest", [.string(method), url, body, headers])
        }
        func action(_ siteId: String, _ method: String, _ url: SscValue, _ body: SscValue, _ effects: SscValue, _ headers: SscValue, _ capture: SscValue = .unit, _ clear: SscValue = .unit) throws -> SscValue {
            return try nativeUiData("NativeUiFetchAction", [
                .string(siteId), try request(method, url, body, headers), effects, capture,
                nativeUiMap(("clearTarget", clear))])
        }
        site("fetchAction") { siteId, _, args in
            try nativeUiRequire(args.count == 4 || args.count == 5, "fetchAction(method, url, body, tick[, headers])")
            return try action(siteId, try nativeUiString(args[0], "fetch method"), .string(try nativeUiString(args[1], "fetch URL")), args[2], try tickEffects(args[3]), args.count == 5 ? args[4] : self.emptyHeaders)
        }
        site("fetchActionTo") { siteId, _, args in
            try nativeUiRequire(args.count == 4 || args.count == 5, "fetchActionTo(method, urlSignal, body, tick[, headers])")
            _ = try nativeUiSignalFields(args[1], "fetchActionTo URL signal")
            return try action(siteId, try nativeUiString(args[0], "fetch method"), args[1], args[2], try tickEffects(args[3]), args.count == 5 ? args[4] : self.emptyHeaders)
        }
        site("fetchActionClear") { siteId, _, args in
            try nativeUiRequire(args.count == 4 || args.count == 5, "fetchActionClear(method, url, body, tick[, headers])")
            return try action(siteId, try nativeUiString(args[0], "fetch method"), .string(try nativeUiString(args[1], "fetch URL")), args[2], try tickEffects(args[3]), args.count == 5 ? args[4] : self.emptyHeaders, .unit, args[2])
        }
        site("fetchCaptureAction") { siteId, _, args in
            try nativeUiRequire(args.count == 5 || args.count == 6, "fetchCaptureAction(method, url, body, into, tick[, headers])")
            return try action(siteId, try nativeUiString(args[0], "fetch method"), .string(try nativeUiString(args[1], "fetch URL")), args[2], try tickEffects(args[4]), args.count == 6 ? args[5] : self.emptyHeaders, args[3])
        }
        site("fetchActionWith") { siteId, _, args in
            try nativeUiRequire(args.count == 4 || args.count == 5, "fetchActionWith(method, url, body, effects[, headers])")
            return try action(siteId, try nativeUiString(args[0], "fetch method"), .string(try nativeUiString(args[1], "fetch URL")), args[2], args[3], args.count == 5 ? args[4] : self.emptyHeaders)
        }

        native("staticRowsSource") { args in
            try nativeUiRequire(args.count == 1, "staticRowsSource(rows)")
            let rows = try nativeUiList(args[0], "NativeUiTableSource.rows")
            let checked: [SscValue] = try rows.enumerated().map {
                SscValue.map(try nativeUiStringMap($0.element, "NativeUiTableSource.rows[\($0.offset)]"))
            }
            return try nativeUiData("NativeUiTableSource", [.string("static"), try nativeUiListValue(checked), .string("")])
        }
        native("signalRowsSource") { args in try nativeUiRequire(args.count == 1, "signalRowsSource(signal)"); _ = try nativeUiSignalFields(args[0], "signalRowsSource"); return try nativeUiData("NativeUiTableSource", [.string("signal"), args[0], .string("")]) }
        native("fetchRowsSource") { args in try nativeUiRequire(args.count == 2, "fetchRowsSource(signal, rowsPath)"); _ = try nativeUiSignalFields(args[0], "fetchRowsSource"); return try nativeUiData("NativeUiTableSource", [.string("fetch"), args[0], .string(try nativeUiString(args[1], "fetchRowsSource rowsPath"))]) }
        native("fieldPayload") { args in try nativeUiRequire(args.count == 1, "fieldPayload(name)"); return try nativeUiData("NativeUiRowPayload", [.string("field"), try nativeUiListValue([.string(try nativeUiString(args[0], "fieldPayload name"))])]) }
        native("wholeRowPayload") { args in try nativeUiRequire(args.isEmpty, "wholeRowPayload()"); return try nativeUiData("NativeUiRowPayload", [.string("wholeRow"), try nativeUiListValue([])]) }
        native("fieldsPayload") { args in try nativeUiRequire(args.count == 1, "fieldsPayload(names)"); return try nativeUiData("NativeUiRowPayload", [.string("fields"), args[0]]) }

        func column(_ kind: String, _ args: [SscValue], _ options: SscValue) throws -> SscValue {
            return try nativeUiData("NativeUiColumn", [
                .string(kind),
                .string(try nativeUiString(args[0], "\(kind)Column title")),
                .string(try nativeUiString(args[1], "\(kind)Column fieldPath")),
                .string(args.count > 2 ? try nativeUiString(args[2], "\(kind)Column align") : ""),
                options,
            ])
        }
        native("fieldColumn") { args in try nativeUiRequire((2...4).contains(args.count), "fieldColumn"); return try column("text", args, nativeUiMap(("editAction", args.count > 3 ? args[3] : .unit))) }
        native("dateColumn") { args in try nativeUiRequire((2...4).contains(args.count), "dateColumn"); return try column("date", args, nativeUiMap(("format", args.count > 3 ? args[3] : .string("")))) }
        native("moneyColumn") { args in try nativeUiRequire((2...5).contains(args.count), "moneyColumn"); return try column("money", args, nativeUiMap(("currency", args.count > 3 ? args[3] : .string("USD")), ("locale", args.count > 4 ? args[4] : .string("")))) }
        native("statusColumn") { args in try nativeUiRequire((2...4).contains(args.count), "statusColumn"); return try column("status", args, nativeUiMap(("colorMap", args.count > 3 ? args[3] : .unit))) }
        native("linkColumn") { args in try nativeUiRequire((2...4).contains(args.count), "linkColumn"); return try column("link", args, nativeUiMap(("urlTemplate", args.count > 3 ? args[3] : .string("")))) }
        native("stackedColumn") { args in try nativeUiRequire(args.count == 3 || args.count == 4, "stackedColumn"); return try nativeUiData("NativeUiColumn", [.string("stacked"), args[0], args[1], args.count == 4 ? args[3] : .string(""), nativeUiMap(("subFieldPath", args[2]))]) }

        func payload(_ name: SscValue, _ operation: String) throws -> SscValue {
            try nativeUiData("NativeUiRowPayload", [.string("field"), try nativeUiListValue([.string(try nativeUiString(name, operation))])])
        }
        func rowAction(_ kind: String, _ label: SscValue, _ requestValue: SscValue, _ payloadValue: SscValue, _ refresh: SscValue, _ options: SscValue) throws -> SscValue {
            try [requestValue, payloadValue, refresh, options].forEach { try nativeUiEnsurePortable($0, "NativeUiRowAction[\(kind)]") }
            return try nativeUiData("NativeUiRowAction", [.string(kind), label, requestValue, payloadValue, refresh, options])
        }
        native("rowDeleteAction") { args in
            try nativeUiRequire(args.count == 3 || args.count == 4, "rowDeleteAction")
            let body = try payload(args[1], "rowDeleteAction idField"), headers = args.count == 4 ? args[3] : self.emptyHeaders
            return try rowAction("delete", .string("Delete"), try request("POST", .string(try nativeUiString(args[0], "rowDeleteAction URL")), body, headers), body, args[2], nativeUiMap())
        }
        native("rowPostAction") { args in
            try nativeUiRequire(args.count == 5 || args.count == 6, "rowPostAction")
            let label = SscValue.string(try nativeUiString(args[0], "rowPostAction label"))
            let p = try payload(args[3], "rowPostAction bodyField")
            return try rowAction("post", label, try request(try nativeUiString(args[1], "rowPostAction method"), .string(try nativeUiString(args[2], "rowPostAction URL")), .unit, args.count == 6 ? args[5] : self.emptyHeaders), p, args[4], nativeUiMap())
        }
        native("rowLinkAction") { args in
            try nativeUiRequire(args.count == 3, "rowLinkAction")
            _ = try nativeUiSignalFields(args[1], "rowLinkAction signal")
            return try rowAction("link", .string(try nativeUiString(args[0], "rowLinkAction label")), .unit, try payload(args[2], "rowLinkAction fieldPath"), .unit, nativeUiMap(("signal", args[1])))
        }
        native("rowEditAction") { args in
            try nativeUiRequire(args.count == 4 || args.count == 5, "rowEditAction")
            let p = try payload(args[2], "rowEditAction idField")
            return try rowAction("edit", .string("Edit"), try request(try nativeUiString(args[0], "rowEditAction method"), .string(try nativeUiString(args[1], "rowEditAction URL")), .unit, args.count == 5 ? args[4] : self.emptyHeaders), p, args[3], nativeUiMap())
        }
        site("dataTableView") { siteId, _, args in try nativeUiRequire(args.count == 3, "dataTableView"); return try nativeUiData("NativeUiDataTable", [.string(siteId)] + args) }

        native("localStorageGet") { [weak self] args in
            try nativeUiRequire(args.count == 1, "localStorageGet")
            let key = try nativeUiString(args[0], "storage key")
            if let value = self!.storage[key] { return try nativeUiData("Some", [.string(value)]) }
            return try nativeUiData("None", [])
        }
        native("localStorageSet") { [weak self] args in try nativeUiRequire(args.count == 2, "localStorageSet"); self!.storage[try nativeUiString(args[0], "storage key")] = try nativeUiString(args[1], "storage value"); return .unit }
        native("localStorageRemove") { [weak self] args in try nativeUiRequire(args.count == 1, "localStorageRemove"); self!.storage.removeValue(forKey: try nativeUiString(args[0], "storage key")); return .unit }
        native("onlineSignal") { [weak self] args in try nativeUiRequire(args.isEmpty, "onlineSignal()"); return try self!.makeSignal(id: "__online__", kind: "online", declaredDefault: .bool(true), metadata: try nativeUiData("NativeUiSignalMetaOnline", []), writable: false) }
        native("persistedSignal") { [weak self] args in
            try nativeUiRequire(args.count == 2, "persistedSignal(name, default)")
            let name = try nativeUiString(args[0], "persisted name"), fallback = try nativeUiString(args[1], "persisted default")
            let signal = try self!.makeSignal(id: name, kind: "persisted", declaredDefault: .string(self!.storage[name] ?? fallback), metadata: try nativeUiData("NativeUiSignalMetaPersisted", [.string(name)]))
            let fields = try nativeUiSignalFields(signal, "persistedSignal")
            self!.signals[self!.signalKey(scope: try nativeUiString(fields[1], "persisted scope"), id: name)]!.afterWrite = { [weak self] value in self!.storage[name] = try nativeUiString(value, "persisted value") }
            return signal
        }
        native("componentScope") { [weak self] args in
            try nativeUiRequire(args.count == 2, "componentScope(scopeId, bodyThunk)")
            let scope = try nativeUiString(args[0], "component scope")
            let body = try nativeUiClosure(args[1], "component body")
            let previousOwnerPath = self!.currentOwnerPath
            self!.currentOwnerPath = self!.ownerComponentPath(previousOwnerPath, scope)
            self!.currentOwnerScopes.insert(scope)
            self!.scopes.append(scope)
            defer {
                self!.scopes.removeLast()
                self!.currentOwnerPath = previousOwnerPath
            }
            return try self!.call(body, [])
        }

        sourced("emit") { [weak self] source, args in
            try nativeUiRequire(args.count == 2, "emit(tree, outDir)")
            let config = try nativeUiData("NativeUiRootConfig", [.string("emit"), args[1], .int(0), .string("")])
            try self!.registerRoot(args[0], config, source); return .unit
        }
        sourced("serve") { [weak self] source, args in
            try nativeUiRequire(args.count == 2 || args.count == 3, "serve(tree, port[, extraCss])")
            let css = args.count == 3 ? try nativeUiString(args[2], "serve extraCss") : ""
            let tree = css.isEmpty || nativeUiMobileCss(css) ? args[0] : try nativeUiData("NativeUiUnsupported", [.string("root extraCss"), source, .string("only std/ui mobileOverrideCss is supported")])
            let config = try nativeUiData("NativeUiRootConfig", [.string("serve"), .string(""), args[1], .string(css)])
            try self!.registerRoot(tree, config, source); return .unit
        }
        return values
    }

    func apply(_ receiver: SscValue, _ arguments: [SscValue]) throws -> SscValue? {
        guard case .data("NativeUiSignal", _) = receiver, arguments.isEmpty else { return nil }
        return try readSignal(receiver, "NativeUiSignal.apply")
    }

    func method(_ name: String, _ receiver: SscValue, _ arguments: [SscValue]) throws -> SscValue? {
        guard case .data("NativeUiSignal", _) = receiver else { return nil }
        switch name {
        case "apply" where arguments.isEmpty: return try readSignal(receiver, "NativeUiSignal.apply")
        case "get" where arguments.isEmpty: return try readSignal(receiver, "NativeUiSignal.get")
        case "set" where arguments.count == 1: try writeSignal(receiver, arguments[0], "NativeUiSignal.set"); return .unit
        case "update" where arguments.count == 1:
            let fn = try nativeUiClosure(arguments[0], "NativeUiSignal.update")
            try writeSignal(receiver, try call(fn, [try readSignal(receiver, "NativeUiSignal.update")]), "NativeUiSignal.update")
            return .unit
        case "id" where arguments.isEmpty: return try nativeUiSignalFields(receiver, "NativeUiSignal.id")[0]
        default: return nil
        }
    }

    private func call(_ closure: SscClosure, _ arguments: [SscValue]) throws -> SscValue {
        guard let invoke else { throw SscRuntimeFailure(description: "native UI host is not bound to AppCore") }
        return try invoke(closure, arguments)
    }

    private func signalKey(scope: String, id: String) -> String { "\(scope)\u{0}\(id)" }

    private func makeSignal(id: String, kind: String, declaredDefault: SscValue, metadata: SscValue, writable: Bool = true) throws -> SscValue {
        try nativeUiEnsurePortable(declaredDefault, "NativeUiSignal[\(id)].default")
        try nativeUiEnsurePortable(metadata, "NativeUiSignal[\(id)].metadata")
        let scope = scopes.last!, key = signalKey(scope: scope, id: id)
        let cell: NativeUiSignalCell
        if let existing = signals[key] {
            guard existing.kind == kind && nativeUiEqual(existing.declaredDefault, declaredDefault) else {
                throw SscRuntimeFailure(description: "duplicate native UI signal '\(id)' in scope '\(scope)' has conflicting kind/default")
            }
            cell = existing
        } else {
            cell = NativeUiSignalCell(id: id, scope: scope, kind: kind, declaredDefault: declaredDefault, writable: writable)
            cell.dynamicRead = { [weak cell] in cell?.current ?? .unit }
            signals[key] = cell
        }
        scopeSignals[scope, default: []].insert(key)
        if scope != "root" { currentOwnerScopes.insert(scope) }
        let read = SscClosure(arity: 0) { [weak self] _ in
            try self?.onRead?(scope, id)
            defer { self?.onReadEnd?(scope, id) }
            let value = try cell.dynamicRead()
            try nativeUiEnsurePortable(value, "NativeUiSignal[\(id)].read")
            return value
        }
        let write = SscClosure(arity: 1) { [weak self] args in
            guard cell.writable else { throw SscRuntimeFailure(description: "native UI signal '\(id)' is read-only") }
            let next = args[0]
            try nativeUiEnsurePortable(next, "NativeUiSignal[\(id)].write")
            let firstSeedWrite = cell.kind == "seed" && !cell.dirty
            if firstSeedWrite || !nativeUiEqual(cell.current, next) {
                cell.current = next; cell.dirty = true; try cell.afterWrite(next)
                self?.onWrite?(scope, id)
            }
            return .unit
        }
        return try nativeUiData("NativeUiSignal", [.string(id), .string(scope), .string(kind), .closure(read), .closure(write), metadata])
    }

    func readSignal(_ signal: SscValue, _ operation: String) throws -> SscValue {
        let fields = try nativeUiSignalFields(signal, operation)
        return try call(try nativeUiClosure(fields[3], operation), [])
    }

    func writeSignal(_ signal: SscValue, _ value: SscValue, _ operation: String) throws {
        let fields = try nativeUiSignalFields(signal, operation)
        _ = try call(try nativeUiClosure(fields[4], operation), [value])
    }

    func reconcileKeyed(
        parentOwnerPath: String,
        siteId: String,
        items: [SscValue],
        key: SscClosure,
        render: SscClosure
    ) throws -> NativeUiKeyedResult {
        let snapshot = snapshotState()
        onBatchBegin?()
        let previousOwnerPath = currentOwnerPath
        let previousOwnerScopes = currentOwnerScopes
        let previousScopes = scopes
        let previousSiteOccurrences = currentSiteOccurrences
        let base = ownerForSitePath(parentOwnerPath, siteId)
        var seen = Set<String>()
        var nextOwnerScopes: [String: Set<String>] = [:]
        var nextOwnerHints: [String: Set<ObjectIdentifier>] = [:]
        var entries: [NativeUiKeyedEntryValue] = []
        do {
            for item in items {
                let keyValue = try call(key, [item])
                guard case let .string(itemKey) = keyValue else {
                    throw SscRuntimeFailure(description: "NativeUiForKeyed key at site \(siteId) must be String")
                }
                guard seen.insert(itemKey).inserted else {
                    throw SscRuntimeFailure(description: "duplicate NativeUiForKeyed key '\(itemKey)' at site \(siteId)")
                }
                let owner = ownerForKeyPath(base, itemKey)
                currentOwnerPath = owner
                currentOwnerScopes = []
                currentSiteOccurrences = [:]
                scopes = ["root"]
                ownerHintCollectors.append([])
                let rendered: SscValue
                do {
                    rendered = try call(render, [item])
                    nextOwnerHints[owner] = ownerHintCollectors.removeLast()
                } catch {
                    _ = ownerHintCollectors.removeLast()
                    throw error
                }
                try nativeUiEnsurePortable(rendered, "NativeUiForKeyed[\(siteId)][\(itemKey)]")
                nextOwnerScopes[owner] = currentOwnerScopes
                entries.append(NativeUiKeyedEntryValue(
                    id: itemKey,
                    ownerPath: owner,
                    value: rendered
                ))
            }

            let oldOwners = keyedOwners[base, default: []]
            let nextOwners = Set(nextOwnerScopes.keys)
            for removed in oldOwners.subtracting(nextOwners) { removeOwnerSubtree(removed) }
            for (owner, usedScopes) in nextOwnerScopes {
                let retained = nextOwnerHints[owner, default: []]
                let obsolete = forOwnerHints.compactMap { id, hint in
                    (hint.path == owner || hint.path.hasPrefix(owner + "/")) && !retained.contains(id) ? id : nil
                }
                for id in obsolete { forOwnerHints.removeValue(forKey: id) }
                ownerScopes[owner] = usedScopes
            }
            keyedOwners[base] = nextOwners
            let disposed = disposeUnreferencedScopes()
            currentOwnerPath = previousOwnerPath
            currentOwnerScopes = previousOwnerScopes
            currentSiteOccurrences = previousSiteOccurrences
            scopes = previousScopes
            onBatchCommit?()
            return NativeUiKeyedResult(entries: entries, disposedSignalKeys: disposed.sorted())
        } catch {
            restoreState(snapshot)
            onBatchRollback?()
            throw error
        }
    }

    private func snapshotState() -> NativeUiHostSnapshot {
        NativeUiHostSnapshot(
            signals: signals,
            signalStates: signals.mapValues { $0.snapshot() },
            storage: storage,
            scopes: scopes,
            ownerScopes: ownerScopes,
            scopeSignals: scopeSignals,
            keyedOwners: keyedOwners,
            siteSources: siteSources,
            signalSources: signalSources,
            forOwnerHints: forOwnerHints,
            currentSiteOccurrences: currentSiteOccurrences,
            currentOwnerPath: currentOwnerPath,
            currentOwnerScopes: currentOwnerScopes
        )
    }

    private func restoreState(_ snapshot: NativeUiHostSnapshot) {
        for (key, cell) in snapshot.signals {
            if let state = snapshot.signalStates[key] { cell.restore(state) }
        }
        signals = snapshot.signals
        storage = snapshot.storage
        scopes = snapshot.scopes
        ownerScopes = snapshot.ownerScopes
        scopeSignals = snapshot.scopeSignals
        keyedOwners = snapshot.keyedOwners
        siteSources = snapshot.siteSources
        signalSources = snapshot.signalSources
        forOwnerHints = snapshot.forOwnerHints
        currentSiteOccurrences = snapshot.currentSiteOccurrences
        currentOwnerPath = snapshot.currentOwnerPath
        currentOwnerScopes = snapshot.currentOwnerScopes
    }

    private func removeOwnerSubtree(_ rootOwner: String) {
        for owner in Array(ownerScopes.keys) where owner == rootOwner || owner.hasPrefix(rootOwner + "/") {
            ownerScopes.removeValue(forKey: owner)
        }
        for base in Array(keyedOwners.keys) where base.hasPrefix(rootOwner + "/") {
            keyedOwners.removeValue(forKey: base)
        }
        let removedHints = forOwnerHints.compactMap {
            $0.value.path == rootOwner || $0.value.path.hasPrefix(rootOwner + "/") ? $0.key : nil
        }
        for id in removedHints { forOwnerHints.removeValue(forKey: id) }
    }

    private func disposeUnreferencedScopes() -> [String] {
        var referenced = Set<String>()
        for scopes in ownerScopes.values { referenced.formUnion(scopes) }
        let disposedScopes = Set(scopeSignals.keys).subtracting(referenced).subtracting(["root"])
        var disposedSignals: [String] = []
        for scope in disposedScopes {
            for key in scopeSignals.removeValue(forKey: scope) ?? [] {
                signals.removeValue(forKey: key)
                signalSources.removeValue(forKey: key)
                disposedSignals.append(key)
            }
        }
        return disposedSignals
    }

    private func ownerForSitePath(_ parent: String, _ site: String) -> String {
        parent + "/f" + String(site.utf8.count) + ":" + site
    }

    private func ownerForKeyPath(_ base: String, _ key: String) -> String {
        base + "/k" + String(key.utf8.count) + ":" + key
    }

    private func ownerComponentPath(_ parent: String, _ scope: String) -> String {
        parent + "/c" + String(scope.utf8.count) + ":" + scope
    }

    func sourceRef(_ siteId: String) -> SscValue? { siteSources[siteId] }
    func sourceRef(for signal: SscValue) -> SscValue? {
        guard case let .data("NativeUiSignal", fields) = signal, fields.count == 6,
              case let .string(id) = fields[0], case let .string(scope) = fields[1] else { return nil }
        return signalSources[signalKey(scope: scope, id: id)]
    }
    func ownerPath(for node: SscValue) -> String? {
        guard case let .data("NativeUiForKeyed", fields) = node, fields.count == 4,
              let hint = forOwnerHints[ObjectIdentifier(fields)],
              hint.fields === fields else { return nil }
        return hint.path
    }

    func ownerHintCount() -> Int { forOwnerHints.count }

    private func bindForOwner(_ node: SscValue, siteId: String) throws {
        guard case let .data("NativeUiForKeyed", fields) = node, fields.count == 4,
              case .closure = fields[3] else {
            throw SscRuntimeFailure(description: "NativeUiForKeyed constructor returned malformed node")
        }
        let key = currentOwnerPath + "\u{0}" + siteId
        let occurrence = currentSiteOccurrences[key, default: 0]
        currentSiteOccurrences[key] = occurrence + 1
        let id = ObjectIdentifier(fields)
        forOwnerHints[id] = NativeUiForOwnerHint(
            fields: fields,
            path: currentOwnerPath + "/o" + String(siteId.utf8.count) + ":" + siteId + ":" + String(occurrence)
        )
        for index in ownerHintCollectors.indices { ownerHintCollectors[index].insert(id) }
    }

    private func bindSignalSource(_ value: SscValue, source: SscValue) throws {
        guard case let .data("NativeUiSignal", fields) = value, fields.count == 6 else { return }
        let id = try nativeUiString(fields[0], "NativeUiSignal id")
        let scope = try nativeUiString(fields[1], "NativeUiSignal scope")
        signalSources[signalKey(scope: scope, id: id)] = source
    }

    private func registerRoot(_ tree: SscValue, _ config: SscValue, _ source: SscValue) throws {
        try nativeUiRequire(active, "native UI root registration requires begin")
        try nativeUiEnsurePortable(tree, "NativeUiAbi.root")
        if let previous = root {
            abort()
            throw SscRuntimeFailure(description: "native UI program registered multiple roots: \(nativeUiRootRegistration(previous.1, previous.2)) and \(nativeUiRootRegistration(config, source))")
        }
        root = (tree, config, source)
    }
}

private func nativeUiRequire(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw SscRuntimeFailure(description: message) }
}

private func nativeUiString(_ value: SscValue, _ operation: String) throws -> String {
    guard case let .string(result) = value else { throw SscRuntimeFailure(description: "\(operation) must be String") }
    return result
}

private func nativeUiClosure(_ value: SscValue, _ operation: String) throws -> SscClosure {
    guard case let .closure(result) = value else { throw SscRuntimeFailure(description: "\(operation) must be closure") }
    return result
}

private func nativeUiData(_ tag: String, _ fields: [SscValue]) throws -> SscValue {
    for (index, field) in fields.enumerated() {
        try nativeUiEnsurePortable(field, "\(tag)[\(index)]")
    }
    return .data(tag, SscFields(fields))
}

private func nativeUiSource(_ operation: String) throws -> SscValue {
    try nativeUiData("NativeUiSourceRef", [.string("<entry>"), .int(0), .int(0), .string(operation)])
}

private func nativeUiMap(_ entries: (String, SscValue)...) -> SscValue {
    let result = SscMap()
    for (key, value) in entries { result.put(.string(key), value) }
    return .map(result)
}

private func nativeUiStringMap(_ value: SscValue, _ path: String) throws -> SscMap {
    guard case let .map(result) = value else { throw SscRuntimeFailure(description: "\(path) expected Map[String, Value]") }
    for (key, item) in result.entries {
        guard case .string = key else { throw SscRuntimeFailure(description: "\(path) requires String keys") }
        try nativeUiEnsurePortable(item, path)
    }
    return result
}

private func nativeUiListValue(_ values: [SscValue]) throws -> SscValue {
    try values.reversed().reduce(try nativeUiData("Nil", [])) { try nativeUiData("Cons", [$1, $0]) }
}

private func nativeUiList(_ value: SscValue, _ operation: String) throws -> [SscValue] {
    var current = value, result: [SscValue] = []
    while true {
        switch current {
        case let .data("Cons", fields) where fields.count == 2: result.append(fields[0]); current = fields[1]
        case .data("Nil", _): return result
        default: throw SscRuntimeFailure(description: "\(operation) expected a valid List")
        }
    }
}

private func nativeUiSignalFields(_ value: SscValue, _ operation: String) throws -> [SscValue] {
    guard case let .data("NativeUiSignal", fields) = value, fields.count == 6 else {
        throw SscRuntimeFailure(description: "\(operation) argument must be NativeUiSignal")
    }
    return fields.asArray()
}

private func nativeUiEvent(_ kind: String, _ target: SscValue, _ payload: SscValue) throws -> SscValue {
    _ = try nativeUiSignalFields(target, "NativeUiEvent target")
    try [target, payload].forEach { try nativeUiEnsurePortable($0, "NativeUiEvent[\(kind)]") }
    return try nativeUiData("NativeUiEvent", [.string(kind), target, payload, nativeUiMap()])
}

private func nativeUiSuccess(_ kind: String, _ target: SscValue, _ payload: SscValue) throws -> SscValue {
    try [target, payload].forEach { try nativeUiEnsurePortable($0, "NativeUiSuccessEffect[\(kind)]") }
    return try nativeUiData("NativeUiSuccessEffect", [.string(kind), target, payload])
}

private func nativeUiEnsurePortable(_ value: SscValue, _ path: String) throws {
    var seen = Set<ObjectIdentifier>()
    func walk(_ current: SscValue, _ at: String) throws {
        switch current {
        case .unit, .bool, .int, .big, .decimal, .float, .string, .bytes: return
        case let .data(tag, fields): for (index, field) in fields.enumerated() { try walk(field, "\(at).\(tag)[\(index)]") }
        case let .closure(closure):
            let id = ObjectIdentifier(closure); if !seen.insert(id).inserted { return }
            for (index, captured) in closure.environment.enumerated() { try walk(captured, "\(at).<closure-env>[\(index)]") }
        case let .map(map):
            let id = ObjectIdentifier(map); if !seen.insert(id).inserted { return }
            for (index, entry) in map.entries.enumerated() { try walk(entry.0, "\(at).<key:\(index)>"); try walk(entry.1, "\(at)[\(index)]") }
        case .cell, .longCell, .array: throw SscRuntimeFailure(description: "\(at) contains a target-specific mutable value")
        }
    }
    try walk(value, path)
}

private func nativeUiEqual(_ left: SscValue, _ right: SscValue) -> Bool {
    var states: [NativeUiPair: Int] = [:] // 1 active, 2 equal, 3 unequal
    func loop(_ lhs: SscValue, _ rhs: SscValue, _ state: inout [NativeUiPair: Int]) -> Bool {
        switch (lhs, rhs) {
        case (.unit, .unit): return true
        case let (.bool(a), .bool(b)): return a == b
        case let (.int(a), .int(b)): return a == b
        case let (.big(a), .big(b)): return a == b
        case let (.decimal(a), .decimal(b)): return a == b
        case let (.float(a), .float(b)): return a.bitPattern == b.bitPattern
        case let (.string(a), .string(b)): return a == b
        case let (.bytes(a), .bytes(b)): return a == b
        case let (.closure(a), .closure(b)): return a === b
        case let (.data(aTag, aFields), .data(bTag, bFields)):
            guard aTag == bTag, aFields.count == bFields.count else { return false }
            for index in aFields.indices where !loop(aFields[index], bFields[index], &state) { return false }
            return true
        case let (.map(a), .map(b)):
            if a === b { return true }
            guard a.entries.count == b.entries.count else { return false }
            let pair = NativeUiPair(left: ObjectIdentifier(a), right: ObjectIdentifier(b))
            if state[pair] == 1 || state[pair] == 2 { return true }
            if state[pair] == 3 { return false }
            state[pair] = 1
            func match(_ index: Int, _ remaining: [Int], _ candidateState: inout [NativeUiPair: Int]) -> Bool {
                if index == a.entries.count { return true }
                for candidate in remaining {
                    var branch = candidateState
                    if loop(a.entries[index].0, b.entries[candidate].0, &branch) &&
                       loop(a.entries[index].1, b.entries[candidate].1, &branch) &&
                       match(index + 1, remaining.filter { $0 != candidate }, &branch) {
                        candidateState = branch; return true
                    }
                }
                return false
            }
            let result = match(0, Array(b.entries.indices), &state)
            state[pair] = result ? 2 : 3
            return result
        default: return false
        }
    }
    return loop(left, right, &states)
}

private func nativeUiMobileCss(_ css: String) -> Bool {
    let pattern = #"^@media\(max-width:[0-9]+px\)\{body,p,label,span\{font-size:[0-9]+px!important\}h1\{font-size:[0-9]+px!important\}h2\{font-size:[0-9]+px!important\}h3\{font-size:[0-9]+px!important\}h4,h5,h6\{font-size:[0-9]+px!important\}button\{font-size:[0-9]+px!important;padding:[0-9]+px [0-9]+px!important;border-radius:[0-9]+px!important\}input\[type=text\],input\[type=email\],input\[type=password\]\{font-size:[0-9]+px!important;padding:[0-9]+px!important;border-radius:[0-9]+px!important\}\}$"#
    return css.range(of: pattern, options: .regularExpression) != nil
}

private func nativeUiRootRegistration(_ config: SscValue, _ source: SscValue) -> String {
    guard case let .data("NativeUiRootConfig", configFields) = config, configFields.count == 4,
          case let .string(operation) = configFields[0],
          case let .data("NativeUiSourceRef", sourceFields) = source, sourceFields.count == 4,
          case let .string(file) = sourceFields[0], case let .int(line) = sourceFields[1],
          case let .int(column) = sourceFields[2], case let .string(sourceOperation) = sourceFields[3] else {
        return "<malformed root registration>"
    }
    return "\(operation) at \(file):\(line):\(column) [\(sourceOperation)]"
}

func nativeUiDebug(_ value: SscValue) -> String {
    guard case let .data("NativeUiAbi", fields) = value, fields.count == 3,
          case let .int(version) = fields[0],
          case let .data(rootTag, _) = fields[1],
          case let .data("NativeUiRootConfig", config) = fields[2], config.count == 4,
          case let .string(operation) = config[0] else {
        return "invalid NativeUi root"
    }
    return "NativeUiAbi(version=\(version), root=\(rootTag), operation=\(operation))"
}
"""
