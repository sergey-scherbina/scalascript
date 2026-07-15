package ssc.swift

private[swift] object SwiftRuntime:
  // Split across two vals: a single triple-quoted literal over ~64KB hits the
  // JVM's per-constant UTF8 length limit, and `a + b` of two literals folds
  // back into one constant at compile time — routing through separate vals
  // keeps each half its own constant pool entry. Declared before `source`:
  // object vals initialize in declaration order, so `source` must come last.
  private val sourcePart1: String = """// Generated ScalaScript v2 CoreIR runtime.
import Foundation

struct SscBigInt: Equatable, Comparable, CustomStringConvertible {
    private let sign: Int
    private let digits: [UInt8] // base-10, most-significant first

    init(_ raw: String) {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let negative = text.hasPrefix("-")
        if negative || text.hasPrefix("+") { text.removeFirst() }
        guard !text.isEmpty && text.allSatisfy({ $0.isNumber }) else { fatalError("invalid BigInt '\(raw)'") }
        let magnitude = Array(text.utf8.drop(while: { $0 == 48 })).map { $0 - 48 }
        self.digits = magnitude.isEmpty ? [0] : magnitude
        self.sign = self.digits == [0] ? 0 : (negative ? -1 : 1)
    }

    private init(sign: Int, digits: [UInt8]) {
        let trimmed = Array(digits.drop(while: { $0 == 0 }))
        self.digits = trimmed.isEmpty ? [0] : trimmed
        self.sign = self.digits == [0] ? 0 : (sign < 0 ? -1 : 1)
    }

    var description: String {
        (sign < 0 ? "-" : "") + digits.map(String.init).joined()
    }

    var signum: Int { sign }
    var isZero: Bool { sign == 0 }
    var isEven: Bool { digits.last! % 2 == 0 }
    var magnitude: SscBigInt { SscBigInt(sign: sign == 0 ? 0 : 1, digits: digits) }

    static func < (lhs: SscBigInt, rhs: SscBigInt) -> Bool {
        if lhs.sign != rhs.sign { return lhs.sign < rhs.sign }
        if lhs.sign == 0 { return false }
        let order = compareMagnitude(lhs.digits, rhs.digits)
        return lhs.sign > 0 ? order < 0 : order > 0
    }

    static prefix func - (value: SscBigInt) -> SscBigInt {
        SscBigInt(sign: -value.sign, digits: value.digits)
    }

    static func + (lhs: SscBigInt, rhs: SscBigInt) -> SscBigInt {
        if lhs.sign == 0 { return rhs }
        if rhs.sign == 0 { return lhs }
        if lhs.sign == rhs.sign { return SscBigInt(sign: lhs.sign, digits: addMagnitude(lhs.digits, rhs.digits)) }
        let order = compareMagnitude(lhs.digits, rhs.digits)
        if order == 0 { return SscBigInt("0") }
        if order > 0 { return SscBigInt(sign: lhs.sign, digits: subtractMagnitude(lhs.digits, rhs.digits)) }
        return SscBigInt(sign: rhs.sign, digits: subtractMagnitude(rhs.digits, lhs.digits))
    }

    static func - (lhs: SscBigInt, rhs: SscBigInt) -> SscBigInt { lhs + (-rhs) }

    static func * (lhs: SscBigInt, rhs: SscBigInt) -> SscBigInt {
        if lhs.sign == 0 || rhs.sign == 0 { return SscBigInt("0") }
        var out = Array(repeating: 0, count: lhs.digits.count + rhs.digits.count)
        for (li, ld) in lhs.digits.reversed().enumerated() {
            for (ri, rd) in rhs.digits.reversed().enumerated() {
                out[out.count - 1 - li - ri] += Int(ld) * Int(rd)
            }
        }
        for i in stride(from: out.count - 1, through: 1, by: -1) {
            out[i - 1] += out[i] / 10
            out[i] %= 10
        }
        return SscBigInt(sign: lhs.sign * rhs.sign, digits: out.map(UInt8.init))
    }

    static func / (lhs: SscBigInt, rhs: SscBigInt) -> SscBigInt { divMod(lhs, rhs).0 }
    static func % (lhs: SscBigInt, rhs: SscBigInt) -> SscBigInt { divMod(lhs, rhs).1 }

    private static func divMod(_ lhs: SscBigInt, _ rhs: SscBigInt) -> (SscBigInt, SscBigInt) {
        if rhs.sign == 0 { fatalError("BigInt division by zero") }
        if lhs.sign == 0 { return (SscBigInt("0"), SscBigInt("0")) }
        let divisor = SscBigInt(sign: 1, digits: rhs.digits)
        var remainder = SscBigInt("0")
        var quotient: [UInt8] = []
        for digit in lhs.digits {
            remainder = SscBigInt(sign: 1, digits: remainder.digits + [digit])
            var q: UInt8 = 0
            if remainder >= divisor {
                for candidate in stride(from: 9, through: 1, by: -1) {
                    if divisor.multiplied(by: UInt8(candidate)) <= remainder { q = UInt8(candidate); break }
                }
                remainder = remainder - divisor.multiplied(by: q)
            }
            quotient.append(q)
        }
        return (
            SscBigInt(sign: lhs.sign * rhs.sign, digits: quotient),
            SscBigInt(sign: lhs.sign, digits: remainder.digits)
        )
    }

    private func multiplied(by small: UInt8) -> SscBigInt {
        if small == 0 || sign == 0 { return SscBigInt("0") }
        var out = Array(repeating: UInt8(0), count: digits.count + 1)
        var carry = 0
        for i in digits.indices.reversed() {
            let product = Int(digits[i]) * Int(small) + carry
            out[i + 1] = UInt8(product % 10)
            carry = product / 10
        }
        out[0] = UInt8(carry)
        return SscBigInt(sign: sign, digits: out)
    }

    private static func compareMagnitude(_ lhs: [UInt8], _ rhs: [UInt8]) -> Int {
        if lhs.count != rhs.count { return lhs.count < rhs.count ? -1 : 1 }
        for (a, b) in zip(lhs, rhs) where a != b { return a < b ? -1 : 1 }
        return 0
    }

    private static func addMagnitude(_ lhs: [UInt8], _ rhs: [UInt8]) -> [UInt8] {
        var a = lhs.reversed().map(Int.init), b = rhs.reversed().map(Int.init)
        let count = max(a.count, b.count); a += repeatElement(0, count: count - a.count); b += repeatElement(0, count: count - b.count)
        var out: [UInt8] = [], carry = 0
        for i in 0..<count { let sum = a[i] + b[i] + carry; out.append(UInt8(sum % 10)); carry = sum / 10 }
        if carry > 0 { out.append(UInt8(carry)) }
        return out.reversed()
    }

    private static func subtractMagnitude(_ lhs: [UInt8], _ rhs: [UInt8]) -> [UInt8] {
        var a = lhs.reversed().map(Int.init), b = rhs.reversed().map(Int.init)
        b += repeatElement(0, count: a.count - b.count)
        var out: [UInt8] = [], borrow = 0
        for i in a.indices {
            var value = a[i] - b[i] - borrow
            if value < 0 { value += 10; borrow = 1 } else { borrow = 0 }
            out.append(UInt8(value))
        }
        return out.reversed()
    }
}

struct SscDecimal: Equatable, Comparable, CustomStringConvertible {
    let unscaled: SscBigInt
    let scale: Int

    init(_ raw: String) {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { fatalError("decimal: invalid text ''") }
        var exponent = 0
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            let exponentText = String(text[text.index(after: e)...])
            guard let parsed = Int(exponentText) else { fatalError("decimal: invalid text '\(raw)'") }
            exponent = parsed
            text = String(text[..<e])
        }
        let negative = text.hasPrefix("-")
        if negative || text.hasPrefix("+") { text.removeFirst() }
        let pieces = text.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count <= 2,
              !pieces[0].isEmpty,
              pieces.allSatisfy({ $0.allSatisfy(\.isNumber) }) else {
            fatalError("decimal: invalid text '\(raw)'")
        }
        let fraction = pieces.count == 2 ? String(pieces[1]) : ""
        var value = SscBigInt((negative ? "-" : "") + String(pieces[0]) + fraction)
        var resultScale = fraction.count - exponent
        if resultScale < 0 { value = value * Self.power10(-resultScale); resultScale = 0 }
        self.unscaled = value
        self.scale = resultScale
    }

    init(unscaled: SscBigInt, scale: Int) {
        if scale < 0 {
            self.unscaled = unscaled * Self.power10(-scale)
            self.scale = 0
        } else {
            self.unscaled = unscaled
            self.scale = scale
        }
    }

    var description: String {
        let negative = unscaled.signum < 0
        var digits = unscaled.magnitude.description
        if scale == 0 { return (negative ? "-" : "") + digits }
        if digits.count <= scale { digits = String(repeating: "0", count: scale - digits.count + 1) + digits }
        let split = digits.index(digits.endIndex, offsetBy: -scale)
        return (negative ? "-" : "") + String(digits[..<split]) + "." + String(digits[split...])
    }

    static func == (lhs: SscDecimal, rhs: SscDecimal) -> Bool { compare(lhs, rhs) == 0 }
    static func < (lhs: SscDecimal, rhs: SscDecimal) -> Bool { compare(lhs, rhs) < 0 }

    static func + (lhs: SscDecimal, rhs: SscDecimal) -> SscDecimal {
        let target = max(lhs.scale, rhs.scale)
        return SscDecimal(unscaled: lhs.aligned(target) + rhs.aligned(target), scale: target)
    }
    static func - (lhs: SscDecimal, rhs: SscDecimal) -> SscDecimal {
        let target = max(lhs.scale, rhs.scale)
        return SscDecimal(unscaled: lhs.aligned(target) - rhs.aligned(target), scale: target)
    }
    static func * (lhs: SscDecimal, rhs: SscDecimal) -> SscDecimal {
        SscDecimal(unscaled: lhs.unscaled * rhs.unscaled, scale: lhs.scale + rhs.scale)
    }
    static prefix func - (value: SscDecimal) -> SscDecimal {
        SscDecimal(unscaled: -value.unscaled, scale: value.scale)
    }

    func remainder(_ rhs: SscDecimal) -> SscDecimal {
        if rhs.unscaled.isZero { fatalError("decimal: division by zero") }
        let target = max(scale, rhs.scale)
        return SscDecimal(unscaled: aligned(target) % rhs.aligned(target), scale: target)
    }

    func divided(by rhs: SscDecimal, scale targetScale: Int, mode: String) -> SscDecimal {
        if rhs.unscaled.isZero { fatalError("decimal: division by zero") }
        let shift = targetScale + rhs.scale - scale
        let numerator = shift >= 0 ? unscaled * Self.power10(shift) : unscaled
        let denominator = shift >= 0 ? rhs.unscaled : rhs.unscaled * Self.power10(-shift)
        return SscDecimal(unscaled: Self.roundedQuotient(numerator, denominator, mode), scale: targetScale)
    }

    func withScale(_ targetScale: Int, mode: String) -> SscDecimal {
        if targetScale >= scale {
            return SscDecimal(unscaled: unscaled * Self.power10(targetScale - scale), scale: targetScale)
        }
        return SscDecimal(
            unscaled: Self.roundedQuotient(unscaled, Self.power10(scale - targetScale), mode),
            scale: targetScale
        )
    }

    func power(_ exponent: Int) -> SscDecimal {
        if exponent < 0 { fatalError("decimal: negative exponent: \(exponent)") }
        var result = SscDecimal("1"), base = self, n = exponent
        while n > 0 { if n & 1 == 1 { result = result * base }; n >>= 1; if n > 0 { base = base * base } }
        return result
    }

    func toBigInt() -> SscBigInt { scale == 0 ? unscaled : unscaled / Self.power10(scale) }
    private func aligned(_ targetScale: Int) -> SscBigInt { unscaled * Self.power10(targetScale - scale) }

    private static func compare(_ lhs: SscDecimal, _ rhs: SscDecimal) -> Int {
        let target = max(lhs.scale, rhs.scale), a = lhs.aligned(target), b = rhs.aligned(target)
        return a < b ? -1 : (a == b ? 0 : 1)
    }

    static func power10(_ exponent: Int) -> SscBigInt {
        if exponent < 0 { fatalError("decimal: negative power-of-ten") }
        return SscBigInt("1" + String(repeating: "0", count: exponent))
    }

    private static func roundedQuotient(_ numerator: SscBigInt, _ denominator: SscBigInt, _ mode: String) -> SscBigInt {
        let quotient = numerator / denominator, remainder = numerator % denominator
        if remainder.isZero { return quotient }
        let direction = numerator.signum * denominator.signum >= 0 ? 1 : -1
        let increment: Bool
        switch mode {
        case "UP": increment = true
        case "DOWN": increment = false
        case "CEILING": increment = direction > 0
        case "FLOOR": increment = direction < 0
        case "HALF_UP", "HALF_DOWN", "HALF_EVEN":
            let doubled = remainder.magnitude * SscBigInt("2"), divisor = denominator.magnitude
            if doubled != divisor { increment = doubled > divisor }
            else if mode == "HALF_UP" { increment = true }
            else if mode == "HALF_DOWN" { increment = false }
            else { increment = !quotient.isEven }
        case "UNNECESSARY": fatalError("decimal: rounding necessary")
        default: fatalError("decimal: unsupported rounding mode '\(mode)'")
        }
        if !increment { return quotient }
        return quotient + SscBigInt(direction > 0 ? "1" : "-1")
    }
}

enum SscConst {
    case unit
    case bool(Bool)
    case int(Int64)
    case big(String)
    case float(Double)
    case string(String)
    case bytes([UInt8])
}

indirect enum SscTerm {
    case literal(SscConst)
    case local(Int)
    case global(String)
    case lambda(Int, SscTerm)
    case apply(SscTerm, [SscTerm])
    case letBindings([SscTerm], SscTerm)
    case letRecursive([SscTerm], SscTerm)
    case ifThenElse(SscTerm, SscTerm, SscTerm)
    case constructor(String, [SscTerm])
    case matchValue(SscTerm, [SscArm], SscTerm?)
    case primitive(String, [SscTerm])
    case whileLoop(SscTerm, SscTerm)
    case sequence([SscTerm])
}

struct SscArm {
    let tag: String
    let arity: Int
    let body: SscTerm
}

struct SscDefinition {
    let name: String
    let body: SscTerm
}

struct SscProgram {
    let definitions: [SscDefinition]
    let entry: SscTerm
    let fieldLayouts: [String: [String]]
}

final class SscClosure {
    let arity: Int
    var environment: [SscValue]
    let body: SscTerm?
    let native: (([SscValue]) throws -> SscValue)?

    init(arity: Int, environment: [SscValue], body: SscTerm) {
        self.arity = arity
        self.environment = environment
        self.body = body
        self.native = nil
    }

    init(arity: Int, native: @escaping ([SscValue]) throws -> SscValue) {
        self.arity = arity
        self.environment = []
        self.body = nil
        self.native = native
    }
}

final class SscCell {
    var value: SscValue
    init(_ value: SscValue) { self.value = value }
}

final class SscArray {
    var values: [SscValue]
    init(_ values: [SscValue] = []) { self.values = values }
}

final class SscMap {
    var entries: [(SscValue, SscValue)] = []

    func index(of key: SscValue) -> Int? {
        entries.firstIndex { sscEqual($0.0, key) }
    }

    func get(_ key: SscValue) -> SscValue? {
        index(of: key).map { entries[$0].1 }
    }

    func put(_ key: SscValue, _ value: SscValue) {
        if let i = index(of: key) { entries[i] = (key, value) }
        else { entries.append((key, value)) }
    }

    func delete(_ key: SscValue) { if let i = index(of: key) { entries.remove(at: i) } }
}

final class SscFields: RandomAccessCollection, ExpressibleByArrayLiteral {
    typealias Element = SscValue
    typealias Index = Int

    private let values: [SscValue]

    init(_ values: [SscValue]) { self.values = values }
    required init(arrayLiteral elements: SscValue...) { self.values = elements }

    var startIndex: Int { values.startIndex }
    var endIndex: Int { values.endIndex }
    subscript(index: Int) -> SscValue { values[index] }
    func index(after index: Int) -> Int { values.index(after: index) }
    func index(before index: Int) -> Int { values.index(before: index) }
    func asArray() -> [SscValue] { values }
}

private func + (left: [SscValue], right: SscFields) -> [SscValue] {
    left + right.asArray()
}

private func + (left: SscFields, right: [SscValue]) -> [SscValue] {
    left.asArray() + right
}

indirect enum SscValue {
    case unit
    case bool(Bool)
    case int(Int64)
    case big(SscBigInt)
    case decimal(SscDecimal)
    case float(Double)
    case string(String)
    case bytes([UInt8])
    case data(String, SscFields)
    case closure(SscClosure)
    case cell(SscCell)
    case longCell(SscCell)
    case map(SscMap)
    case array(SscArray)
}

protocol SscRuntimeExtension: AnyObject {
    func bind(_ invoke: @escaping (SscClosure, [SscValue]) throws -> SscValue)
    func globals() throws -> [String: SscValue]
    func apply(_ receiver: SscValue, _ arguments: [SscValue]) throws -> SscValue?
    func method(_ name: String, _ receiver: SscValue, _ arguments: [SscValue]) throws -> SscValue?
}

struct SscRuntimeFailure: Error, CustomStringConvertible {
    let description: String
}

struct SscEffectId: Equatable, Sendable {
    let value: String
}

struct SscOperationId: Equatable, Sendable {
    let effect: SscEffectId
    let name: String
}

enum SscResumeRejected: Equatable, Sendable {
    case alreadyResumed(SscOperationId)
}

struct SscControlRunFailure: Error, CustomStringConvertible {
    let rejection: SscResumeRejected

    let code = "ONESHOT_VIOLATION"

    var message: String {
        switch rejection {
        case let .alreadyResumed(operation):
            return "One-shot violation: \(operation.effect.value).\(operation.name) resumed more than once"
        }
    }

    var rendered: String { "error [\(code)]: \(message)" }
    var description: String { rendered }
}

private final class SscOneShotClaim: @unchecked Sendable {
    private let lock = NSLock()
    private var claimed = false

    func tryClaim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !claimed else { return false }
        claimed = true
        return true
    }
}

private struct SscThrown: Error, @unchecked Sendable {
    let value: SscValue
}

private enum SscPendingFailure {
    case thrown(SscValue)
    case runtime(SscRuntimeFailure)
    case control(SscControlRunFailure)
    case host(SscRuntimeFailure)

    var terminal: SscRuntimeFailure {
        switch self {
        case let .thrown(value): return SscRuntimeFailure(description: "uncaught throw: \(sscPlain(value))")
        case let .runtime(error), let .host(error): return error
        case let .control(error): return SscRuntimeFailure(description: error.rendered)
        }
    }
}

private enum EvalStep {
    case value(SscValue)
    case call(SscClosure, [SscValue])
}

private enum SscPartialCallResult {
    case matched(SscValue)
    case noMatch
}

enum SscRuntime {
    public static func execute(_ program: SscProgram) {
        let result = evaluate(program)
        if case .unit = result { return }
        Swift.print(sscShow(result))
    }

    static func evaluate(_ program: SscProgram, nativeUiHost: SscRuntimeExtension? = nil) -> SscValue {
        Machine(program, nativeUiHost: nativeUiHost).run()
    }

    static func session(_ program: SscProgram, nativeUiHost: SscRuntimeExtension) -> SscRuntimeSession {
        SscRuntimeSession(program, nativeUiHost: nativeUiHost)
    }
}

final class SscRuntimeSession {
    private let machine: Machine

    init(_ program: SscProgram, nativeUiHost: SscRuntimeExtension) {
        machine = Machine(program, nativeUiHost: nativeUiHost)
    }

    func evaluate() -> Result<SscValue, SscRuntimeFailure> { machine.runResult() }

    func invoke(_ closure: SscClosure, _ arguments: [SscValue]) throws -> SscValue {
        switch machine.invokeResult(closure, arguments) {
        case let .success(value): return value
        case let .failure(error): throw error
        }
    }
}

private final class Machine {
    private let program: SscProgram
    private let nativeUiHost: SscRuntimeExtension?
    private var globals: [String: SscValue] = [:]
    private var failure: SscPendingFailure?
    private var jsonRenderer: SscClosure?
    private var evaluatingProgram = false

    init(_ program: SscProgram, nativeUiHost: SscRuntimeExtension? = nil) {
        self.program = program
        self.nativeUiHost = nativeUiHost
        nativeUiHost?.bind { [weak self] closure, arguments in
            guard let self else { throw SscRuntimeFailure(description: "native UI runtime released") }
            let result = self.call(closure, arguments)
            if let failure = self.failure {
                if !self.evaluatingProgram { self.failure = nil }
                throw failure.terminal
            }
            return result
        }
        installBuiltins()
        if let nativeUiHost {
            do { globals.merge(try nativeUiHost.globals()) { _, replacement in replacement } }
            catch { recordFailure(error) }
        }
        installDefinitions()
    }

    func run() -> SscValue {
        evaluatingProgram = true
        defer { evaluatingProgram = false }
        let result = runTerm(program.entry, [])
        if let failure { fatalError(failure.terminal.description) }
        return result
    }

    func runResult() -> Result<SscValue, SscRuntimeFailure> {
        if let failure { return .failure(failure.terminal) }
        evaluatingProgram = true
        defer { evaluatingProgram = false }
        let result = runTerm(program.entry, [])
        if let failure { return .failure(failure.terminal) }
        return .success(result)
    }

    func invokeResult(_ closure: SscClosure, _ arguments: [SscValue]) -> Result<SscValue, SscRuntimeFailure> {
        if let failure {
            self.failure = nil
            return .failure(failure.terminal)
        }
        let result = call(closure, arguments)
        if let failure {
            self.failure = nil
            return .failure(failure.terminal)
        }
        return .success(result)
    }

    private func recordFailure(_ error: Error) {
        guard failure == nil else { return }
        switch error {
        case let thrown as SscThrown: failure = .thrown(thrown.value)
        case let control as SscControlRunFailure: failure = .control(control)
        case let runtime as SscRuntimeFailure: failure = .runtime(runtime)
        default:
            failure = .host(SscRuntimeFailure(description: "unexpected host error: \(String(describing: error))"))
        }
    }

    private func installBuiltins() {
        globals["print"] = .closure(SscClosure(arity: -1) { args in
            for value in args { Swift.print(sscPlain(value), terminator: "") }
            return .unit
        })
        globals["println"] = .closure(SscClosure(arity: -1) { args in
            for value in args { Swift.print(sscPlain(value), terminator: "") }
            Swift.print("")
            return .unit
        })
        globals["Decimal"] = .closure(SscClosure(arity: -1) { args in
            if args.count == 2 {
                return .decimal(SscDecimal(unscaled: decimalBigInt(args[0]), scale: Int(intValue(args[1]))))
            }
            guard args.count == 1 else { fatalError("decimal: invalid constructor arity") }
            return .decimal(decimal(args[0]))
        })
        globals["BigInt"] = .closure(SscClosure(arity: 1) { args in
            switch args[0] {
            case let .int(value): return .big(SscBigInt(String(value)))
            case let .big(value): return .big(value)
            case let .string(value): return .big(SscBigInt(value))
            default: fatalError("BigInt: unsupported constructor input")
            }
        })
        globals["RoundingMode"] = .data("__RoundingModeCompanion__", [])
        globals["RuntimeException"] = .closure(SscClosure(arity: 1) { args in
            .data("RuntimeException", SscFields(args))
        })
        globals["handle"] = .closure(SscClosure(arity: 1) { [weak self] first in
            guard let self else { fatalError("effect: runtime released") }
            let body: SscValue
            if case let .closure(thunk) = first[0], thunk.arity == 0 { body = self.call(thunk, []) }
            else { body = first[0] }
            return .closure(SscClosure(arity: 1) { handler in self.handleEffect(body, handler[0]) })
        })
        globals["effect"] = .closure(SscClosure(arity: 1) { _ in .unit })
        globals["__throw__"] = .closure(SscClosure(arity: 1) { args in
            throw SscThrown(value: args[0])
        })
        globals["__jsonCoreInstallRenderer"] = .closure(SscClosure(arity: 1) { [weak self] args in
            guard let self, case let .closure(renderer) = args[0] else {
                throw SscRuntimeFailure(description: "__jsonCoreInstallRenderer(render)")
            }
            self.jsonRenderer = renderer
            return .unit
        })
        globals["__jsonCoreWrap"] = .closure(SscClosure(arity: 1) { args in
            try jsonValidateCore(args[0])
            return .data("__JsonBox__", [args[0]])
        })
        globals["__jsonCoreWrapStrict"] = .closure(SscClosure(arity: 1) { args in
            let core = try jsonUnwrapStrict(args[0])
            try jsonValidateCore(core)
            return .data("__JsonBox__", [core])
        })
        globals["__jsonCoreRawStrict"] = .closure(SscClosure(arity: 1) { args in
            let core = try jsonUnwrapStrict(args[0])
            try jsonValidateCore(core)
            return try jsonToRaw(core)
        })
        globals["__jsonCoreEncodeValue"] = .closure(SscClosure(arity: 1) { args in try jsonToCore(args[0]) })
        globals["lookup"] = .closure(SscClosure(arity: 2) { args in
            guard let result = try jsonLookup(args[0], args[1]) else {
                throw SscRuntimeFailure(description: "lookup: key not found")
            }
            return result
        })
        globals["lookupOpt"] = .closure(SscClosure(arity: 2) { args in
            try jsonLookup(args[0], args[1]).map(some) ?? none()
        })
        globals["contentDocument"] = .closure(SscClosure(arity: 0) { _ in sscContentRootModule().document })
        globals["contentCurrentSection"] = .closure(SscClosure(arity: 0) { _ in
            throw SscRuntimeFailure(description: "contentCurrentSection() is unavailable on native without source-aware call identity")
        })
        globals["contentSection"] = .closure(SscClosure(arity: 1) { args in
            guard case let .string(id) = args[0] else { throw SscRuntimeFailure(description: "contentSection(id)") }
            let hit = sscContentFindSection(sscContentSections(sscContentRootModule().document), id)
            return hit.map(some) ?? none()
        })
        globals["contentBlock"] = .closure(SscClosure(arity: 1) { args in
            guard case let .string(id) = args[0] else { throw SscRuntimeFailure(description: "contentBlock(id)") }
            let hit = sscContentFindBlock(sscContentRootModule().document, id)
            return hit.map(some) ?? none()
        })
        globals["contentData"] = .closure(SscClosure(arity: 1) { args in
            guard case let .string(id) = args[0] else { throw SscRuntimeFailure(description: "contentData(id)") }
            let hit = sscContentFindBlock(sscContentRootModule().document, id).flatMap(sscContentBlockData)
            return hit.map(some) ?? none()
        })
        globals["contentMetadata"] = .closure(SscClosure(arity: 1) { args in
            guard case let .string(path) = args[0] else { throw SscRuntimeFailure(description: "contentMetadata(path)") }
            let hit = sscContentMetadata(sscContentRootModule().document, path)
            return hit.map(some) ?? none()
        })
        globals["contentPlainText"] = .closure(SscClosure(arity: 1) { args in .string(sscContentPlain(args[0])) })
        globals["contentToMarkdown"] = .closure(SscClosure(arity: 1) { args in .string(sscContentMarkdown(args[0])) })
        globals["contentModules"] = .closure(SscClosure(arity: 0) { _ in
            let m = SscMap()
            for module in sscContentDirectModules() { m.put(.string(module.namespace), module.document) }
            return .map(m)
        })
        globals["contentModule"] = .closure(SscClosure(arity: 1) { args in
            guard case let .string(namespace) = args[0] else { throw SscRuntimeFailure(description: "contentModule(namespace)") }
            return sscContentImportedModule(namespace).map { some($0.document) } ?? none()
        })
        globals["contentModuleSection"] = .closure(SscClosure(arity: 2) { args in
            guard case let .string(namespace) = args[0], case let .string(id) = args[1] else {
                throw SscRuntimeFailure(description: "contentModuleSection(namespace, id)")
            }
            let hit = sscContentImportedModule(namespace).flatMap { sscContentFindSection(sscContentSections($0.document), id) }
            return hit.map(some) ?? none()
        })
        globals["contentModuleBlock"] = .closure(SscClosure(arity: 2) { args in
            guard case let .string(namespace) = args[0], case let .string(id) = args[1] else {
                throw SscRuntimeFailure(description: "contentModuleBlock(namespace, id)")
            }
            let hit = sscContentImportedModule(namespace).flatMap { sscContentFindBlock($0.document, id) }
            return hit.map(some) ?? none()
        })
        globals["contentModuleData"] = .closure(SscClosure(arity: 2) { args in
            guard case let .string(namespace) = args[0], case let .string(id) = args[1] else {
                throw SscRuntimeFailure(description: "contentModuleData(namespace, id)")
            }
            let hit = sscContentImportedModule(namespace).flatMap { sscContentFindBlock($0.document, id) }.flatMap(sscContentBlockData)
            return hit.map(some) ?? none()
        })
        globals["contentModuleMetadata"] = .closure(SscClosure(arity: 2) { args in
            guard case let .string(namespace) = args[0], case let .string(path) = args[1] else {
                throw SscRuntimeFailure(description: "contentModuleMetadata(namespace, path)")
            }
            let hit = sscContentImportedModule(namespace).flatMap { sscContentMetadata($0.document, path) }
            return hit.map(some) ?? none()
        })
        // arity -1 (variadic): default-arg resolution for extern defs is not guaranteed
        // to insert the default ContentToolkitOptions() at the call site (the JS backend
        // defends the same way — contentToolkitNode(options) treats a missing options as
        // undefined); an absent/non-options first arg is nil-options here.
        globals["contentToolkitNode"] = .closure(SscClosure(arity: -1) { args in
            sscContentToolkitRender(sscContentRootModule().document, sscContentToolkitOptionsArg(args))
        })
        globals["contentToolkitBlock"] = .closure(SscClosure(arity: -1) { args in
            guard case let .string(id) = args.first else { throw SscRuntimeFailure(description: "contentToolkitBlock(id)") }
            let options = args.count > 1 ? sscContentToolkitOptionsArg(Array(args.dropFirst())) : nil
            guard let block = sscContentFindBlock(sscContentRootModule().document, id) else {
                return sscContentToolkitInlineError("contentToolkitBlock: no block with id '\(id)'")
            }
            return sscContentToolkitSafeBlockNode(block, options)
        })
        globals["contentToolkitSection"] = .closure(SscClosure(arity: -1) { args in
            guard case let .string(id) = args.first else { throw SscRuntimeFailure(description: "contentToolkitSection(id)") }
            let options = args.count > 1 ? sscContentToolkitOptionsArg(Array(args.dropFirst())) : nil
            guard let section = sscContentFindSection(sscContentSections(sscContentRootModule().document), id) else {
                return sscContentToolkitInlineError("contentToolkitSection: no section with id '\(id)'")
            }
            return sscContentToolkitSectionNode(section, options)
        })
        // installLocalAssets/publishLocalLocale (std/ui app shell): @js(...) browser-only
        // externs — service-worker/manifest bootstrapping and syncing the locale into a
        // browser JS global. Neither concept applies to a native app; true no-ops.
        globals["installLocalAssets"] = .closure(SscClosure(arity: 0) { _ in .unit })
        globals["publishLocalLocale"] = .closure(SscClosure(arity: 1) { _ in .unit })
        // webauthnRegister/webauthnAssert (std/ui/webauthn.ssc): browser navigator.credentials
        // passkey flow. A real native equivalent (ASAuthorizationPlatformPublicKeyCredential
        // Provider / Face ID via LocalAuthentication) is a separate, not-yet-started feature —
        // this is a placeholder EventHandler (a no-arg closure) so a native build compiles and
        // runs; tapping the button currently does nothing rather than performing enrollment.
        globals["webauthnRegister"] = .closure(SscClosure(arity: -1) { _ in
            .closure(SscClosure(arity: 0) { _ in .unit })
        })
        globals["webauthnAssert"] = .closure(SscClosure(arity: -1) { _ in
            .closure(SscClosure(arity: 0) { _ in .unit })
        })
    }

    private func installDefinitions() {
        for definition in program.definitions {
            if case let .lambda(arity, body) = definition.body {
                globals[definition.name] = .closure(SscClosure(arity: arity, environment: [], body: body))
            }
        }
        for definition in program.definitions {
            if case .lambda = definition.body { continue }
            globals[definition.name] = runTerm(definition.body, [])
        }
    }

    private func runTerm(_ initialTerm: SscTerm, _ initialEnvironment: [SscValue]) -> SscValue {
        var term = initialTerm
        var environment = initialEnvironment
        while true {
            if failure != nil { return .unit }
            switch evaluate(term, environment, tail: true) {
            case let .value(value): return value
            case let .call(closure, arguments):
                checkArity(closure, arguments)
                if let native = closure.native {
                    do { return try native(arguments) }
                    catch { recordFailure(error); return .unit }
                }
                guard let body = closure.body else { fatalError("app: closure has no body") }
                term = body
                environment = closure.environment + arguments
            }
        }
    }

    private func value(_ term: SscTerm, _ environment: [SscValue]) -> SscValue {
        runTerm(term, environment)
    }

    private func checkArity(_ closure: SscClosure, _ arguments: [SscValue]) {
        if closure.arity >= 0 && closure.arity != arguments.count {
            fatalError("arity: \(closure.arity) expected, \(arguments.count) given: \(arguments.map(sscShow))")
        }
    }

    private func call(_ closure: SscClosure, _ arguments: [SscValue]) -> SscValue {
        if failure != nil { return .unit }
        checkArity(closure, arguments)
        if let native = closure.native {
            do { return try native(arguments) }
            catch { recordFailure(error); return .unit }
        }
        guard let body = closure.body else { fatalError("app: closure has no body") }
        return runTerm(body, closure.environment + arguments)
    }

    private func callPartial(_ closure: SscClosure, _ arguments: [SscValue]) -> SscPartialCallResult {
        if failure != nil { return .matched(.unit) }
        checkArity(closure, arguments)
        guard closure.native == nil else { return .matched(call(closure, arguments)) }
        guard let body = closure.body else { fatalError("app: closure has no body") }
        // Only an absent arm in this directly invoked partial-function match is recoverable.
        // Selected arms and fallbacks re-enter the ordinary evaluator, so failures in handler
        // code (including nested non-exhaustive matches) keep their normal failure semantics.
        guard case let .matchValue(scrutinee, arms, fallback) = body else {
            return .matched(runTerm(body, closure.environment + arguments))
        }

        let environment = closure.environment + arguments
        let scrutineeValue = value(scrutinee, environment)
        if failure != nil { return .matched(.unit) }
        if case let .data(tag, fields) = scrutineeValue,
           let arm = arms.first(where: { $0.tag == tag && $0.arity == fields.count }) {
            return .matched(runTerm(arm.body, environment + fields))
        }
        if let fallback { return .matched(runTerm(fallback, environment)) }
        return .noMatch
    }

    private func evaluate(_ term: SscTerm, _ environment: [SscValue], tail: Bool) -> EvalStep {
        if failure != nil { return .value(.unit) }
        switch term {
        case let .literal(constant): return .value(constantValue(constant))
        case let .local(index):
            let resolved = environment.count - 1 - index
            guard environment.indices.contains(resolved) else { fatalError("local: index \(index) out of bounds") }
            return .value(environment[resolved])
        case let .global(name):
            if let result = globals[name] { return .value(result) }
            // Auto-create cells for @xxx globals on first access (lazy init),
            // mirroring v2/src/Runtime.scala:686-689. A `val x = Signal(0)`
            // mutated via `x += 1` lowers to cell.set(Global("@x"), …) but the
            // `@x` cell is never global.reg'd — the general interpreter vivifies
            // it on first touch, so the Swift runtime must too.
            if name.hasPrefix("@") {
                let created = SscValue.cell(SscCell(.unit))
                globals[name] = created
                return .value(created)
            }
            fatalError("unbound global: \(name)")
        case let .lambda(arity, body):
            return .value(.closure(SscClosure(arity: arity, environment: environment, body: body)))
        case let .apply(function, arguments):
            let functionValue = value(function, environment)
            if failure != nil { return .value(.unit) }
            var values: [SscValue] = []
            for argument in arguments {
                values.append(value(argument, environment))
                if failure != nil { return .value(.unit) }
            }
            if case let .closure(closure) = functionValue {
                if tail { return .call(closure, values) }
                return .value(call(closure, values))
            }
            if case .data("Cons", _) = functionValue {
                do {
                    let items = try properList(functionValue)
                    guard values.count == 1, case let .int(rawIndex) = values[0] else {
                        throw SscRuntimeFailure(description: "app: list index requires exactly one Int")
                    }
                    guard rawIndex >= 0, rawIndex < Int64(items.count) else {
                        throw SscRuntimeFailure(description: "app: list index out of bounds")
                    }
                    return .value(items[Int(rawIndex)])
                } catch {
                    recordFailure(error)
                    return .value(.unit)
                }
            }
            if case .data("Nil", _) = functionValue {
                do {
                    let items = try properList(functionValue)
                    guard values.count == 1, case let .int(rawIndex) = values[0] else {
                        throw SscRuntimeFailure(description: "app: list index requires exactly one Int")
                    }
                    guard rawIndex >= 0, rawIndex < Int64(items.count) else {
                        throw SscRuntimeFailure(description: "app: list index out of bounds")
                    }
                    return .value(items[Int(rawIndex)])
                } catch {
                    recordFailure(error)
                    return .value(.unit)
                }
            }
            if case let .map(m) = functionValue {
                do {
                    guard values.count == 1 else {
                        throw SscRuntimeFailure(description: "app: map apply requires exactly one key")
                    }
                    guard let found = m.get(values[0]) else {
                        throw SscRuntimeFailure(description: "key not found: \(sscShow(values[0]))")
                    }
                    return .value(found)
                } catch {
                    recordFailure(error)
                    return .value(.unit)
                }
            }
            do {
                if let result = try nativeUiHost?.apply(functionValue, values) { return .value(result) }
            } catch {
                recordFailure(error)
                return .value(.unit)
            }
            fatalError("app: not a function: \(sscShow(functionValue))")
        case let .letBindings(bindings, body):
            var extended = environment
            for binding in bindings {
                extended.append(value(binding, extended))
                if failure != nil { return .value(.unit) }
            }
            return evaluate(body, extended, tail: tail)
        case let .letRecursive(bindings, body):
            var closures: [SscClosure] = []
            for binding in bindings {
                guard case let .lambda(arity, lambdaBody) = binding else {
                    fatalError("letrec binding must be a lambda")
                }
                closures.append(SscClosure(arity: arity, environment: [], body: lambdaBody))
            }
            let extended = environment + closures.map(SscValue.closure)
            for closure in closures { closure.environment = extended }
            return evaluate(body, extended, tail: tail)
        case let .ifThenElse(condition, ifTrue, ifFalse):
            let conditionValue = value(condition, environment)
            if failure != nil { return .value(.unit) }
            guard case let .bool(test) = conditionValue else {
                fatalError("if: condition not Bool")
            }
            return evaluate(test ? ifTrue : ifFalse, environment, tail: tail)
        case let .constructor(tag, fields):
            var values: [SscValue] = []
            for field in fields {
                values.append(value(field, environment))
                if failure != nil { return .value(.unit) }
            }
            return .value(.data(tag, SscFields(values)))
        case let .matchValue(scrutinee, arms, fallback):
            let scrutineeValue = value(scrutinee, environment)
            if failure != nil { return .value(.unit) }
            if case let .data(tag, fields) = scrutineeValue,
               let arm = arms.first(where: { $0.tag == tag && $0.arity == fields.count }) {
                return evaluate(arm.body, environment + fields, tail: tail)
            }
            if let fallback { return evaluate(fallback, environment, tail: tail) }
            fatalError("match: no arm for \(sscShow(scrutineeValue))")
        case let .primitive(operation, arguments):
            var values: [SscValue] = []
            for argument in arguments {
                values.append(value(argument, environment))
                if failure != nil { return .value(.unit) }
            }
            let result = primitive(operation, values)
            if failure != nil { return .value(.unit) }
            return .value(result)
        case let .whileLoop(condition, body):
            while true {
                let conditionValue = value(condition, environment)
                if failure != nil { return .value(.unit) }
                guard case let .bool(test) = conditionValue else {
                    fatalError("while: condition not Bool")
                }
                if !test { break }
                _ = value(body, environment)
                if failure != nil { return .value(.unit) }
            }
            return .value(.unit)
        case let .sequence(terms):
            guard let last = terms.last else { return .value(.unit) }
            for term in terms.dropLast() {
                _ = value(term, environment)
                if failure != nil { return .value(.unit) }
            }
            return evaluate(last, environment, tail: tail)
        }
    }

    private func constantValue(_ constant: SscConst) -> SscValue {
        switch constant {
        case .unit: return .unit
        case let .bool(value): return .bool(value)
        case let .int(value): return .int(value)
        case let .big(value): return .big(SscBigInt(value))
        case let .float(value): return .float(value)
        case let .string(value): return .string(value)
        case let .bytes(value): return .bytes(value)
        }
    }

    private func primitive(_ operation: String, _ args: [SscValue]) -> SscValue {
        switch operation {
        case "i.add": return .int(int(args, 0) &+ int(args, 1))
        case "i.sub": return .int(int(args, 0) &- int(args, 1))
        case "i.mul": return .int(int(args, 0) &* int(args, 1))
        case "i.div": return .int(intDiv(int(args, 0), int(args, 1)))
        case "i.mod": return .int(intRem(int(args, 0), int(args, 1)))
        case "i.neg": return .int(0 &- int(args, 0))
        case "i.and": return .int(int(args, 0) & int(args, 1))
        case "i.or": return .int(int(args, 0) | int(args, 1))
        case "i.xor": return .int(int(args, 0) ^ int(args, 1))
        case "i.not": return .int(~int(args, 0))
        case "i.shl": return .int(int(args, 0) &<< (int(args, 1) & 63))
        case "i.shr": return .int(int(args, 0) &>> (int(args, 1) & 63))
        case "i.ushr":
            let shifted = UInt64(bitPattern: int(args, 0)) >> UInt64(int(args, 1) & 63)
            return .int(Int64(bitPattern: shifted))
        case "i.eq": return .bool(int(args, 0) == int(args, 1))
        case "i.lt": return .bool(int(args, 0) < int(args, 1))
        case "i.le": return .bool(int(args, 0) <= int(args, 1))
        case "i.gt": return .bool(int(args, 0) > int(args, 1))
        case "i.ge": return .bool(int(args, 0) >= int(args, 1))
        case "not": return .bool(!bool(args, 0))
        case "big.add": return .big(big(args, 0) + big(args, 1))
        case "big.sub": return .big(big(args, 0) - big(args, 1))
        case "big.mul": return .big(big(args, 0) * big(args, 1))
        case "big.div": return .big(big(args, 0) / big(args, 1))
        case "big.mod": return .big(big(args, 0) % big(args, 1))
        case "big.neg": return .big(-big(args, 0))
        case "big.eq": return .bool(big(args, 0) == big(args, 1))
        case "big.lt": return .bool(big(args, 0) < big(args, 1))
        case "big.le": return .bool(big(args, 0) <= big(args, 1))
"""
  private val sourcePart2: String = """
        case "big.gt": return .bool(big(args, 0) > big(args, 1))
        case "big.ge": return .bool(big(args, 0) >= big(args, 1))
        case "i->big": return .big(SscBigInt(String(int(args, 0))))
        case "big->str": return .string(big(args, 0).description)
        case "dec.parse": return .decimal(SscDecimal(string(args, 0)))
        case "dec.from-unscaled": return .decimal(SscDecimal(unscaled: decimalBigInt(args[0]), scale: Int(int(args, 1))))
        case "dec.add": return .decimal(decimal(args[0]) + decimal(args[1]))
        case "dec.sub": return .decimal(decimal(args[0]) - decimal(args[1]))
        case "dec.mul": return .decimal(decimal(args[0]) * decimal(args[1]))
        case "dec.rem": return .decimal(decimal(args[0]).remainder(decimal(args[1])))
        case "dec.div": return .decimal(decimal(args[0]).divided(by: decimal(args[1]), scale: Int(int(args, 2)), mode: roundingMode(args[3])))
        case "dec.compare":
            let a = decimal(args[0]), b = decimal(args[1]); return .int(a < b ? -1 : (a == b ? 0 : 1))
        case "dec.set-scale": return .decimal(decimal(args[0]).withScale(Int(int(args, 1)), mode: roundingMode(args[2])))
        case "dec.pow": return .decimal(decimal(args[0]).power(Int(int(args, 1))))
        case "dec.abs": let value = decimal(args[0]); return .decimal(value.unscaled.signum < 0 ? -value : value)
        case "dec.negate": return .decimal(-decimal(args[0]))
        case "dec.signum": return .int(Int64(decimal(args[0]).unscaled.signum))
        case "dec.scale": return .int(Int64(decimal(args[0]).scale))
        case "dec.unscaled": return .big(decimal(args[0]).unscaled)
        case "dec.to-bigint": return .big(decimal(args[0]).toBigInt())
        case "dec.to-string": return .string(decimal(args[0]).description)
        case "effect.pure":
            guard args.count == 1 else { fatalError("effect: effect.pure expects 1 argument") }
            return .data("Pure", [args[0]])
        case "effect.perform":
            guard !args.isEmpty, case let .string(label) = args[0] else {
                fatalError("effect: effect.perform expects a String label")
            }
            let identity = SscClosure(arity: 1) { values in values[0] }
            return effectOperation(label, Array(args.dropFirst()), identity)
        case "effect.perform.oneshot":
            guard args.count >= 2,
                  case let .string(effectId) = args[0],
                  case let .string(operationName) = args[1] else {
                fatalError("effect: effect.perform.oneshot expects effect id and operation name Strings")
            }
            let operation = SscOperationId(effect: SscEffectId(value: effectId), name: operationName)
            let identity = oneShotContinuation(operation) { values in values[0] }
            return effectOperation("\(effectId).\(operationName)", Array(args.dropFirst(2)), identity)
        case "effect.handle":
            guard args.count == 2 else { fatalError("effect: effect.handle expects 2 arguments") }
            return handleEffect(args[0], args[1])
        case "f.add": return .float(float(args, 0) + float(args, 1))
        case "f.sub": return .float(float(args, 0) - float(args, 1))
        case "f.mul": return .float(float(args, 0) * float(args, 1))
        case "f.div": return .float(float(args, 0) / float(args, 1))
        case "f.neg": return .float(-float(args, 0))
        case "f.sqrt": return .float(sqrt(float(args, 0)))
        case "f.floor": return .float(floor(float(args, 0)))
        case "f.ceil": return .float(ceil(float(args, 0)))
        case "f.round": return .float(float(args, 0).rounded(.toNearestOrEven))
        case "f.trunc": return .float(float(args, 0).rounded(.towardZero))
        case "f.eq": return .bool(float(args, 0) == float(args, 1))
        case "f.lt": return .bool(float(args, 0) < float(args, 1))
        case "f.le": return .bool(float(args, 0) <= float(args, 1))
        case "f.gt": return .bool(float(args, 0) > float(args, 1))
        case "f.ge": return .bool(float(args, 0) >= float(args, 1))
        case "f.isNaN": return .bool(float(args, 0).isNaN)
        case "f.isInf": return .bool(float(args, 0).isInfinite)
        case "i->f": return .float(Double(int(args, 0)))
        case "f->i": return .int(Int64(float(args, 0)))
        case "i->str": return .string(String(int(args, 0)))
        case "f->str": return .string(showFloat(float(args, 0)))
        case "slen": return .int(Int64(string(args, 0).utf16.count))
        case "sconcat": return .string(sscPlain(args[0]) + sscPlain(args[1]))
        case "sslice":
            let units = Array(string(args, 0).utf16)
            let slice = units[Int(int(args, 1))..<Int(int(args, 2))]
            return .string(String(decoding: slice, as: UTF16.self))
        case "scodeAt": return .int(Int64(Array(string(args, 0).utf16)[Int(int(args, 1))]))
        case "sfromCodes":
            let units = list(args[0]).map { UInt16(truncatingIfNeeded: intValue($0)) }
            return .string(String(decoding: units, as: UTF16.self))
        case "seq": return .bool(string(args, 0) == string(args, 1))
        case "scmp":
            let order = string(args, 0).compare(string(args, 1))
            return .int(order == .orderedAscending ? -1 : (order == .orderedDescending ? 1 : 0))
        case "sindexOf":
            let source = string(args, 0) as NSString
            return .int(Int64(source.range(of: string(args, 1)).location == NSNotFound ? -1 : source.range(of: string(args, 1)).location))
        case "str.trim": return .string(string(args, 0).trimmingCharacters(in: .whitespacesAndNewlines))
        case "str.replace": return .string(string(args, 0).replacingOccurrences(of: string(args, 1), with: string(args, 2)))
        case "str.lines": return listValue(string(args, 0).components(separatedBy: "\n").map(SscValue.string))
        case "blen": return .int(Int64(bytes(args, 0).count))
        case "bget": return .int(Int64(bytes(args, 0)[Int(int(args, 1))]))
        case "bslice": return .bytes(Array(bytes(args, 0)[Int(int(args, 1))..<Int(int(args, 2))]))
        case "bconcat": return .bytes(bytes(args, 0) + bytes(args, 1))
        case "str->utf8": return .bytes(Array(string(args, 0).utf8))
        case "utf8->str": return .string(String(decoding: bytes(args, 0), as: UTF8.self))
        case "tagOf": return .string(data(args[0]).0)
        case "arity": return .int(Int64(data(args[0]).1.count))
        case "fieldAt":
            if args.count >= 3, case .data("__RoundingModeCompanion__", _) = args[0] { return .string(string(args, 2)) }
            let (tag, fields) = data(args[0])
            if args.count >= 3, let names = program.fieldLayouts["\(tag)#\(fields.count)"], let index = names.firstIndex(of: string(args, 2)) {
                return fields[index]
            }
            return fields[Int(int(args, 1))]
        case "__isTag__":
            guard case let .data(tag, fields) = args[0] else { return .bool(false) }
            let arity = int(args, 2)
            return .bool(tag == string(args, 1) && (arity < 0 || fields.count == Int(arity)))
        case "__autoPrint__":
            if case .unit = args[0] { return .unit }
            if case .data("Op", _) = args[0] { return .unit }
            Swift.print(sscPlain(args[0])); return .unit
        case "cell.new": return .cell(SscCell(args[0]))
        case "cell.get": return cell(args[0]).value
        case "cell.set": cell(args[0]).value = args[1]; return .unit
        case "lcell.new": return .longCell(SscCell(.int(intValue(args[0]))))
        case "lcell.get": return longCell(args[0]).value
        case "lcell.set": longCell(args[0]).value = .int(intValue(args[1])); return .unit
        case "map.new": return .map(SscMap())
        case "map.get": return map(args[0]).get(args[1]).map(some) ?? none()
        case "map.put": map(args[0]).put(args[1], args[2]); return .unit
        case "map.has": return .bool(map(args[0]).get(args[1]) != nil)
        case "map.del": map(args[0]).delete(args[1]); return .unit
        case "map.keys": return listValue(map(args[0]).entries.map { $0.0 })
        case "map.size": return .int(Int64(map(args[0]).entries.count))
        case "arr.new": return .array(SscArray())
        case "__mk_arr__": return .array(SscArray(args))
        case "arr.len": return .int(Int64(array(args[0]).values.count))
        case "arr.get": return array(args[0]).values[Int(int(args, 1))]
        case "arr.set": array(args[0]).values[Int(int(args, 1))] = args[2]; return .unit
        case "arr.push": array(args[0]).values.append(args[1]); return .unit
        case "arr.pop": return array(args[0]).values.removeLast()
        case "arr.slice": return .array(SscArray(Array(array(args[0]).values[Int(int(args, 1))..<Int(int(args, 2))])))
        case "__mk_map__":
            let result = SscMap()
            for pair in args {
                let (_, fields) = data(pair)
                guard fields.count == 2 else { fatalError("Map factory: expected pair") }
                result.put(fields[0], fields[1])
            }
            return .map(result)
        case "__math_obj__": return .data("__Math__", [])
        case "__match_fail_prim__": fatalError("match: no matching case")
        case "__method__", "__effect__":
            guard args.count >= 2 else { fatalError("__method__: missing receiver") }
            return method(string(args, 0), args[1], Array(args.dropFirst(2)))
        case "__effect_oneshot__":
            guard args.count >= 3,
                  case let .string(effectId) = args[0],
                  case let .string(operationName) = args[1] else {
                fatalError("__effect_oneshot__: expected effect id, operation name, receiver, and arguments")
            }
            let dispatched = method(operationName, args[2], Array(args.dropFirst(3)))
            if failure != nil { return .unit }
            return guardOneShotOperation(dispatched, effectId, operationName)
        case "__arith__": return dynamicArithmetic(string(args, 0), args[1], args[2])
        case "__unary__":
            let op = string(args, 0)
            if op == "!" { return .bool(!boolValue(args[1])) }
            if op == "-" {
                switch args[1] {
                case let .int(value): return .int(0 &- value)
                case let .float(value): return .float(-value)
                default: fatalError("__unary__: unsupported value")
                }
            }
            fatalError("__unary__: unsupported operation \(op)")
        case "io.print": Swift.print(sscPlain(args[0]), terminator: ""); return .unit
        case "io.println": Swift.print(sscPlain(args[0])); return .unit
        case "io.nanoTime": return .int(Int64(bitPattern: DispatchTime.now().uptimeNanoseconds))
        case "io.args": return listValue(CommandLine.arguments.dropFirst().map(SscValue.string))
        case "global.reg": globals[string(args, 0)] = args[1]; return .unit
        case "__try__":
            guard case let .closure(thunk) = args[0], case let .closure(handler) = args[1] else {
                fatalError("__try__(thunk, handler)")
            }
            let result = call(thunk, [])
            guard let caught = failure else { return result }
            switch caught {
            case let .thrown(value):
                failure = nil
                return call(handler, [value])
            case let .runtime(error):
                failure = nil
                return call(handler, [.string(error.description)])
            case .control, .host:
                return .unit
            }
        default: fatalError("swift runtime: unsupported primitive '\(operation)'")
        }
    }

    private func dynamicArithmetic(_ op: String, _ lhs: SscValue, _ rhs: SscValue) -> SscValue {
        if op == "->" { return .data("Tuple2", [lhs, rhs]) }
        if case .data("Op", _) = lhs { return liftOperation(lhs) { [weak self] resumed in self!.dynamicArithmetic(op, resumed, rhs) } }
        if case .data("Op", _) = rhs { return liftOperation(rhs) { [weak self] resumed in self!.dynamicArithmetic(op, lhs, resumed) } }
        if case .decimal = lhs { return decimalArithmetic(op, lhs, rhs) }
        if case .decimal = rhs { return decimalArithmetic(op, lhs, rhs) }
        if case .big = lhs { return bigArithmetic(op, lhs, rhs) }
        if case .big = rhs { return bigArithmetic(op, lhs, rhs) }
        if op == "+" || op == "++" {
            let leftIsList: Bool
            switch lhs {
            case .data("Cons", _), .data("Nil", _): leftIsList = true
            default: leftIsList = false
            }
            if leftIsList {
                do {
                    let left = try properList(lhs)
                    let rightIsList: Bool
                    switch rhs {
                    case .data("Cons", _), .data("Nil", _): rightIsList = true
                    default: rightIsList = false
                    }
                    guard rightIsList else {
                        throw SscRuntimeFailure(description: "list concat: right operand must be List")
                    }
                    let right = try properList(rhs)
                    return listValue(left + right)
                } catch let error as SscRuntimeFailure {
                    let normalized = error.description == "app: malformed list"
                        ? SscRuntimeFailure(description: "list concat: malformed list")
                        : error
                    recordFailure(normalized)
                    return .unit
                } catch {
                    recordFailure(error)
                    return .unit
                }
            }
        }
        if case let .string(value) = lhs, op == "+" || op == "++" { return .string(value + sscPlain(rhs)) }
        if case let .string(value) = rhs, op == "+" || op == "++" { return .string(sscPlain(lhs) + value) }
        switch (lhs, rhs) {
        case let (.int(a), .int(b)):
            switch op {
            case "+": return .int(a &+ b); case "-": return .int(a &- b); case "*": return .int(a &* b)
            case "/": return .int(intDiv(a, b)); case "%": return .int(intRem(a, b))
            case "==": return .bool(a == b); case "!=": return .bool(a != b)
            case "<": return .bool(a < b); case "<=": return .bool(a <= b)
            case ">": return .bool(a > b); case ">=": return .bool(a >= b)
            default: break
            }
        case let (.float(a), .float(b)):
            switch op {
            case "+": return .float(a + b); case "-": return .float(a - b); case "*": return .float(a * b); case "/": return .float(a / b)
            case "==": return .bool(a == b); case "!=": return .bool(a != b)
            case "<": return .bool(a < b); case "<=": return .bool(a <= b)
            case ">": return .bool(a > b); case ">=": return .bool(a >= b)
            default: break
            }
        case let (.string(a), .string(b)):
            switch op {
            case "+", "++": return .string(a + b)
            case "==": return .bool(a == b); case "!=": return .bool(a != b)
            case "<": return .bool(a < b); case "<=": return .bool(a <= b)
            case ">": return .bool(a > b); case ">=": return .bool(a >= b)
            default: break
            }
        default:
            if op == "==" { return .bool(sscEqual(lhs, rhs)) }
            if op == "!=" { return .bool(!sscEqual(lhs, rhs)) }
            if case .closure = lhs, case .unit = rhs { return .unit }
        }
        fatalError("__arith__: unsupported operation \(op) on \(sscShow(lhs)), \(sscShow(rhs))")
    }

    private func bigArithmetic(_ op: String, _ lhs: SscValue, _ rhs: SscValue) -> SscValue {
        func value(_ input: SscValue) -> SscBigInt {
            switch input { case let .big(v): return v; case let .int(v): return SscBigInt(String(v)); default: fatalError("BigInt: incompatible operand") }
        }
        let a = value(lhs), b = value(rhs)
        switch op {
        case "+": return .big(a + b); case "-": return .big(a - b); case "*": return .big(a * b)
        case "/": return .big(a / b); case "%": return .big(a % b)
        case "==": return .bool(a == b); case "!=": return .bool(a != b)
        case "<": return .bool(a < b); case "<=": return .bool(a <= b)
        case ">": return .bool(a > b); case ">=": return .bool(a >= b)
        default: fatalError("BigInt: unsupported operation \(op)")
        }
    }

    private func renderJsonCore(_ core: SscValue) -> String? {
        do { try jsonValidateCore(core) }
        catch { recordFailure(error); return nil }
        if let renderer = jsonRenderer {
            let rendered = call(renderer, [core])
            if failure != nil { return nil }
            guard case let .string(text) = rendered else {
                recordFailure(SscRuntimeFailure(description: "self-hosted JSON renderer returned non-string"))
                return nil
            }
            return text
        }
        do { return try jsonRenderCoreNative(core) }
        catch { recordFailure(error); return nil }
    }

    private func method(_ name: String, _ receiver: SscValue, _ args: [SscValue]) -> SscValue {
        do {
            if let result = try nativeUiHost?.method(name, receiver, args) { return result }
        } catch {
            recordFailure(error)
            return .unit
        }
        if case .data("Op", _) = receiver {
            return liftOperation(receiver) { [weak self] resumed in self!.method(name, resumed, args) }
        }
        if case let .data(tag, fields) = receiver,
           let names = program.fieldLayouts["\(tag)#\(fields.count)"],
           let index = names.firstIndex(of: name) {
            if args.isEmpty { return fields[index] }
            if case let .closure(fn) = fields[index] { return call(fn, args) }
            // A named field holding a Map is called like `record.field(key)` — e.g.
            // std/ui/form.ssc's `case class Form(specs: ..., drafts: Map[String, Any])`
            // with `def draft(f: Form, name: String): Any = f.drafts(name)`. Mirrors
            // Map's own "apply" primitive (m(k)), which also throws on a missing key.
            if case let .map(m) = fields[index], args.count == 1 {
                if let found = m.get(args[0]) { return found }
                recordFailure(SscRuntimeFailure(description: "key not found: \(sscShow(args[0]))"))
                return .unit
            }
        }
        switch receiver {
        case let .decimal(value):
            switch name {
            case "toString", "toPlainString": return .string(value.description)
            case "setScale" where args.count == 1: return .decimal(value.withScale(Int(intValue(args[0])), mode: "HALF_UP"))
            case "setScale" where args.count == 2: return .decimal(value.withScale(Int(intValue(args[0])), mode: roundingMode(args[1])))
            case "divide" where args.count == 3: return .decimal(value.divided(by: decimal(args[0]), scale: Int(intValue(args[1])), mode: roundingMode(args[2])))
            case "toBigInt": return .big(value.toBigInt())
            case "scale": return .int(Int64(value.scale))
            case "unscaledValue": return .big(value.unscaled)
            case "abs": return .decimal(value.unscaled.signum < 0 ? -value : value)
            case "negate": return .decimal(-value)
            case "signum": return .int(Int64(value.unscaled.signum))
            case "pow": return .decimal(value.power(Int(intValue(args[0]))))
            case "compareTo": let other = decimal(args[0]); return .int(value < other ? -1 : (value == other ? 0 : 1))
            default: break
            }
        case let .big(value):
            switch name {
            case "toString": return .string(value.description)
            case "pow":
                var base = value, result = SscBigInt("1"), exponent = Int(intValue(args[0]))
                if exponent < 0 { fatalError("BigInt.pow: negative exponent") }
                while exponent > 0 { if exponent & 1 == 1 { result = result * base }; exponent >>= 1; if exponent > 0 { base = base * base } }
                return .big(result)
            default: break
            }
        case let .map(value):
            switch name {
            case "getOrElse": return value.get(args[0]) ?? args[1]
            case "get": return value.get(args[0]).map(some) ?? none()
            case "contains": return .bool(value.get(args[0]) != nil)
            case "size": return .int(Int64(value.entries.count))
            case "updated":
                let copy = SscMap()
                copy.entries = value.entries
                copy.put(args[0], args[1])
                return .map(copy)
            case "removed":
                let copy = SscMap()
                copy.entries = value.entries
                copy.delete(args[0])
                return .map(copy)
            default: break
            }
        case .data("Cons", _), .data("Nil", _):
            if name == "toList" && args.isEmpty { return receiver }
            let values = list(receiver)
            switch name {
            case "map":
                guard case let .closure(fn) = args[0] else { fatalError("List.map expects closure") }
                return listValue(values.map { item in
                    if case let .data(tag, fields) = item, tag.hasPrefix("Tuple"), fn.arity == fields.count {
                        return call(fn, fields.asArray())
                    }
                    return call(fn, [item])
                })
            case "foldLeft":
                guard case let .closure(fn) = args[1] else { fatalError("List.foldLeft expects closure") }
                return values.reduce(args[0]) { call(fn, [$0, $1]) }
            case "zipWithIndex":
                return listValue(values.enumerated().map { .data("Tuple2", [$0.element, .int(Int64($0.offset))]) })
            case "filter":
                guard case let .closure(fn) = args[0] else { fatalError("List.filter expects closure") }
                return listValue(values.filter { item in
                    if case .bool(true) = call(fn, [item]) { return true }
                    return false
                })
            case "flatMap":
                guard case let .closure(fn) = args[0] else { fatalError("List.flatMap expects closure") }
                return listValue(values.flatMap { item -> [SscValue] in
                    let result: SscValue
                    if case let .data(tag, fields) = item, tag.hasPrefix("Tuple"), fn.arity == fields.count {
                        result = call(fn, fields.asArray())
                    } else {
                        result = call(fn, [item])
                    }
                    switch result {
                    case .data("Cons", _), .data("Nil", _): return list(result)
                    default: return [result]
                    }
                })
            case "reverse": return listValue(Array(values.reversed()))
            case "mkString":
                let delimiters: (String, String, String)?
                if args.isEmpty {
                    delimiters = ("", "", "")
                } else if args.count == 1, case let .string(separator) = args[0] {
                    delimiters = ("", separator, "")
                } else if args.count == 3,
                          case let .string(prefix) = args[0],
                          case let .string(separator) = args[1],
                          case let .string(suffix) = args[2] {
                    delimiters = (prefix, separator, suffix)
                } else {
                    delimiters = nil
                }
                if let (prefix, separator, suffix) = delimiters {
                    return .string(prefix + values.map(sscPlain).joined(separator: separator) + suffix)
                }
            case "length", "size": return .int(Int64(values.count))
            case "isEmpty": return .bool(values.isEmpty)
            case "nonEmpty": return .bool(!values.isEmpty)
            case "head":
                guard let first = values.first else {
                    recordFailure(SscRuntimeFailure(description: "head on empty list"))
                    return .unit
                }
                return first
            case "tail":
                guard !values.isEmpty else {
                    recordFailure(SscRuntimeFailure(description: "tail on empty list"))
                    return .unit
                }
                return listValue(Array(values.dropFirst()))
            default: break
            }
        case let .string(value):
            if name == "toString" { return .string(value) }
            if name == "length" || name == "size" { return .int(Int64(value.utf16.count)) }
            if name == "charAt" {
                let index = Int(intValue(args[0])), units = Array(value.utf16)
                guard units.indices.contains(index) else {
                    recordFailure(SscRuntimeFailure(description: "String.charAt: index out of bounds"))
                    return .unit
                }
                return .int(Int64(units[index]))
            }
            if name == "substring" {
                let from = Int(intValue(args[0])), to = Int(intValue(args[1])), units = Array(value.utf16)
                guard from >= 0, to >= from, to <= units.count else {
                    recordFailure(SscRuntimeFailure(description: "String.substring: index out of bounds"))
                    return .unit
                }
                return .string(String(decoding: units[from..<to], as: UTF16.self))
            }
            if name == "trim" {
                let units = Array(value.utf16)
                var start = 0, end = units.count
                while start < end && units[start] <= 0x20 { start += 1 }
                while end > start && units[end - 1] <= 0x20 { end -= 1 }
                return .string(String(decoding: units[start..<end], as: UTF16.self))
            }
            if name == "matches", args.count == 1, case let .string(pattern) = args[0] {
                // Java/Scala String.matches requires the WHOLE string to satisfy the
                // pattern (Matcher.matches, not find) — check the match spans the
                // entire input, not just that the pattern occurs somewhere in it.
                guard let regex = try? NSRegularExpression(pattern: pattern) else {
                    recordFailure(SscRuntimeFailure(description: "String.matches: invalid regex"))
                    return .unit
                }
                let range = NSRange(value.startIndex..<value.endIndex, in: value)
                let found = regex.firstMatch(in: value, range: range)
                return .bool(found?.range == range)
            }
            if name == "toInt" {
                let units = Array(value.utf16)
                var start = 0, end = units.count
                while start < end && units[start] <= 0x20 { start += 1 }
                while end > start && units[end - 1] <= 0x20 { end -= 1 }
                let trimmed = String(decoding: units[start..<end], as: UTF16.self)
                if let parsed = Int64(trimmed) { return .int(parsed) }
                recordFailure(SscRuntimeFailure(description: "String.toInt: invalid integer"))
                return .unit
            }
            if name == "replace", args.count == 2, case let .string(from) = args[0], case let .string(to) = args[1] {
                return .string(value.replacingOccurrences(of: from, with: to))
            }
            if name == "contains", args.count == 1, case let .string(sub) = args[0] {
                return .bool(sub.isEmpty || value.contains(sub))
            }
            if name == "startsWith", args.count == 1, case let .string(prefix) = args[0] {
                return .bool(value.hasPrefix(prefix))
            }
            if name == "endsWith", args.count == 1, case let .string(suffix) = args[0] {
                return .bool(value.hasSuffix(suffix))
            }
        case let .int(value):
            if name == "toString" { return .string(String(value)) }
        case .data("__RoundingModeCompanion__", _):
            return .string(name)
        case let .data("__JsonBox__", boxFields) where boxFields.count == 1:
            let core = boxFields[0]
            func boxed(_ value: SscValue) -> SscValue { .data("__JsonBox__", [value]) }
            do {
                try jsonValidateCore(core)
                func decimalText() throws -> String? {
                    let raw: String?
                    if let number = jsonNumberText(core) { raw = number }
                    else { raw = try jsonStringValue(core) }
                    guard let raw, jsonDecimalParts(raw) != nil else { return nil }
                    return raw
                }
                switch name {
                case "get":
                    guard case let .string(key) = args[0] else { return boxed(jsonNullCore()) }
                    return boxed(try jsonObjectValues(core).first { $0.0 == key }?.1 ?? jsonNullCore())
                case "at":
                    guard case let .int(index) = args[0] else { return boxed(jsonNullCore()) }
                    let items = try jsonArrayValues(core)
                    return (index >= 0 && Int(index) < items.count) ? boxed(items[Int(index)]) : boxed(jsonNullCore())
                case "isNull": return .bool(jsonIsNull(core))
                case "asString": return .string(try jsonStringValue(core) ?? "")
                case "asInt":
                    if let raw = try decimalText(), let parts = jsonDecimalParts(raw) {
                        return .int(jsonLowInt64(parts, requireIntegral: false) ?? 0)
                    }
                    return .int(0)
                case "asDouble":
                    if let raw = try decimalText(), let value = Double(raw) { return .float(value) }
                    return .float(0.0)
                case "asBool": return .bool(jsonBoolValue(core) ?? false)
                case "asList": return listValue(try jsonArrayValues(core).map(boxed))
                case "asDecimal":
                    guard let raw = try decimalText() else { return .decimal(SscDecimal("0")) }
                    return .decimal(try jsonDecimalValue(raw))
                case "optString":
                    if let text = try jsonStringValue(core) { return some(.string(text)) }
                    return none()
                case "optInt":
                    if let raw = try decimalText(), let parts = jsonDecimalParts(raw),
                       let value = jsonLowInt64(parts, requireIntegral: true) { return some(.int(value)) }
                    return none()
                case "optDecimal":
                    if let raw = try decimalText() { return some(.decimal(try jsonDecimalValue(raw))) }
                    return none()
                case "getOrElse":
                    let fallback: String
                    if case let .string(value) = args[1] { fallback = value } else { fallback = "" }
                    guard case let .string(key) = args[0],
                          let value = try jsonObjectValues(core).first(where: { $0.0 == key })?.1 else {
                        return .string(fallback)
                    }
                    if let text = try jsonStringValue(value) { return .string(text) }
                    guard let rendered = renderJsonCore(value) else { return .unit }
                    return .string(rendered)
                case "raw":
                    guard let rendered = renderJsonCore(core) else { return .unit }
                    return .string(rendered)
                case "size":
                    let items = try jsonArrayValues(core)
                    return .int(Int64(items.isEmpty ? (try jsonObjectValues(core).count) : items.count))
                case "keys": return listValue(try jsonObjectValues(core).map { .string($0.0) })
                default: break
                }
            } catch {
                recordFailure(error)
                return .unit
            }
        case let .data(tag, fields):
            // Option.getOrElse, mirroring v2/src/Runtime.scala's general interpreter exactly
            // (DataV("Some", Seq(v)) => v; DataV("None", _) => the passed default, used as-is,
            // not invoked as a thunk). Found running busi's real app.ssc natively: without
            // this, `someOption.getOrElse(...)` on None fell through to the generic
            // unimplemented-method Op-deferral below, and using that Op as an `if` condition
            // crashed with "if: condition not Bool".
            if tag == "Some", fields.count == 1, name == "getOrElse", args.count == 1 { return fields[0] }
            if tag == "None", name == "getOrElse", args.count == 1 { return args[0] }
            let argument: SscValue
            if args.isEmpty { argument = .unit }
            else if args.count == 1 { argument = args[0] }
            else { argument = .data("__EffArgs__", SscFields(args)) }
            let identity = SscClosure(arity: 1) { values in values[0] }
            return .data("Op", [.string("\(tag).\(name)"), argument, .closure(identity)])
        default: break
        }
        fatalError("method not found: \(name) on \(sscShow(receiver))")
    }

    private func decimalArithmetic(_ op: String, _ lhs: SscValue, _ rhs: SscValue) -> SscValue {
        if case .float = lhs { fatalError("decimal: Decimal and Double cannot be mixed") }
        if case .float = rhs { fatalError("decimal: Decimal and Double cannot be mixed") }
        let a = decimal(lhs), b = decimal(rhs)
        switch op {
        case "+": return .decimal(a + b)
        case "-": return .decimal(a - b)
        case "*": return .decimal(a * b)
        case "/": return .decimal(a.divided(by: b, scale: max(max(a.scale, b.scale), 10), mode: "HALF_UP"))
        case "%": return .decimal(a.remainder(b))
        case "==": return .bool(a == b); case "!=": return .bool(a != b)
        case "<": return .bool(a < b); case "<=": return .bool(a <= b)
        case ">": return .bool(a > b); case ">=": return .bool(a >= b)
        case "++": return .string(a.description + b.description)
        default: fatalError("decimal: operator '\(op)' is not valid for Decimal")
        }
    }

    private func handleEffect(_ computation: SscValue, _ handlerValue: SscValue) -> SscValue {
        guard case let .closure(handler) = handlerValue else { fatalError("effect: handler must be a closure") }
        switch computation {
        case let .data("Pure", fields) where fields.count == 1:
            return handleEffect(fields[0], handlerValue)
        case let .data("Op", fields) where fields.count == 3:
            guard case let .string(label) = fields[0], case let .closure(continuation) = fields[2] else {
                fatalError("effect: malformed Op")
            }
            let resume = SscClosure(arity: 1) { [weak self] values in
                guard let self else { fatalError("effect: runtime released") }
                let resumed = self.call(continuation, values)
                if self.failure != nil { return .unit }
                return self.handleEffect(resumed, handlerValue)
            }
            let eventArgs: [SscValue]
            switch fields[1] {
            case .unit: eventArgs = [.closure(resume)]
            case let .data("__EffArgs__", packed): eventArgs = packed + [.closure(resume)]
            default: eventArgs = [fields[1], .closure(resume)]
            }
            let operation = label.split(separator: ".").last.map(String.init) ?? label
            return call(handler, [.data(operation, SscFields(eventArgs))])
        case .data("Op", _): fatalError("effect: malformed Op")
        default:
            switch callPartial(handler, [.data("Return", [computation])]) {
            case let .matched(value): return value
            case .noMatch: return computation
            }
        }
    }

    private func liftOperation(_ operation: SscValue, _ transform: @escaping (SscValue) -> SscValue) -> SscValue {
        guard case let .data("Op", fields) = operation, fields.count == 3,
              case let .closure(continuation) = fields[2] else { fatalError("effect: malformed Op") }
        let lifted = SscClosure(arity: 1) { [weak self] values in
            guard let self else { fatalError("effect: runtime released") }
            let resumed = self.call(continuation, values)
            if self.failure != nil { return .unit }
            return transform(resumed)
        }
        return .data("Op", [fields[0], fields[1], .closure(lifted)])
    }

    private func effectOperation(_ label: String, _ arguments: [SscValue], _ continuation: SscClosure) -> SscValue {
        let argument: SscValue
        if arguments.isEmpty { argument = .unit }
        else if arguments.count == 1 { argument = arguments[0] }
        else { argument = .data("__EffArgs__", SscFields(arguments)) }
        return .data("Op", [.string(label), argument, .closure(continuation)])
    }

    private func oneShotContinuation(
        _ operation: SscOperationId,
        _ continuation: @escaping ([SscValue]) throws -> SscValue
    ) -> SscClosure {
        let claim = SscOneShotClaim()
        return SscClosure(arity: 1) { values in
            guard claim.tryClaim() else {
                throw SscControlRunFailure(rejection: .alreadyResumed(operation))
            }
            return try continuation(values)
        }
    }

    private func guardOneShotOperation(
        _ value: SscValue,
        _ effectId: String,
        _ operationName: String
    ) -> SscValue {
        let expectedLabel = "\(effectId).\(operationName)"
        guard case let .data("Op", fields) = value,
              fields.count == 3,
              case let .string(label) = fields[0],
              label == expectedLabel,
              case let .closure(continuation) = fields[2] else { return value }
        let operation = SscOperationId(effect: SscEffectId(value: effectId), name: operationName)
        let guarded = oneShotContinuation(operation) { [weak self] values in
            guard let self else { fatalError("effect: runtime released") }
            return self.call(continuation, values)
        }
        return .data("Op", [fields[0], fields[1], .closure(guarded)])
    }

    private func intDiv(_ lhs: Int64, _ rhs: Int64) -> Int64 {
        if rhs == 0 { fatalError("integer division by zero") }
        if lhs == Int64.min && rhs == -1 { return Int64.min }
        return lhs / rhs
    }

    private func intRem(_ lhs: Int64, _ rhs: Int64) -> Int64 {
        if rhs == 0 { fatalError("integer division by zero") }
        if lhs == Int64.min && rhs == -1 { return 0 }
        return lhs % rhs
    }
}

private func canonicalInteger(_ raw: String) -> String {
    var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    let negative = text.hasPrefix("-")
    if negative || text.hasPrefix("+") { text.removeFirst() }
    text = String(text.drop(while: { $0 == "0" }))
    if text.isEmpty { return "0" }
    return negative ? "-" + text : text
}

private func int(_ args: [SscValue], _ index: Int) -> Int64 { intValue(args[index]) }
private func intValue(_ value: SscValue) -> Int64 {
    guard case let .int(result) = value else { fatalError("expected Int, got \(sscShow(value))") }
    return result
}
private func bool(_ args: [SscValue], _ index: Int) -> Bool { boolValue(args[index]) }
private func boolValue(_ value: SscValue) -> Bool {
    guard case let .bool(result) = value else { fatalError("expected Bool, got \(sscShow(value))") }
    return result
}
private func float(_ args: [SscValue], _ index: Int) -> Double {
    switch args[index] { case let .float(value): return value; case let .int(value): return Double(value); default: fatalError("expected Float") }
}
private func big(_ args: [SscValue], _ index: Int) -> SscBigInt {
    guard case let .big(result) = args[index] else { fatalError("expected BigInt") }
    return result
}
private func decimalBigInt(_ value: SscValue) -> SscBigInt {
    switch value { case let .big(result): return result; case let .int(result): return SscBigInt(String(result)); default: fatalError("decimal: unscaled value must be Int or BigInt") }
}
private func decimal(_ value: SscValue) -> SscDecimal {
    switch value {
    case let .decimal(result): return result
    case let .int(result): return SscDecimal(String(result))
    case let .big(result): return SscDecimal(result.description)
    case let .string(result): return SscDecimal(result)
    case .float: fatalError("decimal: binary floating-point input is inexact")
    default: fatalError("decimal: expected Decimal-compatible value, got \(sscShow(value))")
    }
}
private func roundingMode(_ value: SscValue) -> String {
    switch value {
    case let .string(mode): return mode
    case let .data("RoundingMode", fields):
        if fields.count == 1, case let .string(mode) = fields[0] { return mode }
        fatalError("decimal: malformed RoundingMode")
    case let .data(mode, _): return mode
    default: fatalError("decimal: unsupported rounding mode \(sscShow(value))")
    }
}
private func string(_ args: [SscValue], _ index: Int) -> String {
    guard case let .string(result) = args[index] else { fatalError("expected String") }
    return result
}
private func bytes(_ args: [SscValue], _ index: Int) -> [UInt8] {
    guard case let .bytes(result) = args[index] else { fatalError("expected Bytes") }
    return result
}
private func data(_ value: SscValue) -> (String, [SscValue]) {
    guard case let .data(tag, fields) = value else { fatalError("expected Data, got \(sscShow(value))") }
    return (tag, fields.asArray())
}
private func cell(_ value: SscValue) -> SscCell {
    guard case let .cell(result) = value else { fatalError("expected Cell") }
    return result
}
private func longCell(_ value: SscValue) -> SscCell {
    guard case let .longCell(result) = value else { fatalError("expected LongCell") }
    return result
}
private func map(_ value: SscValue) -> SscMap {
    guard case let .map(result) = value else { fatalError("expected Map") }
    return result
}
private func array(_ value: SscValue) -> SscArray {
    guard case let .array(result) = value else { fatalError("expected Array") }
    return result
}

private func none() -> SscValue { .data("None", []) }
private func some(_ value: SscValue) -> SscValue { .data("Some", [value]) }
private func listValue(_ values: [SscValue]) -> SscValue {
    values.reversed().reduce(.data("Nil", [])) { .data("Cons", [$1, $0]) }
}
private func list(_ value: SscValue) -> [SscValue] {
    var result: [SscValue] = []
    var current = value
    while true {
        switch current {
        case .data("Nil", _): return result
        case let .data("Cons", fields) where fields.count == 2:
            result.append(fields[0]); current = fields[1]
        default: fatalError("expected List, got \(sscShow(current))")
        }
    }
}
private func properList(_ value: SscValue) throws -> [SscValue] {
    var result: [SscValue] = []
    var current = value
    while true {
        switch current {
        case let .data("Nil", fields) where fields.isEmpty: return result
        case let .data("Cons", fields) where fields.count == 2:
            result.append(fields[0]); current = fields[1]
        default: throw SscRuntimeFailure(description: "app: malformed list")
        }
    }
}

// ── JsonCore bridge: navigation/value mapping for std/json.ssc's opaque JsonValue.
// Mirrors ssc.plugin.json.NativeJsonCodec (the JVM plugin) so the portable
// JsonCore* ADT produced by the self-hosted json-core.ssc parser behaves the
// same way here as on the interpreter/JVM lanes.

private func jsonNullCore() -> SscValue { .data("JsonCoreNull", []) }

private func jsonIsNull(_ core: SscValue) -> Bool {
    if case .data("JsonCoreNull", _) = core { return true }
    return false
}

private func jsonList(_ value: SscValue) throws -> [SscValue] {
    var result: [SscValue] = []
    var current = value
    while true {
        switch current {
        case .data("Nil", _): return result
        case let .data("Cons", fields) where fields.count == 2:
            result.append(fields[0]); current = fields[1]
        default: throw SscRuntimeFailure(description: "invalid JsonCore list")
        }
    }
}

private func jsonDecodeCodeUnits(_ value: SscValue) throws -> String {
    let units = try jsonList(value).map { item -> UInt16 in
        guard case let .int(unit) = item, unit >= 0, unit <= 0xffff else {
            throw SscRuntimeFailure(description: "invalid JsonCore string code unit")
        }
        return UInt16(unit)
    }
    var index = 0
    while index < units.count {
        let unit = units[index]
        if unit >= 0xd800 && unit <= 0xdbff {
            guard index + 1 < units.count, units[index + 1] >= 0xdc00, units[index + 1] <= 0xdfff else {
                throw SscRuntimeFailure(description: "invalid JsonCore UTF-16 surrogate pair")
            }
            index += 2
        } else if unit >= 0xdc00 && unit <= 0xdfff {
            throw SscRuntimeFailure(description: "invalid JsonCore UTF-16 surrogate pair")
        } else {
            index += 1
        }
    }
    return String(decoding: units, as: UTF16.self)
}

private func jsonCodeUnits(_ text: String) -> SscValue {
    listValue(text.utf16.map { .int(Int64($0)) })
}

private func jsonStringValue(_ core: SscValue) throws -> String? {
    if case let .data("JsonCoreString", fields) = core, fields.count == 1 { return try jsonDecodeCodeUnits(fields[0]) }
    return nil
}

private func jsonNumberText(_ core: SscValue) -> String? {
    if case let .data("JsonCoreNumber", fields) = core, fields.count == 1, case let .string(raw) = fields[0] { return raw }
    return nil
}

private func jsonBoolValue(_ core: SscValue) -> Bool? {
    if case let .data("JsonCoreBool", fields) = core, fields.count == 1, case let .bool(b) = fields[0] { return b }
    return nil
}

private func jsonArrayValues(_ core: SscValue) throws -> [SscValue] {
    if case let .data("JsonCoreArray", fields) = core, fields.count == 1 { return try jsonList(fields[0]) }
    return []
}

private func jsonObjectValues(_ core: SscValue) throws -> [(String, SscValue)] {
    guard case let .data("JsonCoreObject", fields) = core, fields.count == 1 else { return [] }
    return try jsonList(fields[0]).map { entry in
        guard case let .data("JsonCoreField", kv) = entry, kv.count == 2 else {
            throw SscRuntimeFailure(description: "invalid JsonCoreField")
        }
        return (try jsonDecodeCodeUnits(kv[0]), kv[1])
    }
}

private struct JsonDecimalParts {
    let negative: Bool
    let digits: [UInt8]
    let scale: Int
}

private func jsonDecimalParts(_ raw: String) -> JsonDecimalParts? {
    let bytes = Array(raw.utf8)
    guard !bytes.isEmpty else { return nil }
    var index = 0, negative = false
    if bytes[index] == 45 || bytes[index] == 43 { negative = bytes[index] == 45; index += 1 }
    let integerStart = index
    while index < bytes.count && bytes[index] >= 48 && bytes[index] <= 57 { index += 1 }
    guard index > integerStart else { return nil }
    var digits = bytes[integerStart..<index].map { $0 - 48 }
    var fractionCount = 0
    if index < bytes.count && bytes[index] == 46 {
        index += 1
        let fractionStart = index
        while index < bytes.count && bytes[index] >= 48 && bytes[index] <= 57 { index += 1 }
        fractionCount = index - fractionStart
        digits.append(contentsOf: bytes[fractionStart..<index].map { $0 - 48 })
    }
    var exponent = 0
    if index < bytes.count && (bytes[index] == 101 || bytes[index] == 69) {
        index += 1
        let exponentStart = index
        if index < bytes.count && (bytes[index] == 45 || bytes[index] == 43) { index += 1 }
        let digitsStart = index
        while index < bytes.count && bytes[index] >= 48 && bytes[index] <= 57 { index += 1 }
        guard index > digitsStart, let parsed = Int(String(decoding: bytes[exponentStart..<index], as: UTF8.self)) else { return nil }
        exponent = parsed
    }
    guard index == bytes.count else { return nil }
    let (scale, overflow) = fractionCount.subtractingReportingOverflow(exponent)
    guard !overflow else { return nil }
    return JsonDecimalParts(negative: negative, digits: digits, scale: scale)
}

private func jsonLowInt64(_ parts: JsonDecimalParts, requireIntegral: Bool) -> Int64? {
    var integerDigits = parts.digits
    if parts.scale > 0 {
        if requireIntegral {
            let suffix = min(parts.scale, integerDigits.count)
            if integerDigits.suffix(suffix).contains(where: { $0 != 0 }) { return nil }
        }
        integerDigits = parts.scale >= integerDigits.count ? [] : Array(integerDigits.dropLast(parts.scale))
    }
    var bits: UInt64 = 0
    for digit in integerDigits { bits = bits &* 10 &+ UInt64(digit) }
    if parts.scale < 0 {
        guard parts.scale != Int.min else { return nil }
        for _ in 0..<(-parts.scale) { bits = bits &* 10 }
    }
    if parts.negative { bits = 0 &- bits }
    return Int64(bitPattern: bits)
}

private func jsonDecimalValue(_ raw: String) throws -> SscDecimal {
    guard let parts = jsonDecimalParts(raw), abs(parts.scale) <= 10000 else {
        throw SscRuntimeFailure(description: "invalid or unrepresentable JSON decimal")
    }
    return SscDecimal(raw)
}

private func jsonValidateCore(_ core: SscValue) throws {
    switch core {
    case .data("JsonCoreNull", let fields) where fields.isEmpty: return
    case .data("JsonCoreBool", let fields) where fields.count == 1:
        guard case .bool = fields[0] else { break }; return
    case .data("JsonCoreNumber", let fields) where fields.count == 1:
        guard case let .string(raw) = fields[0], jsonDecimalParts(raw) != nil else { break }; return
    case .data("JsonCoreString", let fields) where fields.count == 1:
        _ = try jsonDecodeCodeUnits(fields[0]); return
    case .data("JsonCoreArray", let fields) where fields.count == 1:
        for value in try jsonList(fields[0]) { try jsonValidateCore(value) }; return
    case .data("JsonCoreObject", let fields) where fields.count == 1:
        for entry in try jsonList(fields[0]) {
            guard case let .data("JsonCoreField", pair) = entry, pair.count == 2 else {
                throw SscRuntimeFailure(description: "invalid JsonCoreField")
            }
            _ = try jsonDecodeCodeUnits(pair[0]); try jsonValidateCore(pair[1])
        }
        return
    default: break
    }
    throw SscRuntimeFailure(description: "invalid JsonCore value")
}

private func jsonStringCore(_ text: String) -> SscValue { .data("JsonCoreString", [jsonCodeUnits(text)]) }
private func jsonNumberCore(_ raw: String) -> SscValue { .data("JsonCoreNumber", [.string(raw)]) }
private func jsonArrayCore(_ values: [SscValue]) -> SscValue { .data("JsonCoreArray", [listValue(values)]) }
private func jsonObjectCore(_ entries: [(String, SscValue)]) -> SscValue {
    .data("JsonCoreObject", [listValue(entries.map { .data("JsonCoreField", [jsonCodeUnits($0.0), $0.1]) })])
}

private func jsonUnwrapStrict(_ result: SscValue) throws -> SscValue {
    switch result {
    case let .data("JsonCoreOk", fields) where fields.count == 2: return fields[0]
    case let .data("JsonCoreErr", fields) where fields.count == 2:
        guard case let .string(message) = fields[0], case let .int(offset) = fields[1] else {
            throw SscRuntimeFailure(description: "invalid JSON")
        }
        throw SscRuntimeFailure(description: "invalid JSON at \(offset): \(message)")
    default: throw SscRuntimeFailure(description: "invalid self-hosted JSON parser result")
    }
}

private func jsonToRaw(_ core: SscValue) throws -> SscValue {
    switch core {
    case .data("JsonCoreNull", _): return .unit
    case let .data("JsonCoreBool", fields) where fields.count == 1: return fields[0]
    case let .data("JsonCoreNumber", fields) where fields.count == 1:
        guard case let .string(raw) = fields[0] else { throw SscRuntimeFailure(description: "invalid JsonCore number") }
        if !raw.contains(".") && !raw.lowercased().contains("e") {
            if let i = Int64(raw) { return .int(i) }
            return .big(SscBigInt(raw))
        }
        return .decimal(try jsonDecimalValue(raw))
    case .data("JsonCoreString", _): return .string(try jsonStringValue(core) ?? "")
    case .data("JsonCoreArray", _): return listValue(try jsonArrayValues(core).map { try jsonToRaw($0) })
    case .data("JsonCoreObject", _):
        let result = SscMap()
        for (key, value) in try jsonObjectValues(core) { result.put(.string(key), try jsonToRaw(value)) }
        return .map(result)
    default: throw SscRuntimeFailure(description: "invalid JsonCore value")
    }
}

private func jsonReferenceKeyText(_ value: SscValue) -> String {
    switch value {
    case .unit: return "UnitV"
    case let .bool(value): return "BoolV(\(value))"
    case let .int(value): return "IntV(\(value))"
    case let .big(value): return "BigV(\(value))"
    case let .decimal(value): return "DecimalV(\(value))"
    case let .float(value): return "FloatV(\(value))"
    default: return sscShow(value)
    }
}

private func jsonToCore(_ value: SscValue) throws -> SscValue {
    switch value {
    case let .data("__JsonBox__", fields) where fields.count == 1: return fields[0]
    case let .data(tag, _) where tag.hasPrefix("JsonCore"): return value
    case .unit: return jsonNullCore()
    case let .bool(b): return .data("JsonCoreBool", [.bool(b)])
    case let .int(n): return jsonNumberCore(String(n))
    case let .big(n): return jsonNumberCore(n.description)
    case let .decimal(d): return jsonNumberCore(d.description)
    case let .float(n):
        guard n.isFinite else { throw SscRuntimeFailure(description: "jsonStringify cannot encode NaN or Infinity") }
        return jsonNumberCore(String(n))
    case let .string(s): return jsonStringCore(s)
    case let .bytes(b): return jsonArrayCore(b.map { jsonNumberCore(String($0)) })
    case .data("Nil", _): return jsonArrayCore([])
    case .data("Cons", _): return jsonArrayCore(try jsonList(value).map { try jsonToCore($0) })
    case .data("None", _): return jsonNullCore()
    case let .data("Some", fields) where fields.count == 1: return try jsonToCore(fields[0])
    case let .data(tag, fields) where tag.hasPrefix("Tuple"):
        return jsonArrayCore(try fields.asArray().map { try jsonToCore($0) })
    case let .data(tag, fields):
        return jsonObjectCore([("tag", jsonStringCore(tag)), ("fields", jsonArrayCore(try fields.asArray().map { try jsonToCore($0) }))])
    case let .map(m):
        let entries = try m.entries.map { (keyText: SscValue, value: SscValue) -> (String, SscValue) in
            if case let .string(s) = keyText { return (s, try jsonToCore(value)) }
            return (jsonReferenceKeyText(keyText), try jsonToCore(value))
        }.sorted { $0.0 < $1.0 }
        return jsonObjectCore(entries)
    case .closure: return jsonStringCore("<function>")
    default: return jsonStringCore(sscPlain(value))
    }
}

private func jsonQuote(_ text: String) -> String {
    var result = "\""
    for unit in text.utf16 {
        switch unit {
        case 34: result += "\\\""
        case 92: result += "\\\\"
        case 10: result += "\\n"
        case 13: result += "\\r"
        case 9: result += "\\t"
        case 8: result += "\\b"
        case 12: result += "\\f"
        default:
            if unit < 0x20 || unit > 0x7e { result += String(format: "\\u%04x", unit) }
            else { result.unicodeScalars.append(Unicode.Scalar(UInt32(unit))!) }
        }
    }
    result += "\""
    return result
}

private func jsonRenderCoreNative(_ core: SscValue) throws -> String {
    try jsonValidateCore(core)
    switch core {
    case .data("JsonCoreNull", _): return "null"
    case let .data("JsonCoreBool", fields) where fields.count == 1:
        if case let .bool(b) = fields[0] { return b ? "true" : "false" }
        return "null"
    case .data("JsonCoreNumber", _): return jsonNumberText(core) ?? "0"
    case .data("JsonCoreString", _): return jsonQuote(try jsonStringValue(core) ?? "")
    case .data("JsonCoreArray", _):
        return "[" + (try jsonArrayValues(core).map { try jsonRenderCoreNative($0) }).joined(separator: ",") + "]"
    case .data("JsonCoreObject", _):
        return "{" + (try jsonObjectValues(core).map { jsonQuote($0.0) + ":" + (try jsonRenderCoreNative($0.1)) }).joined(separator: ",") + "}"
    default: throw SscRuntimeFailure(description: "cannot render JsonCore value")
    }
}

private func jsonLookup(_ receiver: SscValue, _ key: SscValue) throws -> SscValue? {
    switch receiver {
    case let .data("__JsonBox__", fields) where fields.count == 1:
        try jsonValidateCore(fields[0])
        if case let .string(k) = key {
            guard let value = try jsonObjectValues(fields[0]).first(where: { $0.0 == k })?.1 else { return nil }
            return try jsonToRaw(value)
        }
        if case let .int(i) = key {
            let items = try jsonArrayValues(fields[0])
            return (i >= 0 && Int(i) < items.count) ? try jsonToRaw(items[Int(i)]) : nil
        }
        return nil
    case let .map(m): return m.get(key)
    case .data("Cons", _), .data("Nil", _):
        guard case let .int(i) = key, i >= 0 else { return nil }
        let values = try jsonList(receiver)
        return Int(i) < values.count ? values[Int(i)] : nil
    case let .string(s):
        guard case let .int(i) = key, i >= 0, Int(i) < s.utf16.count else { return nil }
        let idx = s.utf16.index(s.utf16.startIndex, offsetBy: Int(i))
        return .string(String(utf16CodeUnits: [s.utf16[idx]], count: 1))
    default: return nil
    }
}

private func sscEqual(_ lhs: SscValue, _ rhs: SscValue) -> Bool {
    switch (lhs, rhs) {
    case (.unit, .unit): return true
    case let (.bool(a), .bool(b)): return a == b
    case let (.int(a), .int(b)): return a == b
    case let (.big(a), .big(b)): return a == b
    case let (.decimal(a), .decimal(b)): return a == b
    case let (.float(a), .float(b)): return a == b
    case let (.string(a), .string(b)): return a == b
    case let (.bytes(a), .bytes(b)): return a == b
    case let (.data(at, af), .data(bt, bf)):
        return at == bt && af.count == bf.count && zip(af, bf).allSatisfy(sscEqual)
    case let (.closure(a), .closure(b)): return a === b
    case let (.cell(a), .cell(b)): return a === b
    case let (.longCell(a), .longCell(b)): return a === b
    case let (.map(a), .map(b)): return a === b
    case let (.array(a), .array(b)): return a === b
    default: return false
    }
}

private func showFloat(_ value: Double) -> String {
    if value.isNaN { return "nan" }
    if value == Double.infinity { return "inf" }
    if value == -Double.infinity { return "-inf" }
    if value.rounded(.towardZero) == value { return String(format: "%.0f", value) }
    return String(value)
}

private func sscPlain(_ value: SscValue) -> String {
    if case let .string(text) = value { return text }
    if case .data("Cons", _) = value { return "List(" + list(value).map(sscPlain).joined(separator: ", ") + ")" }
    if case .data("Nil", _) = value { return "List()" }
    return sscShow(value)
}

private func sscShow(_ value: SscValue) -> String {
    switch value {
    case .unit: return "()"
    case let .bool(value): return String(value)
    case let .int(value): return String(value)
    case let .big(value): return value.description
    case let .decimal(value): return value.description
    case let .float(value): return showFloat(value)
    case let .string(value): return "\"" + value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
    case let .bytes(value): return "#" + value.map { String(format: "%02x", $0) }.joined()
    case .data("Nil", _): return "List()"
    case .data("Cons", _): return "List(" + list(value).map(sscShow).joined(separator: ", ") + ")"
    case let .data(tag, fields) where tag.hasPrefix("Tuple"):
        return "(" + fields.map(sscShow).joined(separator: ", ") + ")"
    case let .data(tag, fields): return fields.isEmpty ? tag : tag + "(" + fields.map(sscShow).joined(separator: ", ") + ")"
    case .closure: return "<closure>"
    case .cell: return "<cell>"
    case let .longCell(cell): return "<lcell:\(sscShow(cell.value))>"
    case .map: return "<map>"
    case .array: return "<array>"
    }
}
"""
  private val sourcePart3: String = """
// ── Content modules: decode + native accessors (std/content.ssc, std/ui/content.ssc) ─
// Mirrors ssc.plugin.content.ContentNativePlugin (JVM) exactly, reading the SAME
// NativeContentCodec binary format the JVM/build-jvm paths already produce
// (specs/v2.1-native-content.md), embedded per-app as a base64 Swift string literal
// (sscContentModulesBase64, generated by SwiftBackend.contentModulesSource — always
// present, "" when the source closure never imports std/ui/content).

struct SscContentModule {
    let source: String
    let explicitRoot: Bool
    let directImports: [String]
    let namespace: String
    let document: SscValue
}

private final class SscContentDecoder {
    private let bytes: [UInt8]
    private var offset: Int = 0
    init(_ bytes: [UInt8]) { self.bytes = bytes }

    private func readByte() -> UInt8 {
        guard offset < bytes.count else { fatalError("content codec: truncated input") }
        let b = bytes[offset]; offset += 1; return b
    }
    private func readInt32() -> Int32 {
        var v: UInt32 = 0
        for _ in 0..<4 { v = (v << 8) | UInt32(readByte()) }
        return Int32(bitPattern: v)
    }
    private func readInt64() -> Int64 {
        var v: UInt64 = 0
        for _ in 0..<8 { v = (v << 8) | UInt64(readByte()) }
        return Int64(bitPattern: v)
    }
    private func readDouble() -> Double { Double(bitPattern: UInt64(bitPattern: readInt64())) }
    private func readString() -> String {
        let len = Int(readInt32())
        guard len >= 0 else { fatalError("content codec: invalid string length") }
        var s = [UInt8](); s.reserveCapacity(len)
        for _ in 0..<len { s.append(readByte()) }
        return String(decoding: s, as: UTF8.self)
    }
    private func readCount() -> Int {
        let n = Int(readInt32())
        guard n >= 0 else { fatalError("content codec: invalid count") }
        return n
    }

    func readValue() -> SscValue {
        switch readByte() {
        case 0: return .unit
        case 1: return .bool(readByte() != 0)
        case 2: return .int(readInt64())
        case 3: return .big(SscBigInt(readString()))
        case 4: return .float(readDouble())
        case 5: return .string(readString())
        case 6: return .decimal(SscDecimal(readString()))
        case 7:
            let tag = readString()
            let count = readCount()
            var fields: [SscValue] = []; fields.reserveCapacity(count)
            for _ in 0..<count { fields.append(readValue()) }
            return .data(tag, SscFields(fields))
        case 8:
            let count = readCount()
            let m = SscMap()
            for _ in 0..<count { let key = readValue(); let value = readValue(); m.put(key, value) }
            return .map(m)
        default: fatalError("content codec: unknown value tag")
        }
    }

    func readModules() -> [SscContentModule] {
        let magic = readString()
        guard magic == "SSC-CONTENT-1" else { fatalError("content codec: unsupported native content artifact format: \(magic)") }
        let count = readCount()
        var modules: [SscContentModule] = []; modules.reserveCapacity(count)
        for _ in 0..<count {
            let source = readString()
            let explicitRoot = readByte() != 0
            let importCount = readCount()
            var imports: [String] = []; imports.reserveCapacity(importCount)
            for _ in 0..<importCount { imports.append(readString()) }
            let namespace = readString()
            let document = readValue()
            modules.append(SscContentModule(source: source, explicitRoot: explicitRoot, directImports: imports, namespace: namespace, document: document))
        }
        return modules
    }
}

private func sscContentModules() -> [SscContentModule] {
    if sscContentModulesBase64.isEmpty { return [] }
    guard let data = Data(base64Encoded: sscContentModulesBase64) else {
        fatalError("content codec: invalid base64 in generated ContentModules.swift")
    }
    return SscContentDecoder([UInt8](data)).readModules()
}

private func sscContentRootModule() -> SscContentModule {
    guard let root = sscContentModules().first(where: { $0.explicitRoot }) else {
        fatalError("contentDocument() is unavailable: native compilation has no explicit root content")
    }
    return root
}

// direct (non-helper) imports of the root module — std/content.ssc / std/content-bind-core.ssc /
// std/ui/content.ssc are excluded, matching ContentNativePlugin.helperImport/directModules.
private func sscContentHelperImport(_ path: String) -> Bool {
    path == "std/content.ssc" || path.hasSuffix("/std/content.ssc") ||
    path == "std/content-bind-core.ssc" || path.hasSuffix("/std/content-bind-core.ssc") ||
    path == "std/ui/content.ssc" || path.hasSuffix("/std/ui/content.ssc")
}
private func sscContentDirectModules() -> [SscContentModule] {
    let modules = sscContentModules()
    let root = sscContentRootModule()
    let paths = Set(root.directImports.filter { !sscContentHelperImport($0) })
    return modules.filter { paths.contains($0.source) }
}
private func sscContentImportedModule(_ namespace: String) -> SscContentModule? {
    let matches = sscContentDirectModules().filter { $0.namespace == namespace }
    if matches.count > 1 { fatalError("contentModule: duplicate imported content namespace '\(namespace)'") }
    return matches.first
}

private func sscContentData(_ value: SscValue, _ tag: String, _ arity: Int) -> [SscValue] {
    guard case let .data(t, fields) = value, t == tag, fields.count == arity else {
        fatalError("expected \(tag)/\(arity), got \(sscShow(value))")
    }
    return fields.asArray()
}
private func sscContentAttrs(_ value: SscValue) -> SscMap {
    guard case let .map(m) = value else { fatalError("expected content attrs map, got \(sscShow(value))") }
    return m
}
private func sscContentAttrString(_ value: SscValue, _ key: String) -> String? {
    guard let found = sscContentAttrs(value).get(.string(key)) else { return nil }
    if case let .data("Str", fields) = found, fields.count == 1, case let .string(text) = fields[0] { return text }
    return nil
}
private func sscContentBlocks(_ documentOrSection: SscValue) -> [SscValue] {
    switch documentOrSection {
    case let .data("DocumentContent", fields) where fields.count == 6: return list(fields[5])
    case let .data("SectionContent", fields) where fields.count == 6: return list(fields[4])
    default: fatalError("expected document or section, got \(sscShow(documentOrSection))")
    }
}
private func sscContentSections(_ document: SscValue) -> [SscValue] { list(sscContentData(document, "DocumentContent", 6)[4]) }
private func sscContentSectionChildren(_ section: SscValue) -> [SscValue] { list(sscContentData(section, "SectionContent", 6)[5]) }

private func sscContentFindSection(_ values: [SscValue], _ id: String) -> SscValue? {
    for section in values {
        let fields = sscContentData(section, "SectionContent", 6)
        if case let .string(sid) = fields[0], sid == id { return section }
        if let hit = sscContentFindSection(sscContentSectionChildren(section), id) { return hit }
    }
    return nil
}
private func sscContentBlockAttrsField(_ block: SscValue) -> SscValue? {
    switch block {
    case let .data("Paragraph", f) where f.count == 2: return f[1]
    case let .data("BulletList", f) where f.count == 2: return f[1]
    case let .data("OrderedList", f) where f.count == 3: return f[2]
    case let .data("Image", f) where f.count == 4: return f[3]
    case let .data("Table", f) where f.count == 4: return f[3]
    case let .data("Embedded", f) where f.count == 5: return f[4]
    default: return nil
    }
}
private func sscContentFindBlockIn(_ values: [SscValue], _ id: String) -> SscValue? {
    for block in values {
        if let attrs = sscContentBlockAttrsField(block), sscContentAttrString(attrs, "id") == id { return block }
    }
    return nil
}
private func sscContentFindBlockSections(_ values: [SscValue], _ id: String) -> SscValue? {
    for section in values {
        if let hit = sscContentFindBlockIn(sscContentBlocks(section), id) { return hit }
        if let hit = sscContentFindBlockSections(sscContentSectionChildren(section), id) { return hit }
    }
    return nil
}
private func sscContentFindBlock(_ document: SscValue, _ id: String) -> SscValue? {
    sscContentFindBlockIn(sscContentBlocks(document), id) ?? sscContentFindBlockSections(sscContentSections(document), id)
}
private func sscContentBlockData(_ block: SscValue) -> SscValue? {
    guard case let .data("Embedded", f) = block, f.count == 5, case let .data("Some", inner) = f[3], inner.count == 1 else { return nil }
    return inner[0]
}
private func sscContentMap(_ value: SscValue) -> SscMap? {
    guard case let .data("MapV", f) = value, f.count == 1, case let .map(m) = f[0] else { return nil }
    return m
}
private func sscContentMetadata(_ document: SscValue, _ path: String) -> SscValue? {
    let manifest = sscContentData(document, "DocumentContent", 6)[0]
    var current: SscValue? = sscContentMap(manifest).flatMap { $0.get(.string("content")) }
    for part in path.split(separator: ".").map(String.init) where !part.isEmpty {
        current = current.flatMap(sscContentMap).flatMap { $0.get(.string(part)) }
    }
    return current
}

// ── plain text / semantic Markdown rendering (contentPlainText / contentToMarkdown) ──

private func sscContentInlineText(_ value: SscValue) -> String {
    switch value {
    case let .data("Text", f) where f.count == 1: if case let .string(s) = f[0] { return s }; return ""
    case let .data("Code", f) where f.count == 1: if case let .string(s) = f[0] { return s }; return ""
    case let .data("Expr", f) where f.count == 1: if case let .string(s) = f[0] { return "${" + s + "}" }; return ""
    case let .data("Emphasis", f) where f.count == 1: return list(f[0]).map(sscContentInlineText).joined()
    case let .data("Strong", f) where f.count == 1: return list(f[0]).map(sscContentInlineText).joined()
    case let .data("Link", f) where f.count == 3: return list(f[0]).map(sscContentInlineText).joined()
    default: return ""
    }
}
private func sscContentInlineMarkdown(_ value: SscValue) -> String {
    switch value {
    case let .data("Text", f) where f.count == 1: if case let .string(s) = f[0] { return s }; return ""
    case let .data("Code", f) where f.count == 1: if case let .string(s) = f[0] { return "`" + s + "`" }; return ""
    case let .data("Expr", f) where f.count == 1: if case let .string(s) = f[0] { return "${" + s + "}" }; return ""
    case let .data("Emphasis", f) where f.count == 1: return "*" + list(f[0]).map(sscContentInlineMarkdown).joined() + "*"
    case let .data("Strong", f) where f.count == 1: return "**" + list(f[0]).map(sscContentInlineMarkdown).joined() + "**"
    case let .data("Link", f) where f.count == 3:
        let label = list(f[0]).map(sscContentInlineMarkdown).joined()
        guard case let .string(href) = f[1] else { return label }
        var suffix = ""
        if case let .data("Some", t) = f[2], t.count == 1, case let .string(text) = t[0] { suffix = " \"" + text + "\"" }
        return "[" + label + "](" + href + suffix + ")"
    default: return ""
    }
}
private func sscContentAttrEntries(_ value: SscValue) -> [(String, String)] {
    sscContentAttrs(value).entries.compactMap { (key, val) in
        guard case let .string(k) = key, case let .data("Str", f) = val, f.count == 1, case let .string(text) = f[0] else { return nil }
        return (k, text)
    }
}
private func sscContentHeadingAttrs(_ value: SscValue) -> String {
    let entries = sscContentAttrEntries(value)
    if entries.isEmpty { return "" }
    let rendered = entries.map { (k, v) in k == "id" ? "#" + v : k + "=" + v }.joined(separator: " ")
    return " {" + rendered + "}"
}
private func sscContentMetadataPrefix(_ value: SscValue) -> String {
    let entries = sscContentAttrEntries(value)
    if entries.isEmpty { return "" }
    return "<!-- @meta " + entries.map { (k, v) in k + "=" + v }.joined(separator: " ") + " -->\n"
}
private func sscContentFenceAttrs(_ value: SscValue) -> String {
    let entries = sscContentAttrEntries(value)
    if entries.isEmpty { return "" }
    return " " + entries.map { (k, v) in k == "id" ? "@id=" + v : "@" + k + "=" + v }.joined(separator: " ")
}
private func sscContentPlainBlock(_ value: SscValue) -> String {
    switch value {
    case let .data("Paragraph", f) where f.count == 2: return list(f[0]).map(sscContentInlineText).joined()
    case let .data("BulletList", f) where f.count == 2:
        return list(f[0]).map { item in list(item).map(sscContentPlainBlock).joined(separator: " ") }.joined(separator: "\n")
    case let .data("OrderedList", f) where f.count == 3:
        return list(f[0]).map { item in list(item).map(sscContentPlainBlock).joined(separator: " ") }.joined(separator: "\n")
    case let .data("Image", f) where f.count == 4: if case let .string(alt) = f[1] { return alt }; return ""
    case let .data("Table", f) where f.count == 4:
        let header = list(f[0]).map { cell in list(cell).map(sscContentInlineText).joined() }.joined(separator: " | ")
        let body = list(f[1]).map { row in list(row).map { cell in list(cell).map(sscContentInlineText).joined() }.joined(separator: " | ") }
        return ([header] + body).joined(separator: "\n")
    case let .data("Embedded", f) where f.count == 5: if case let .string(source) = f[1] { return source }; return ""
    default: return ""
    }
}
private func sscContentPlain(_ value: SscValue) -> String {
    switch value {
    case let .data("SectionContent", f) where f.count == 6:
        guard case let .string(title) = f[2] else { return "" }
        let parts = [title] + sscContentBlocks(value).map(sscContentPlainBlock) + sscContentSectionChildren(value).map(sscContentPlain)
        return parts.filter { !$0.isEmpty }.joined(separator: "\n")
    case .data("DocumentContent", _):
        let parts = sscContentBlocks(value).map(sscContentPlainBlock) + sscContentSections(value).map(sscContentPlain)
        return parts.filter { !$0.isEmpty }.joined(separator: "\n")
    default: return sscContentPlainBlock(value)
    }
}
private func sscContentMarkdownBlock(_ value: SscValue) -> String {
    switch value {
    case let .data("Paragraph", f) where f.count == 2:
        return sscContentMetadataPrefix(f[1]) + list(f[0]).map(sscContentInlineMarkdown).joined()
    case let .data("BulletList", f) where f.count == 2:
        return sscContentMetadataPrefix(f[1]) + list(f[0]).map { item in "- " + list(item).map(sscContentMarkdownBlock).joined(separator: " ") }.joined(separator: "\n")
    case let .data("OrderedList", f) where f.count == 3:
        guard case let .int(start) = f[1] else { return "" }
        let items = list(f[0])
        let rendered = items.enumerated().map { (i, item) in "\(start + Int64(i)). " + list(item).map(sscContentMarkdownBlock).joined(separator: " ") }
        return sscContentMetadataPrefix(f[2]) + rendered.joined(separator: "\n")
    case let .data("Image", f) where f.count == 4:
        guard case let .string(src) = f[0], case let .string(alt) = f[1] else { return "" }
        var titleText = ""
        if case let .data("Some", t) = f[2], t.count == 1, case let .string(text) = t[0] { titleText = " \"" + text + "\"" }
        return sscContentMetadataPrefix(f[3]) + "![" + alt + "](" + src + titleText + ")"
    case let .data("Table", f) where f.count == 4:
        let header = list(f[0]).map { cell in list(cell).map(sscContentInlineMarkdown).joined() }
        let aligns = list(f[2]).map { v -> String in
            if case let .string(a) = v {
                switch a { case "left": return ":---"; case "right": return "---:"; case "center": return ":---:"; default: return "---" }
            }
            return "---"
        }
        let body = list(f[1]).map { row in "| " + list(row).map { cell in list(cell).map(sscContentInlineMarkdown).joined() }.joined(separator: " | ") + " |" }
        let lines = ["| " + header.joined(separator: " | ") + " |", "| " + aligns.joined(separator: " | ") + " |"] + body
        return sscContentMetadataPrefix(f[3]) + lines.joined(separator: "\n")
    case let .data("Embedded", f) where f.count == 5:
        guard case let .string(lang) = f[0], case let .string(source) = f[1] else { return "" }
        return "```" + lang + sscContentFenceAttrs(f[4]) + "\n" + source + "\n```"
    default: fatalError("contentToMarkdown: unsupported value \(sscShow(value))")
    }
}
private func sscContentMarkdown(_ value: SscValue) -> String {
    switch value {
    case let .data("SectionContent", f) where f.count == 6:
        guard case let .int(level) = f[1], case let .string(title) = f[2] else { return "" }
        let heading = String(repeating: "#", count: Int(level)) + " " + title + sscContentHeadingAttrs(f[3])
        let parts = [heading] + sscContentBlocks(value).map(sscContentMarkdownBlock) + sscContentSectionChildren(value).map(sscContentMarkdown)
        return parts.joined(separator: "\n\n")
    case .data("DocumentContent", _):
        let parts = sscContentBlocks(value).map(sscContentMarkdownBlock) + sscContentSections(value).map(sscContentMarkdown)
        return parts.joined(separator: "\n\n")
    default: return sscContentMarkdownBlock(value)
    }
}

// ── contentToolkitNode/Block/Section: markdown -> TkNode (std/ui/content.ssc) ────────
// Faithful port of ContentToolkitJs.scala's _ssc_tk_* runtime, which the JS backend
// already ships at parity with JvmGen. Scope of this port: static Markdown (headings,
// paragraphs, bullet/ordered lists, tables) plus the fail-soft per-block error
// placeholder and multi-document id lookup — the exact surface busi's real content
// (offline_guide.ssc, zero toolkit:/@ui=toolkit/component: usage) exercises. Interactive
// controls (toolkit: links, @ui=toolkit YAML trees, registered components/actions/
// row-bindings/slots) fail with a clear, explicit "not yet supported on Swift"
// diagnostic rather than silently mis-rendering — a deliberately scoped follow-up,
// not a silent gap.

// ContentToolkitOptions field order (std/ui/content.ssc): includeCode, sectionGap,
// blockGap, listGap, wrapDocumentInCard, wrapTopLevelSectionsInCards, components,
// bindings, actions, rowBindings, computed, slots.
private func sscContentToolkitGapAt(_ options: SscValue?, _ index: Int, _ dflt: Int64) -> Int64 {
    guard let options = options, case let .data("ContentToolkitOptions", fields) = options, fields.count > index,
          case let .int(n) = fields[index] else { return dflt }
    return n
}
private func sscContentToolkitSectionGap(_ options: SscValue?) -> Int64 { sscContentToolkitGapAt(options, 1, 16) }
private func sscContentToolkitBlockGap(_ options: SscValue?) -> Int64 { sscContentToolkitGapAt(options, 2, 8) }
private func sscContentToolkitListGap(_ options: SscValue?) -> Int64 { sscContentToolkitGapAt(options, 3, 4) }

private func sscContentToolkitUnsupported(_ what: String) -> Never {
    fatalError("contentToolkitNode: '\(what)' is not yet supported on the Swift backend (static Markdown only)")
}

private func sscContentToolkitHasSingleLink(_ inlines: [SscValue]) -> Bool {
    let significant = inlines.filter { inline in
        if case let .data("Text", f) = inline, f.count == 1, case let .string(s) = f[0] { return !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return true
    }
    guard significant.count == 1, case let .data("Link", f) = significant[0], f.count == 3, case let .string(href) = f[1] else { return false }
    return href.hasPrefix("toolkit:")
}

private func sscContentToolkitTableCell(_ cell: SscValue) -> SscValue { .data("TextNode_", [.string(list(cell).map(sscContentInlineText).joined())]) }
private func sscContentToolkitTable(_ headers: SscValue, _ rows: SscValue) -> SscValue {
    let columns = list(headers).enumerated().map { (idx, header) -> SscValue in
        .data("TableColumn", [.string(list(header).map(sscContentInlineText).joined()), .string("col\(idx)")])
    }
    let renderedRows = list(rows).map { row in listValue(list(row).map(sscContentToolkitTableCell)) }
    return .data("TableNode", [listValue(columns), listValue(renderedRows), .unit])
}

// Paragraph/BulletList/OrderedList: a single toolkit: link paragraph/item is an
// interactive control (unsupported for now); everything else is plain text, which
// _ssc_tk_markdown_block leaves to the caller's raw-text fallback (returns nil here).
private func sscContentToolkitMarkdownBlock(_ block: SscValue, _ options: SscValue?) -> SscValue? {
    switch block {
    case let .data("Paragraph", f) where f.count == 2:
        if sscContentToolkitHasSingleLink(list(f[0])) { sscContentToolkitUnsupported("toolkit: link control") }
        return nil
    case let .data("BulletList", f) where f.count == 2:
        for item in list(f[0]) {
            let blocks = list(item)
            if blocks.count == 1, case let .data("Paragraph", pf) = blocks[0], pf.count == 2, sscContentToolkitHasSingleLink(list(pf[0])) {
                sscContentToolkitUnsupported("toolkit: link control")
            }
        }
        return nil
    case let .data("OrderedList", f) where f.count == 3:
        for item in list(f[0]) {
            let blocks = list(item)
            if blocks.count == 1, case let .data("Paragraph", pf) = blocks[0], pf.count == 2, sscContentToolkitHasSingleLink(list(pf[0])) {
                sscContentToolkitUnsupported("toolkit: link control")
            }
        }
        return nil
    default: return nil
    }
}

private func sscContentToolkitInlineError(_ message: String) -> SscValue { .data("RawTextNode", [.string("\u{26A0} " + message)]) }

private func sscContentToolkitBlockNode(_ block: SscValue, _ options: SscValue?) -> SscValue {
    let attrs = sscContentBlockAttrsField(block)
    if let attrs = attrs, sscContentAttrString(attrs, "component") != nil { sscContentToolkitUnsupported("component:") }
    if let attrs = attrs, sscContentAttrString(attrs, "ui") == "toolkit" { sscContentToolkitUnsupported("@ui=toolkit") }
    if let rendered = sscContentToolkitMarkdownBlock(block, options) { return rendered }
    if case let .data("Table", f) = block, f.count == 4 { return sscContentToolkitTable(f[0], f[1]) }
    return .data("TextNode_", [.string(sscContentPlainBlock(block))])
}
private func sscContentToolkitSafeBlockNode(_ block: SscValue, _ options: SscValue?) -> SscValue {
    // The JVM/JS providers only degrade gracefully in the browser; a genuinely
    // unsupported construct still fatalErrors here (fail loud during development,
    // matching this port's deliberately narrower scope) rather than pretending a
    // rendered placeholder is equivalent to real support.
    sscContentToolkitBlockNode(block, options)
}
private func sscContentToolkitSectionNode(_ section: SscValue, _ options: SscValue?) -> SscValue {
    let fields = sscContentData(section, "SectionContent", 6)
    if sscContentAttrString(fields[3], "component") != nil { sscContentToolkitUnsupported("component:") }
    guard case let .string(title) = fields[2] else { fatalError("SectionContent.title") }
    guard case let .int(level) = fields[1] else { fatalError("SectionContent.level") }
    let heading: SscValue = .data("HeadingNode", [.int(level), .string(title)])
    let children = [heading]
        + sscContentBlocks(section).map { sscContentToolkitSafeBlockNode($0, options) }
        + sscContentSectionChildren(section).map { sscContentToolkitSectionNode($0, options) }
    return .data("VStackNode", [.int(sscContentToolkitBlockGap(options)), listValue(children)])
}

private func sscContentToolkitRender(_ document: SscValue, _ options: SscValue?) -> SscValue {
    let children = sscContentBlocks(document).map { sscContentToolkitSafeBlockNode($0, options) }
        + sscContentSections(document).map { sscContentToolkitSectionNode($0, options) }
    return .data("VStackNode", [.int(sscContentToolkitSectionGap(options)), listValue(children)])
}

private func sscContentToolkitOptionsArg(_ args: [SscValue]) -> SscValue? {
    guard let first = args.first else { return nil }
    if case .data("ContentToolkitOptions", _) = first { return first }
    return nil
}

// ── CoreIR S-expr decoder: mirrors ssc.Reader (v2/src/CoreIR.scala) exactly ──
// SwiftBackend used to emit the whole Program as ONE nested Swift literal
// expression (`.apply(.global(...), [.lambda(...)])`, etc). Swift 6's
// compiler enforces a hard "structure nesting level exceeded maximum of 256"
// limit per expression, which real-world-sized programs (e.g. busi's own
// production app.ssc) exceed. The Program is instead embedded as this
// canonical S-expr TEXT (sscProgramSExpr, generated by
// SwiftBackend.programDataSource — the SAME format ssc.Writer.program
// already produces and the JS/Rust/JVM backends already consume via
// ssc.Reader.parseProgram) and decoded here via ordinary recursive Swift
// function calls at RUNTIME, which are bound only by the real call stack,
// not the compiler's expression-nesting limit.

private enum CoreSx {
    case atom(String)
    case str(String)
    case list([CoreSx])
}

private final class CoreSxParser {
    private let chars: [Character]
    private var pos: Int = 0
    init(_ s: String) { chars = Array(s) }

    private func atEnd() -> Bool { pos >= chars.count }
    private func peek() -> Character { chars[pos] }
    private func isDelim(_ c: Character) -> Bool {
        c.isWhitespace || c == "(" || c == ")" || c == ";" || c == "\""
    }
    private func skipWs() {
        var go = true
        while go {
            if atEnd() { go = false }
            else if peek() == ";" { while !atEnd() && peek() != "\n" { pos += 1 } }
            else if peek().isWhitespace { pos += 1 }
            else { go = false }
        }
    }

    func parseOne() -> CoreSx {
        skipWs()
        return sexpr()
    }

    private func sexpr() -> CoreSx {
        skipWs()
        guard !atEnd() else { fatalError("coreir decode: unexpected EOF") }
        switch peek() {
        case "(":
            pos += 1
            var items: [CoreSx] = []
            var go = true
            while go {
                skipWs()
                guard !atEnd() else { fatalError("coreir decode: unterminated list") }
                if peek() == ")" { pos += 1; go = false }
                else { items.append(sexpr()) }
            }
            return .list(items)
        case ")":
            fatalError("coreir decode: unexpected ')'")
        case "\"":
            return .str(readString())
        default:
            return .atom(readAtom())
        }
    }

    private func readAtom() -> String {
        var s = ""
        while !atEnd() && !isDelim(peek()) { s.append(peek()); pos += 1 }
        return s
    }

    private func readString() -> String {
        pos += 1
        var s = ""
        var go = true
        while go {
            guard !atEnd() else { fatalError("coreir decode: unterminated string") }
            let c = peek(); pos += 1
            if c == "\"" { go = false }
            else if c == "\\" {
                guard !atEnd() else { fatalError("coreir decode: bad escape") }
                let e = peek(); pos += 1
                switch e {
                case "\"": s.append("\"")
                case "\\": s.append("\\")
                case "n": s.append("\n")
                case "r": s.append("\r")
                case "t": s.append("\t")
                case "u":
                    let hexEnd = pos + 4
                    guard hexEnd <= chars.count,
                          let code = UInt32(String(chars[pos..<hexEnd]), radix: 16),
                          let scalar = Unicode.Scalar(code) else {
                        fatalError("coreir decode: bad \\u escape")
                    }
                    s.append(Character(scalar))
                    pos = hexEnd
                default: fatalError("coreir decode: bad escape \\\(e)")
                }
            } else {
                s.append(c)
            }
        }
        return s
    }
}

private func coreSxHead(_ items: [CoreSx]) -> String? {
    guard case let .atom(head)? = items.first else { return nil }
    return head
}

private func coreSxToTerm(_ sx: CoreSx) -> SscTerm {
    guard case let .list(items) = sx, let head = coreSxHead(items) else {
        fatalError("coreir decode: not a term")
    }
    let rest = Array(items.dropFirst())
    switch head {
    case "lit":
        return .literal(coreSxToConst(rest))
    case "local":
        guard rest.count == 1, case let .atom(i) = rest[0], let n = Int(i) else {
            fatalError("coreir decode: bad local")
        }
        return .local(n)
    case "global":
        guard rest.count == 1, case let .atom(n) = rest[0] else { fatalError("coreir decode: bad global") }
        return .global(n)
    case "lam":
        guard rest.count == 2, case let .atom(a) = rest[0], let arity = Int(a) else {
            fatalError("coreir decode: bad lam")
        }
        return .lambda(arity, coreSxToTerm(rest[1]))
    case "app":
        guard let fn = rest.first else { fatalError("coreir decode: bad app") }
        return .apply(coreSxToTerm(fn), rest.dropFirst().map(coreSxToTerm))
    case "let":
        guard rest.count == 2, case let .list(r) = rest[0] else { fatalError("coreir decode: bad let") }
        return .letBindings(r.map(coreSxToTerm), coreSxToTerm(rest[1]))
    case "letrec":
        guard rest.count == 2, case let .list(l) = rest[0] else { fatalError("coreir decode: bad letrec") }
        return .letRecursive(l.map(coreSxToTerm), coreSxToTerm(rest[1]))
    case "if":
        guard rest.count == 3 else { fatalError("coreir decode: bad if") }
        return .ifThenElse(coreSxToTerm(rest[0]), coreSxToTerm(rest[1]), coreSxToTerm(rest[2]))
    case "ctor":
        guard let tag = coreSxHead(rest) else { fatalError("coreir decode: bad ctor") }
        return .constructor(tag, rest.dropFirst().map(coreSxToTerm))
    case "prim":
        guard let op = coreSxHead(rest) else { fatalError("coreir decode: bad prim") }
        return .primitive(op, rest.dropFirst().map(coreSxToTerm))
    case "while":
        guard rest.count == 2 else { fatalError("coreir decode: bad while") }
        return .whileLoop(coreSxToTerm(rest[0]), coreSxToTerm(rest[1]))
    case "seq":
        return .sequence(rest.map(coreSxToTerm))
    case "match":
        guard rest.count == 2 || rest.count == 3, case let .list(arms) = rest[1] else {
            fatalError("coreir decode: bad match")
        }
        let scrutinee = coreSxToTerm(rest[0])
        let armValues = arms.map(coreSxToArm)
        var defaultTerm: SscTerm? = nil
        if rest.count == 3 {
            guard case let .list(defItems) = rest[2], defItems.count == 2,
                  coreSxHead(defItems) == "default" else {
                fatalError("coreir decode: bad match default")
            }
            defaultTerm = coreSxToTerm(defItems[1])
        }
        return .matchValue(scrutinee, armValues, defaultTerm)
    default:
        fatalError("coreir decode: unknown term head: (\(head) ...)")
    }
}

private func coreSxToArm(_ sx: CoreSx) -> SscArm {
    guard case let .list(items) = sx, items.count == 4, coreSxHead(items) == "arm",
          case let .atom(tag) = items[1], case let .atom(arityStr) = items[2],
          let arity = Int(arityStr) else {
        fatalError("coreir decode: bad arm")
    }
    return SscArm(tag: tag, arity: arity, body: coreSxToTerm(items[3]))
}

private func coreSxToConst(_ rest: [CoreSx]) -> SscConst {
    guard rest.count == 1 else { fatalError("coreir decode: bad const") }
    switch rest[0] {
    case .atom("unit"): return .unit
    case .atom("true"): return .bool(true)
    case .atom("false"): return .bool(false)
    case let .list(items):
        guard let head = coreSxHead(items) else { fatalError("coreir decode: bad const") }
        let body = Array(items.dropFirst())
        switch head {
        case "int":
            guard case let .atom(n)? = body.first, let v = Int64(n) else {
                fatalError("coreir decode: bad int const")
            }
            return .int(v)
        case "big":
            guard case let .atom(n)? = body.first else { fatalError("coreir decode: bad big const") }
            return .big(n)
        case "float":
            guard case let .atom(x)? = body.first else { fatalError("coreir decode: bad float const") }
            switch x {
            case "nan": return .float(Double.nan)
            case "inf": return .float(Double.infinity)
            case "-inf": return .float(-Double.infinity)
            default:
                guard let v = Double(x) else { fatalError("coreir decode: bad float const") }
                return .float(v)
            }
        case "str":
            guard case let .str(s)? = body.first else { fatalError("coreir decode: bad str const") }
            return .string(s)
        case "bytes":
            guard case let .atom(hex)? = body.first else { return .bytes([]) }
            var out: [UInt8] = []
            var i = hex.startIndex
            while i < hex.endIndex {
                let j = hex.index(i, offsetBy: 2)
                guard let byte = UInt8(hex[i..<j], radix: 16) else { fatalError("coreir decode: bad bytes const") }
                out.append(byte)
                i = j
            }
            return .bytes(out)
        default:
            fatalError("coreir decode: bad const: (\(head) ...)")
        }
    default:
        fatalError("coreir decode: bad const")
    }
}

private func coreSxToDef(_ sx: CoreSx) -> SscDefinition {
    guard case let .list(items) = sx, items.count == 3, coreSxHead(items) == "def",
          case let .atom(name) = items[1] else {
        fatalError("coreir decode: bad def")
    }
    return SscDefinition(name: name, body: coreSxToTerm(items[2]))
}

func sscDecodeProgram(_ sExpr: String, fieldLayouts: [String: [String]]) -> SscProgram {
    let sx = CoreSxParser(sExpr).parseOne()
    guard case let .list(items) = sx, items.count == 3, coreSxHead(items) == "program" else {
        fatalError("coreir decode: expected (program <defs> <entry>)")
    }
    guard case let .list(dItems) = items[1], coreSxHead(dItems) == "defs" else {
        fatalError("coreir decode: expected (defs ...)")
    }
    let defs = dItems.dropFirst().map(coreSxToDef)
    guard case let .list(eItems) = items[2], eItems.count == 2, coreSxHead(eItems) == "entry" else {
        fatalError("coreir decode: expected (entry <term>)")
    }
    let entry = coreSxToTerm(eItems[1])
    return SscProgram(definitions: defs, entry: entry, fieldLayouts: fieldLayouts)
}
"""
  val source: String = sourcePart1 + sourcePart2 + sourcePart3
