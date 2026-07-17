package ssc.swift

private[swift] object SwiftNativeUiApple:
  val inventoryElementTags: Set[String] = Set(
    "a", "button", "code", "div", "em", "hr", "img", "input", "label", "li",
    "ol", "option", "p", "pre", "select", "span", "strong", "table", "tbody",
    "td", "th", "thead", "tr", "ul",
  )

  val inventoryCssProperties: Set[String] = Set(
    "align-items", "background", "border", "border-bottom", "border-collapse",
    "border-radius", "border-top", "border-top-color", "box-shadow", "box-sizing",
    "color", "cursor", "display", "flex", "flex-direction", "flex-wrap", "font-family",
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

  private val storeSourcePart1: String = """import Combine
import CoreFoundation
import Foundation
import Network
import SwiftUI

protocol NativeUiOnlineMonitoring: AnyObject {
    func start(_ update: @escaping @Sendable (Bool) -> Void)
    func cancel()
}

private final class NativeUiPathMonitor: NativeUiOnlineMonitoring, @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "scalascript.nativeui.online")

    func start(_ update: @escaping @Sendable (Bool) -> Void) {
        monitor.pathUpdateHandler = { path in update(path.status == .satisfied) }
        monitor.start(queue: queue)
    }

    func cancel() { monitor.cancel() }
}

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

enum NativeUiTableIdentity: Hashable, CustomStringConvertible {
    case string(String)
    case int(Int64)
    case big(String)

    var description: String {
        switch self {
        case let .string(value): return "string:" + value
        case let .int(value): return "int:" + String(value)
        case let .big(value): return "bigint:" + value
        }
    }
}

struct NativeUiTableColumn {
    let kind: String
    let title: String
    let fieldPath: String
    let alignment: String
    let options: SscMap
    let editAction: SscValue?
}

struct NativeUiTableAction {
    let kind: String
    let label: String
    let request: SscValue
    let payload: SscValue
    let refresh: SscValue
    let options: SscMap
    let signature: String
}

private struct NativeUiTableCapability {
    let descriptorSignature: String
    let actionSignatures: [Int: String]
    var rowIdentities: Set<NativeUiTableIdentity>
}

struct NativeUiTableCellValue {
    let primary: String
    let secondary: String?
    let link: URL?
    let statusColor: String?
    let alignment: String
}

struct NativeUiTableRow: Identifiable {
    let identity: NativeUiTableIdentity
    let value: SscMap
    let cells: [NativeUiTableCellValue]
    var id: String { identity.description }
}

struct NativeUiTableDescriptor {
    let siteId: String
    let sourceKind: String
    let sourceValue: SscValue
    let rowsPath: String
    let columns: [NativeUiTableColumn]
    let actions: [NativeUiTableAction]
    let rowKeyPath: String
}

struct NativeUiTableSnapshot {
    let rows: [NativeUiTableRow]
    let status: String?
    let error: String?
}

enum NativeUiColorGrammar {
    static func isValid(_ text: String) -> Bool {
        let lower = text.lowercased()
        if ["black", "white", "red", "green", "blue", "gray", "grey", "transparent", "none"].contains(lower) { return true }
        if lower.range(of: "^#[0-9a-f]{3}([0-9a-f]{3})?$", options: .regularExpression) != nil { return true }
        return rgba(lower) != nil
    }

    static func rgba(_ text: String) -> (Double, Double, Double, Double)? {
        let lower = text.lowercased()
        guard lower.hasPrefix("rgba("), lower.hasSuffix(")") else { return nil }
        let parts = lower.dropFirst(5).dropLast().split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        guard parts.count == 4,
              let red = Int(parts[0]), let green = Int(parts[1]), let blue = Int(parts[2]),
              let alpha = Double(parts[3]), (0...255).contains(red),
              (0...255).contains(green), (0...255).contains(blue), alpha >= 0, alpha <= 1 else { return nil }
        return (Double(red) / 255, Double(green) / 255, Double(blue) / 255, alpha)
    }
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
    private var dependencyOnlineOwners: [String: Set<String>] = [:]
    private var cachedValues: [String: SscValue] = [:]
    private var pendingBatchWrites: [NativeUiPendingWrite]?
    private var pendingBatchWriteSet = Set<NativeUiPendingWrite>()
    private var pendingSeedReleases = Set<String>()
    private let urlSession: URLSession
    private let backendBaseURL: URL?
    private let onlineMonitorFactory: () -> any NativeUiOnlineMonitoring
    private var onlineMonitor: (any NativeUiOnlineMonitoring)?
    private var onlineMonitorToken: UUID?
    private var onlineSubscriberCounts: [String: Int] = [:]
    private var onlineSignals: [String: SscValue] = [:]
    private var tokenOnlineKeys: [UUID: String] = [:]
    private var fetchTasks: [String: Task<Void, Never>] = [:]
    private var fetchGenerations: [String: UInt64] = [:]
    private var fetchTaskTokens: [String: UUID] = [:]
    private var actionTaskStatusKeys: [String: Set<String>] = [:]
    private var tableCapabilities: [String: NativeUiTableCapability] = [:]
    private var fetchSubscriberCounts: [String: Int] = [:]
    private var tokenFetchKeys: [UUID: String] = [:]
    private var openURLHandler: ((URL) -> Void)?
    private var mutationObserver: ((String, String) -> Void)?

    init(
        urlSession: URLSession = .shared,
        backendBaseURL: URL? = SscGeneratedProgram.nativeUiBackendBaseURL.flatMap { URL(string: $0) },
        userDefaults: UserDefaults = .standard,
        onlineMonitorFactory: @escaping () -> any NativeUiOnlineMonitoring = { NativeUiPathMonitor() }
    ) {
        self.urlSession = urlSession
        self.backendBaseURL = backendBaseURL
        self.onlineMonitorFactory = onlineMonitorFactory
        do {
            let defaults = userDefaults
            let built = try SscGeneratedProgram.makeNativeUiRoot(
                persistedRead: { defaults.string(forKey: $0) },
                persistedWrite: { defaults.set($1, forKey: $0) })
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
        onlineMonitor?.cancel()
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
        if signalKind(cell.signal) == "online" {
            tokenOnlineKeys[token.id] = cell.key
            acquireOnlineSignal(cell.signal, key: cell.key)
        }
        return token
    }

    func unsubscribe(_ token: NativeUiSubscriptionToken) {
        if let fetchKey = tokenFetchKeys.removeValue(forKey: token.id) {
            releaseFetchSignal(key: fetchKey)
        }
        if let onlineKey = tokenOnlineKeys.removeValue(forKey: token.id) {
            releaseOnlineSignal(key: onlineKey)
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
        launchNetworkTask(
            key: taskKey, identity: identity, request: request,
            onResponse: { [weak self] data, response in
                self?.completeFetchAction(
                    fields: fields, phase: phase, errorSignal: errorSignal,
                    key: taskKey, identity: identity, data: data, response: response)
            },
            onError: { [weak self] error in
                guard let self else { return }
                _ = self.targetTransaction([
                    (errorSignal, .string(self.bounded("request failed: " + String(describing: error)))),
                    (phase, .string("error")),
                ])
            },
            onCancel: { [weak self] in
                _ = self?.targetTransaction([(errorSignal, .string("")), (phase, .string("idle"))])
            })
    }

    func cancelFetchAction(_ event: SscValue, ownerPath: String) {
        guard let session,
              case let .data("NativeUiFetchAction", fields) = event, fields.count == 5,
              case let .string(siteId) = fields[0], case let .map(status) = fields[4],
              let phase = status.get(.string("phase")),
              let error = status.get(.string("error")),
              session.validActionStatus(event, phase: phase, error: error),
              let phaseKey = signalKey(phase), let errorKey = signalKey(error) else { return }
        cancelNetworkTask(key: "action\u{0}" + ownerPath + "\u{0}" + siteId)
        let statusKeys: Set<String> = [phaseKey, errorKey]
        let stillOwned = actionTaskStatusKeys.values.contains { $0 == statusKeys }
        if !stillOwned { _ = targetTransaction([(error, .string("")), (phase, .string("idle"))]) }
    }

"""

  private val storeSourcePart2: String = """    func nativeTableDescriptorSignature(_ value: SscValue) -> String {
        func encode(_ value: SscValue, visiting: inout Set<ObjectIdentifier>) -> String {
            switch value {
            case .unit: return "u"
            case let .bool(value): return value ? "b1" : "b0"
            case let .int(value): return "i" + String(value)
            case let .big(value): return "g" + value.description
            case let .decimal(value): return "d" + value.description
            case let .float(value): return "f" + String(value.bitPattern)
            case let .string(value): return "s" + String(value.utf8.count) + ":" + value
            case let .bytes(value): return "y" + value.map { String(format: "%02x", $0) }.joined()
            case let .data("NativeUiSignal", fields) where fields.count == 6:
                guard case let .string(id) = fields[0], case let .string(scope) = fields[1],
                      case let .string(kind) = fields[2] else { return "invalid-signal" }
                return "signal(" + scope + "," + id + "," + kind + "," + encode(fields[5], visiting: &visiting) + ")"
            case let .data(tag, fields):
                let identity = ObjectIdentifier(fields)
                guard visiting.insert(identity).inserted else { return "cycle:" + tag }
                defer { visiting.remove(identity) }
                return "data(" + tag + ":" + fields.map { encode($0, visiting: &visiting) }.joined(separator: ",") + ")"
            case .closure: return "closure"
            case let .map(map):
                let identity = ObjectIdentifier(map)
                guard visiting.insert(identity).inserted else { return "cycle:map" }
                defer { visiting.remove(identity) }
                return "map(" + map.entries.map {
                    encode($0.0, visiting: &visiting) + "=" + encode($0.1, visiting: &visiting)
                }.sorted().joined(separator: ",") + ")"
            case .cell, .longCell, .array: return "target-specific"
            }
        }
        var visiting = Set<ObjectIdentifier>()
        return encode(value, visiting: &visiting)
    }

    private func nativeTableCapabilityKey(ownerPath: String, siteId: String) -> String {
        ownerPath + "\u{0}" + siteId
    }

    func installNativeTableCapability(
        ownerPath: String,
        descriptor: NativeUiTableDescriptor,
        signature: String,
        rows: [NativeUiTableRow]
    ) {
        let key = nativeTableCapabilityKey(ownerPath: ownerPath, siteId: descriptor.siteId)
        if let previous = tableCapabilities[key], previous.descriptorSignature != signature {
            cancelNativeTableTasks(ownerPath: ownerPath, siteId: descriptor.siteId)
        }
        var actions = Dictionary(uniqueKeysWithValues: descriptor.actions.enumerated().map { ($0.offset, $0.element.signature) })
        for (index, column) in descriptor.columns.enumerated() {
            if let raw = column.editAction,
               let action = try? decodeNativeTableActionForEdit(raw, columnIndex: index) {
                actions[descriptor.actions.count + index] = action.signature
            }
        }
        tableCapabilities[key] = NativeUiTableCapability(
            descriptorSignature: signature, actionSignatures: actions,
            rowIdentities: Set(rows.map(\.identity)))
    }

    func removeNativeTableCapability(ownerPath: String, siteId: String, signature: String) {
        let key = nativeTableCapabilityKey(ownerPath: ownerPath, siteId: siteId)
        guard tableCapabilities[key]?.descriptorSignature == signature else { return }
        tableCapabilities.removeValue(forKey: key)
        cancelNativeTableTasks(ownerPath: ownerPath, siteId: siteId)
    }

    private func currentNativeTableCapability(
        ownerPath: String,
        siteId: String,
        signature: String,
        action: NativeUiTableAction,
        actionIndex: Int,
        rowIdentity: NativeUiTableIdentity
    ) -> Bool {
        guard let capability = tableCapabilities[nativeTableCapabilityKey(ownerPath: ownerPath, siteId: siteId)] else { return false }
        return capability.descriptorSignature == signature &&
            capability.actionSignatures[actionIndex] == action.signature &&
            capability.rowIdentities.contains(rowIdentity)
    }

    func updateNativeTableCapabilityRows(
        ownerPath: String,
        siteId: String,
        signature: String,
        rows: [NativeUiTableRow]
    ) {
        let key = nativeTableCapabilityKey(ownerPath: ownerPath, siteId: siteId)
        guard var capability = tableCapabilities[key], capability.descriptorSignature == signature else { return }
        capability.rowIdentities = Set(rows.map(\.identity))
        tableCapabilities[key] = capability
    }

    func runNativeTableAction(
        _ action: NativeUiTableAction,
        row: NativeUiTableRow,
        actionIndex: Int,
        ownerPath: String,
        siteId: String,
        descriptorSignature: String,
        editField: String? = nil,
        editValue: String? = nil,
        update: @escaping @MainActor (String, String?) -> Void
    ) {
        guard currentNativeTableCapability(
            ownerPath: ownerPath, siteId: siteId, signature: descriptorSignature,
            action: action, actionIndex: actionIndex, rowIdentity: row.identity) else {
            update("error", "native table action capability is stale")
            return
        }
        if action.kind == "edit" {
            guard editField != nil, editValue != nil else {
                update("error", "native table edit action requires field and value")
                return
            }
        } else if editField != nil || editValue != nil {
            update("error", "native table edit field/value supplied to non-edit action")
            return
        }
        if action.kind == "link" {
            do {
                let (_, names) = try decodeNativeRowPayload(action.payload, operation: "native table link")
                let value = try nativeTableDotted(row.value, path: names[0], missingAllowed: false)
                let text = try nativeTablePayloadScalarText(value, path: names[0])
                guard let target = action.options.get(.string("signal")) else {
                    throw SscRuntimeFailure(description: "native table link target is missing")
                }
                guard case .string = try readUserTarget(target) else {
                    throw SscRuntimeFailure(description: "native table link target is not current String")
                }
                try writeUserTarget(target, .string(text))
                update("done", nil)
            } catch { update("error", bounded(String(describing: error))) }
            return
        }
        guard let refreshValue = try? readUserTarget(action.refresh),
              case let .int(currentRefresh) = refreshValue, currentRefresh < Int64.max else {
            update("error", "native table refresh must be writable non-overflowing Int signal")
            return
        }
        let taskKey = nativeTableTaskKey(
            ownerPath: ownerPath, siteId: siteId, descriptorSignature: descriptorSignature,
            identity: row.identity, actionIndex: actionIndex)
        let identity = beginNetworkTask(key: taskKey)
        update("loading", nil)
        let request: URLRequest
        do {
            request = try nativeTableRequest(
                action, row: row, editField: editField, editValue: editValue)
        } catch {
            finishTask(key: taskKey, identity: identity)
            update("error", bounded("request failed: " + String(describing: error)))
            return
        }
        launchNetworkTask(
            key: taskKey, identity: identity, request: request,
            onResponse: { [weak self] data, response in
                guard let self else { return }
                self.finishTask(key: taskKey, identity: identity)
                guard self.currentNativeTableCapability(
                    ownerPath: ownerPath, siteId: siteId, signature: descriptorSignature,
                    action: action, actionIndex: actionIndex, rowIdentity: row.identity) else { return }
                guard let http = response as? HTTPURLResponse else {
                    update("error", "request failed: response was not HTTP"); return
                }
                guard (200..<300).contains(http.statusCode) else {
                    let body = String(data: data, encoding: .utf8) ?? ""
                    update("error", self.bounded("HTTP \(http.statusCode): " + body)); return
                }
                do {
                    guard case let .int(current) = try self.readUserTarget(action.refresh), current < Int64.max else {
                        throw SscRuntimeFailure(description: "native table refresh must be writable non-overflowing Int signal")
                    }
                    try self.writeUserTarget(action.refresh, .int(current + 1))
                    update("done", nil)
                } catch { update("error", self.bounded(String(describing: error))) }
            },
            onError: { [weak self] error in
                guard let self,
                      self.currentNativeTableCapability(
                        ownerPath: ownerPath, siteId: siteId, signature: descriptorSignature,
                        action: action, actionIndex: actionIndex, rowIdentity: row.identity) else { return }
                update("error", self.bounded("request failed: " + String(describing: error)))
            },
            onCancel: {})
    }

    func cancelNativeTableTasks(ownerPath: String, siteId: String) {
        let prefix = "table\u{0}" + ownerPath + "\u{0}" + siteId + "\u{0}"
        let keys = Set(fetchTasks.keys).union(fetchGenerations.keys).filter { $0.hasPrefix(prefix) }
        for key in keys { cancelNetworkTask(key: key) }
    }

    func cancelNativeTableRowTasks(
        ownerPath: String,
        siteId: String,
        descriptorSignature: String,
        identity: NativeUiTableIdentity
    ) {
        let prefix = "table\u{0}" + ownerPath + "\u{0}" + siteId + "\u{0}" + descriptorSignature +
            "\u{0}" + identity.description + "\u{0}"
        let keys = Set(fetchTasks.keys).union(fetchGenerations.keys).filter { $0.hasPrefix(prefix) }
        for key in keys { cancelNetworkTask(key: key) }
    }

    private func nativeTableTaskKey(
        ownerPath: String,
        siteId: String,
        descriptorSignature: String,
        identity: NativeUiTableIdentity,
        actionIndex: Int
    ) -> String {
        "table\u{0}" + ownerPath + "\u{0}" + siteId + "\u{0}" + descriptorSignature +
            "\u{0}" + identity.description + "\u{0}" + String(actionIndex)
    }

    private func nativeTableRequest(
        _ action: NativeUiTableAction,
        row: NativeUiTableRow,
        editField: String?,
        editValue: String?
    ) throws -> URLRequest {
        guard let session,
              case let .data("NativeUiFetchRequest", fields) = action.request, fields.count == 4,
              case let .string(method) = fields[0], case let .string(rawURL) = fields[1] else {
            throw SscRuntimeFailure(description: "native table request is malformed")
        }
        let substituted = try nativeTableSubstituteURL(rawURL, row: row.value)
        var request = URLRequest(url: try resolveRequestURL(substituted))
        request.httpMethod = method.uppercased()
        let body = try nativeTablePayloadBody(
            action.payload, row: row.value,
            editField: editField, editValue: editValue)
        request.httpBody = body.data
        let headersValue = try resolveRequestSource(fields[3], operation: "native table headers", session: session)
        let headers = try requestHeaders(headersValue)
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        if body.isJson && !headers.keys.contains(where: { $0.caseInsensitiveCompare("Content-Type") == .orderedSame }) {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    private func nativeTableSubstituteURL(_ template: String, row: SscMap) throws -> String {
        let expression = try NSRegularExpression(
            pattern: "/:([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)(?=/|\\?|#|$)")
        let source = template as NSString
        let matches = expression.matches(in: template, range: NSRange(location: 0, length: source.length))
        let exactStarts = Set(matches.map { $0.range(at: 0).location })
        var search = NSRange(location: 0, length: source.length)
        while search.length > 0 {
            let marker = source.range(of: "/:", options: [], range: search)
            if marker.location == NSNotFound { break }
            guard exactStarts.contains(marker.location) else {
                throw SscRuntimeFailure(description: "native table URL contains malformed /: token")
            }
            let next = marker.location + marker.length
            search = NSRange(location: next, length: source.length - next)
        }
        var result = template
        for match in matches.reversed() {
            let field = source.substring(with: match.range(at: 1))
            try validateDottedPath(field, operation: "native table URL token")
            let raw = try nativeTableDotted(row, path: field, missingAllowed: false)
            let text = try nativeTablePayloadScalarText(raw, path: field)
            var allowed = CharacterSet.alphanumerics
            allowed.insert(charactersIn: "-._~")
            guard let encoded = text.addingPercentEncoding(withAllowedCharacters: allowed) else {
                throw SscRuntimeFailure(description: "native table URL token " + field + " cannot be encoded")
            }
            let replacement = "/" + encoded
            let range = Range(match.range(at: 0), in: result)!
            result.replaceSubrange(range, with: replacement)
        }
        return result
    }

    private func nativeTablePayloadBody(
        _ payload: SscValue,
        row: SscMap,
        editField: String?,
        editValue: String?
    ) throws -> (data: Data, isJson: Bool) {
        let (kind, names) = try decodeNativeRowPayload(payload, operation: "native table action")
        if let editField {
            try validateDottedPath(editField, operation: "native table edit field")
            guard let editValue else { throw SscRuntimeFailure(description: "native table edit value is missing") }
            var object: [String: Any] = [:]
            for name in names {
                object[name] = try jsonObject(try nativeTableDotted(row, path: name, missingAllowed: false))
            }
            object[editField] = editValue
            return (try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), true)
        }
        switch kind {
        case "field":
            let value = try nativeTableDotted(row, path: names[0], missingAllowed: false)
            let text = try nativeTablePayloadScalarText(value, path: names[0])
            return (Data(text.utf8), false)
        case "wholeRow":
            return (try JSONSerialization.data(withJSONObject: jsonObject(.map(row)), options: [.sortedKeys]), true)
        case "fields":
            var object: [String: Any] = [:]
            for name in names {
                object[name] = try jsonObject(try nativeTableDotted(row, path: name, missingAllowed: false))
            }
            return (try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), true)
        default: preconditionFailure("validated row payload kind")
        }
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

    func isUserWritable(_ signal: SscValue) -> Bool { session?.isWritable(signal) == true }
    func readUserTarget(_ signal: SscValue) throws -> SscValue {
        guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
        return try session.userRead(signal)
    }
    func writeUserTarget(_ signal: SscValue, _ value: SscValue) throws {
        guard let session else { throw SscRuntimeFailure(description: "native UI session is unavailable") }
        try session.userWrite(signal, value)
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
        let previousOnline = dependencyOnlineOwners[reader, default: []]
        let nextOnline = Set(next.compactMap(onlineSignalForKey).compactMap(signalKey))
        if nextOnline.isEmpty { dependencyOnlineOwners.removeValue(forKey: reader) }
        else { dependencyOnlineOwners[reader] = nextOnline }
        for key in previousOnline.subtracting(nextOnline) { releaseOnlineSignal(key: key) }
        for key in nextOnline.subtracting(previousOnline) {
            if let signal = onlineSignalForKey(key) { acquireOnlineSignal(signal, key: key) }
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
        for key in dependencyOnlineOwners.removeValue(forKey: reader) ?? [] {
            releaseOnlineSignal(key: key)
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
            tokenOnlineKeys.removeValue(forKey: id)
        }
        for key in removed where fetchSubscriberCounts.removeValue(forKey: key) != nil {
            cancelNetworkTask(key: key)
        }
        for key in removed {
            onlineSubscriberCounts.removeValue(forKey: key)
            onlineSignals.removeValue(forKey: key)
        }
        if onlineSubscriberCounts.isEmpty {
            stopOnlineMonitor()
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
        for reader in Array(dependencyOnlineOwners.keys) {
            dependencyOnlineOwners[reader]?.subtract(removed)
            if dependencyOnlineOwners[reader]?.isEmpty == true { dependencyOnlineOwners.removeValue(forKey: reader) }
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

    private func acquireOnlineSignal(_ signal: SscValue, key: String) {
        let wasInactive = onlineSubscriberCounts.values.reduce(0, +) == 0
        onlineSubscriberCounts[key, default: 0] += 1
        onlineSignals[key] = signal
        guard wasInactive else { return }
        let monitor = onlineMonitorFactory()
        let token = UUID()
        onlineMonitor = monitor
        onlineMonitorToken = token
        monitor.start { [weak self] online in
            Task { @MainActor [weak self] in self?.applyOnlineStatus(online, token: token) }
        }
    }

    private func releaseOnlineSignal(key: String) {
        let remaining = max(0, onlineSubscriberCounts[key, default: 1] - 1)
        if remaining == 0 {
            onlineSubscriberCounts.removeValue(forKey: key)
            onlineSignals.removeValue(forKey: key)
        } else {
            onlineSubscriberCounts[key] = remaining
        }
        if onlineSubscriberCounts.isEmpty {
            stopOnlineMonitor()
        }
    }

    private func stopOnlineMonitor() {
        let monitor = onlineMonitor
        onlineMonitorToken = nil
        onlineMonitor = nil
        monitor?.cancel()
    }

    private func applyOnlineStatus(_ online: Bool, token: UUID) {
        guard onlineMonitorToken == token else { return }
        let writes = onlineSignals.compactMap { key, signal in
            onlineSubscriberCounts[key, default: 0] > 0 ? (signal, SscValue.bool(online)) : nil
        }
        if !writes.isEmpty { _ = targetTransaction(writes) }
    }

    func onlineOwnerCount() -> Int { onlineSubscriberCounts.values.reduce(0, +) }
    func onlineMonitorActive() -> Bool { onlineMonitor != nil }

    private func beginNetworkTask(key: String) -> NativeUiTaskIdentity {
        fetchTasks.removeValue(forKey: key)?.cancel()
        actionTaskStatusKeys.removeValue(forKey: key)
        let generation = fetchGenerations[key, default: 0] &+ 1
        let token = UUID()
        fetchGenerations[key] = generation
        fetchTaskTokens[key] = token
        return NativeUiTaskIdentity(generation: generation, token: token)
    }

    private func launchNetworkTask(
        key: String,
        identity: NativeUiTaskIdentity,
        request: URLRequest,
        onResponse: @escaping @MainActor (Data, URLResponse) -> Void,
        onError: @escaping @MainActor (Error) -> Void,
        onCancel: @escaping @MainActor () -> Void
    ) {
        let transport = urlSession
        let task = Task { @MainActor [weak self] in
            do {
                let (data, response) = try await transport.data(for: request)
                guard let self, self.isCurrentTask(key: key, identity: identity) else { return }
                onResponse(data, response)
            } catch {
                guard let self, self.isCurrentTask(key: key, identity: identity) else { return }
                self.finishTask(key: key, identity: identity)
                if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                    onCancel()
                } else {
                    onError(error)
                }
            }
        }
        fetchTasks[key] = task
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
        for key in ownedKeys where (key.hasPrefix("action\u{0}") || key.hasPrefix("table\u{0}")) &&
            (key.contains(marker + "\u{0}") || key.contains(marker + "/")) {
            cancelNetworkTask(key: key)
        }
        for key in Array(tableCapabilities.keys) where key.hasPrefix(owner + "\u{0}") || key.hasPrefix(owner + "/") {
            tableCapabilities.removeValue(forKey: key)
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
                      safeExternalURL(path) != nil, openURLHandler != nil else {
                    throw SscRuntimeFailure(description: "navigate requires an absolute http/https/mailto URL and SwiftUI openURL environment")
                }
            case "openJson":
                guard case let .string(template) = effectFields[1], !template.isEmpty,
                      case let .string(field) = effectFields[2], !field.isEmpty,
                      safeExternalURL(template.replacingOccurrences(of: ":value", with: "ssc")) != nil,
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
                      let url = safeExternalURL(path), openURLHandler != nil else {
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
                guard let url = safeExternalURL(destination),
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

    func safeExternalURL(_ text: String) -> URL? {
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased() else { return nil }
        switch scheme {
        case "http", "https":
            guard let host = url.host, !host.isEmpty else { return nil }
        case "mailto":
            let target = String(text.dropFirst(scheme.count + 1))
            guard !target.isEmpty, !target.hasPrefix("?"),
                  target.unicodeScalars.allSatisfy({ $0.value > 0x20 && $0.value != 0x7f }) else { return nil }
        default: return nil
        }
        return url
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
        guard case let .string(urlText) = urlValue else {
            throw SscRuntimeFailure(description: "native fetch URL must be String")
        }
        let url = try resolveRequestURL(urlText)
        guard isHttpToken(method) else {
            throw SscRuntimeFailure(description: "native fetch method must be an RFC HTTP token")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method.uppercased()
        request.httpBody = try requestBody(bodyValue)
        for (name, value) in try requestHeaders(headersValue) { request.setValue(value, forHTTPHeaderField: name) }
        return request
    }

    func resolveRequestURL(_ text: String) throws -> URL {
        guard !text.hasPrefix("//"), let parsed = URLComponents(string: text), parsed.fragment == nil,
              parsed.user == nil, parsed.password == nil else {
            throw SscRuntimeFailure(description: "native fetch URL rejects scheme-relative, credential, and fragment forms")
        }
        let candidate: URL
        if let rawScheme = parsed.scheme?.lowercased() {
            guard text.lowercased().hasPrefix(rawScheme + "://") else {
                throw SscRuntimeFailure(description: "native fetch URL must be an absolute http/https URL with a host")
            }
            guard let absolute = parsed.url else {
                throw SscRuntimeFailure(description: "native fetch URL is malformed")
            }
            candidate = absolute
        } else {
            guard let backendBaseURL, let resolved = URL(string: text, relativeTo: backendBaseURL)?.absoluteURL else {
                throw SscRuntimeFailure(description: "relative native fetch URL requires --server-url")
            }
            candidate = resolved
        }
        guard let components = URLComponents(url: candidate, resolvingAgainstBaseURL: true),
              let scheme = components.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = components.host, !host.isEmpty, components.user == nil,
              components.password == nil, components.fragment == nil else {
            throw SscRuntimeFailure(description: "native fetch URL must resolve to an absolute http/https URL with a host")
        }
        return candidate
    }

"""

  private val storeSourcePart3: String = """    func decodeNativeTable(_ value: SscValue) throws -> NativeUiTableDescriptor {
        guard case let .data("NativeUiDataTable", fields) = value, fields.count == 5,
              case let .string(siteId) = fields[0],
              case let .data("NativeUiTableSource", sourceFields) = fields[1], sourceFields.count == 3,
              case let .string(sourceKind) = sourceFields[0],
              case let .string(rowsPath) = sourceFields[2],
              case let .string(rawRowKeyPath) = fields[4] else {
            throw SscRuntimeFailure(description: "NativeUiDataTable must contain exactly five fields")
        }
        guard ["static", "signal", "fetch"].contains(sourceKind) else {
            throw SscRuntimeFailure(description: "NativeUiTableSource kind must be static, signal, or fetch")
        }
        if sourceKind == "static" || sourceKind == "signal" {
            guard rowsPath.isEmpty else {
                throw SscRuntimeFailure(description: "NativeUiTableSource \(sourceKind) rowsPath must be empty")
            }
        } else if !rowsPath.isEmpty {
            try validateDottedPath(rowsPath, operation: "NativeUiTableSource fetch rowsPath")
        }
        if sourceKind != "static", signalKind(sourceFields[1]) == nil {
            throw SscRuntimeFailure(description: "NativeUiTableSource \(sourceKind) value must be NativeUiSignal")
        }
        if sourceKind == "fetch" {
            guard case let .data("NativeUiSignal", signalFields) = sourceFields[1], signalFields.count == 6,
                  case let .data("NativeUiSignalMetaFetch", metadata) = signalFields[5], metadata.count == 5 else {
                throw SscRuntimeFailure(description: "NativeUiTableSource fetch value must have exact fetch metadata")
            }
        }
        let rowKeyPath = rawRowKeyPath.isEmpty ? "id" : rawRowKeyPath
        try validateDottedPath(rowKeyPath, operation: "NativeUiDataTable.rowKeyPath")
        let columns = try list(fields[2], operation: "NativeUiDataTable.columns").enumerated().map {
            try decodeNativeTableColumn($0.element, index: $0.offset)
        }
        let actions = try list(fields[3], operation: "NativeUiDataTable.actions").enumerated().map {
            let action = try decodeNativeTableAction($0.element, index: $0.offset)
            guard action.kind != "edit" else {
                throw SscRuntimeFailure(description: "NativeUiDataTable edit actions belong to editable columns")
            }
            return action
        }
        return NativeUiTableDescriptor(
            siteId: siteId, sourceKind: sourceKind, sourceValue: sourceFields[1],
            rowsPath: rowsPath, columns: columns, actions: actions, rowKeyPath: rowKeyPath)
    }

    func nativeTableDiagnostic(_ error: Error, value: SscValue) -> String {
        let siteId: String
        if case let .data("NativeUiDataTable", fields) = value,
           let first = fields.first, case let .string(site) = first { siteId = site }
        else { siteId = "" }
        return "Error: " + bounded(String(describing: error)) + " at " + source(siteId)
    }

    private func decodeNativeTableColumn(_ value: SscValue, index: Int) throws -> NativeUiTableColumn {
        guard case let .data("NativeUiColumn", fields) = value, fields.count == 5,
              case let .string(kind) = fields[0], case let .string(title) = fields[1],
              case let .string(fieldPath) = fields[2], case let .string(rawAlignment) = fields[3],
              case let .map(options) = fields[4] else {
            throw SscRuntimeFailure(description: "NativeUiDataTable column[\(index)] is malformed")
        }
        guard ["text", "date", "money", "status", "link", "stacked"].contains(kind) else {
            throw SscRuntimeFailure(description: "NativeUiDataTable column[\(index)] has unsupported kind \(kind)")
        }
        try validateDottedPath(fieldPath, operation: "NativeUiDataTable column[\(index)].fieldPath")
        let alignment: String
        switch rawAlignment {
        case "", "start", "left": alignment = "leading"
        case "center": alignment = "center"
        case "end", "right": alignment = "trailing"
        default:
            throw SscRuntimeFailure(description: "NativeUiDataTable column[\(index)] alignment is invalid")
        }
        let expected: Set<String>
        switch kind {
        case "text": expected = ["editAction"]
        case "date": expected = ["format"]
        case "money": expected = ["currency", "locale"]
        case "status": expected = ["colorMap"]
        case "link": expected = ["urlTemplate"]
        case "stacked": expected = ["subFieldPath"]
        default: preconditionFailure("validated table column kind")
        }
        try validateExactOptionKeys(options, expected: expected, operation: "NativeUiDataTable column[\(index)]")
        var editAction: SscValue?
        switch kind {
        case "text":
            guard let raw = options.get(.string("editAction")) else { preconditionFailure("validated option keys") }
            if case .unit = raw {} else {
                let action = try decodeNativeTableAction(raw, index: index)
                guard action.kind == "edit" else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable editable text column requires edit row action")
                }
                editAction = raw
            }
        case "date":
            guard case .string = options.get(.string("format")) else {
                throw SscRuntimeFailure(description: "NativeUiDataTable date format must be String")
            }
        case "money":
            guard case let .string(currency) = options.get(.string("currency")),
                  case let .string(locale) = options.get(.string("locale")), !currency.isEmpty else {
                throw SscRuntimeFailure(description: "NativeUiDataTable money currency/locale must be Strings")
            }
            guard Locale.commonISOCurrencyCodes.contains(currency.uppercased()) else {
                throw SscRuntimeFailure(description: "NativeUiDataTable money currency is not ISO-4217")
            }
            guard locale.isEmpty || Locale.availableIdentifiers.contains(locale) else {
                throw SscRuntimeFailure(description: "NativeUiDataTable money locale is invalid")
            }
        case "status":
            guard let raw = options.get(.string("colorMap")) else { preconditionFailure("validated option keys") }
            if case .unit = raw {} else {
                guard case let .map(colors) = raw else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable status colorMap must be Map or Unit")
                }
                for (key, color) in colors.entries {
                    guard case .string = key, case let .string(text) = color, NativeUiColorGrammar.isValid(text) else {
                        throw SscRuntimeFailure(description: "NativeUiDataTable status colorMap is malformed")
                    }
                }
            }
        case "link":
            guard case .string = options.get(.string("urlTemplate")) else {
                throw SscRuntimeFailure(description: "NativeUiDataTable link urlTemplate must be String")
            }
        case "stacked":
            guard case let .string(path) = options.get(.string("subFieldPath")) else {
                throw SscRuntimeFailure(description: "NativeUiDataTable stacked subFieldPath must be String")
            }
            try validateDottedPath(path, operation: "NativeUiDataTable stacked subFieldPath")
        default: break
        }
        return NativeUiTableColumn(
            kind: kind, title: title, fieldPath: fieldPath, alignment: alignment,
            options: options, editAction: editAction)
    }

    private func decodeNativeTableAction(_ value: SscValue, index: Int) throws -> NativeUiTableAction {
        guard case let .data("NativeUiRowAction", fields) = value, fields.count == 6,
              case let .string(kind) = fields[0], case let .string(label) = fields[1],
              case let .map(options) = fields[5], ["delete", "post", "link", "edit"].contains(kind) else {
            throw SscRuntimeFailure(description: "NativeUiDataTable action[\(index)] is malformed")
        }
        let (payloadKind, _) = try decodeNativeRowPayload(fields[3], operation: "NativeUiDataTable action[\(index)]")
        if ["delete", "link", "edit"].contains(kind) && payloadKind != "field" {
            throw SscRuntimeFailure(description: "NativeUiDataTable \(kind) action requires Field payload")
        }
        if kind == "link" {
            guard case .unit = fields[2], case .unit = fields[4] else {
                throw SscRuntimeFailure(description: "NativeUiDataTable link action must not contain request or refresh")
            }
            try validateExactOptionKeys(options, expected: ["signal"], operation: "NativeUiDataTable link action")
            guard let signal = options.get(.string("signal")), signalKind(signal) != nil,
                  isUserWritable(signal), case .string = try readUserTarget(signal) else {
                throw SscRuntimeFailure(description: "NativeUiDataTable link action target must be current writable String signal")
            }
        } else {
            guard case let .data("NativeUiFetchRequest", requestFields) = fields[2], requestFields.count == 4,
                  case let .string(method) = requestFields[0], isHttpToken(method),
                  case .string = requestFields[1] else {
                throw SscRuntimeFailure(description: "NativeUiDataTable action[\(index)] request is malformed")
            }
            try validateExactOptionKeys(options, expected: [], operation: "NativeUiDataTable action[\(index)]")
            guard signalKind(fields[4]) != nil, isUserWritable(fields[4]),
                  case let .int(current) = try readUserTarget(fields[4]), current < Int64.max else {
                throw SscRuntimeFailure(description: "NativeUiDataTable action[\(index)] refresh must be current writable non-overflowing Int signal")
            }
        }
        return NativeUiTableAction(
            kind: kind, label: label, request: fields[2], payload: fields[3],
            refresh: fields[4], options: options,
            signature: nativeTableDescriptorSignature(value))
    }

    func decodeNativeTableActionForEdit(_ value: SscValue, columnIndex: Int) throws -> NativeUiTableAction {
        let action = try decodeNativeTableAction(value, index: columnIndex)
        guard action.kind == "edit" else {
            throw SscRuntimeFailure(description: "NativeUiDataTable editable column requires edit action")
        }
        return action
    }

    private func decodeNativeRowPayload(_ value: SscValue, operation: String) throws -> (String, [String]) {
        guard case let .data("NativeUiRowPayload", fields) = value, fields.count == 2,
              case let .string(kind) = fields[0] else {
            throw SscRuntimeFailure(description: operation + " row payload is malformed")
        }
        let names = try list(fields[1], operation: operation + " payload names").map { raw -> String in
            guard case let .string(name) = raw else {
                throw SscRuntimeFailure(description: operation + " payload names must be String")
            }
            try validateDottedPath(name, operation: operation + " payload name")
            return name
        }
        switch kind {
        case "field" where names.count == 1: break
        case "wholeRow" where names.isEmpty: break
        case "fields" where !names.isEmpty && Set(names).count == names.count: break
        default: throw SscRuntimeFailure(description: operation + " row payload is malformed")
        }
        return (kind, names)
    }

    private func validateExactOptionKeys(_ options: SscMap, expected: Set<String>, operation: String) throws {
        var actual = Set<String>()
        for (key, _) in options.entries {
            guard case let .string(name) = key else {
                throw SscRuntimeFailure(description: operation + " option keys must be String")
            }
            actual.insert(name)
        }
        guard actual == expected else {
            throw SscRuntimeFailure(description: operation + " options must contain exactly " + expected.sorted().joined(separator: ","))
        }
    }

    private func validateDottedPath(_ path: String, operation: String) throws {
        guard !path.isEmpty,
              path.split(separator: ".", omittingEmptySubsequences: false).allSatisfy({ !$0.isEmpty }) else {
            throw SscRuntimeFailure(description: operation + " must be a non-empty dotted path")
        }
    }

    func nativeTableObservedSignals(_ descriptor: NativeUiTableDescriptor) -> [SscValue] {
        guard descriptor.sourceKind != "static" else { return [] }
        var result = [descriptor.sourceValue]
        if descriptor.sourceKind == "fetch",
           case let .data("NativeUiSignal", signalFields) = descriptor.sourceValue, signalFields.count == 6,
           case let .data("NativeUiSignalMetaFetch", metadata) = signalFields[5], metadata.count == 5 {
            result.append(metadata[3]); result.append(metadata[4])
        }
        return result
    }

    func nativeTableSnapshot(
        _ descriptor: NativeUiTableDescriptor,
        retaining previousRows: [NativeUiTableRow] = [],
        locale: Locale = .current,
        timeZone: TimeZone = .current
    ) -> NativeUiTableSnapshot {
        let sourceLocation = source(descriptor.siteId)
        do {
            let candidateValue: SscValue
            var sourceError: String?
            switch descriptor.sourceKind {
            case "static": candidateValue = descriptor.sourceValue
            case "signal": candidateValue = read(descriptor.sourceValue)
            case "fetch":
                candidateValue = read(descriptor.sourceValue)
                guard case let .data("NativeUiSignal", signalFields) = descriptor.sourceValue,
                      signalFields.count == 6,
                      case let .data("NativeUiSignalMetaFetch", metadata) = signalFields[5], metadata.count == 5 else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable fetch signal metadata is malformed")
                }
                let phase = string(read(metadata[3]))
                let error = string(read(metadata[4]))
                if phase == "loading" {
                    let retained = try previousRows.map { previous in
                        NativeUiTableRow(
                            identity: previous.identity, value: previous.value,
                            cells: try descriptor.columns.enumerated().map {
                                try nativeTableCell(
                                    previous.value, column: $0.element, index: $0.offset,
                                    locale: locale, timeZone: timeZone)
                            })
                    }
                    return NativeUiTableSnapshot(rows: retained, status: "Loading…", error: nil)
                }
                else if phase == "error" { sourceError = error.isEmpty ? "request failed" : error }
                else if !["idle", "done"].contains(phase) {
                    throw SscRuntimeFailure(description: "NativeUiDataTable fetch phase is invalid")
                }
            default: preconditionFailure("validated table source kind")
            }
            if let sourceError {
                return NativeUiTableSnapshot(
                    rows: previousRows, status: "Error: " + bounded(sourceError),
                    error: "Error: " + bounded(sourceError) + " at " + sourceLocation)
            }
            let rawRows: [SscValue]
            if descriptor.sourceKind == "fetch" {
                guard case let .string(body) = candidateValue else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable fetch value must be UTF-8 JSON text")
                }
                if body.isEmpty {
                    rawRows = []
                } else {
                    rawRows = try nativeTableJsonRows(body, rowsPath: descriptor.rowsPath)
                }
            } else {
                rawRows = try list(candidateValue, operation: "NativeUiDataTable rows")
            }
            var identities = Set<NativeUiTableIdentity>()
            let rows = try rawRows.enumerated().map { index, raw -> NativeUiTableRow in
                guard case let .map(row) = raw else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable row[\(index)] must be Map")
                }
                for (key, _) in row.entries {
                    guard case .string = key else {
                        throw SscRuntimeFailure(description: "NativeUiDataTable row[\(index)] keys must be String")
                    }
                }
                let keyValue = try nativeTableDotted(row, path: descriptor.rowKeyPath, missingAllowed: false)
                let identity = try nativeTableIdentity(keyValue, index: index)
                guard identities.insert(identity).inserted else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable duplicate row identity \(identity)")
                }
                let cells = try descriptor.columns.enumerated().map { columnIndex, column in
                    try nativeTableCell(row, column: column, index: columnIndex, locale: locale, timeZone: timeZone)
                }
                return NativeUiTableRow(identity: identity, value: row, cells: cells)
            }
            let visibleStatus = rows.isEmpty ? "No rows" : nil
            return NativeUiTableSnapshot(rows: rows, status: visibleStatus, error: nil)
        } catch {
            let message = "Error: " + bounded(String(describing: error))
            return NativeUiTableSnapshot(
                rows: previousRows,
                status: previousRows.isEmpty ? message : message,
                error: message + " at " + sourceLocation)
        }
    }

    private func nativeTableJsonRows(_ text: String, rowsPath: String) throws -> [SscValue] {
        guard let data = text.data(using: .utf8) else {
            throw SscRuntimeFailure(description: "NativeUiDataTable response is not UTF-8")
        }
        let root: Any
        do { root = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) }
        catch { throw SscRuntimeFailure(description: "NativeUiDataTable response is not valid JSON") }
        var candidates: [Any] = []
        if rowsPath.isEmpty {
            if root is [Any] { candidates.append(root) }
        } else if let explicit = nativeTableJsonDotted(root, path: rowsPath) {
            candidates.append(explicit)
        }
        if let object = root as? [String: Any] {
            for key in ["data", "rows", "items", "results"] {
                if let value = object[key] { candidates.append(value) }
            }
        }
        guard let array = candidates.first(where: { $0 is [Any] }) as? [Any] else {
            throw SscRuntimeFailure(description: "NativeUiDataTable response has no row array")
        }
        return try array.map(nativeTableJsonValue)
    }

    private func nativeTableJsonDotted(_ root: Any, path: String) -> Any? {
        var current: Any = root
        for segment in path.split(separator: ".").map(String.init) {
            guard let object = current as? [String: Any], let next = object[segment] else { return nil }
            current = next
        }
        return current
    }

    private func nativeTableJsonValue(_ raw: Any) throws -> SscValue {
        if raw is NSNull { return .unit }
        if let value = raw as? String { return .string(value) }
        if let value = raw as? NSNumber {
            if CFGetTypeID(value) == CFBooleanGetTypeID() { return .bool(value.boolValue) }
            let text = value.stringValue
            if !text.contains(".") && !text.lowercased().contains("e") {
                if let integer = Int64(text) { return .int(integer) }
                return .big(SscBigInt(text))
            }
            return .decimal(SscDecimal(text))
        }
        if let values = raw as? [Any] {
            return try nativeTableList(values.map(nativeTableJsonValue))
        }
        if let object = raw as? [String: Any] {
            let map = SscMap()
            for key in object.keys.sorted() { map.put(.string(key), try nativeTableJsonValue(object[key]!)) }
            return .map(map)
        }
        throw SscRuntimeFailure(description: "NativeUiDataTable JSON contains unsupported value")
    }

    private func nativeTableList(_ values: [SscValue]) throws -> SscValue {
        var result = SscValue.data("Nil", [])
        for value in values.reversed() { result = .data("Cons", [value, result]) }
        return result
    }

    private func nativeTableDotted(_ row: SscMap, path: String, missingAllowed: Bool) throws -> SscValue {
        var current: SscValue = .map(row)
        for segment in path.split(separator: ".").map(String.init) {
            guard case let .map(map) = current, let next = map.get(.string(segment)) else {
                if missingAllowed { return .unit }
                throw SscRuntimeFailure(description: "NativeUiDataTable missing dotted path " + path)
            }
            current = next
        }
        return current
    }

    private func nativeTableIdentity(_ value: SscValue, index: Int) throws -> NativeUiTableIdentity {
        switch value {
        case let .string(value) where !value.isEmpty: return .string(value)
        case let .int(value): return .int(value)
        case let .big(value): return .big(value.description)
        default:
            throw SscRuntimeFailure(description: "NativeUiDataTable row[\(index)] identity must be non-empty String, Int, or BigInt")
        }
    }

    private func nativeTableScalarText(_ value: SscValue, path: String) throws -> String? {
        switch value {
        case .unit: return nil
        case let .string(value): return value
        case let .int(value): return String(value)
        case let .big(value): return value.description
        case let .decimal(value): return value.description
        case let .bool(value): return value ? "true" : "false"
        default: throw SscRuntimeFailure(description: "NativeUiDataTable field " + path + " must be scalar")
        }
    }

    private func nativeTablePayloadScalarText(_ value: SscValue, path: String) throws -> String {
        switch value {
        case let .string(value): return value
        case let .int(value): return String(value)
        case let .big(value): return value.description
        case let .bool(value): return value ? "true" : "false"
        default: throw SscRuntimeFailure(description: "NativeUiDataTable payload field " + path + " must be String, Int, BigInt, or Bool")
        }
    }

    private func nativeTableCell(
        _ row: SscMap,
        column: NativeUiTableColumn,
        index: Int,
        locale: Locale,
        timeZone: TimeZone
    ) throws -> NativeUiTableCellValue {
        let raw = try nativeTableDotted(row, path: column.fieldPath, missingAllowed: true)
        let text: String
        if column.kind == "money" {
            switch raw {
            case .unit: text = ""
            case let .string(value): text = value
            case let .int(value): text = String(value)
            case let .big(value): text = value.description
            case let .decimal(value): text = value.description
            case let .float(value) where value.isFinite: text = String(value)
            default: throw SscRuntimeFailure(description: "NativeUiDataTable money field must be finite numeric or String")
            }
        } else {
            text = try nativeTableScalarText(raw, path: column.fieldPath) ?? ""
        }
        var primary = text
        var secondary: String?
        var link: URL?
        var color: String?
        switch column.kind {
        case "text": break
        case "date":
            guard case let .string(format) = column.options.get(.string("format")) else { preconditionFailure("validated date options") }
            if !text.isEmpty, let date = nativeTableDate(text, timeZone: timeZone) {
                let output = DateFormatter()
                output.locale = locale; output.timeZone = timeZone
                switch format {
                case "": output.dateStyle = .medium; output.timeStyle = .none
                case "short": output.dateStyle = .short; output.timeStyle = .none
                case "medium": output.dateStyle = .medium; output.timeStyle = .none
                case "long": output.dateStyle = .long; output.timeStyle = .none
                case "full": output.dateStyle = .full; output.timeStyle = .none
                default: output.dateFormat = format
                }
                primary = output.string(from: date)
            }
        case "money":
            guard case let .string(currency) = column.options.get(.string("currency")),
                  case let .string(localeId) = column.options.get(.string("locale")) else { preconditionFailure("validated money options") }
            let decimalText: String?
            switch raw {
            case .unit: decimalText = nil
            case let .int(value): decimalText = String(value)
            case let .big(value): decimalText = value.description
            case let .decimal(value): decimalText = value.description
            case let .float(value) where value.isFinite: decimalText = String(value)
            case let .string(value): decimalText = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")) == nil ? nil : value
            default: throw SscRuntimeFailure(description: "NativeUiDataTable money field must be numeric or numeric String")
            }
            if let decimalText, let number = Decimal(string: decimalText, locale: Locale(identifier: "en_US_POSIX")) {
                let formatter = NumberFormatter()
                formatter.numberStyle = .currency
                formatter.currencyCode = currency.uppercased()
                formatter.locale = localeId.isEmpty ? locale : Locale(identifier: localeId)
                guard let formatted = formatter.string(from: number as NSDecimalNumber) else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable money formatter failed")
                }
                primary = formatted
            }
        case "status":
            if let rawMap = column.options.get(.string("colorMap")), case let .map(colors) = rawMap,
               let mapped = colors.get(.string(text)), case let .string(value) = mapped { color = value }
        case "link":
            guard case let .string(template) = column.options.get(.string("urlTemplate")) else { preconditionFailure("validated link options") }
            let destination: String
            if template.isEmpty { destination = text }
            else {
                var allowed = CharacterSet.alphanumerics
                allowed.insert(charactersIn: "-_.!~*'()")
                guard let encoded = text.addingPercentEncoding(withAllowedCharacters: allowed) else {
                    throw SscRuntimeFailure(description: "NativeUiDataTable link value cannot be encoded")
                }
                destination = template.replacingOccurrences(of: ":value", with: encoded)
            }
            guard text.isEmpty || safeExternalURL(destination) != nil else {
                throw SscRuntimeFailure(description: "NativeUiDataTable link target is unsafe")
            }
            if !text.isEmpty { link = safeExternalURL(destination) }
        case "stacked":
            guard case let .string(subPath) = column.options.get(.string("subFieldPath")) else { preconditionFailure("validated stacked options") }
            let subValue = try nativeTableDotted(row, path: subPath, missingAllowed: true)
            let subText = try nativeTableScalarText(subValue, path: subPath) ?? ""
            if !subText.isEmpty { secondary = subText }
        default: preconditionFailure("validated table column kind")
        }
        return NativeUiTableCellValue(
            primary: primary, secondary: secondary, link: link,
            statusColor: color, alignment: column.alignment)
    }

    private func nativeTableDate(_ text: String, timeZone: TimeZone) -> Date? {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let value = iso.date(from: text) { return value }
        iso.formatOptions = [.withInternetDateTime]
        if let value = iso.date(from: text) { return value }
        let exact = DateFormatter()
        exact.locale = Locale(identifier: "en_US_POSIX")
        exact.timeZone = timeZone
        exact.dateFormat = "yyyy-MM-dd"
        exact.isLenient = false
        guard let value = exact.date(from: text), exact.string(from: value) == text else { return nil }
        return value
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

    private func onlineSignalForKey(_ key: String) -> SscValue? {
        let parts = key.split(separator: "\u{0}", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2,
              session?.signalKind(scope: String(parts[0]), id: String(parts[1])) == "online" else { return nil }
        return session?.signal(scope: String(parts[0]), id: String(parts[1]))
    }
}

@MainActor
final class NativeUiTableModel: ObservableObject {
    struct ActionState {
        let phase: String
        let error: String?
    }

    private unowned let store: NativeUiStore
    @Published private(set) var descriptor: NativeUiTableDescriptor?
    let ownerPath: String
    @Published private(set) var snapshot: NativeUiTableSnapshot
    @Published private(set) var actionStates: [String: ActionState] = [:]
    private var descriptorSignature: String
    private var observedCells: [NativeUiObservableCell]
    private var observers: [AnyCancellable] = []
    private var sourceToken: NativeUiSubscriptionToken?
    private var editRevisions: [String: UInt64] = [:]
    private var nextEditRevision: UInt64 = 0
    private var mounted = false

    init(store: NativeUiStore, value: SscValue, ownerPath: String) {
        self.store = store
        self.ownerPath = ownerPath
        self.descriptorSignature = store.nativeTableDescriptorSignature(value)
        do {
            let decoded = try store.decodeNativeTable(value)
            descriptor = decoded
            observedCells = store.nativeTableObservedSignals(decoded).map { store.cell(for: $0) }
            snapshot = store.nativeTableSnapshot(decoded)
        } catch {
            descriptor = nil
            observedCells = []
            let diagnostic = store.nativeTableDiagnostic(error, value: value)
            snapshot = NativeUiTableSnapshot(
                rows: [], status: diagnostic, error: diagnostic)
        }
    }

    func mount() {
        mounted = true
        guard let descriptor else { return }
        store.installNativeTableCapability(
            ownerPath: ownerPath, descriptor: descriptor, signature: descriptorSignature,
            rows: snapshot.rows)
        if sourceToken == nil, let sourceCell = observedCells.first {
            sourceToken = store.subscribe(sourceCell)
        }
        if observers.isEmpty {
            observers = observedCells.map { cell in
                cell.objectWillChange.sink { [weak self] _ in
                    Task { @MainActor [weak self] in self?.refresh() }
                }
            }
        }
        apply(store.nativeTableSnapshot(descriptor, retaining: snapshot.rows))
    }

    func unmount() {
        mounted = false
        if let sourceToken { store.unsubscribe(sourceToken); self.sourceToken = nil }
        observers.removeAll()
        if let descriptor {
            store.removeNativeTableCapability(
                ownerPath: ownerPath, siteId: descriptor.siteId, signature: descriptorSignature)
        }
    }

    func update(_ value: SscValue) {
        let nextSignature = store.nativeTableDescriptorSignature(value)
        guard nextSignature != descriptorSignature else { return }
        let wasMounted = mounted
        do {
            let decoded = try store.decodeNativeTable(value)
            let candidate = store.nativeTableSnapshot(decoded, retaining: snapshot.rows)
            guard candidate.error == nil else {
                snapshot = NativeUiTableSnapshot(
                    rows: snapshot.rows, status: candidate.status, error: candidate.error)
                return
            }
            if wasMounted { unmount() }
            descriptorSignature = nextSignature
            descriptor = decoded
            observedCells = store.nativeTableObservedSignals(decoded).map { store.cell(for: $0) }
            snapshot = candidate
            actionStates.removeAll()
            editRevisions.removeAll()
            if wasMounted { mount() }
        } catch {
            let diagnostic = store.nativeTableDiagnostic(error, value: value)
            snapshot = NativeUiTableSnapshot(
                rows: snapshot.rows,
                status: diagnostic, error: diagnostic)
        }
    }

    func refresh() {
        guard let descriptor else { return }
        apply(store.nativeTableSnapshot(descriptor, retaining: snapshot.rows))
    }

    func actionState(row: NativeUiTableRow, index: Int) -> ActionState {
        actionStates[actionKey(row: row, index: index)] ?? ActionState(phase: "idle", error: nil)
    }

    func run(_ action: NativeUiTableAction, row: NativeUiTableRow, index: Int) {
        guard let descriptor else { return }
        let key = actionKey(row: row, index: index)
        store.runNativeTableAction(
            action, row: row, actionIndex: index, ownerPath: ownerPath, siteId: descriptor.siteId,
            descriptorSignature: descriptorSignature
        ) { [weak self] phase, error in
            self?.actionStates[key] = ActionState(phase: phase, error: error)
        }
    }

    func beginEdit(row: NativeUiTableRow, columnIndex: Int) -> UInt64 {
        nextEditRevision &+= 1
        let key = editKey(row: row, columnIndex: columnIndex)
        editRevisions[key] = nextEditRevision
        return nextEditRevision
    }

    func commitEdit(
        row: NativeUiTableRow,
        column: NativeUiTableColumn,
        columnIndex: Int,
        revision: UInt64,
        value: String
    ) {
        let key = editKey(row: row, columnIndex: columnIndex)
        guard editRevisions[key] == revision, let rawAction = column.editAction,
              let action = try? store.decodeNativeTableActionForEdit(rawAction, columnIndex: columnIndex),
              let descriptor else { return }
        editRevisions.removeValue(forKey: key)
        let actionIndex = descriptor.actions.count + columnIndex
        let stateKey = actionKey(row: row, index: actionIndex)
        store.runNativeTableAction(
            action, row: row, actionIndex: actionIndex, ownerPath: ownerPath,
            siteId: descriptor.siteId, descriptorSignature: descriptorSignature,
            editField: column.fieldPath, editValue: value
        ) { [weak self] phase, error in
            self?.actionStates[stateKey] = ActionState(phase: phase, error: error)
        }
    }

    private func actionKey(row: NativeUiTableRow, index: Int) -> String {
        row.identity.description + "\u{0}" + String(index)
    }

    private func editKey(row: NativeUiTableRow, columnIndex: Int) -> String {
        row.identity.description + "\u{0}" + String(columnIndex)
    }

    private func apply(_ next: NativeUiTableSnapshot) {
        if next.error == nil, let descriptor {
            let old = Set(snapshot.rows.map(\.identity))
            let retained = Set(next.rows.map(\.identity))
            for identity in old.subtracting(retained) {
                store.cancelNativeTableRowTasks(
                    ownerPath: ownerPath, siteId: descriptor.siteId,
                    descriptorSignature: descriptorSignature, identity: identity)
                let prefix = identity.description + "\u{0}"
                actionStates = actionStates.filter { !$0.key.hasPrefix(prefix) }
                editRevisions = editRevisions.filter { !$0.key.hasPrefix(prefix) }
            }
            store.updateNativeTableCapabilityRows(
                ownerPath: ownerPath, siteId: descriptor.siteId,
                signature: descriptorSignature, rows: next.rows)
        }
        snapshot = next
    }
}
"""

  val storeSource: String = storeSourcePart1 + storeSourcePart2 + storeSourcePart3

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
        "data-ssc-raw-html", "disabled", "hidden", "href", "id", "placeholder",
        "required", "role", "selected", "src", "start", "style", "text-align",
        "title", "type", "value"
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
            do {
                let descriptor = try NativeUiHtmlAdapter.decode(fields, store: store)
                return AnyView(NativeUiTrustedHtmlView(
                    html: descriptor.html,
                    source: descriptor.source,
                    safeExternalURL: { store.safeExternalURL($0) },
                    openURL: { openURL($0) }))
            } catch {
                return unsupported(String(describing: error))
            }
        case "NativeUiDataTable" where fields.count == 5:
            return AnyView(NativeUiDataTableView(
                store: store, value: value,
                ownerPath: store.ownerPath(for: value, fallback: ownerPath)))
        case "NativeUiDataTable":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("malformed NativeUiDataTable at " + store.source(site))
        case "NativeUiUnsupported" where fields.count == 3:
            return unsupported(store.string(fields[0]) + " at " + store.source(fields[1]) + ": " + store.string(fields[2]))
        case "NativeUiElement":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("malformed NativeUiElement at " + store.source(site))
        case "NativeUiForKeyed":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("malformed NativeUiForKeyed at " + store.source(site))
        case "NativeUiTrustedHtml":
            let site = fields.first.map(store.string) ?? ""
            return unsupported("malformed NativeUiTrustedHtml at " + store.source(site))
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
        if let issue = Self.reactiveAttributeDiagnostic(attrs, source: source) {
            return unsupported(issue)
        }
        let content: AnyView
        switch tag {
        case "div" where style(attrs, "flex-direction") == "row" && style(attrs, "flex-wrap") == "wrap":
            // HTML flex-row with wrapping. SwiftUI has no flex-wrap, so the faithful
            // equivalent is a real wrapping flow layout (custom `Layout`, macOS 13/iOS 16)
            // rather than the non-wrapping HStack used for the plain flex-row below.
            content = AnyView(NativeUiFlowLayout(spacing: gap(attrs)) { renderChildren(children) })
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
        case "table":
            content = renderSemanticTable(children, source: source, nodeOwnerPath: nodeOwnerPath)
        case "thead", "tbody", "tr", "th", "td":
            return unsupported("<" + tag + "> is only valid inside a <table> at " + source)
        case "select":
            let options: [NativeUiSelectOption]
            do { options = try Self.decodeSelectOptions(children, source: source, store: store) }
            catch let error as SelectOptionsInvalid { return unsupported(error.message) }
            catch { return unsupported(String(describing: error)) }
            if let bound = attrs.get(.string("value")), case .data("NativeUiSignal", _) = bound {
                content = AnyView(NativeUiSelectControl(
                    store: store, signal: bound, options: options, events: events,
                    siteId: store.string(fields[0]), ownerPath: nodeOwnerPath))
            } else {
                // No two-way binding — render a real, read-only dropdown pinned to the
                // pre-selected <option> rather than a stub.
                let current = options.first(where: { $0.selected })?.value ?? options.first?.value ?? ""
                content = AnyView(Picker("", selection: .constant(current)) {
                    ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                        Text(option.label).tag(option.value)
                    }
                }.pickerStyle(.menu).labelsHidden().disabled(true))
            }
        case "option":
            return unsupported("<option> is only valid inside a <select> at " + source)
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

    private func renderSemanticTable(_ children: [SscValue], source: String, nodeOwnerPath: String) -> AnyView {
        let model: SemanticTableModel
        do {
            model = try Self.decodeSemanticTable(children, source: source, store: store)
        } catch let error as SemanticTableInvalid {
            return unsupported(error.message)
        } catch {
            return unsupported(String(describing: error))
        }
        return AnyView(ScrollView(.horizontal) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                ForEach(Array(model.headerRows.enumerated()), id: \.offset) { _, cells in
                    GridRow {
                        ForEach(Array(0..<model.columns), id: \.self) { column in
                            semanticTableCell(
                                column < cells.count ? cells[column] : nil,
                                source: source, nodeOwnerPath: nodeOwnerPath)
                        }
                    }
                }
                if !model.headerRows.isEmpty { Divider() }
                ForEach(Array(model.bodyRows.enumerated()), id: \.offset) { _, cells in
                    GridRow {
                        ForEach(Array(0..<model.columns), id: \.self) { column in
                            semanticTableCell(
                                column < cells.count ? cells[column] : nil,
                                source: source, nodeOwnerPath: nodeOwnerPath)
                        }
                    }
                }
            }
        })
    }

    private func semanticTableCell(_ cell: SscValue?, source: String, nodeOwnerPath: String) -> AnyView {
        guard let cell else { return AnyView(Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])) }
        guard case let .data("NativeUiElement", cellFields) = cell, cellFields.count == 5,
              case let .map(attrs) = cellFields[2], case let .map(events) = cellFields[3],
              let inline = properList(cellFields[4]) else {
            return unsupported("malformed table cell at " + source)
        }
        let siteId = store.string(cellFields[0])
        if let issue = Self.validate(attrs, names: Self.supportedAttributes, kind: "attribute") {
            return unsupported(issue + " at " + store.source(siteId))
        }
        if let issue = Self.validate(events, names: Self.supportedEventSlots, kind: "event slot") {
            return unsupported(issue + " at " + store.source(siteId))
        }
        let inner = VStack(alignment: .leading, spacing: 0) { renderChildren(inline) }
        let content: AnyView = store.string(cellFields[1]) == "th" ? AnyView(inner.bold()) : AnyView(inner)
        let styled = NativeUiStyles.apply(content, attrs: attrs, store: store, siteId: siteId)
        if events.entries.isEmpty { return styled }
        return AnyView(Button(action: {
            runEvents(events, siteId: siteId, nodeOwnerPath: nodeOwnerPath)
        }) { styled }.buttonStyle(.plain))
    }

    static func decodeSemanticTable(
        _ children: [SscValue], source: String, store: NativeUiStore
    ) throws -> SemanticTableModel {
        func rows(of value: SscValue) -> [SscValue]? {
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
        func rowCells(_ row: SscValue) throws -> [SscValue] {
            guard case let .data("NativeUiElement", rowFields) = row, rowFields.count == 5,
                  store.string(rowFields[1]) == "tr", let cells = rows(of: rowFields[4]) else {
                throw SemanticTableInvalid(message: "table row must be <tr> at " + source)
            }
            for cell in cells {
                guard case let .data("NativeUiElement", cellFields) = cell, cellFields.count == 5,
                      store.string(cellFields[1]) == "th" || store.string(cellFields[1]) == "td" else {
                    throw SemanticTableInvalid(message: "table cell must be <th> or <td> at " + source)
                }
            }
            return cells
        }
        var headerRows: [[SscValue]] = [], bodyRows: [[SscValue]] = []
        for child in children {
            guard case let .data("NativeUiElement", groupFields) = child, groupFields.count == 5 else {
                throw SemanticTableInvalid(message: "table child must be <thead>, <tbody>, or <tr> at " + source)
            }
            let groupTag = store.string(groupFields[1])
            switch groupTag {
            case "thead", "tbody":
                guard let sectionRows = rows(of: groupFields[4]) else {
                    throw SemanticTableInvalid(message: "table section must be a proper List at " + source)
                }
                for row in sectionRows {
                    let cells = try rowCells(row)
                    if groupTag == "thead" { headerRows.append(cells) } else { bodyRows.append(cells) }
                }
            case "tr":
                bodyRows.append(try rowCells(child))
            default:
                throw SemanticTableInvalid(message: "table child must be <thead>, <tbody>, or <tr> at " + source)
            }
        }
        let columns = (headerRows + bodyRows).map(\.count).max() ?? 0
        guard !headerRows.isEmpty || !bodyRows.isEmpty else {
            throw SemanticTableInvalid(message: "semantic table requires at least one row at " + source)
        }
        guard columns > 0 else {
            throw SemanticTableInvalid(message: "semantic table requires at least one cell at " + source)
        }
        return SemanticTableModel(headerRows: headerRows, bodyRows: bodyRows, columns: columns)
    }

    struct SemanticTableModel {
        let headerRows: [[SscValue]]
        let bodyRows: [[SscValue]]
        let columns: Int
    }

    struct SemanticTableInvalid: Error {
        let message: String
    }

    struct SelectOptionsInvalid: Error {
        let message: String
    }

    // Decode the <option> children of a <select> into value/label pairs. Each child
    // must be a NativeUiElement whose tag is "option" and whose own children are plain
    // text (the label). Anything else has no faithful Picker mapping and becomes a
    // sourced Unsupported, matching the strict semantic-table decoding.
    static func decodeSelectOptions(
        _ children: [SscValue], source: String, store: NativeUiStore
    ) throws -> [NativeUiSelectOption] {
        func plainText(_ nodes: [SscValue]) -> String {
            var text = ""
            for node in nodes {
                switch node {
                case let .data("NativeUiText", inner) where inner.count == 1:
                    text += store.string(inner[0])
                case let .data("NativeUiFragment", inner) where inner.count == 1:
                    var current = inner[0], gathered: [SscValue] = []
                    while case let .data("Cons", pair) = current, pair.count == 2 {
                        gathered.append(pair[0]); current = pair[1]
                    }
                    text += plainText(gathered)
                default: break
                }
            }
            return text
        }
        var options: [NativeUiSelectOption] = []
        for child in children {
            guard case let .data("NativeUiElement", optionFields) = child, optionFields.count == 5,
                  store.string(optionFields[1]) == "option" else {
                throw SelectOptionsInvalid(message: "<select> child must be <option> at " + source)
            }
            guard case let .map(optionAttrs) = optionFields[2],
                  let inline = { () -> [SscValue]? in
                      var current = optionFields[4], result: [SscValue] = []
                      while true {
                          switch current {
                          case let .data("Cons", pair) where pair.count == 2:
                              result.append(pair[0]); current = pair[1]
                          case .data("Nil", _): return result
                          default: return nil
                          }
                      }
                  }() else {
                throw SelectOptionsInvalid(message: "malformed <option> at " + source)
            }
            func optBool(_ name: String) -> Bool {
                optionAttrs.get(.string(name)).map(store.bool) ?? false
            }
            options.append(NativeUiSelectOption(
                value: optionAttrs.get(.string("value")).map(store.string) ?? "",
                label: plainText(inline),
                disabled: optBool("disabled"),
                hidden: optBool("hidden"),
                selected: optBool("selected")))
        }
        return options
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

    // A reactive (signal-bound) attribute is honored only when it has a faithful
    // native binding: the two-way input controls (`value`/`checked`) or one of the
    // one-way modifiers resolved by `NativeUiStyles` (see `reactiveAttributes`).
    // Any other signal-bound attribute has no faithful SwiftUI mapping and stays a
    // strict, sourced Unsupported rather than a silent no-op.
    static func reactiveAttributeDiagnostic(_ attrs: SscMap, source: String) -> String? {
        for (key, value) in attrs.entries {
            guard case let .string(name) = key,
                  case .data("NativeUiSignal", _) = value else { continue }
            if name == "value" || name == "checked" ||
                NativeUiStyles.reactiveAttributes.contains(name) { continue }
            return "reactive attribute " + name + " is not mapped at " + source
        }
        return nil
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

struct NativeUiSelectOption {
    let value: String
    let label: String
    let disabled: Bool
    let hidden: Bool
    let selected: Bool
}

// Faithful <select> renderer: a menu-style Picker two-way bound to the value Signal,
// exactly mirroring the reactive plumbing of NativeUiTextControl/NativeUiToggleControl
// (subscribe to the bound cell on appear; on selection change run the element's
// `change` events with the picked value as `input`, which is how the web `<select>`
// lowers via `inputChange(selected)`). The placeholder <option> (value "") is kept as a
// tagged menu entry so the current-selection label still reads correctly while the
// signal is empty — SwiftUI's Picker cannot hide an individual entry, so its HTML
// `hidden` attribute is the one honest approximation here (it stays selectable-to-clear
// instead of disappearing after the first pick).
@MainActor
private struct NativeUiSelectControl: View {
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    let options: [NativeUiSelectOption]
    let events: SscMap
    let siteId: String
    let ownerPath: String
    @State private var token: NativeUiSubscriptionToken?

    init(store: NativeUiStore, signal: SscValue, options: [NativeUiSelectOption], events: SscMap, siteId: String, ownerPath: String) {
        self.store = store
        self.cell = store.cell(for: signal)
        self.options = options
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
                Picker("", selection: Binding(
                    get: { store.string(cell.read()) },
                    set: { next in
                        for (_, event) in events.entries {
                            NativeUiActions.run(
                                event, input: next, store: store, siteId: siteId,
                                ownerPath: store.actionOwnerPath(for: event, mountedAt: ownerPath))
                        }
                    }
                )) {
                    ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                        Text(option.label).tag(option.value)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
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

// SwiftUI has no flex-wrap; this is the faithful equivalent of a wrapping CSS flex-row —
// a real `Layout` (macOS 13/iOS 16, same floor the semantic-table `Grid` already
// requires) that flows subviews left-to-right and wraps to a new line when the next one
// would overflow the proposed width, honoring the same `gap` spacing the HStack path uses.
private struct NativeUiFlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0
        var totalHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth > 0 && rowWidth + spacing + size.width > maxWidth {
                totalWidth = max(totalWidth, rowWidth)
                totalHeight += rowHeight + spacing
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += (rowWidth > 0 ? spacing : 0) + size.width
                rowHeight = max(rowHeight, size.height)
            }
        }
        totalWidth = max(totalWidth, rowWidth)
        totalHeight += rowHeight
        return CGSize(width: min(totalWidth, maxWidth), height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
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
        }
        .onDisappear { if let token { store.unsubscribe(token); self.token = nil } }
        .task(id: itemsCell.revision) {
            if itemsCell.renderedDiagnostic() == nil { model.refresh(itemsCell.read()) }
        }
    }
}

@MainActor
private struct NativeUiDataTableView: View {
    @ObservedObject var store: NativeUiStore
    @StateObject private var model: NativeUiTableModel
    let value: SscValue

    init(store: NativeUiStore, value: SscValue, ownerPath: String) {
        self.store = store
        self.value = value
        self._model = StateObject(wrappedValue: NativeUiTableModel(
            store: store, value: value, ownerPath: ownerPath))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let descriptor = model.descriptor {
                ScrollView(.horizontal) {
                    Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                        GridRow {
                            ForEach(Array(descriptor.columns.enumerated()), id: \.offset) { _, column in
                                Text(column.title).bold()
                            }
                            if !descriptor.actions.isEmpty { Text("Actions").bold() }
                        }
                        Divider()
                        ForEach(model.snapshot.rows) { row in
                            GridRow {
                                ForEach(Array(descriptor.columns.enumerated()), id: \.offset) { index, column in
                                    NativeUiTableCellView(
                                        model: model, row: row, column: column,
                                        columnIndex: index, cell: row.cells[index])
                                }
                                if !descriptor.actions.isEmpty {
                                    HStack(spacing: 6) {
                                        ForEach(Array(descriptor.actions.enumerated()), id: \.offset) { index, action in
                                            let state = model.actionState(row: row, index: index)
                                            Button(action.label) { model.run(action, row: row, index: index) }
                                                .disabled(state.phase == "loading")
                                            if state.phase == "loading" { ProgressView().controlSize(.small) }
                                            if let error = state.error { Text(error).foregroundStyle(.red) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if let status = model.snapshot.status {
                Text(status).foregroundStyle(model.snapshot.error == nil ? Color.secondary : Color.red)
            }
        }
        .onAppear { model.mount() }
        .onDisappear { model.unmount() }
        .task(id: store.nativeTableDescriptorSignature(value)) { model.update(value) }
    }
}

@MainActor
private struct NativeUiTableCellView: View {
    @ObservedObject var model: NativeUiTableModel
    let row: NativeUiTableRow
    let column: NativeUiTableColumn
    let columnIndex: Int
    let cell: NativeUiTableCellValue

    var body: some View {
        Group {
            if column.editAction != nil {
                NativeUiEditableTableCell(
                    model: model, row: row, column: column,
                    columnIndex: columnIndex, initial: cell.primary)
            } else if let link = cell.link {
                Link(cell.primary, destination: link)
            } else {
                VStack(alignment: .leading, spacing: 1) {
                    Text(cell.primary)
                    if let secondary = cell.secondary {
                        Text(secondary).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(cell.statusColor == nil ? 0 : 4)
                .background(nativeUiTableColor(cell.statusColor))
            }
        }
        .frame(maxWidth: .infinity, alignment: nativeUiTableAlignment(cell.alignment))
    }
}

@MainActor
private struct NativeUiEditableTableCell: View {
    @ObservedObject var model: NativeUiTableModel
    let row: NativeUiTableRow
    let column: NativeUiTableColumn
    let columnIndex: Int
    @State private var text: String
    @State private var revision: UInt64?
    @FocusState private var focused: Bool

    init(
        model: NativeUiTableModel,
        row: NativeUiTableRow,
        column: NativeUiTableColumn,
        columnIndex: Int,
        initial: String
    ) {
        self.model = model
        self.row = row
        self.column = column
        self.columnIndex = columnIndex
        self._text = State(initialValue: initial)
    }

    var body: some View {
        TextField("", text: $text)
            .focused($focused)
            .onSubmit { commit() }
            .task(id: focused) {
                if focused { revision = model.beginEdit(row: row, columnIndex: columnIndex) }
                else { commit() }
            }
    }

    private func commit() {
        guard let revision else { return }
        self.revision = nil
        model.commitEdit(
            row: row, column: column, columnIndex: columnIndex,
            revision: revision, value: text)
    }
}

private func nativeUiTableAlignment(_ value: String) -> Alignment {
    switch value {
    case "center": return .center
    case "trailing": return .trailing
    default: return .leading
    }
}

private func nativeUiTableColor(_ value: String?) -> Color {
    guard let value else { return .clear }
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
                    blue: Double(number & 255) / 255)
            }
        }
        if let (red, green, blue, alpha) = NativeUiColorGrammar.rgba(raw) {
            return Color(red: red, green: green, blue: blue, opacity: alpha)
        }
        return .clear
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
        "display", "flex-direction", "flex-wrap", "align-items", "justify-content", "gap", "flex", "flex-grow",
        "padding", "padding-left", "padding-right", "padding-top", "padding-bottom",
        "margin", "margin-left", "margin-right", "margin-top", "margin-bottom",
        "width", "min-width", "max-width", "height", "min-height", "max-height",
        "color", "background", "background-color", "border", "border-top", "border-right",
        "border-bottom", "border-left", "border-color", "border-top-color", "border-radius",
        "font-size", "font-family", "font-weight", "opacity", "text-decoration", "white-space",
        "overflow", "overflow-x", "text-align", "position", "inset", "z-index", "box-shadow",
        "box-sizing", "border-collapse", "cursor", "user-select"
    ]

    // Attributes whose signal-bound (reactive) form has a faithful native mapping
    // resolved by `applyResolved`: the CSS `style` string plus the semantic
    // accessibility/state modifiers. Each is read as a live value and re-applied on
    // change; a signal-bound attribute NOT listed here is rejected upstream
    // (`NativeUiRenderer.reactiveAttributeDiagnostic`). `value`/`checked` are handled
    // separately as two-way input controls and are intentionally absent.
    static let reactiveAttributes: Set<String> = [
        "style", "disabled", "aria-disabled", "title", "aria-label", "required", "aria-modal"
    ]

    // Clone `attrs`, replacing every reactive attribute's signal with its current
    // live value so `applyResolved` sees a plain resolved value (a String/Bool) and
    // applies the real SwiftUI modifier. This is the single resolution both the live
    // `NativeUiReactiveView` and its tests share.
    @MainActor
    static func resolvedAttributes(_ attrs: SscMap, store: NativeUiStore) -> SscMap {
        let resolved = SscMap()
        for (key, value) in attrs.entries {
            if case let .string(name) = key, case .data("NativeUiSignal", _) = value,
               reactiveAttributes.contains(name) {
                resolved.put(key, store.read(value))
            } else {
                resolved.put(key, value)
            }
        }
        return resolved
    }

    @MainActor
    static func apply(
        _ content: AnyView,
        attrs: SscMap,
        store: NativeUiStore,
        siteId: String
    ) -> AnyView {
        var reactive: [SscValue] = []
        for (key, value) in attrs.entries {
            guard case let .string(name) = key, reactiveAttributes.contains(name),
                  case .data("NativeUiSignal", _) = value else { continue }
            reactive.append(value)
        }
        if reactive.isEmpty {
            return applyResolved(content, attrs: attrs, store: store, siteId: siteId)
        }
        return AnyView(NativeUiReactiveView(
            content: content, attrs: attrs, pending: reactive, store: store, siteId: siteId
        ))
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
        // `width:100%` / `height:100%` mean "fill the available cross-axis extent" — the
        // faithful SwiftUI mapping is an infinite max in that axis (used by the `.ssc`
        // std/ui textField/select/table styles, which all emit width:100%).
        if declarations["width"] == "100%" { result = AnyView(result.frame(maxWidth: .infinity)) }
        if declarations["height"] == "100%" { result = AnyView(result.frame(maxHeight: .infinity)) }
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
        let fillProperties: Set<String> = [
            "width", "min-width", "max-width", "height", "min-height", "max-height"
        ]
        for property in scalarLengths {
            if let value = values[property], pixels(value) == nil,
               !(fillProperties.contains(property) && value == "100%") {
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
        if let value = values["flex-wrap"], !["wrap", "nowrap", "wrap-reverse"].contains(value) {
            return "unsupported native CSS value flex-wrap:" + value
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
        // Cosmetic no-op properties the real std/ui toolkit emits (box-sizing,
        // border-collapse, cursor, user-select) have no SwiftUI analog, but —
        // like overflow:visible — an unrecognized value must still surface as a
        // sourced Unsupported diagnostic rather than being silently swallowed.
        for (property, accepted) in cosmeticNoOpValues {
            if let value = values[property], !accepted.contains(value) {
                return "unsupported native CSS value " + property + ":" + value
            }
        }
        return nil
    }

    private static let cosmeticNoOpValues: [(String, Set<String>)] = [
        ("box-sizing", ["content-box", "border-box"]),
        ("border-collapse", ["collapse", "separate"]),
        ("user-select", ["none", "auto", "text", "all", "contain"]),
        ("cursor", [
            "auto", "default", "none", "context-menu", "help", "pointer", "progress",
            "wait", "cell", "crosshair", "text", "vertical-text", "alias", "copy",
            "move", "no-drop", "not-allowed", "grab", "grabbing", "e-resize", "n-resize",
            "ne-resize", "nw-resize", "s-resize", "se-resize", "sw-resize", "w-resize",
            "ew-resize", "ns-resize", "nesw-resize", "nwse-resize", "col-resize",
            "row-resize", "all-scroll", "zoom-in", "zoom-out",
        ]),
    ]

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
            if let (red, green, blue, alpha) = NativeUiColorGrammar.rgba(raw) {
                return Color(red: red, green: green, blue: blue, opacity: alpha)
            }
            return nil
        }
    }
}

// Live binding for reactive (signal-bound) attributes — `style` plus the semantic
// modifiers in `NativeUiStyles.reactiveAttributes`. Each level observes one signal's
// cell (re-rendering when it changes) and subscribes/unsubscribes on appear/disappear,
// exactly as the former style-only view did; chaining one level per pending signal
// keeps that proven single-cell plumbing while supporting several reactive attributes
// on one element. The innermost level resolves every reactive attribute to its live
// value (`NativeUiStyles.resolvedAttributes`) and hands plain values to `applyResolved`,
// which applies the real modifier — or a strict Unsupported for a malformed value.
@MainActor
private struct NativeUiReactiveView: View {
    let content: AnyView
    let attrs: SscMap
    let pending: [SscValue]
    @ObservedObject var cell: NativeUiObservableCell
    @ObservedObject var store: NativeUiStore
    let siteId: String
    @State private var token: NativeUiSubscriptionToken?

    init(content: AnyView, attrs: SscMap, pending: [SscValue], store: NativeUiStore, siteId: String) {
        self.content = content
        self.attrs = attrs
        self.pending = pending
        self.cell = store.cell(for: pending[0])
        self.store = store
        self.siteId = siteId
    }

    var body: some View {
        let rendered: AnyView
        if let diagnostic = cell.renderedDiagnostic() {
            rendered = AnyView(Text(diagnostic).foregroundStyle(.red)
                .accessibilityLabel("Unsupported native UI: " + diagnostic))
        } else {
            let rest = Array(pending.dropFirst())
            if rest.isEmpty {
                rendered = NativeUiStyles.applyResolved(
                    content, attrs: NativeUiStyles.resolvedAttributes(attrs, store: store),
                    store: store, siteId: siteId)
            } else {
                rendered = AnyView(NativeUiReactiveView(
                    content: content, attrs: attrs, pending: rest, store: store, siteId: siteId))
            }
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
        guard store.isUserWritable(fields[1]) else {
            store.report("native event " + kind + " target must be writable NativeUiSignal at " + store.source(siteId))
            return
        }
        func write(_ value: SscValue) -> Bool {
            do { try store.writeUserTarget(fields[1], value); return true }
            catch {
                store.report("native event " + kind + " write failed: " + String(describing: error) + " at " + store.source(siteId))
                return false
            }
        }
        func read() -> SscValue? {
            do { return try store.readUserTarget(fields[1]) }
            catch {
                store.report("native event " + kind + " read failed: " + String(describing: error) + " at " + store.source(siteId))
                return nil
            }
        }
        switch kind {
        case "set": _ = write(fields[2])
        case "input":
            guard case .unit = fields[2] else {
                store.report("native event input payload must be Unit at " + store.source(siteId)); return
            }
            _ = write(.string(input ?? ""))
        case "toggle":
            guard case .unit = fields[2], let currentValue = read(), case let .bool(current) = currentValue else {
                store.report("native event toggle requires Unit payload and Bool target at " + store.source(siteId)); return
            }
            _ = write(.bool(!current))
        case "increment":
            guard case let .int(amount) = fields[2], let currentValue = read(), case let .int(value) = currentValue else {
                store.report("native event increment requires Int payload and Int target at " + store.source(siteId)); return
            }
            let (next, overflow) = value.addingReportingOverflow(amount)
            guard !overflow else {
                store.report("native event increment would overflow Int64 at " + store.source(siteId)); return
            }
            _ = write(.int(next))
        default: preconditionFailure("validated NativeUiEvent kind")
        }
    }
}

"""

  val htmlSource: String = """import Foundation
import SwiftUI
import WebKit

@MainActor
enum NativeUiHtmlAdapter {
    static let minimumHeight: CGFloat = 1
    static let maximumHeight: CGFloat = 100000
    static let messageName = "sscNativeUiHeight"
    static let contentRuleIdentifier = "ssc.nativeui.trusted-html.network-v1"
    static let contentRuleJSON = "[" + ["http", "https", "ws", "wss", "ftp"].map { scheme in
        "{\"trigger\":{\"url-filter\":\"^\(scheme)://\",\"resource-type\":[\"image\",\"style-sheet\",\"script\",\"font\",\"media\",\"raw\",\"svg-document\"]},\"action\":{\"type\":\"block\"}}"
    }.joined(separator: ",") + "]"

    static func bounded(_ value: String) -> String {
        String(value.unicodeScalars.prefix(1024))
    }

    static func clampHeight(_ value: Double) -> CGFloat? {
        guard value.isFinite, value > 0 else { return nil }
        return CGFloat(min(max(value, Double(minimumHeight)), Double(maximumHeight)))
    }

    static func document(_ html: String) -> String {
        "<style>html,body{margin:0!important;overflow:hidden!important;}</style>" + html
    }

    static func revisionKey(html: String, source: String) -> String {
        String(source.utf8.count) + ":" + source + html
    }

    static func externalURL(
        _ text: String,
        navigationType: WKNavigationType,
        safeExternalURL: (String) -> URL?
    ) -> URL? {
        guard navigationType == .linkActivated else { return nil }
        return safeExternalURL(text)
    }

    static func decode(_ fields: SscFields, store: NativeUiStore) throws -> (html: String, source: String) {
        let candidateSite = fields.count > 0 ? store.string(fields[0]) : ""
        let candidateSource = store.source(candidateSite)
        guard fields.count == 2,
              case let .string(siteId) = fields[0],
              case let .string(html) = fields[1],
              store.source(siteId) != "<unknown source>" else {
            throw SscRuntimeFailure(
                description: "malformed NativeUiTrustedHtml at " + candidateSource)
        }
        return (html, store.source(siteId))
    }

    static func heightScript(generation: UInt64) -> String {
        [
            "(() => {",
            "  if (globalThis.__sscNativeUiHeightObserver) globalThis.__sscNativeUiHeightObserver.disconnect();",
            "  const generation = " + String(generation) + ";",
            "  const publish = () => {",
            "    const body = document.body; const rect = body ? body.getBoundingClientRect() : null;",
            "    const range = document.createRange(); if (body) range.selectNodeContents(body);",
            "    let height = body ? range.getBoundingClientRect().height : 0;",
            "    if (body && rect) for (const child of body.children) height = Math.max(height, child.getBoundingClientRect().bottom - rect.top);",
            "    height = Math.max(1, height);",
            "    webkit.messageHandlers." + messageName + ".postMessage({generation, height});",
            "  };",
            "  const observer = new ResizeObserver(publish);",
            "  if (document.documentElement) observer.observe(document.documentElement);",
            "  if (document.body) observer.observe(document.body);",
            "  globalThis.__sscNativeUiHeightObserver = observer; publish();",
            "})();",
        ].joined(separator: "\n")
    }
}

@MainActor
private final class NativeUiHtmlMessageProxy: NSObject, WKScriptMessageHandler {
    weak var target: NativeUiHtmlCoordinator?

    init(target: NativeUiHtmlCoordinator) { self.target = target }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.receiveHeight(message)
    }
}

enum NativeUiHtmlDocumentPolicyDecision: Equatable {
    case unrelated
    case allow
    case cancel
}

struct NativeUiHtmlDocumentPolicyGate {
    private(set) var awaitingGeneration: UInt64?

    mutating func begin(generation: UInt64) -> Bool {
        guard awaitingGeneration == nil else { return false }
        awaitingGeneration = generation
        return true
    }

    mutating func consume(currentGeneration: UInt64) -> NativeUiHtmlDocumentPolicyDecision {
        guard let candidate = awaitingGeneration else { return .unrelated }
        awaitingGeneration = nil
        return candidate == currentGeneration ? .allow : .cancel
    }

    @discardableResult
    mutating func cancel(generation: UInt64) -> Bool {
        guard awaitingGeneration == generation else { return false }
        awaitingGeneration = nil
        return true
    }

    mutating func reset() { awaitingGeneration = nil }
}

@MainActor
final class NativeUiHtmlCoordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
    private weak var webView: WKWebView?
    private var controller: WKUserContentController?
    private lazy var messageProxy = NativeUiHtmlMessageProxy(target: self)
    private var generation: UInt64 = 0
    private var documentPolicyGate = NativeUiHtmlDocumentPolicyGate()
    private var preparedLoad: (generation: UInt64, html: String, rule: WKContentRuleList)?
    private var loadingGeneration: UInt64?
    private var activeNavigation: (generation: UInt64, handle: WKNavigation)?
    private var lastHTML: String?
    private var lastSource: String?
    private var mounted = false
    #if !os(macOS)
    private var contentSizeObservation: NSKeyValueObservation?
    #endif
    private var safeExternalURL: (String) -> URL?
    private var openURL: (URL) -> Void
    private var publishHeight: (CGFloat) -> Void
    private var publishError: (String?) -> Void
    #if SSC_NATIVEUI_HTML_PROBE
    private var probeRuleCompiler: ((@escaping (WKContentRuleList?, Error?) -> Void) -> Void)?
    private var probeNavigationLoader: ((WKWebView, String) -> WKNavigation?)?
    private var probeIssuedNavigations: [WKNavigation] = []
    #endif

    init(
        safeExternalURL: @escaping (String) -> URL?,
        openURL: @escaping (URL) -> Void,
        publishHeight: @escaping (CGFloat) -> Void,
        publishError: @escaping (String?) -> Void
    ) {
        self.safeExternalURL = safeExternalURL
        self.openURL = openURL
        self.publishHeight = publishHeight
        self.publishError = publishError
    }

    #if SSC_NATIVEUI_HTML_PROBE
    convenience init(
        safeExternalURL: @escaping (String) -> URL?,
        openURL: @escaping (URL) -> Void,
        publishHeight: @escaping (CGFloat) -> Void,
        publishError: @escaping (String?) -> Void,
        _probeRuleCompiler: @escaping (@escaping (WKContentRuleList?, Error?) -> Void) -> Void,
        _probeNavigationLoader: ((WKWebView, String) -> WKNavigation?)? = nil
    ) {
        self.init(
            safeExternalURL: safeExternalURL,
            openURL: openURL,
            publishHeight: publishHeight,
            publishError: publishError)
        probeRuleCompiler = _probeRuleCompiler
        probeNavigationLoader = _probeNavigationLoader
    }

    func probeNavigation(at index: Int) -> WKNavigation? {
        guard probeIssuedNavigations.indices.contains(index) else { return nil }
        return probeIssuedNavigations[index]
    }

    func probeState() -> String {
        "generation=\(generation) awaiting=\(String(describing: documentPolicyGate.awaitingGeneration)) " +
            "prepared=\(String(describing: preparedLoad?.generation)) issued=\(probeIssuedNavigations.count)"
    }
    #endif

    func updateCallbacks(
        safeExternalURL: @escaping (String) -> URL?,
        openURL: @escaping (URL) -> Void,
        publishHeight: @escaping (CGFloat) -> Void,
        publishError: @escaping (String?) -> Void
    ) {
        self.safeExternalURL = safeExternalURL
        self.openURL = openURL
        self.publishHeight = publishHeight
        self.publishError = publishError
    }

    func makeWebView(html: String, source: String) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let contentController = WKUserContentController()
        #if os(macOS)
        contentController.add(messageProxy, contentWorld: .defaultClient, name: NativeUiHtmlAdapter.messageName)
        #endif
        configuration.userContentController = contentController
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsLinkPreview = false
        #if os(macOS)
        webView.allowsMagnification = false
        #else
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        #endif
        self.webView = webView
        self.controller = contentController
        mounted = true
        schedule(html: html, source: source, in: webView)
        return webView
    }

    func update(html: String, source: String, in webView: WKWebView) {
        guard lastHTML != html || lastSource != source else { return }
        schedule(html: html, source: source, in: webView)
    }

    private func schedule(html: String, source: String, in webView: WKWebView) {
        generation &+= 1
        let candidateGeneration = generation
        lastHTML = html
        lastSource = source
        loadingGeneration = nil
        preparedLoad = nil
        publishError(nil)
        #if !os(macOS)
        contentSizeObservation?.invalidate()
        contentSizeObservation = nil
        #endif
        controller?.removeAllUserScripts()
        compileRule { [weak self, weak webView] rule, error in
            guard let self, let webView, self.mounted,
                  self.generation == candidateGeneration,
                  self.webView === webView else { return }
            guard let rule else {
                self.fail(
                    "trusted HTML content-rule compilation failed: " +
                    (error?.localizedDescription ?? "unknown WebKit error"),
                    generation: candidateGeneration)
                return
            }
            self.preparedLoad = (candidateGeneration, html, rule)
            self.startPreparedLoadIfPossible()
        }
    }

    private func compileRule(_ completion: @escaping (WKContentRuleList?, Error?) -> Void) {
        #if SSC_NATIVEUI_HTML_PROBE
        if let probeRuleCompiler {
            probeRuleCompiler(completion)
            return
        }
        #endif
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: NativeUiHtmlAdapter.contentRuleIdentifier,
            encodedContentRuleList: NativeUiHtmlAdapter.contentRuleJSON,
            completionHandler: completion)
    }

    private func loadDocument(_ html: String, in webView: WKWebView) -> WKNavigation? {
        let document = NativeUiHtmlAdapter.document(html)
        #if SSC_NATIVEUI_HTML_PROBE
        if let probeNavigationLoader { return probeNavigationLoader(webView, document) }
        #endif
        return webView.loadHTMLString(document, baseURL: nil)
    }

    private func startPreparedLoadIfPossible() {
        guard mounted, documentPolicyGate.awaitingGeneration == nil,
              let candidate = preparedLoad,
              candidate.generation == generation,
              let webView, documentPolicyGate.begin(generation: candidate.generation) else { return }
        preparedLoad = nil
        webView.stopLoading()
        activeNavigation = nil
        controller?.removeAllContentRuleLists()
        controller?.add(candidate.rule)
        controller?.removeAllUserScripts()
        #if os(macOS)
        controller?.addUserScript(WKUserScript(
            source: NativeUiHtmlAdapter.heightScript(generation: candidate.generation),
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: true,
            in: .defaultClient))
        #endif
        guard let navigation = loadDocument(candidate.html, in: webView) else {
            documentPolicyGate.cancel(generation: candidate.generation)
            fail("trusted HTML navigation did not start", generation: candidate.generation)
            return
        }
        activeNavigation = (candidate.generation, navigation)
        #if SSC_NATIVEUI_HTML_PROBE
        probeIssuedNavigations.append(navigation)
        #endif
    }

    private func fail(_ message: String, generation candidateGeneration: UInt64) {
        guard mounted, generation == candidateGeneration else { return }
        let source = lastSource ?? "<unknown source>"
        if preparedLoad?.generation == candidateGeneration { preparedLoad = nil }
        documentPolicyGate.cancel(generation: candidateGeneration)
        loadingGeneration = nil
        if activeNavigation?.generation == candidateGeneration { activeNavigation = nil }
        publishError(NativeUiHtmlAdapter.bounded(message) + " at " + source)
    }

    @discardableResult
    func handoffExternal(_ text: String, navigationType: WKNavigationType) -> Bool {
        guard let url = NativeUiHtmlAdapter.externalURL(
            text, navigationType: navigationType,
            safeExternalURL: safeExternalURL) else { return false }
        openURL(url)
        return true
    }

    @discardableResult
    func handoffMainFrame(
        _ text: String,
        navigationType: WKNavigationType,
        isMainFrame: Bool
    ) -> Bool {
        guard isMainFrame else { return false }
        return handoffExternal(text, navigationType: navigationType)
    }

    @discardableResult
    func handoffNewWindow(
        _ text: String,
        navigationType: WKNavigationType,
        targetFrameIsNil: Bool
    ) -> Bool {
        guard targetFrameIsNil else { return false }
        return handoffExternal(text, navigationType: navigationType)
    }

    private func documentPolicyDecision(_ action: WKNavigationAction) -> NativeUiHtmlDocumentPolicyDecision {
        guard action.targetFrame?.isMainFrame == true,
              action.navigationType == .other,
              action.request.url?.absoluteString == "about:blank" else { return .unrelated }
        return documentPolicyGate.consume(currentGeneration: generation)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
    ) {
        guard mounted, self.webView === webView else { decisionHandler(.cancel); return }
        switch documentPolicyDecision(navigationAction) {
        case .allow:
            loadingGeneration = generation
            decisionHandler(.allow)
            startPreparedLoadIfPossible()
            return
        case .cancel:
            decisionHandler(.cancel)
            startPreparedLoadIfPossible()
            return
        case .unrelated:
            break
        }
        if let text = navigationAction.request.url?.absoluteString {
            _ = handoffMainFrame(
                text,
                navigationType: navigationAction.navigationType,
                isMainFrame: navigationAction.targetFrame?.isMainFrame == true)
        }
        decisionHandler(.cancel)
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        guard mounted, self.webView === webView else { return nil }
        if let text = navigationAction.request.url?.absoluteString {
            _ = handoffNewWindow(
                text,
                navigationType: navigationAction.navigationType,
                targetFrameIsNil: navigationAction.targetFrame == nil)
        }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let navigation, let activeNavigation,
              mounted, self.webView === webView,
              activeNavigation.generation == generation,
              activeNavigation.handle === navigation,
              loadingGeneration == generation else { return }
        self.activeNavigation = nil
        #if !os(macOS)
        let observedGeneration = generation
        contentSizeObservation?.invalidate()
        contentSizeObservation = webView.scrollView.observe(\.contentSize, options: [.initial, .new]) {
            [weak self] scrollView, _ in
            MainActor.assumeIsolated {
                guard let self, self.mounted, self.generation == observedGeneration,
                      self.webView === webView,
                      let height = NativeUiHtmlAdapter.clampHeight(Double(scrollView.contentSize.height)) else { return }
                self.publishHeight(height)
            }
        }
        #endif
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard let navigation, let activeNavigation,
              mounted, self.webView === webView,
              activeNavigation.handle === navigation else { return }
        self.activeNavigation = nil
        let failedGeneration = activeNavigation.generation
        if documentPolicyGate.cancel(generation: failedGeneration) { startPreparedLoadIfPossible() }
        fail("trusted HTML navigation failed: " + error.localizedDescription, generation: failedGeneration)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard let navigation, let activeNavigation,
              mounted, self.webView === webView,
              activeNavigation.handle === navigation else { return }
        self.activeNavigation = nil
        let failedGeneration = activeNavigation.generation
        if documentPolicyGate.cancel(generation: failedGeneration) { startPreparedLoadIfPossible() }
        fail("trusted HTML navigation failed: " + error.localizedDescription, generation: failedGeneration)
    }

    func receiveHeight(_ message: WKScriptMessage) {
        guard mounted, message.frameInfo.isMainFrame,
              let body = message.body as? [String: Any],
              let rawGeneration = body["generation"] as? NSNumber,
              let rawHeight = body["height"] as? NSNumber,
              rawGeneration.doubleValue.isFinite,
              rawGeneration.doubleValue.rounded() == rawGeneration.doubleValue,
              UInt64(exactly: rawGeneration.uint64Value) == generation,
              let height = NativeUiHtmlAdapter.clampHeight(rawHeight.doubleValue) else { return }
        publishHeight(height)
    }

    func dismantle(_ webView: WKWebView) {
        guard self.webView === webView else { return }
        mounted = false
        generation &+= 1
        documentPolicyGate.reset()
        preparedLoad = nil
        loadingGeneration = nil
        activeNavigation = nil
        #if !os(macOS)
        contentSizeObservation?.invalidate()
        contentSizeObservation = nil
        #endif
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        controller?.removeAllContentRuleLists()
        controller?.removeAllUserScripts()
        #if os(macOS)
        controller?.removeScriptMessageHandler(
            forName: NativeUiHtmlAdapter.messageName, contentWorld: .defaultClient)
        #endif
        messageProxy.target = nil
        controller = nil
        self.webView = nil
        safeExternalURL = { _ in nil }
        openURL = { _ in }
        publishHeight = { _ in }
        publishError = { _ in }
    }

    isolated deinit {
        #if !os(macOS)
        contentSizeObservation?.invalidate()
        #endif
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.uiDelegate = nil
        controller?.removeAllContentRuleLists()
        controller?.removeAllUserScripts()
        #if os(macOS)
        controller?.removeScriptMessageHandler(
            forName: NativeUiHtmlAdapter.messageName, contentWorld: .defaultClient)
        #endif
        messageProxy.target = nil
    }
}

struct NativeUiTrustedHtmlView: View {
    let html: String
    let source: String
    let safeExternalURL: (String) -> URL?
    let openURL: (URL) -> Void
    @State private var height = NativeUiHtmlAdapter.minimumHeight
    @State private var diagnostic: String?

    var body: some View {
        Group {
            if let diagnostic {
                Text(diagnostic).foregroundStyle(.red)
                    .accessibilityLabel("Unsupported native UI: " + diagnostic)
            } else {
                NativeUiHtmlRepresentable(
                    html: html,
                    source: source,
                    safeExternalURL: safeExternalURL,
                    openURL: openURL,
                    publishHeight: { height = $0 },
                    publishError: { diagnostic = $0 })
                    .frame(height: height)
            }
        }
        .task(id: NativeUiHtmlAdapter.revisionKey(html: html, source: source)) { diagnostic = nil }
    }
}

#if os(macOS)
private struct NativeUiHtmlRepresentable: NSViewRepresentable {
    let html: String
    let source: String
    let safeExternalURL: (String) -> URL?
    let openURL: (URL) -> Void
    let publishHeight: (CGFloat) -> Void
    let publishError: (String?) -> Void

    func makeCoordinator() -> NativeUiHtmlCoordinator {
        NativeUiHtmlCoordinator(
            safeExternalURL: safeExternalURL, openURL: openURL,
            publishHeight: publishHeight, publishError: publishError)
    }

    func makeNSView(context: Context) -> WKWebView {
        context.coordinator.makeWebView(html: html, source: source)
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        context.coordinator.updateCallbacks(
            safeExternalURL: safeExternalURL, openURL: openURL,
            publishHeight: publishHeight, publishError: publishError)
        context.coordinator.update(html: html, source: source, in: webView)
    }

    static func dismantleNSView(_ webView: WKWebView, coordinator: NativeUiHtmlCoordinator) {
        coordinator.dismantle(webView)
    }
}
#else
private struct NativeUiHtmlRepresentable: UIViewRepresentable {
    let html: String
    let source: String
    let safeExternalURL: (String) -> URL?
    let openURL: (URL) -> Void
    let publishHeight: (CGFloat) -> Void
    let publishError: (String?) -> Void

    func makeCoordinator() -> NativeUiHtmlCoordinator {
        NativeUiHtmlCoordinator(
            safeExternalURL: safeExternalURL, openURL: openURL,
            publishHeight: publishHeight, publishError: publishError)
    }

    func makeUIView(context: Context) -> WKWebView {
        context.coordinator.makeWebView(html: html, source: source)
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.updateCallbacks(
            safeExternalURL: safeExternalURL, openURL: openURL,
            publishHeight: publishHeight, publishError: publishError)
        context.coordinator.update(html: html, source: source, in: webView)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: NativeUiHtmlCoordinator) {
        coordinator.dismantle(webView)
    }
}
#endif
"""
