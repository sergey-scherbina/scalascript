package ssc.swift

private[swift] object SwiftNativeUiApple:
  val inventoryElementTags: Set[String] = Set(
    "a", "button", "code", "div", "em", "hr", "img", "input", "label", "li",
    "ol", "p", "pre", "span", "strong", "table", "tbody", "td", "th", "thead", "tr", "ul",
  )

  val inventoryCssProperties: Set[String] = Set(
    "align-items", "background", "border", "border-bottom", "border-collapse",
    "border-radius", "border-top", "border-top-color", "box-shadow", "box-sizing",
    "color", "cursor", "display", "flex", "flex-direction", "font-family",
    "font-size", "font-weight", "gap", "height", "inset", "justify-content",
    "margin", "margin-bottom", "margin-left", "margin-right", "margin-top",
    "max-width", "min-width", "opacity", "overflow", "padding", "padding-bottom",
    "padding-left", "padding-right", "padding-top", "position", "text-align",
    "text-decoration", "user-select", "white-space", "width", "z-index",
  )

  def appSource(product: String): String =
    s"""import SwiftUI
       |
       |@main
       |struct ${product}App: App {
       |    @StateObject private var store = NativeUiStore()
       |
       |    var body: some Scene {
       |        WindowGroup {
       |            NativeUiRenderer(store: store, value: store.root)
       |                .frame(minWidth: 320, minHeight: 240)
       |        }
       |    }
       |}
       |""".stripMargin

  val storeSource: String = """import CoreFoundation
import Foundation
import SwiftUI

struct NativeUiSubscriptionToken: Hashable {
    fileprivate let id = UUID()
}

private struct NativeUiPendingWrite: Hashable {
    let scope: String
    let id: String
}

private struct NativeUiTaskIdentity: Equatable {
    let generation: UInt64
    let token: UUID
}

private struct NativeUiPreparedEffect {
    let kind: String
    let target: SscValue
    let payload: SscValue
    let url: URL?
}

@MainActor
final class NativeUiObservableCell: ObservableObject {
    let key: String
    var signal: SscValue
    @Published private(set) var revision: UInt64 = 0
    private weak var store: NativeUiStore?

    init(key: String, signal: SscValue, store: NativeUiStore) {
        self.key = key
        self.signal = signal
        self.store = store
    }

    func read() -> SscValue { store?.read(signal) ?? .unit }
    func write(_ value: SscValue) { store?.write(signal, value) }
    func renderedDiagnostic() -> String? { store?.renderedSignalDiagnostic(signal) }
    fileprivate func changed() { revision &+= 1 }
}

@MainActor
final class NativeUiStore: ObservableObject {
    let root: SscValue
    private let session: NativeUiSession?
    @Published private(set) var failure: String?
    private var cells: [String: NativeUiObservableCell] = [:]
    private var tokens: [UUID: String] = [:]
    private var subscriberCounts: [String: Int] = [:]
    private var currentReader: String?
    private var pendingDependencies = Set<String>()
    private var readStack: [String] = []
    private var dependencies: [String: Set<String>] = [:]
    private var dependents: [String: Set<String>] = [:]
    private var dependencyFetchOwners: [String: Set<String>] = [:]
    private var cachedValues: [String: SscValue] = [:]
    private var pendingBatchWrites: [NativeUiPendingWrite]?
    private var pendingBatchWriteSet = Set<NativeUiPendingWrite>()
    private var pendingSeedReleases = Set<String>()
    private let urlSession: URLSession
    private var fetchTasks: [String: Task<Void, Never>] = [:]
    private var fetchGenerations: [String: UInt64] = [:]
    private var fetchTaskTokens: [String: UUID] = [:]
    private var actionTaskStatusKeys: [String: Set<String>] = [:]
    private var fetchSubscriberCounts: [String: Int] = [:]
    private var tokenFetchKeys: [UUID: String] = [:]
    private var openURLHandler: ((URL) -> Void)?
    private var mutationObserver: ((String, String) -> Void)?

    init(urlSession: URLSession = .shared) {
        self.urlSession = urlSession
        do {
            let built = try SscGeneratedProgram.makeNativeUiRoot()
            session = built
            root = built.root
            built.observe(
                onRead: { [weak self] scope, id in
                    try MainActor.assumeIsolated { try self?.recordRead(scope: scope, id: id) }
                },
                onReadEnd: { [weak self] scope, id in
                    MainActor.assumeIsolated { self?.finishRead(scope: scope, id: id) }
                },
                onWrite: { [weak self] scope, id in
                    MainActor.assumeIsolated { self?.recordWrite(scope: scope, id: id) }
                },
                onBatchBegin: { [weak self] in
                    MainActor.assumeIsolated { self?.beginBatch() }
                },
                onBatchCommit: { [weak self] in
                    MainActor.assumeIsolated { self?.commitBatch() }
                },
                onBatchRollback: { [weak self] in
                    MainActor.assumeIsolated { self?.rollbackBatch() }
                }
            )
        } catch {
            session = nil
            root = .data("NativeUiUnsupported", [
                .string("root evaluation"),
                .data("NativeUiSourceRef", [.string("<entry>"), .int(0), .int(0), .string("makeNativeUiRoot")]),
                .string(String(describing: error)),
            ])
            failure = String(describing: error)
        }
    }

    isolated deinit {
        for task in fetchTasks.values { task.cancel() }
        session?.dispose()
    }

    func cell(for signal: SscValue) -> NativeUiObservableCell {
        guard let key = signalKey(signal) else {
            let invalid = "<invalid-signal>"
            if let existing = cells[invalid] { return existing }
            let created = NativeUiObservableCell(key: invalid, signal: signal, store: self)
            cells[invalid] = created
            return created
        }
        if let existing = cells[key] {
            existing.signal = signal
            return existing
        }
        let created = NativeUiObservableCell(key: key, signal: signal, store: self)
        cells[key] = created
        return created
    }

    func subscribe(_ cell: NativeUiObservableCell) -> NativeUiSubscriptionToken {
        let token = NativeUiSubscriptionToken()
        tokens[token.id] = cell.key
        let previous = subscriberCounts[cell.key, default: 0]
        subscriberCounts[cell.key] = previous + 1
        if previous == 0, isDerived(cell.signal) {
            _ = evaluateDerived(cell.signal, key: cell.key, trackDependencies: true)
        }
        if let fetch = session?.fetchSignal(for: cell.signal), let fetchKey = signalKey(fetch) {
            tokenFetchKeys[token.id] = fetchKey
            acquireFetchSignal(fetch, key: fetchKey)
        }
        return token
    }

    func unsubscribe(_ token: NativeUiSubscriptionToken) {
        if let fetchKey = tokenFetchKeys.removeValue(forKey: token.id) {
            releaseFetchSignal(key: fetchKey)
        }
        guard let key = tokens.removeValue(forKey: token.id) else { return }
        let remaining = max(0, subscriberCounts[key, default: 1] - 1)
        if remaining == 0 {
            subscriberCounts.removeValue(forKey: key)
            releaseDependencies(reader: key)
            cachedValues.removeValue(forKey: key)
        }
        else { subscriberCounts[key] = remaining }
    }

    func read(_ signal: SscValue) -> SscValue {
        guard let session, let key = signalKey(signal) else { return .unit }
        if isDerived(signal) {
            return evaluateDerived(
                signal,
                key: key,
                trackDependencies: subscriberCounts[key, default: 0] > 0
            ).value
        }
        do { return try session.read(signal) }
        catch { failure = String(describing: error); return .unit }
    }

    private func evaluateDerived(
        _ signal: SscValue,
        key: String,
        trackDependencies: Bool
    ) -> (value: SscValue, changed: Bool) {
        guard let session else { return (.unit, false) }
        let previousReader = currentReader
        let previousPending = pendingDependencies
        let previousReadStack = readStack
        currentReader = key
        pendingDependencies = []
        readStack = []
        var succeeded = false
        defer {
            if succeeded && trackDependencies { commitDependencies(reader: key, next: pendingDependencies) }
            currentReader = previousReader
            pendingDependencies = previousPending
            readStack = previousReadStack
        }
        do {
            let value = try session.read(signal)
            let changed = cachedValues[key].map { !session.equal($0, value) } ?? false
            cachedValues[key] = value
            succeeded = true
            return (value, changed)
        } catch {
            failure = String(describing: error)
            return (cachedValues[key] ?? .unit, false)
        }
    }

    func write(_ signal: SscValue, _ value: SscValue) {
        do { try session?.write(signal, value) }
        catch { failure = String(describing: error) }
    }

    @discardableResult
    private func targetTransaction(_ writes: [(SscValue, SscValue)]) -> Bool {
        beginBatch()
        do {
            try session?.targetWrite(writes)
            commitBatch()
            return true
        } catch {
            rollbackBatch()
            failure = String(describing: error)
            return false
        }
    }

    func installOpenURL(_ handler: @escaping (URL) -> Void) { openURLHandler = handler }
    func installMutationObserver(_ observer: @escaping (String, String) -> Void) {
        mutationObserver = observer
    }
    func fetchOwnerCount(for signal: SscValue) -> Int {
        guard let fetch = session?.fetchSignal(for: signal), let key = signalKey(fetch) else { return 0 }
        return fetchSubscriberCounts[key, default: 0]
    }
    func networkMetadataCount() -> Int {
        Set(fetchGenerations.keys).union(fetchTaskTokens.keys).union(fetchTasks.keys)
            .union(actionTaskStatusKeys.keys).count
    }
    func hostSignalCount() -> Int { session?.signalCount() ?? 0 }
    func actionStatusHintCount() -> Int { session?.actionStatusHintCount() ?? 0 }

    func runFetchAction(_ event: SscValue, ownerPath: String, sourceSiteId: String = "") {
        let diagnosticSite: String
        if case let .data("NativeUiFetchAction", candidate) = event,
           candidate.count > 0, case let .string(site) = candidate[0] { diagnosticSite = site }
        else { diagnosticSite = sourceSiteId }
        guard let session,
              case let .data("NativeUiFetchAction", fields) = event, fields.count == 5,
              case let .string(siteId) = fields[0],
              case let .data("NativeUiFetchRequest", requestFields) = fields[1], requestFields.count == 4,
              case let .string(method) = requestFields[0],
              case let .map(status) = fields[4],
              let phase = status.get(.string("phase")),
              let errorSignal = status.get(.string("error")) else {
            report("malformed native fetch action at " + source(diagnosticSite))
            return
        }
        guard session.validActionStatus(event, phase: phase, error: errorSignal) else {
            report("native fetch action status capability mismatch at " + source(diagnosticSite))
            return
        }
        guard let phaseKey = signalKey(phase), let errorKey = signalKey(errorSignal) else {
            report("native fetch action status signals are malformed at " + source(diagnosticSite))
            return
        }
        do { try validateActionSuccess(fields: fields, status: status) }
        catch { report(bounded(String(describing: error)) + " at " + source(siteId)); return }
        let taskKey = "action\u{0}" + ownerPath + "\u{0}" + siteId
        let identity = beginNetworkTask(key: taskKey)
        actionTaskStatusKeys[taskKey] = [phaseKey, errorKey]
        guard targetTransaction([(phase, .string("loading")), (errorSignal, .string(""))]) else {
            finishTask(key: taskKey, identity: identity)
            return
        }
        let request: URLRequest
        do {
            let url = try resolveRequestSource(requestFields[1], operation: "fetch action URL", session: session)
            let body = try resolveRequestSource(requestFields[2], operation: "fetch action body", session: session)
            let headers = try resolveRequestSource(requestFields[3], operation: "fetch action headers", session: session)
            request = try makeRequest(method: method, urlValue: url, bodyValue: body, headersValue: headers)
        } catch {
            finishTask(key: taskKey, identity: identity)
            _ = targetTransaction([
                (errorSignal, .string(bounded("request failed: " + String(describing: error)))),
                (phase, .string("error")),
            ])
            return
        }
        let transport = urlSession
        let task = Task { @MainActor [weak self] in
            do {
                let (data, response) = try await transport.data(for: request)
                guard let self else { return }
                self.completeFetchAction(
                    fields: fields, phase: phase, errorSignal: errorSignal,
                    key: taskKey, identity: identity, data: data, response: response)
            } catch {
                guard let self, self.isCurrentTask(key: taskKey, identity: identity) else { return }
                if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                    self.finishTask(key: taskKey, identity: identity)
                    _ = self.targetTransaction([(errorSignal, .string("")), (phase, .string("idle"))])
                    return
                }
                self.finishTask(key: taskKey, identity: identity)
                _ = self.targetTransaction([
                    (errorSignal, .string(self.bounded("request failed: " + String(describing: error)))),
                    (phase, .string("error")),
                ])
            }
        }
        fetchTasks[taskKey] = task
    }

    func cancelFetchAction(_ event: SscValue, ownerPath: String) {
        guard case let .data("NativeUiFetchAction", fields) = event, fields.count == 5,
              case let .string(siteId) = fields[0] else { return }
        cancelNetworkTask(key: "action\u{0}" + ownerPath + "\u{0}" + siteId)
    }

    func invoke(_ closure: SscClosure, _ arguments: [SscValue]) -> SscValue {
        do { return try session?.invoke(closure, arguments) ?? .unit }
        catch { failure = String(describing: error); return .unit }
    }

    func report(_ message: String) { failure = message }

    func reconcileKeyed(
        parentOwnerPath: String,
        siteId: String,
        items: [SscValue],
        key: SscClosure,
        render: SscClosure
    ) throws -> NativeUiKeyedResult {
        guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
        let result = try session.reconcileKeyed(
            parentOwnerPath: parentOwnerPath,
            siteId: siteId,
            items: items,
            key: key,
            render: render
        )
        disposeSignalKeys(result.disposedSignalKeys)
        for status in result.replacedActionStatuses {
            invalidateActionStatus(phase: status.phase, error: status.error)
        }
        for signal in result.changedFetchSignals { replaceFetchSignal(signal) }
        for owner in result.disposedOwnerPaths { cancelTasks(ownedBy: owner) }
        return result
    }

    func string(_ value: SscValue) -> String {
        switch value {
        case let .string(value): return value
        case let .int(value): return String(value)
        case let .bool(value): return String(value)
        case let .big(value): return value.description
        case let .decimal(value): return value.description
        case let .float(value): return String(value)
        case .unit: return ""
        case let .data(tag, _): return tag
        default: return ""
        }
    }

    func bool(_ value: SscValue) -> Bool {
        if case let .bool(result) = value { return result }
        return false
    }

    func source(_ siteId: String) -> String {
        guard let value = session?.sourceRef(siteId) else { return "<unknown source>" }
        return source(value)
    }

    func source(for signal: SscValue) -> String {
        guard let value = session?.sourceRef(for: signal) else { return "<unknown source>" }
        return source(value)
    }

    func signalKind(_ signal: SscValue) -> String? {
        var visiting = Set<ObjectIdentifier>()
        return signalKind(signal, visiting: &visiting)
    }

    private func signalKind(_ signal: SscValue, visiting: inout Set<ObjectIdentifier>) -> String? {
        guard case let .data("NativeUiSignal", fields) = signal, fields.count == 6,
              case .string = fields[0], case .string = fields[1],
              case let .string(kind) = fields[2],
              case .closure = fields[3], case .closure = fields[4] else { return nil }
        let supported = Set(["mutable", "seed", "computed", "equality", "hash", "fetch", "online", "persisted"])
        guard supported.contains(kind) else { return nil }
        let identity = ObjectIdentifier(fields)
        guard visiting.insert(identity).inserted else { return kind }
        defer { visiting.remove(identity) }
        return validSignalMetadata(kind: kind, value: fields[5], visiting: &visiting) ? kind : nil
    }

    private func validSignalMetadata(
        kind: String,
        value: SscValue,
        visiting: inout Set<ObjectIdentifier>
    ) -> Bool {
        switch (kind, value) {
        case ("mutable", let .map(metadata)):
            return metadata.entries.allSatisfy { if case .string = $0.0 { true } else { false } }
        case ("seed", let .data("NativeUiSignalMetaSeed", fields)):
            return fields.count == 1 && signalKind(fields[0], visiting: &visiting) != nil
        case ("computed", let .data("NativeUiSignalMetaComputed", fields)):
            return fields.count == 1 && { if case .closure = fields[0] { true } else { false } }()
        case ("equality", let .data("NativeUiSignalMetaEquality", fields)):
            return fields.count == 2 && signalKind(fields[0], visiting: &visiting) != nil
        case ("hash", let .data("NativeUiSignalMetaHash", fields)):
            return fields.isEmpty
        case ("fetch", let .data("NativeUiSignalMetaFetch", fields)):
            guard fields.count == 5 else { return false }
            let urlValid: Bool
            if case .string = fields[0] { urlValid = true }
            else { urlValid = signalKind(fields[0], visiting: &visiting) != nil }
            guard urlValid else { return false }
            for index in 1..<5 where signalKind(fields[index], visiting: &visiting) == nil { return false }
            return true
        case ("online", let .data("NativeUiSignalMetaOnline", fields)):
            return fields.isEmpty
        case ("persisted", let .data("NativeUiSignalMetaPersisted", fields)):
            return fields.count == 1 && { if case .string = fields[0] { true } else { false } }()
        default: return false
        }
    }

    func renderedSignalDiagnostic(_ signal: SscValue) -> String? {
        nil
    }

    func ownerPath(for node: SscValue, fallback: String) -> String {
        session?.ownerPath(for: node) ?? fallback
    }

    func actionOwnerPath(for event: SscValue, mountedAt ownerPath: String) -> String {
        guard let construction = session?.ownerPath(for: event),
              let suffix = construction.range(of: "/o", options: .backwards) else {
            return ownerPath
        }
        return ownerPath + construction[suffix.lowerBound...]
    }

    func source(_ value: SscValue) -> String {
        guard case let .data("NativeUiSourceRef", fields) = value, fields.count == 4,
              case let .string(file) = fields[0], case let .int(line) = fields[1],
              case let .int(column) = fields[2], case let .string(operation) = fields[3] else {
            return "<unknown source>"
        }
        return "\(file):\(line):\(column) [\(operation)]"
    }

    private func signalKey(_ signal: SscValue) -> String? {
        guard case let .data("NativeUiSignal", fields) = signal, fields.count == 6,
              case let .string(id) = fields[0], case let .string(scope) = fields[1] else { return nil }
        return scope + "\u{0}" + id
    }

    private func recordRead(scope: String, id: String) throws {
        guard let reader = currentReader else { return }
        let source = scope + "\u{0}" + id
        if let cycleStart = readStack.firstIndex(of: source) {
            let cycle = (Array(readStack[cycleStart...]) + [source]).map(signalDisplay).joined(separator: " -> ")
            throw SscRuntimeFailure(description: "native UI signal dependency cycle: " + cycle)
        }
        if source != reader { pendingDependencies.insert(source) }
        readStack.append(source)
    }

    private func finishRead(scope: String, id: String) {
        let source = scope + "\u{0}" + id
        if readStack.last == source { readStack.removeLast() }
    }

    private func commitDependencies(reader: String, next: Set<String>) {
        let old = dependencies[reader, default: []]
        for source in old.subtracting(next) { dependents[source]?.remove(reader) }
        for source in next.subtracting(old) { dependents[source, default: []].insert(reader) }
        dependencies[reader] = next
        let previousFetches = dependencyFetchOwners[reader, default: []]
        let nextFetches = Set(next.compactMap(fetchSignalForKey).compactMap(signalKey))
        if nextFetches.isEmpty { dependencyFetchOwners.removeValue(forKey: reader) }
        else { dependencyFetchOwners[reader] = nextFetches }
        for key in previousFetches.subtracting(nextFetches) { releaseFetchSignal(key: key) }
        for key in nextFetches.subtracting(previousFetches) {
            if let signal = fetchSignalForKey(key) { acquireFetchSignal(signal, key: key) }
        }
    }

    private func releaseDependencies(reader: String) {
        let old = dependencies.removeValue(forKey: reader) ?? []
        for source in old {
            dependents[source]?.remove(reader)
            if dependents[source]?.isEmpty == true { dependents.removeValue(forKey: source) }
        }
        for key in dependencyFetchOwners.removeValue(forKey: reader) ?? [] {
            releaseFetchSignal(key: key)
        }
    }

    private func disposeSignalKeys(_ keys: [String]) {
        let removed = Set(keys)
        let invalidActions = actionTaskStatusKeys.compactMap { taskKey, statusKeys in
            statusKeys.isDisjoint(with: removed) ? nil : taskKey
        }
        for taskKey in invalidActions { cancelNetworkTask(key: taskKey) }
        for key in removed {
            releaseDependencies(reader: key)
            cachedValues.removeValue(forKey: key)
            cells.removeValue(forKey: key)
            subscriberCounts.removeValue(forKey: key)
        }
        let removedTokens = tokens.compactMap { removed.contains($0.value) ? $0.key : nil }
        for id in removedTokens {
            tokens.removeValue(forKey: id)
            tokenFetchKeys.removeValue(forKey: id)
        }
        for key in removed where fetchSubscriberCounts.removeValue(forKey: key) != nil {
            cancelNetworkTask(key: key)
        }
        for key in removed { dependents.removeValue(forKey: key) }
        for reader in Array(dependencies.keys) {
            dependencies[reader]?.subtract(removed)
            if dependencies[reader]?.isEmpty == true { dependencies.removeValue(forKey: reader) }
        }
        for reader in Array(dependencyFetchOwners.keys) {
            dependencyFetchOwners[reader]?.subtract(removed)
            if dependencyFetchOwners[reader]?.isEmpty == true { dependencyFetchOwners.removeValue(forKey: reader) }
        }
        for source in Array(dependents.keys) {
            dependents[source]?.subtract(removed)
            if dependents[source]?.isEmpty == true { dependents.removeValue(forKey: source) }
        }
    }

    private func invalidateActionStatus(phase: SscValue, error: SscValue) {
        guard let phaseKey = signalKey(phase), let errorKey = signalKey(error) else { return }
        let statusKeys: Set<String> = [phaseKey, errorKey]
        let invalidActions = actionTaskStatusKeys.compactMap { taskKey, ownedStatusKeys in
            ownedStatusKeys.isDisjoint(with: statusKeys) ? nil : taskKey
        }
        for taskKey in invalidActions { cancelNetworkTask(key: taskKey) }
        _ = targetTransaction([(error, .string("")), (phase, .string("idle"))])
    }

    private func replaceFetchSignal(_ signal: SscValue) {
        guard let key = signalKey(signal) else { return }
        cells[key]?.signal = signal
        guard fetchSubscriberCounts[key, default: 0] > 0 else { return }
        startFetchSignal(signal, key: key)
    }

    private func publish(scope: String, id: String) {
        let key = scope + "\u{0}" + id
        var processed: Set<String> = [key]
        cells[key]?.changed()
        publishDependents(of: key, processed: &processed)
    }

    private func recordWrite(scope: String, id: String) {
        mutationObserver?(scope, id)
        let key = scope + "\u{0}" + id
        if signalKind(cells[key]?.signal ?? .unit) == "seed" {
            if pendingBatchWrites != nil { pendingSeedReleases.insert(key) }
            else { releaseDependencies(reader: key) }
        }
        let write = NativeUiPendingWrite(scope: scope, id: id)
        if pendingBatchWrites != nil {
            if pendingBatchWriteSet.insert(write).inserted { pendingBatchWrites?.append(write) }
        } else {
            publish(scope: scope, id: id)
        }
    }

    private func beginBatch() {
        precondition(pendingBatchWrites == nil, "nested NativeUi keyed batch")
        pendingBatchWrites = []
        pendingBatchWriteSet.removeAll(keepingCapacity: true)
        pendingSeedReleases.removeAll(keepingCapacity: true)
    }

    private func commitBatch() {
        let writes = pendingBatchWrites ?? []
        let seedReleases = pendingSeedReleases
        pendingBatchWrites = nil
        pendingBatchWriteSet.removeAll(keepingCapacity: true)
        pendingSeedReleases.removeAll(keepingCapacity: true)
        for key in seedReleases { releaseDependencies(reader: key) }
        for write in writes { publish(scope: write.scope, id: write.id) }
    }

    private func rollbackBatch() {
        pendingBatchWrites = nil
        pendingBatchWriteSet.removeAll(keepingCapacity: true)
        pendingSeedReleases.removeAll(keepingCapacity: true)
    }

    private func publishDependents(of key: String, processed: inout Set<String>) {
        for dependent in Array(dependents[key, default: []]) {
            guard processed.insert(dependent).inserted,
                  let cell = cells[dependent] else { continue }
            let active = signalKind(cell.signal) == "fetch"
                ? fetchSubscriberCounts[dependent, default: 0] > 0
                : subscriberCounts[dependent, default: 0] > 0
            guard active else { continue }
            if signalKind(cell.signal) == "fetch" {
                startFetchSignal(cell.signal, key: dependent)
                continue
            }
            let refreshed = evaluateDerived(cell.signal, key: dependent, trackDependencies: true)
            if refreshed.changed {
                cell.changed()
                publishDependents(of: dependent, processed: &processed)
            }
        }
    }

    private func isDerived(_ signal: SscValue) -> Bool {
        guard case let .data("NativeUiSignal", fields) = signal, fields.count == 6,
              case let .string(kind) = fields[2] else { return false }
        return kind == "computed" || kind == "equality" || kind == "seed"
    }

    private func signalDisplay(_ key: String) -> String {
        key.split(separator: "\u{0}", omittingEmptySubsequences: false).last.map(String.init) ?? key
    }

    private func acquireFetchSignal(_ signal: SscValue, key: String) {
        let previous = fetchSubscriberCounts[key, default: 0]
        fetchSubscriberCounts[key] = previous + 1
        if previous == 0 {
            _ = cell(for: signal)
            startFetchSignal(signal, key: key)
        }
    }

    private func releaseFetchSignal(key: String) {
        let remaining = max(0, fetchSubscriberCounts[key, default: 1] - 1)
        if remaining == 0 {
            fetchSubscriberCounts.removeValue(forKey: key)
            cancelNetworkTask(key: key)
        } else {
            fetchSubscriberCounts[key] = remaining
        }
    }

    private func beginNetworkTask(key: String) -> NativeUiTaskIdentity {
        fetchTasks.removeValue(forKey: key)?.cancel()
        actionTaskStatusKeys.removeValue(forKey: key)
        let generation = fetchGenerations[key, default: 0] &+ 1
        let token = UUID()
        fetchGenerations[key] = generation
        fetchTaskTokens[key] = token
        return NativeUiTaskIdentity(generation: generation, token: token)
    }

    private func cancelNetworkTask(key: String) {
        fetchTasks.removeValue(forKey: key)?.cancel()
        fetchGenerations.removeValue(forKey: key)
        fetchTaskTokens.removeValue(forKey: key)
        actionTaskStatusKeys.removeValue(forKey: key)
    }

    private func cancelTasks(ownedBy owner: String) {
        let marker = "\u{0}" + owner
        let ownedKeys = Set(fetchTasks.keys).union(fetchGenerations.keys)
        for key in ownedKeys where key.hasPrefix("action\u{0}") &&
            (key.contains(marker + "\u{0}") || key.contains(marker + "/")) {
            cancelNetworkTask(key: key)
        }
    }

    private func isCurrentTask(key: String, identity: NativeUiTaskIdentity) -> Bool {
        fetchGenerations[key] == identity.generation && fetchTaskTokens[key] == identity.token
    }

    private func finishTask(key: String, identity: NativeUiTaskIdentity) {
        guard isCurrentTask(key: key, identity: identity) else { return }
        fetchTasks.removeValue(forKey: key)
        fetchGenerations.removeValue(forKey: key)
        fetchTaskTokens.removeValue(forKey: key)
        actionTaskStatusKeys.removeValue(forKey: key)
    }

    private func startFetchSignal(_ signal: SscValue, key: String) {
        guard fetchSubscriberCounts[key, default: 0] > 0,
              case let .data("NativeUiSignal", signalFields) = signal,
              signalFields.count == 6,
              case let .data("NativeUiSignalMetaFetch", metadata) = signalFields[5],
              metadata.count == 5 else { return }
        let phase = metadata[3], errorSignal = metadata[4]
        let identity = beginNetworkTask(key: key)
        guard targetTransaction([(phase, .string("loading")), (errorSignal, .string(""))]) else {
            finishTask(key: key, identity: identity)
            return
        }
        let request: URLRequest
        do {
            request = try captureFetchRequest(metadata: metadata, reader: key)
        } catch {
            finishTask(key: key, identity: identity)
            _ = targetTransaction([
                (errorSignal, .string(bounded("request failed: " + String(describing: error)))),
                (phase, .string("error")),
            ])
            return
        }
        let transport = urlSession
        let task = Task { @MainActor [weak self] in
            do {
                let (data, response) = try await transport.data(for: request)
                guard let self else { return }
                self.completeFetchSignal(
                    signal, phase: phase, errorSignal: errorSignal, key: key,
                    identity: identity, data: data, response: response)
            } catch {
                guard let self, self.isCurrentTask(key: key, identity: identity) else { return }
                if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                    self.finishTask(key: key, identity: identity)
                    guard self.fetchSubscriberCounts[key, default: 0] > 0 else { return }
                    _ = self.targetTransaction([(errorSignal, .string("")), (phase, .string("idle"))])
                    return
                }
                self.finishTask(key: key, identity: identity)
                guard self.fetchSubscriberCounts[key, default: 0] > 0 else { return }
                _ = self.targetTransaction([
                    (errorSignal, .string(self.bounded("request failed: " + String(describing: error)))),
                    (phase, .string("error")),
                ])
            }
        }
        fetchTasks[key] = task
    }

    private func completeFetchSignal(
        _ signal: SscValue,
        phase: SscValue,
        errorSignal: SscValue,
        key: String,
        identity: NativeUiTaskIdentity,
        data: Data,
        response: URLResponse
    ) {
        guard isCurrentTask(key: key, identity: identity),
              fetchSubscriberCounts[key, default: 0] > 0 else { return }
        finishTask(key: key, identity: identity)
        guard let http = response as? HTTPURLResponse else {
            _ = targetTransaction([
                (errorSignal, .string("request failed: response was not HTTP")),
                (phase, .string("error")),
            ])
            return
        }
        guard let body = String(data: data, encoding: .utf8) else {
            _ = targetTransaction([
                (errorSignal, .string("request failed: response body is not valid UTF-8")),
                (phase, .string("error")),
            ])
            return
        }
        if (200..<300).contains(http.statusCode) {
            _ = targetTransaction([
                (signal, .string(body)), (errorSignal, .string("")), (phase, .string("done")),
            ])
        } else {
            _ = targetTransaction([
                (errorSignal, .string(bounded("HTTP \(http.statusCode): " + body))),
                (phase, .string("error")),
            ])
        }
    }

    private func completeFetchAction(
        fields: SscFields,
        phase: SscValue,
        errorSignal: SscValue,
        key: String,
        identity: NativeUiTaskIdentity,
        data: Data,
        response: URLResponse
    ) {
        guard isCurrentTask(key: key, identity: identity) else { return }
        finishTask(key: key, identity: identity)
        guard let http = response as? HTTPURLResponse else {
            failAction(phase: phase, errorSignal: errorSignal, message: "request failed: response was not HTTP")
            return
        }
        guard let body = String(data: data, encoding: .utf8) else {
            failAction(phase: phase, errorSignal: errorSignal, message: "request failed: response body is not valid UTF-8")
            return
        }
        guard (200..<300).contains(http.statusCode) else {
            failAction(phase: phase, errorSignal: errorSignal, message: "HTTP \(http.statusCode): " + body)
            return
        }
        do {
            guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
            let capture = fields[3]
            guard unitOrWritableSignal(capture) else {
                throw SscRuntimeFailure(description: "native fetch capture target must be writable NativeUiSignal or Unit")
            }
            guard case let .map(status) = fields[4] else {
                throw SscRuntimeFailure(description: "native fetch action status must be Map")
            }
            let clear = status.get(.string("clearTarget")) ?? .unit
            guard unitOrWritableSignal(clear) else {
                throw SscRuntimeFailure(description: "native fetch clear target must be writable NativeUiSignal or Unit")
            }
            var preceding: [(SscValue, SscValue)] = []
            if case .unit = capture {} else { preceding.append((capture, .string(body))) }
            if case .unit = clear {} else { preceding.append((clear, .string(""))) }
            let effects = try prepareEffects(fields[2], responseBody: body, preceding: preceding)
            if case .unit = capture {} else { try session.write(capture, .string(body)) }
            if case .unit = clear {} else { try session.write(clear, .string("")) }
            guard targetTransaction([(errorSignal, .string("")), (phase, .string("done"))]) else { return }
            for effect in effects { try apply(effect) }
        } catch {
            failAction(
                phase: phase, errorSignal: errorSignal,
                message: "request failed: " + String(describing: error))
        }
    }

    private func failAction(phase: SscValue, errorSignal: SscValue, message: String) {
        _ = targetTransaction([
            (errorSignal, .string(bounded(message))), (phase, .string("error")),
        ])
    }

    private func writableSignal(_ value: SscValue) -> Bool {
        guard session?.isWritable(value) == true, let kind = signalKind(value) else { return false }
        return ["mutable", "seed", "hash", "persisted"].contains(kind)
    }

    private func unitOrWritableSignal(_ value: SscValue) -> Bool {
        if case .unit = value { return true }
        return writableSignal(value)
    }

    private func validateActionSuccess(fields: SscFields, status: SscMap) throws {
        guard unitOrWritableSignal(fields[3]) else {
            throw SscRuntimeFailure(description: "native fetch capture target must be writable NativeUiSignal or Unit")
        }
        let clear = status.get(.string("clearTarget")) ?? .unit
        guard unitOrWritableSignal(clear) else {
            throw SscRuntimeFailure(description: "native fetch clear target must be writable NativeUiSignal or Unit")
        }
        var predicted: [String: SscValue] = [:]
        func predict(_ signal: SscValue, _ value: SscValue) {
            if let key = signalKey(signal) { predicted[key] = value }
        }
        if case .unit = fields[3] {} else { predict(fields[3], .string("<response>")) }
        if case .unit = clear {} else { predict(clear, .string("")) }
        for effect in try list(fields[2], operation: "NativeUiFetchAction.onSuccess") {
            guard case let .data("NativeUiSuccessEffect", effectFields) = effect,
                  effectFields.count == 3, case let .string(kind) = effectFields[0] else {
                throw SscRuntimeFailure(description: "native fetch success effect is malformed")
            }
            switch kind {
            case "bumpTick":
                let current = signalKey(effectFields[1]).flatMap { predicted[$0] } ?? read(effectFields[1])
                guard writableSignal(effectFields[1]), case .unit = effectFields[2],
                      case let .int(value) = current, value < Int64.max else {
                    throw SscRuntimeFailure(description: "bumpTick requires writable non-overflowing Int signal and Unit payload")
                }
                predict(effectFields[1], .int(value + 1))
            case "setSignal":
                guard writableSignal(effectFields[1]),
                      signalKind(effectFields[1]) != "persisted" || {
                          if case .string = effectFields[2] { true } else { false }
                      }() else {
                    throw SscRuntimeFailure(description: "setSignal requires writable NativeUiSignal")
                }
                predict(effectFields[1], effectFields[2])
            case "navigate":
                guard case .unit = effectFields[1], case let .string(path) = effectFields[2],
                      let url = URL(string: path), let scheme = url.scheme?.lowercased(),
                      ["http", "https", "mailto"].contains(scheme), openURLHandler != nil else {
                    throw SscRuntimeFailure(description: "navigate requires an absolute http/https/mailto URL and SwiftUI openURL environment")
                }
            case "openJson":
                guard case let .string(template) = effectFields[1], !template.isEmpty,
                      case let .string(field) = effectFields[2], !field.isEmpty,
                      openURLHandler != nil else {
                    throw SscRuntimeFailure(description: "openJson requires template, field, and SwiftUI openURL environment")
                }
            default:
                throw SscRuntimeFailure(description: "unsupported native fetch success effect " + kind)
            }
        }
    }

    private func prepareEffects(
        _ value: SscValue,
        responseBody: String,
        preceding: [(SscValue, SscValue)]
    ) throws -> [NativeUiPreparedEffect] {
        var result: [NativeUiPreparedEffect] = []
        var predicted: [String: SscValue] = [:]
        func predict(_ signal: SscValue, _ value: SscValue) {
            if let key = signalKey(signal) { predicted[key] = value }
        }
        for (signal, value) in preceding { predict(signal, value) }
        for effect in try list(value, operation: "NativeUiFetchAction.onSuccess") {
            guard case let .data("NativeUiSuccessEffect", fields) = effect, fields.count == 3,
                  case let .string(kind) = fields[0] else {
                throw SscRuntimeFailure(description: "native fetch success effect is malformed")
            }
            switch kind {
            case "bumpTick":
                let current = signalKey(fields[1]).flatMap { predicted[$0] } ?? read(fields[1])
                guard writableSignal(fields[1]), case .unit = fields[2],
                      case let .int(value) = current, value < Int64.max else {
                    throw SscRuntimeFailure(description: "bumpTick requires writable Int signal and Unit payload")
                }
                let next = SscValue.int(value + 1)
                predict(fields[1], next)
                result.append(NativeUiPreparedEffect(kind: kind, target: fields[1], payload: next, url: nil))
            case "setSignal":
                guard writableSignal(fields[1]),
                      signalKind(fields[1]) != "persisted" || {
                          if case .string = fields[2] { true } else { false }
                      }() else {
                    throw SscRuntimeFailure(description: "setSignal requires writable NativeUiSignal")
                }
                predict(fields[1], fields[2])
                result.append(NativeUiPreparedEffect(kind: kind, target: fields[1], payload: fields[2], url: nil))
            case "navigate":
                guard case .unit = fields[1], case let .string(path) = fields[2],
                      let url = URL(string: path), let scheme = url.scheme?.lowercased(),
                      ["http", "https", "mailto"].contains(scheme), openURLHandler != nil else {
                    throw SscRuntimeFailure(description: "navigate requires an absolute http/https/mailto URL and SwiftUI openURL environment")
                }
                result.append(NativeUiPreparedEffect(kind: kind, target: fields[1], payload: fields[2], url: url))
            case "openJson":
                guard case let .string(template) = fields[1], case let .string(field) = fields[2],
                      let data = responseBody.data(using: .utf8),
                      let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let raw = object[field] else {
                    throw SscRuntimeFailure(description: "openJson requires a JSON object containing the declared field")
                }
                let replacement: String
                if let value = raw as? String { replacement = value }
                else if let value = raw as? NSNumber,
                        CFGetTypeID(value) != CFBooleanGetTypeID() { replacement = value.stringValue }
                else { throw SscRuntimeFailure(description: "openJson field must be String or number") }
                var componentAllowed = CharacterSet.alphanumerics
                componentAllowed.insert(charactersIn: "-_.!~*'()")
                guard let encoded = replacement.addingPercentEncoding(withAllowedCharacters: componentAllowed) else {
                    throw SscRuntimeFailure(description: "openJson field could not be URL-encoded")
                }
                let destination = template.replacingOccurrences(of: ":value", with: encoded)
                guard let url = URL(string: destination),
                      let scheme = url.scheme?.lowercased(), ["http", "https", "mailto"].contains(scheme),
                      openURLHandler != nil else {
                    throw SscRuntimeFailure(description: "openJson produced an unsafe or invalid URL")
                }
                result.append(NativeUiPreparedEffect(kind: kind, target: fields[1], payload: fields[2], url: url))
            default:
                throw SscRuntimeFailure(description: "unsupported native fetch success effect " + kind)
            }
        }
        return result
    }

    private func apply(_ effect: NativeUiPreparedEffect) throws {
        guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
        switch effect.kind {
        case "bumpTick": try session.write(effect.target, effect.payload)
        case "setSignal": try session.write(effect.target, effect.payload)
        case "navigate", "openJson":
            guard let url = effect.url, let handler = openURLHandler else {
                throw SscRuntimeFailure(description: "SwiftUI openURL environment is unavailable")
            }
            handler(url)
        default: preconditionFailure("validated NativeUiSuccessEffect kind")
        }
    }

    private func captureFetchRequest(metadata: SscFields, reader: String) throws -> URLRequest {
        guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
        let previousReader = currentReader
        let previousPending = pendingDependencies
        let previousReadStack = readStack
        currentReader = reader
        pendingDependencies = []
        readStack = []
        var succeeded = false
        defer {
            if succeeded { commitDependencies(reader: reader, next: pendingDependencies) }
            currentReader = previousReader
            pendingDependencies = previousPending
            readStack = previousReadStack
        }
        let urlValue = try resolveRequestSource(metadata[0], operation: "fetch URL", session: session)
        _ = try session.read(metadata[1])
        let headersValue = try session.read(metadata[2])
        succeeded = true
        return try makeRequest(method: "GET", urlValue: urlValue, bodyValue: .unit, headersValue: headersValue)
    }

    private func resolveRequestSource(_ value: SscValue, operation: String, session: NativeUiSession) throws -> SscValue {
        if case .data("NativeUiSignal", _) = value { return try session.read(value) }
        return value
    }

    private func makeRequest(method: String, urlValue: SscValue, bodyValue: SscValue, headersValue: SscValue) throws -> URLRequest {
        guard case let .string(urlText) = urlValue,
              let url = URL(string: urlText),
              let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host, !host.isEmpty else {
            throw SscRuntimeFailure(description: "native fetch URL must be an absolute http/https URL")
        }
        guard isHttpToken(method) else {
            throw SscRuntimeFailure(description: "native fetch method must be an RFC HTTP token")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method.uppercased()
        request.httpBody = try requestBody(bodyValue)
        for (name, value) in try requestHeaders(headersValue) { request.setValue(value, forHTTPHeaderField: name) }
        return request
    }

    private func requestHeaders(_ value: SscValue) throws -> [String: String] {
        guard case let .string(text) = value else {
            throw SscRuntimeFailure(description: "native fetch headers signal must contain JSON text")
        }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [:] }
        guard let data = text.data(using: .utf8) else {
            throw SscRuntimeFailure(description: "native fetch headers must be valid UTF-8 JSON")
        }
        let decoded: Any
        do { decoded = try JSONSerialization.jsonObject(with: data) }
        catch { throw SscRuntimeFailure(description: "native fetch headers must be a JSON object") }
        guard let object = decoded as? [String: Any] else {
            throw SscRuntimeFailure(description: "native fetch headers must be a JSON object")
        }
        var result: [String: String] = [:]
        for (name, raw) in object {
            guard let header = raw as? String else {
                throw SscRuntimeFailure(description: "native fetch header '\(name)' must be String")
            }
            guard isHttpToken(name) else {
                throw SscRuntimeFailure(description: "native fetch header name '\(name)' must be an RFC HTTP token")
            }
            guard header.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value != 0x7f }) else {
                throw SscRuntimeFailure(description: "native fetch header '\(name)' contains a control character")
            }
            result[name] = header
        }
        return result
    }

    private func isHttpToken(_ value: String) -> Bool {
        guard !value.isEmpty else { return false }
        let punctuation = Set("!#$%&'*+-.^_`|~".unicodeScalars.map(\.value))
        return value.unicodeScalars.allSatisfy { scalar in
            let code = scalar.value
            return (code >= 48 && code <= 57) || (code >= 65 && code <= 90) ||
                (code >= 97 && code <= 122) || punctuation.contains(code)
        }
    }

    private func requestBody(_ value: SscValue) throws -> Data? {
        switch value {
        case .unit: return nil
        case let .string(text): return text.data(using: .utf8)
        case let .data("NativeUiFormBody", fields) where fields.count == 1:
            guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
            var object: [String: Any] = [:]
            for descriptor in try list(fields[0], operation: "NativeUiFormBody.fields") {
                let wire: String
                let signalId: String
                switch descriptor {
                case let .string(name): wire = name; signalId = name
                case let .data(tag, pair) where (tag == "Tuple2" || tag == "Pair") && pair.count == 2:
                    guard case let .string(key) = pair[0], case let .string(id) = pair[1] else {
                        throw SscRuntimeFailure(description: "NativeUiFormBody tuple entries must contain String names")
                    }
                    wire = key; signalId = id
                default:
                    throw SscRuntimeFailure(description: "NativeUiFormBody entries must be String or (String, String)")
                }
                object[wire] = try jsonObject(try session.readSignal(named: signalId))
            }
            return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        default:
            return try JSONSerialization.data(withJSONObject: jsonObject(value), options: [.sortedKeys, .fragmentsAllowed])
        }
    }

    private func jsonObject(_ value: SscValue) throws -> Any {
        switch value {
        case .unit: return NSNull()
        case let .bool(value): return value
        case let .int(value): return value
        case let .float(value): return value
        case let .string(value): return value
        case let .big(value): return value.description
        case let .decimal(value): return value.description
        case .data("Nil", _): return [Any]()
        case .data("Cons", _): return try list(value, operation: "JSON list").map(jsonObject)
        case let .map(map):
            var object: [String: Any] = [:]
            for (key, item) in map.entries {
                guard case let .string(name) = key else { throw SscRuntimeFailure(description: "JSON map keys must be String") }
                object[name] = try jsonObject(item)
            }
            return object
        default: throw SscRuntimeFailure(description: "request body contains a non-JSON portable value")
        }
    }

    private func list(_ value: SscValue, operation: String) throws -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: throw SscRuntimeFailure(description: operation + " must be a proper List")
            }
        }
    }

    private func bounded(_ message: String) -> String {
        String(message.unicodeScalars.prefix(1024))
    }

    private func fetchSignalForKey(_ key: String) -> SscValue? {
        let parts = key.split(separator: "\u{0}", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        return session?.fetchSignal(scope: String(parts[0]), id: String(parts[1]))
    }
}
"""

  val rendererSource: String = """import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

@MainActor
struct NativeUiRenderer: View {
    private static let supportedAttributes: Set<String> = [
        "alt", "aria-disabled", "aria-label", "aria-modal", "checked", "class",
        "data-ssc-raw-html", "disabled", "href", "id", "placeholder", "required",
        "role", "src", "start", "style", "text-align", "title", "type", "value"
    ]
    private static let supportedEventSlots: Set<String> = ["change", "click"]
    @ObservedObject var store: NativeUiStore
    @Environment(\.openURL) private var openURL
    let value: SscValue
    let ownerPath: String
    let showFailure: Bool

    init(
        store: NativeUiStore,
        value: SscValue,
        ownerPath: String = "root",
        showFailure: Bool = true
    ) {
        self.store = store
        self.value = value
        self.ownerPath = ownerPath
        self.showFailure = showFailure
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if showFailure, let failure = store.failure {
                Text(failure).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + failure)
            }
            render(value)
        }
        .onAppear { store.installOpenURL { openURL($0) } }
    }

    private func render(_ value: SscValue) -> AnyView {
        guard case let .data(tag, fields) = value else {
            return unsupported("expected NativeUi node, got " + store.string(value))
        }
        if let diagnostic = Self.structuralDiagnostic(value, store: store) {
            return unsupported(diagnostic)
        }
        switch tag {
        case "NativeUiAbi" where fields.count == 3:
            guard case let .int(version) = fields[0], version == 1 else {
                return unsupported("unknown NativeUi ABI version")
            }
            return render(fields[1])
        case "NativeUiText" where fields.count == 1:
            return AnyView(Text(store.string(fields[0])))
        case "NativeUiSignalText" where fields.count == 1:
            if let diagnostic = store.renderedSignalDiagnostic(fields[0]) { return unsupported(diagnostic) }
            return AnyView(NativeUiSignalTextView(store: store, signal: fields[0]))
        case "NativeUiShow" where fields.count == 3:
            return AnyView(NativeUiShowView(
                store: store, fields: fields, ownerPath: ownerPath
            ))
        case "NativeUiFragment" where fields.count == 1:
            guard let children = properList(fields[0]) else { return unsupported("NativeUiFragment children must be a proper List") }
            return renderChildren(children)
        case "NativeUiElement" where fields.count == 5:
            return renderElement(fields, nodeOwnerPath: store.ownerPath(for: value, fallback: ownerPath))
        case "NativeUiForKeyed" where fields.count == 4:
            guard case .string = fields[0], case .data("NativeUiSignal", _) = fields[1],
                  case .closure = fields[2], case .closure = fields[3] else {
                let site = fields.first.map(store.string) ?? ""
                return unsupported("malformed NativeUiForKeyed at " + store.source(site))
            }
            return AnyView(NativeUiForKeyedView(
                store: store,
                fields: fields,
                parentOwnerPath: store.ownerPath(for: value, fallback: ownerPath)
            ))
        case "NativeUiTrustedHtml" where fields.count == 2:
            let site = store.string(fields[0])
            return unsupported("trusted HTML adapter pending at " + store.source(site))
        case "NativeUiDataTable":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("native data table adapter pending at " + store.source(site))
        case "NativeUiUnsupported" where fields.count == 3:
            return unsupported(store.string(fields[0]) + " at " + store.source(fields[1]) + ": " + store.string(fields[2]))
        case "NativeUiElement":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("malformed NativeUiElement at " + store.source(site))
        case "NativeUiForKeyed":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("malformed NativeUiForKeyed at " + store.source(site))
        default:
            return unsupported("unsupported NativeUi node " + tag)
        }
    }

    private func renderChildren(_ children: [SscValue]) -> AnyView {
        AnyView(ForEach(Array(children.enumerated()), id: \.offset) { _, child in
            NativeUiRenderer(
                store: store, value: child, ownerPath: ownerPath,
                showFailure: false
            )
        })
    }

    private func renderElement(_ fields: SscFields, nodeOwnerPath: String) -> AnyView {
        let tag = store.string(fields[1])
        let source = store.source(store.string(fields[0]))
        guard case let .map(attrs) = fields[2] else { return unsupported("NativeUiElement attrs must be Map at " + source) }
        guard case let .map(events) = fields[3] else { return unsupported("NativeUiElement events must be Map at " + source) }
        guard let children = properList(fields[4]) else { return unsupported("NativeUiElement children must be a proper List at " + source) }
        if let issue = Self.validate(attrs, names: Self.supportedAttributes, kind: "attribute") {
            return unsupported(issue + " at " + source)
        }
        if let issue = Self.validate(events, names: Self.supportedEventSlots, kind: "event slot") {
            return unsupported(issue + " at " + source)
        }
        for (key, value) in attrs.entries {
            guard case let .string(name) = key else { continue }
            if case .data("NativeUiSignal", _) = value,
               name != "style", name != "value", name != "checked" {
                return unsupported("reactive attribute " + name + " is not mapped at " + source)
            }
        }
        let content: AnyView
        switch tag {
        case "div" where style(attrs, "flex-direction") == "row":
            content = AnyView(HStack(alignment: Self.centeredAlignment(attrs) ? .center : .top, spacing: gap(attrs)) { renderChildren(children) })
        case "div", "section", "main", "article", "nav", "form":
            content = AnyView(VStack(alignment: Self.centeredAlignment(attrs) ? .center : .leading, spacing: gap(attrs)) { renderChildren(children) })
        case "strong" where Self.semanticTextBehavior(tag) == "bold": content = AnyView(renderChildren(children).bold())
        case "em" where Self.semanticTextBehavior(tag) == "italic": content = AnyView(renderChildren(children).italic())
        case "code" where Self.semanticTextBehavior(tag) == "monospace": content = AnyView(renderChildren(children).font(.system(.body, design: .monospaced)))
        case "p", "span", "label", "pre",
             "h1", "h2", "h3", "h4", "h5", "h6":
            content = AnyView(renderChildren(children))
        case "br": content = AnyView(Text("\n"))
        case "hr": content = AnyView(Divider())
        case "button":
            content = AnyView(Button(action: { runEvents(events, siteId: store.string(fields[0]), nodeOwnerPath: nodeOwnerPath) }) { renderChildren(children) })
        case "input" where attribute(attrs, "type") == "checkbox":
            if let checked = attrs.get(.string("checked")), case .data("NativeUiSignal", _) = checked {
                content = AnyView(NativeUiToggleControl(
                    store: store, signal: checked, events: events, siteId: store.string(fields[0]), ownerPath: nodeOwnerPath
                ))
            } else {
                content = AnyView(Toggle("", isOn: Binding(
                    get: { self.boundBool(attrs) },
                    set: { _ in self.runEvents(events, siteId: store.string(fields[0]), nodeOwnerPath: nodeOwnerPath) })))
            }
        case "input":
            if let value = attrs.get(.string("value")), case .data("NativeUiSignal", _) = value {
                content = AnyView(NativeUiTextControl(
                    store: store,
                    signal: value,
                    placeholder: attribute(attrs, "placeholder") ?? "",
                    events: events,
                    siteId: store.string(fields[0]),
                    ownerPath: nodeOwnerPath
                ))
            } else {
                content = AnyView(TextField(attribute(attrs, "placeholder") ?? "", text: Binding(
                    get: { self.boundText(attrs) },
                    set: { next in self.runEvents(events, input: next, siteId: store.string(fields[0]), nodeOwnerPath: nodeOwnerPath) })))
            }
        case "a":
            if Self.anchorBehavior(attrs: attrs, events: events) == "event" {
                content = AnyView(Button(action: { runEvents(events, siteId: store.string(fields[0]), nodeOwnerPath: nodeOwnerPath) }) { renderChildren(children) })
            } else if Self.anchorBehavior(attrs: attrs, events: events) == "href",
                      let href = attribute(attrs, "href"), let url = URL(string: href) {
                content = AnyView(Link(destination: url) { renderChildren(children) })
            } else {
                return unsupported("anchor requires href or event at " + source)
            }
        case "img":
            guard let source = attribute(attrs, "src"), !source.isEmpty else {
                return unsupported("image src is required at " + store.source(store.string(fields[0])))
            }
            content = AnyView(NativeUiImageView(
                source: source,
                alt: attribute(attrs, "alt") ?? ""
            ))
        case "ul":
            content = AnyView(VStack(alignment: .leading, spacing: gap(attrs)) { renderChildren(children) })
        case "ol":
            guard let start = Self.orderedListStart(attrs) else {
                return unsupported("ordered-list start must be Int at " + source)
            }
            content = renderOrderedList(children, start: start, source: source)
        case "li":
            content = AnyView(HStack(alignment: .top) { Text("•"); renderChildren(children) })
        case "table", "thead", "tbody", "tr", "th", "td":
            return unsupported("semantic table adapter pending at " + store.source(store.string(fields[0])))
        default:
            return unsupported("unsupported element <" + tag + "> at " + store.source(store.string(fields[0])))
        }
        return AnyView(NativeUiStyles.apply(
            content,
            attrs: attrs,
            store: store,
            siteId: store.string(fields[0])
        ).onDisappear { cancelEvents(events, nodeOwnerPath: nodeOwnerPath) })
    }

    private func renderOrderedList(_ children: [SscValue], start: Int, source: String) -> AnyView {
        for child in children {
            guard case let .data("NativeUiElement", fields) = child, fields.count == 5,
                  store.string(fields[1]) == "li" else {
                return unsupported("ordered-list child must be li at " + source)
            }
        }
        return AnyView(VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(children.enumerated()), id: \.offset) { index, child in
                if case let .data("NativeUiElement", fields) = child,
                   let itemChildren = properList(fields[4]) {
                    HStack(alignment: .top) {
                        Text(String(start + index) + ".")
                        renderChildren(itemChildren)
                    }
                }
            }
        })
    }

    private func runEvents(_ events: SscMap, input: String? = nil, siteId: String, nodeOwnerPath: String) {
        for (_, event) in events.entries {
            NativeUiActions.run(
                event, input: input, store: store, siteId: siteId,
                ownerPath: store.actionOwnerPath(for: event, mountedAt: nodeOwnerPath))
        }
    }

    private func cancelEvents(_ events: SscMap, nodeOwnerPath: String) {
        for (_, event) in events.entries {
            store.cancelFetchAction(event, ownerPath: store.actionOwnerPath(for: event, mountedAt: nodeOwnerPath))
        }
    }

    private func boundText(_ attrs: SscMap) -> String {
        guard let value = attrs.get(.string("value")) else { return "" }
        if case .data("NativeUiSignal", _) = value { return store.string(store.read(value)) }
        return store.string(value)
    }

    private func boundBool(_ attrs: SscMap) -> Bool {
        guard let value = attrs.get(.string("checked")) else { return false }
        if case .data("NativeUiSignal", _) = value { return store.bool(store.read(value)) }
        return store.bool(value)
    }

    private func unsupported(_ message: String) -> AnyView {
        AnyView(Text(message).foregroundStyle(.red).accessibilityLabel("Unsupported native UI: " + message))
    }

    private func properList(_ value: SscValue) -> [SscValue]? {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: return nil
            }
        }
    }

    private func attribute(_ attrs: SscMap, _ name: String) -> String? {
        attrs.get(.string(name)).map(store.string)
    }

    static func inventoryDiagnostic(attrs: SscMap, events: SscMap) -> String? {
        validate(attrs, names: supportedAttributes, kind: "attribute") ??
            validate(events, names: supportedEventSlots, kind: "event slot")
    }

    static func structuralDiagnostic(_ value: SscValue, store: NativeUiStore) -> String? {
        guard case let .data(tag, fields) = value else { return "expected NativeUi node" }
        let site = fields.first.map(store.string) ?? ""
        switch tag {
        case "NativeUiElement" where fields.count != 5:
            return "malformed NativeUiElement at " + store.source(site)
        case "NativeUiForKeyed":
            guard fields.count == 4, case .string = fields[0],
                  case .data("NativeUiSignal", _) = fields[1],
                  case .closure = fields[2], case .closure = fields[3] else {
                return "malformed NativeUiForKeyed at " + store.source(site)
            }
            return nil
        default: return nil
        }
    }

    static func centeredAlignment(_ attrs: SscMap) -> Bool {
        guard case let .string(raw) = attrs.get(.string("style")) else { return false }
        return cssValue(raw, name: "align-items") == "center"
    }

    static func semanticTextBehavior(_ tag: String) -> String? {
        switch tag {
        case "strong": return "bold"
        case "em": return "italic"
        case "code": return "monospace"
        default: return nil
        }
    }

    static func anchorBehavior(attrs: SscMap, events: SscMap) -> String {
        if !events.entries.isEmpty { return "event" }
        guard case let .string(href) = attrs.get(.string("href")),
              (href.hasPrefix("https://") || href.hasPrefix("http://") || href.hasPrefix("mailto:")),
              URL(string: href) != nil else { return "unsupported" }
        return "href"
    }

    static func orderedListStart(_ attrs: SscMap) -> Int? {
        guard let value = attrs.get(.string("start")) else { return 1 }
        switch value {
        case let .int(start): return Int(exactly: start)
        case let .string(raw): return Int(raw)
        default: return nil
        }
    }

    private static func cssValue(_ raw: String, name: String) -> String? {
        raw.split(separator: ";").compactMap { declaration -> (String, String)? in
            let pair = declaration.split(separator: ":", maxSplits: 1).map {
                $0.trimmingCharacters(in: .whitespaces)
            }
            return pair.count == 2 ? (pair[0], pair[1]) : nil
        }.first(where: { $0.0 == name })?.1
    }

    private static func validate(
        _ values: SscMap,
        names: Set<String>,
        kind: String
    ) -> String? {
        for (key, _) in values.entries {
            guard case let .string(name) = key else { return kind + " key must be String" }
            if !names.contains(name) { return "unsupported native " + kind + " " + name }
        }
        return nil
    }

    private func style(_ attrs: SscMap, _ name: String) -> String? {
        guard let raw = attribute(attrs, "style") else { return nil }
        return raw.split(separator: ";").compactMap { declaration -> (String, String)? in
            let pair = declaration.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            return pair.count == 2 ? (pair[0], pair[1]) : nil
        }.first(where: { $0.0 == name })?.1
    }

    private func gap(_ attrs: SscMap) -> CGFloat {
        guard let raw = style(attrs, "gap")?.replacingOccurrences(of: "px", with: ""),
              let value = Double(raw) else { return 0 }
        return CGFloat(value)
    }
}

private struct NativeUiImageView: View {
    let source: String
    let alt: String

    var body: some View {
        if source.hasPrefix("http://") || source.hasPrefix("https://") {
            AsyncImage(url: URL(string: source)) { phase in
                switch phase {
                case let .success(image): image.resizable().scaledToFit()
                case .failure: Text("Image failed: " + alt)
                case .empty: ProgressView()
                @unknown default: Text("Image unavailable: " + alt)
                }
            }
            .accessibilityLabel(alt)
        } else if let image = dataImage(source) {
            image.resizable().scaledToFit().accessibilityLabel(alt)
        } else {
            Image(source).resizable().scaledToFit().accessibilityLabel(alt)
        }
    }

    private func dataImage(_ source: String) -> Image? {
        guard source.hasPrefix("data:image/"), let comma = source.firstIndex(of: ","),
              source[..<comma].hasSuffix(";base64"),
              let bytes = Data(base64Encoded: String(source[source.index(after: comma)...])) else { return nil }
#if os(macOS)
        return NSImage(data: bytes).map(Image.init(nsImage:))
#else
        return UIImage(data: bytes).map(Image.init(uiImage:))
#endif
    }
}

@MainActor
private struct NativeUiTextControl: View {
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    let placeholder: String
    let events: SscMap
    let siteId: String
    let ownerPath: String
    @State private var token: NativeUiSubscriptionToken?

    init(store: NativeUiStore, signal: SscValue, placeholder: String, events: SscMap, siteId: String, ownerPath: String) {
        self.store = store
        self.cell = store.cell(for: signal)
        self.placeholder = placeholder
        self.events = events
        self.siteId = siteId
        self.ownerPath = ownerPath
    }

    @ViewBuilder var body: some View {
        Group {
            if let diagnostic = cell.renderedDiagnostic() {
                Text(diagnostic).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + diagnostic)
            } else {
                TextField(placeholder, text: Binding(
                    get: { store.string(cell.read()) },
                    set: { next in
                        for (_, event) in events.entries {
                            NativeUiActions.run(
                                event, input: next, store: store, siteId: siteId,
                                ownerPath: store.actionOwnerPath(for: event, mountedAt: ownerPath))
                        }
                    }
                ))
            }
        }
        .onAppear { if token == nil { token = store.subscribe(cell) } }
        .onDisappear {
            if let token { store.unsubscribe(token); self.token = nil }
            for (_, event) in events.entries {
                store.cancelFetchAction(event, ownerPath: store.actionOwnerPath(for: event, mountedAt: ownerPath))
            }
        }
    }
}

@MainActor
private struct NativeUiToggleControl: View {
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    let events: SscMap
    let siteId: String
    let ownerPath: String
    @State private var token: NativeUiSubscriptionToken?

    init(store: NativeUiStore, signal: SscValue, events: SscMap, siteId: String, ownerPath: String) {
        self.store = store
        self.cell = store.cell(for: signal)
        self.events = events
        self.siteId = siteId
        self.ownerPath = ownerPath
    }

    @ViewBuilder var body: some View {
        Group {
            if let diagnostic = cell.renderedDiagnostic() {
                Text(diagnostic).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + diagnostic)
            } else {
                Toggle("", isOn: Binding(
                    get: { store.bool(cell.read()) },
                    set: { _ in
                        for (_, event) in events.entries {
                            NativeUiActions.run(
                                event, input: nil, store: store, siteId: siteId,
                                ownerPath: store.actionOwnerPath(for: event, mountedAt: ownerPath))
                        }
                    }
                ))
            }
        }
        .onAppear { if token == nil { token = store.subscribe(cell) } }
        .onDisappear {
            if let token { store.unsubscribe(token); self.token = nil }
            for (_, event) in events.entries {
                store.cancelFetchAction(event, ownerPath: store.actionOwnerPath(for: event, mountedAt: ownerPath))
            }
        }
    }
}

@MainActor
private struct NativeUiShowView: View {
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    let whenTrue: SscValue
    let whenFalse: SscValue
    let ownerPath: String
    @State private var token: NativeUiSubscriptionToken?

    init(
        store: NativeUiStore,
        fields: SscFields,
        ownerPath: String
    ) {
        self.store = store
        self.cell = store.cell(for: fields[0])
        self.whenTrue = fields[1]
        self.whenFalse = fields[2]
        self.ownerPath = ownerPath
    }

    @ViewBuilder var body: some View {
        Group {
            if let diagnostic = cell.renderedDiagnostic() {
                Text(diagnostic).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + diagnostic)
            } else {
                let visible = store.bool(cell.read())
                NativeUiRenderer(
                    store: store,
                    value: visible ? whenTrue : whenFalse,
                    ownerPath: ownerPath,
                    showFailure: false
                )
            }
        }
        .onAppear { if token == nil { token = store.subscribe(cell) } }
        .onDisappear { if let token { store.unsubscribe(token); self.token = nil } }
    }
}

@MainActor
private struct NativeUiSignalTextView: View {
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    @State private var token: NativeUiSubscriptionToken?

    init(store: NativeUiStore, signal: SscValue) {
        self.store = store
        self.cell = store.cell(for: signal)
    }

    @ViewBuilder var body: some View {
        Group {
            if let diagnostic = cell.renderedDiagnostic() {
                Text(diagnostic).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + diagnostic)
            } else {
                Text(store.string(cell.read()))
            }
        }
        .onAppear { if token == nil { token = store.subscribe(cell) } }
        .onDisappear { if let token { store.unsubscribe(token); self.token = nil } }
    }
}

private struct NativeUiKeyedEntry: Identifiable {
    let id: String
    let ownerPath: String
    let value: SscValue
}

@MainActor
private final class NativeUiKeyedModel: ObservableObject {
    @Published private(set) var entries: [NativeUiKeyedEntry] = []
    @Published private(set) var error: String?
    private let store: NativeUiStore
    private let siteId: String
    private let parentOwnerPath: String
    private let key: SscClosure
    private let render: SscClosure

    init?(store: NativeUiStore, fields: SscFields, parentOwnerPath: String) {
        guard fields.count == 4, case let .string(siteId) = fields[0],
              case let .closure(key) = fields[2], case let .closure(render) = fields[3] else { return nil }
        self.store = store
        self.siteId = siteId
        self.parentOwnerPath = parentOwnerPath
        self.key = key
        self.render = render
    }

    func refresh(_ itemsValue: SscValue) {
        do {
            let result = try store.reconcileKeyed(
                parentOwnerPath: parentOwnerPath,
                siteId: siteId,
                items: try list(itemsValue),
                key: key,
                render: render
            )
            entries = result.entries.map {
                NativeUiKeyedEntry(
                    id: $0.id,
                    ownerPath: $0.ownerPath,
                    value: $0.value
                )
            }
            error = nil
        } catch {
            self.error = String(describing: error) + " at " + store.source(siteId)
        }
    }

    private func list(_ value: SscValue) throws -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2: result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: throw SscRuntimeFailure(description: "NativeUiForKeyed items must be a proper List")
            }
        }
    }
}

@MainActor
private struct NativeUiForKeyedView: View {
    @ObservedObject var store: NativeUiStore
    @ObservedObject var itemsCell: NativeUiObservableCell
    @StateObject private var model: NativeUiKeyedModel
    @State private var token: NativeUiSubscriptionToken?

    init(store: NativeUiStore, fields: SscFields, parentOwnerPath: String) {
        self.store = store
        let items = fields.count > 1 ? fields[1] : .unit
        self.itemsCell = store.cell(for: items)
        guard let model = NativeUiKeyedModel(store: store, fields: fields, parentOwnerPath: parentOwnerPath) else {
            fatalError("malformed NativeUiForKeyed")
        }
        self._model = StateObject(wrappedValue: model)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let diagnostic = itemsCell.renderedDiagnostic() {
                Text(diagnostic).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + diagnostic)
            } else if let error = model.error {
                Text(error).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + error)
            }
            ForEach(model.entries) { entry in
                NativeUiRenderer(
                    store: store,
                    value: entry.value,
                    ownerPath: entry.ownerPath,
                    showFailure: false
                )
            }
        }
        .onAppear {
            if token == nil { token = store.subscribe(itemsCell) }
            if itemsCell.renderedDiagnostic() == nil { model.refresh(itemsCell.read()) }
        }
        .onDisappear { if let token { store.unsubscribe(token); self.token = nil } }
        .onChange(of: itemsCell.revision) { _, _ in
            if itemsCell.renderedDiagnostic() == nil { model.refresh(itemsCell.read()) }
        }
    }
}
"""

  val stylesSource: String = """import SwiftUI

enum NativeUiStyles {
    private struct NativeShadow {
        let color: Color
        let radius: CGFloat
        let x: CGFloat
        let y: CGFloat
    }

    private static let supportedProperties: Set<String> = [
        "display", "flex-direction", "align-items", "justify-content", "gap", "flex", "flex-grow",
        "padding", "padding-left", "padding-right", "padding-top", "padding-bottom",
        "margin", "margin-left", "margin-right", "margin-top", "margin-bottom",
        "width", "min-width", "max-width", "height", "min-height", "max-height",
        "color", "background", "background-color", "border", "border-top", "border-right",
        "border-bottom", "border-left", "border-color", "border-top-color", "border-radius",
        "font-size", "font-family", "font-weight", "opacity", "text-decoration", "white-space",
        "overflow", "overflow-x", "text-align", "position", "inset", "z-index", "box-shadow",
        "box-sizing", "border-collapse", "cursor", "user-select"
    ]

    @MainActor
    static func apply(
        _ content: AnyView,
        attrs: SscMap,
        store: NativeUiStore,
        siteId: String
    ) -> AnyView {
        if let style = attrs.get(.string("style")), case .data("NativeUiSignal", _) = style {
            return AnyView(NativeUiSignalStyleView(
                content: content, attrs: attrs, signal: style, store: store, siteId: siteId
            ))
        }
        return applyResolved(content, attrs: attrs, store: store, siteId: siteId)
    }

    @MainActor
    static func applyResolved(
        _ content: AnyView,
        attrs: SscMap,
        store: NativeUiStore,
        siteId: String
    ) -> AnyView {
        var result = content
        let declarations = css(attrs, store: store)
        if let message = diagnostic(declarations: declarations, store: store, siteId: siteId) {
            return AnyView(Text(message).foregroundStyle(.red)
                .accessibilityLabel("Unsupported native UI: " + message))
        }
        if let message = semanticAttributeDiagnostic(attrs, store: store, siteId: siteId) {
            return AnyView(Text(message).foregroundStyle(.red)
                .accessibilityLabel("Unsupported native UI: " + message))
        }
        if displayBehavior(declarations["display"]) == "hidden" { return AnyView(EmptyView()) }
        if let padding = edgeInsets(declarations["padding"]) {
            result = AnyView(result.padding(padding))
        }
        if let top = pixels(declarations["padding-top"]) { result = AnyView(result.padding(.top, top)) }
        if let right = pixels(declarations["padding-right"]) { result = AnyView(result.padding(.trailing, right)) }
        if let bottom = pixels(declarations["padding-bottom"]) { result = AnyView(result.padding(.bottom, bottom)) }
        if let left = pixels(declarations["padding-left"]) { result = AnyView(result.padding(.leading, left)) }
        if let margin = edgeInsets(declarations["margin"]) { result = AnyView(result.padding(margin)) }
        if let top = pixels(declarations["margin-top"]) { result = AnyView(result.padding(.top, top)) }
        if let right = pixels(declarations["margin-right"]) { result = AnyView(result.padding(.trailing, right)) }
        if let bottom = pixels(declarations["margin-bottom"]) { result = AnyView(result.padding(.bottom, bottom)) }
        if let left = pixels(declarations["margin-left"]) { result = AnyView(result.padding(.leading, left)) }
        if let opacity = declarations["opacity"].flatMap(Double.init) { result = AnyView(result.opacity(opacity)) }
        if let size = pixels(declarations["font-size"]) { result = AnyView(result.font(.system(size: size))) }
        if let family = declarations["font-family"], !family.isEmpty {
            result = AnyView(result.font(.custom(family, size: pixels(declarations["font-size"]) ?? 14)))
        }
        if let weight = declarations["font-weight"].flatMap(fontWeight) {
            result = AnyView(result.fontWeight(weight))
        }
        if let color = color(declarations["color"]) { result = AnyView(result.foregroundStyle(color)) }
        if let background = color(declarations["background"] ?? declarations["background-color"]) {
            result = AnyView(result.background(background))
        }
        if let radius = pixels(declarations["border-radius"]) { result = AnyView(result.clipShape(RoundedRectangle(cornerRadius: radius))) }
        let minWidth = pixels(declarations["min-width"])
        let maxWidth = pixels(declarations["max-width"])
        let minHeight = pixels(declarations["min-height"])
        let maxHeight = pixels(declarations["max-height"])
        if let width = pixels(declarations["width"]), let height = pixels(declarations["height"]) {
            result = AnyView(result.frame(width: width, height: height))
        } else if let width = pixels(declarations["width"]) {
            result = AnyView(result.frame(width: width))
        } else if let height = pixels(declarations["height"]) {
            result = AnyView(result.frame(height: height))
        }
        if minWidth != nil || maxWidth != nil || minHeight != nil || maxHeight != nil {
            result = AnyView(result.frame(
                minWidth: minWidth, maxWidth: maxWidth,
                minHeight: minHeight, maxHeight: maxHeight
            ))
        }
        if declarations["flex"]?.hasPrefix("1") == true || declarations["flex-grow"] == "1" {
            result = AnyView(result.frame(maxWidth: .infinity, maxHeight: .infinity))
        }
        if let border = declarations["border"],
           border != "none", let borderColor = borderColor(border, explicit: declarations["border-color"]) {
            result = AnyView(result.overlay(
                RoundedRectangle(cornerRadius: pixels(declarations["border-radius"]) ?? 0)
                    .stroke(borderColor, lineWidth: borderWidth(border))
            ))
        }
        if let border = declarations["border-top"], border != "none",
           let color = borderColor(border, explicit: declarations["border-top-color"] ?? declarations["border-color"]) {
            result = AnyView(result.overlay(alignment: .top) {
                Rectangle().fill(color).frame(height: borderWidth(border))
            })
        } else if let border = declarations["border"], border != "none",
                  let color = color(declarations["border-top-color"]) {
            result = AnyView(result.overlay(alignment: .top) {
                Rectangle().fill(color).frame(height: borderWidth(border))
            })
        }
        if declarations["text-decoration"] == "underline" { result = AnyView(result.underline()) }
        if declarations["text-decoration"] == "line-through" { result = AnyView(result.strikethrough()) }
        switch declarations["text-align"] {
        case "center": result = AnyView(result.multilineTextAlignment(.center))
        case "right": result = AnyView(result.multilineTextAlignment(.trailing))
        case "left": result = AnyView(result.multilineTextAlignment(.leading))
        default: break
        }
        if declarations["overflow"] == "hidden" || declarations["overflow-x"] == "hidden" {
            result = AnyView(result.clipped())
        }
        if let z = declarations["z-index"].flatMap(Double.init) { result = AnyView(result.zIndex(z)) }
        if let value = declarations["box-shadow"], let shadow = boxShadow(value) {
            result = AnyView(result.shadow(
                color: shadow.color, radius: shadow.radius, x: shadow.x, y: shadow.y
            ))
        }
        if let label = attrs.get(.string("aria-label")) { result = AnyView(result.accessibilityLabel(store.string(label))) }
        if let title = attrs.get(.string("title")) { result = AnyView(result.accessibilityHint(store.string(title))) }
        if let id = attrs.get(.string("id")) { result = AnyView(result.id(store.string(id))) }
        let disabled = truthy(attrs.get(.string("disabled")), store: store) ||
            truthy(attrs.get(.string("aria-disabled")), store: store)
        if disabled { result = AnyView(result.disabled(true).accessibilityAddTraits(.isStaticText)) }
        if truthy(attrs.get(.string("required")), store: store) {
            result = AnyView(result.accessibilityHint("Required"))
        }
        if let role = attrs.get(.string("role")).map(store.string) {
            switch role {
            case "button": result = AnyView(result.accessibilityAddTraits(.isButton))
            case "link": result = AnyView(result.accessibilityAddTraits(.isLink))
            case "heading": result = AnyView(result.accessibilityAddTraits(.isHeader))
            case "img", "image": result = AnyView(result.accessibilityAddTraits(.isImage))
            case "dialog", "table", "row", "cell", "status", "group", "navigation": break
            default:
                let message = "unsupported native role " + role + " at " + store.source(siteId)
                return AnyView(Text(message).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + message))
            }
        }
        if truthy(attrs.get(.string("aria-modal")), store: store) {
            result = AnyView(result.accessibilityAddTraits(.isModal))
        }
        return result
    }

    @MainActor
    static func diagnostic(attrs: SscMap, store: NativeUiStore, siteId: String) -> String? {
        diagnostic(declarations: css(attrs, store: store), store: store, siteId: siteId) ??
            semanticAttributeDiagnostic(attrs, store: store, siteId: siteId)
    }

    @MainActor
    private static func diagnostic(
        declarations: [String: String],
        store: NativeUiStore,
        siteId: String
    ) -> String? {
        let unknown = Set(declarations.keys).subtracting(supportedProperties).sorted()
        if !unknown.isEmpty {
            return "unsupported native CSS " + unknown.joined(separator: ", ") +
                " at " + store.source(siteId)
        }
        if let invalid = invalidDeclaration(declarations) {
            return invalid + " at " + store.source(siteId)
        }
        return nil
    }

    @MainActor
    private static func css(_ attrs: SscMap, store: NativeUiStore) -> [String: String] {
        var result: [String: String] = [:]
        if let aligned = attrs.get(.string("text-align")) { result["text-align"] = store.string(aligned) }
        guard let value = attrs.get(.string("style")) else { return result }
        for declaration in store.string(value).split(separator: ";") {
            let pair = declaration.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if pair.count == 2, !pair[0].isEmpty, !pair[1].isEmpty { result[pair[0]] = pair[1] }
            else { result["<malformed>"] = String(declaration) }
        }
        return result
    }

    private static func invalidDeclaration(_ values: [String: String]) -> String? {
        let scalarLengths = [
            "padding-top", "padding-right", "padding-bottom", "padding-left",
            "margin-top", "margin-right", "margin-bottom", "margin-left",
            "width", "min-width", "max-width", "height", "min-height", "max-height",
            "font-size", "border-radius"
        ]
        for property in scalarLengths {
            if let value = values[property], pixels(value) == nil {
                return "unsupported native CSS value " + property + ":" + value
            }
        }
        for property in ["padding", "margin"] {
            if let value = values[property], edgeInsets(value) == nil {
                return "unsupported native CSS value " + property + ":" + value
            }
        }
        for property in ["color", "background", "background-color", "border-color", "border-top-color"] {
            if let value = values[property], color(value) == nil {
                return "unsupported native CSS color " + property + ":" + value
            }
        }
        if let value = values["opacity"], Double(value) == nil {
            return "unsupported native CSS value opacity:" + value
        }
        if let value = values["z-index"], Double(value) == nil {
            return "unsupported native CSS value z-index:" + value
        }
        if let value = values["display"], displayBehavior(value) == nil {
            return "unsupported native CSS value display:" + value
        }
        if let value = values["flex-direction"], value != "row", value != "column" {
            return "unsupported native CSS value flex-direction:" + value
        }
        if let value = values["gap"], pixels(value) == nil {
            return "unsupported native CSS value gap:" + value
        }
        if let value = values["flex"], value != "1 1 auto" {
            return "unsupported native CSS value flex:" + value
        }
        if let value = values["flex-grow"], value != "0", value != "1" {
            return "unsupported native CSS value flex-grow:" + value
        }
        if let value = values["text-align"], !["left", "center", "right"].contains(value) {
            return "unsupported native CSS value text-align:" + value
        }
        if let value = values["text-decoration"],
           !["none", "underline", "line-through"].contains(value) {
            return "unsupported native CSS value text-decoration:" + value
        }
        for property in ["border", "border-top"] {
            if let value = values[property], !validBorder(value, property: property, declarations: values) {
                return "unsupported native CSS value " + property + ":" + value
            }
        }
        if let value = values["box-shadow"], value != "none", boxShadow(value) == nil {
            return "unsupported native CSS value box-shadow:" + value
        }
        if let value = values["font-weight"], fontWeight(value) == nil {
            return "unsupported native CSS value font-weight:" + value
        }
        for property in ["position", "inset", "border-right", "border-bottom", "border-left", "white-space"] {
            if let value = values[property] {
                return "native CSS " + property + ":" + value + " is not mapped"
            }
        }
        if let value = values["overflow"], value != "hidden", value != "visible" {
            return "unsupported native CSS value overflow:" + value
        }
        if let value = values["overflow-x"], value != "hidden", value != "visible" {
            return "unsupported native CSS value overflow-x:" + value
        }
        if let value = values["align-items"], value != "center" {
            return "unsupported native CSS value align-items:" + value
        }
        if let value = values["justify-content"] {
            return "native CSS justify-content:" + value + " is not mapped"
        }
        return nil
    }

    private static func pixels(_ value: String?) -> CGFloat? {
        guard let value else { return nil }
        let raw = value.trimmingCharacters(in: .whitespaces)
        let number = raw.hasSuffix("px") ? String(raw.dropLast(2)) : raw
        guard let parsed = Double(number), parsed.isFinite else { return nil }
        return CGFloat(parsed)
    }

    static func displayBehavior(_ value: String?) -> String? {
        guard let value else { return "default" }
        switch value {
        case "none": return "hidden"
        case "flex": return "flex"
        case "inline-block": return "inline"
        case "contents": return "contents"
        default: return nil
        }
    }

    static func boxShadowBehavior(_ value: String) -> String? {
        if value == "none" { return "none" }
        guard let shadow = boxShadow(value) else { return nil }
        return "x=\(shadow.x),y=\(shadow.y),blur=\(shadow.radius)"
    }

    private static func boxShadow(_ value: String) -> NativeShadow? {
        let parts = value.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count == 4,
              let x = pixels(parts[0]), let y = pixels(parts[1]), let radius = pixels(parts[2]),
              radius >= 0, let color = color(parts[3]) else { return nil }
        return NativeShadow(color: color, radius: radius, x: x, y: y)
    }

    private static func fontWeight(_ value: String) -> Font.Weight? {
        switch fontWeightBehavior(value) {
        case "regular": return .regular
        case "medium": return .medium
        case "semibold": return .semibold
        case "bold": return .bold
        case "heavy": return .heavy
        case "black": return .black
        default: return nil
        }
    }

    static func fontWeightBehavior(_ value: String) -> String? {
        switch value.lowercased() {
        case "normal", "400": return "regular"
        case "500": return "medium"
        case "600", "semibold": return "semibold"
        case "bold", "700": return "bold"
        case "800": return "heavy"
        case "900": return "black"
        default: return nil
        }
    }

    @MainActor
    private static func semanticAttributeDiagnostic(
        _ attrs: SscMap,
        store: NativeUiStore,
        siteId: String
    ) -> String? {
        for name in ["aria-disabled", "aria-modal", "disabled", "required"] {
            guard let value = attrs.get(.string(name)) else { continue }
            if case .bool = value { continue }
            guard case let .string(text) = value else {
                return "invalid native " + name + " value at " + store.source(siteId)
            }
            let accepted: Set<String>
            switch name {
            case "aria-disabled", "aria-modal": accepted = ["true", "false"]
            case "disabled": accepted = ["true", "false", "disabled"]
            default: accepted = ["true", "false", "required"]
            }
            if !accepted.contains(text.lowercased()) {
                return "invalid native " + name + " value " + text + " at " + store.source(siteId)
            }
        }
        return nil
    }

    @MainActor
    private static func truthy(_ value: SscValue?, store: NativeUiStore) -> Bool {
        guard let value else { return false }
        if case let .bool(result) = value { return result }
        let text = store.string(value).lowercased()
        return text == "true" || text == "disabled" || text == "required"
    }

    private static func edgeInsets(_ value: String?) -> EdgeInsets? {
        guard let value else { return nil }
        let parts = value.split(whereSeparator: { $0.isWhitespace }).compactMap {
            pixels(String($0))
        }
        switch parts.count {
        case 1: return EdgeInsets(top: parts[0], leading: parts[0], bottom: parts[0], trailing: parts[0])
        case 2: return EdgeInsets(top: parts[0], leading: parts[1], bottom: parts[0], trailing: parts[1])
        case 4: return EdgeInsets(top: parts[0], leading: parts[3], bottom: parts[2], trailing: parts[1])
        default: return nil
        }
    }

    private static func borderWidth(_ value: String) -> CGFloat {
        value.split(whereSeparator: { $0.isWhitespace }).compactMap { pixels(String($0)) }.first ?? 1
    }

    private static func validBorder(_ value: String, property: String, declarations: [String: String]) -> Bool {
        if value == "none" { return true }
        let parts = value.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count == 3, let width = pixels(parts[0]), width >= 0,
              parts[1] == "solid", color(parts[2]) != nil else { return false }
        let explicit = property == "border-top"
            ? declarations["border-top-color"] ?? declarations["border-color"]
            : declarations["border-color"]
        return borderColor(value, explicit: explicit) != nil
    }

    private static func borderColor(_ value: String, explicit: String?) -> Color? {
        if let explicit = color(explicit) {
            return explicit
        }
        return value.split(whereSeparator: { $0.isWhitespace }).compactMap { color(String($0)) }.last
    }

    private static func color(_ value: String?) -> Color? {
        guard let value else { return nil }
        switch value.lowercased() {
        case "black": return .black; case "white": return .white; case "red": return .red
        case "green": return .green; case "blue": return .blue; case "gray", "grey": return .gray
        case "transparent", "none": return .clear
        default:
            let raw = value.lowercased()
            if raw.hasPrefix("#") {
                let hex = String(raw.dropFirst())
                let expanded = hex.count == 3 ? hex.map { "\($0)\($0)" }.joined() : hex
                if expanded.count == 6, let number = UInt64(expanded, radix: 16) {
                    return Color(
                        red: Double((number >> 16) & 255) / 255,
                        green: Double((number >> 8) & 255) / 255,
                        blue: Double(number & 255) / 255
                    )
                }
            }
            if raw.hasPrefix("rgba("), raw.hasSuffix(")") {
                let parts = raw.dropFirst(5).dropLast().split(separator: ",").map {
                    $0.trimmingCharacters(in: .whitespaces)
                }
                if parts.count == 4, let red = Double(parts[0]), let green = Double(parts[1]),
                   let blue = Double(parts[2]), let alpha = Double(parts[3]) {
                    return Color(red: red / 255, green: green / 255, blue: blue / 255, opacity: alpha)
                }
            }
            return nil
        }
    }
}

@MainActor
private struct NativeUiSignalStyleView: View {
    let content: AnyView
    let attrs: SscMap
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    let siteId: String
    @State private var token: NativeUiSubscriptionToken?

    init(content: AnyView, attrs: SscMap, signal: SscValue, store: NativeUiStore, siteId: String) {
        self.content = content
        self.attrs = attrs
        self.cell = store.cell(for: signal)
        self.store = store
        self.siteId = siteId
    }

    var body: some View {
        let rendered: AnyView
        if let diagnostic = cell.renderedDiagnostic() {
            rendered = AnyView(Text(diagnostic).foregroundStyle(.red)
                .accessibilityLabel("Unsupported native UI: " + diagnostic))
        } else {
            let resolved = SscMap()
            for (key, value) in attrs.entries { resolved.put(key, value) }
            resolved.put(.string("style"), cell.read())
            rendered = NativeUiStyles.applyResolved(content, attrs: resolved, store: store, siteId: siteId)
        }
        return rendered
            .onAppear { if token == nil { token = store.subscribe(cell) } }
            .onDisappear { if let token { store.unsubscribe(token); self.token = nil } }
    }
}

enum NativeUiActions {
    @MainActor
    static func run(_ event: SscValue, input: String?, store: NativeUiStore, siteId: String, ownerPath: String) {
        if case .data("NativeUiFetchAction", _) = event {
            store.runFetchAction(event, ownerPath: ownerPath, sourceSiteId: siteId)
            return
        }
        guard case let .data("NativeUiEvent", fields) = event, fields.count == 4,
              case let .string(kind) = fields[0] else {
            store.report("malformed native event at " + store.source(siteId))
            return
        }
        guard ["set", "input", "toggle", "increment"].contains(kind) else {
            store.report("unsupported native event kind " + kind + " at " + store.source(siteId))
            return
        }
        guard case let .map(metadata) = fields[3] else {
            store.report("native event " + kind + " metadata must be Map at " + store.source(siteId))
            return
        }
        for (key, _) in metadata.entries {
            guard case .string = key else {
                store.report("native event " + kind + " metadata key must be String at " + store.source(siteId))
                return
            }
        }
        guard store.signalKind(fields[1]) != nil else {
            store.report("native event " + kind + " target must be NativeUiSignal at " + store.source(siteId))
            return
        }
        switch kind {
        case "set": store.cell(for: fields[1]).write(fields[2])
        case "input":
            guard case .unit = fields[2] else {
                store.report("native event input payload must be Unit at " + store.source(siteId)); return
            }
            store.cell(for: fields[1]).write(.string(input ?? ""))
        case "toggle":
            guard case .unit = fields[2], case let .bool(current) = store.read(fields[1]) else {
                store.report("native event toggle requires Unit payload and Bool target at " + store.source(siteId)); return
            }
            store.cell(for: fields[1]).write(.bool(!current))
        case "increment":
            guard case let .int(amount) = fields[2], case let .int(value) = store.read(fields[1]) else {
                store.report("native event increment requires Int payload and Int target at " + store.source(siteId)); return
            }
            store.cell(for: fields[1]).write(.int(value + amount))
        default: preconditionFailure("validated NativeUiEvent kind")
        }
    }
}

"""

  val htmlSource: String = """// Trusted HTML is intentionally surfaced as NativeUiUnsupported
// until the isolated non-persistent WKWebView adapter slice is linked.
enum NativeUiHtmlAdapter {
    static let available = false
}
"""
