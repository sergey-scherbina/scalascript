package ssc.swift

private[swift] object SwiftRuntime:
  val source: String = """// Generated ScalaScript v2 CoreIR runtime.
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

indirect enum SscValue {
    case unit
    case bool(Bool)
    case int(Int64)
    case big(SscBigInt)
    case decimal(SscDecimal)
    case float(Double)
    case string(String)
    case bytes([UInt8])
    case data(String, [SscValue])
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

private enum EvalStep {
    case value(SscValue)
    case call(SscClosure, [SscValue])
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
    private var failure: SscRuntimeFailure?

    init(_ program: SscProgram, nativeUiHost: SscRuntimeExtension? = nil) {
        self.program = program
        self.nativeUiHost = nativeUiHost
        nativeUiHost?.bind { [weak self] closure, arguments in
            guard let self else { throw SscRuntimeFailure(description: "native UI runtime released") }
            let result = self.call(closure, arguments)
            if let failure = self.failure { throw failure }
            return result
        }
        installBuiltins()
        if let nativeUiHost {
            do { globals.merge(try nativeUiHost.globals()) { _, replacement in replacement } }
            catch { recordFailure(error) }
        }
        installDefinitions()
    }

    func run() -> SscValue { runTerm(program.entry, []) }

    func runResult() -> Result<SscValue, SscRuntimeFailure> {
        if let failure { return .failure(failure) }
        let result = runTerm(program.entry, [])
        if let failure { return .failure(failure) }
        return .success(result)
    }

    func invokeResult(_ closure: SscClosure, _ arguments: [SscValue]) -> Result<SscValue, SscRuntimeFailure> {
        if let failure { return .failure(failure) }
        let result = call(closure, arguments)
        if let failure { return .failure(failure) }
        return .success(result)
    }

    private func recordFailure(_ error: Error) {
        guard failure == nil else { return }
        failure = error as? SscRuntimeFailure ?? SscRuntimeFailure(description: String(describing: error))
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
            .data("RuntimeException", args)
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
            throw SscRuntimeFailure(description: "throw: \(sscPlain(args[0]))")
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

    private func evaluate(_ term: SscTerm, _ environment: [SscValue], tail: Bool) -> EvalStep {
        if failure != nil { return .value(.unit) }
        switch term {
        case let .literal(constant): return .value(constantValue(constant))
        case let .local(index):
            let resolved = environment.count - 1 - index
            guard environment.indices.contains(resolved) else { fatalError("local: index \(index) out of bounds") }
            return .value(environment[resolved])
        case let .global(name):
            guard let result = globals[name] else { fatalError("unbound global: \(name)") }
            return .value(result)
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
            do {
                if let result = try nativeUiHost?.apply(functionValue, values) { return .value(result) }
            } catch {
                recordFailure(error)
                return .value(.unit)
            }
            fatalError("app: not a function")
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
            return .value(.data(tag, values))
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
            let operationArgs = Array(args.dropFirst())
            let argument: SscValue
            if operationArgs.isEmpty { argument = .unit }
            else if operationArgs.count == 1 { argument = operationArgs[0] }
            else { argument = .data("__EffArgs__", operationArgs) }
            let identity = SscClosure(arity: 1) { values in values[0] }
            return .data("Op", [.string(label), argument, .closure(identity)])
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
            default: break
            }
        case .data("Cons", _), .data("Nil", _):
            let values = list(receiver)
            switch name {
            case "map":
                guard case let .closure(fn) = args[0] else { fatalError("List.map expects closure") }
                return listValue(values.map { item in
                    if case let .data(tag, fields) = item, tag.hasPrefix("Tuple"), fn.arity == fields.count {
                        return call(fn, fields)
                    }
                    return call(fn, [item])
                })
            case "foldLeft":
                guard case let .closure(fn) = args[1] else { fatalError("List.foldLeft expects closure") }
                return values.reduce(args[0]) { call(fn, [$0, $1]) }
            case "zipWithIndex":
                return listValue(values.enumerated().map { .data("Tuple2", [$0.element, .int(Int64($0.offset))]) })
            case "length", "size": return .int(Int64(values.count))
            case "isEmpty": return .bool(values.isEmpty)
            case "nonEmpty": return .bool(!values.isEmpty)
            default: break
            }
        case let .string(value):
            if name == "toString" { return .string(value) }
        case let .int(value):
            if name == "toString" { return .string(String(value)) }
        case .data("__RoundingModeCompanion__", _):
            return .string(name)
        case let .data(tag, _):
            let argument: SscValue
            if args.isEmpty { argument = .unit }
            else if args.count == 1 { argument = args[0] }
            else { argument = .data("__EffArgs__", args) }
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
                return self.handleEffect(self.call(continuation, values), handlerValue)
            }
            let eventArgs: [SscValue]
            switch fields[1] {
            case .unit: eventArgs = [.closure(resume)]
            case let .data("__EffArgs__", packed): eventArgs = packed + [.closure(resume)]
            default: eventArgs = [fields[1], .closure(resume)]
            }
            let operation = label.split(separator: ".").last.map(String.init) ?? label
            return call(handler, [.data(operation, eventArgs)])
        case .data("Op", _): fatalError("effect: malformed Op")
        default:
            return call(handler, [.data("Return", [computation])])
        }
    }

    private func liftOperation(_ operation: SscValue, _ transform: @escaping (SscValue) -> SscValue) -> SscValue {
        guard case let .data("Op", fields) = operation, fields.count == 3,
              case let .closure(continuation) = fields[2] else { fatalError("effect: malformed Op") }
        let lifted = SscClosure(arity: 1) { [weak self] values in
            guard let self else { fatalError("effect: runtime released") }
            return transform(self.call(continuation, values))
        }
        return .data("Op", [fields[0], fields[1], .closure(lifted)])
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
    return (tag, fields)
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
