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
}

final class SscClosure {
    let arity: Int
    var environment: [SscValue]
    let body: SscTerm?
    let native: (([SscValue]) -> SscValue)?

    init(arity: Int, environment: [SscValue], body: SscTerm) {
        self.arity = arity
        self.environment = environment
        self.body = body
        self.native = nil
    }

    init(arity: Int, native: @escaping ([SscValue]) -> SscValue) {
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

private enum EvalStep {
    case value(SscValue)
    case call(SscClosure, [SscValue])
}

enum SscRuntime {
    public static func execute(_ program: SscProgram) {
        let runtime = Machine(program)
        let result = runtime.run()
        if case .unit = result { return }
        Swift.print(sscShow(result))
    }
}

private final class Machine {
    private let program: SscProgram
    private var globals: [String: SscValue] = [:]

    init(_ program: SscProgram) {
        self.program = program
        installBuiltins()
        installDefinitions()
    }

    func run() -> SscValue { runTerm(program.entry, []) }

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
            switch evaluate(term, environment, tail: true) {
            case let .value(value): return value
            case let .call(closure, arguments):
                checkArity(closure, arguments)
                if let native = closure.native { return native(arguments) }
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
            fatalError("arity: \(closure.arity) expected, \(arguments.count) given")
        }
    }

    private func call(_ closure: SscClosure, _ arguments: [SscValue]) -> SscValue {
        checkArity(closure, arguments)
        if let native = closure.native { return native(arguments) }
        guard let body = closure.body else { fatalError("app: closure has no body") }
        return runTerm(body, closure.environment + arguments)
    }

    private func evaluate(_ term: SscTerm, _ environment: [SscValue], tail: Bool) -> EvalStep {
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
            guard case let .closure(closure) = value(function, environment) else {
                fatalError("app: not a function")
            }
            let values = arguments.map { value($0, environment) }
            if tail { return .call(closure, values) }
            return .value(call(closure, values))
        case let .letBindings(bindings, body):
            var extended = environment
            for binding in bindings { extended.append(value(binding, extended)) }
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
            guard case let .bool(test) = value(condition, environment) else {
                fatalError("if: condition not Bool")
            }
            return evaluate(test ? ifTrue : ifFalse, environment, tail: tail)
        case let .constructor(tag, fields):
            return .value(.data(tag, fields.map { value($0, environment) }))
        case let .matchValue(scrutinee, arms, fallback):
            let scrutineeValue = value(scrutinee, environment)
            if case let .data(tag, fields) = scrutineeValue,
               let arm = arms.first(where: { $0.tag == tag && $0.arity == fields.count }) {
                return evaluate(arm.body, environment + fields, tail: tail)
            }
            if let fallback { return evaluate(fallback, environment, tail: tail) }
            fatalError("match: no arm for \(sscShow(scrutineeValue))")
        case let .primitive(operation, arguments):
            return .value(primitive(operation, arguments.map { value($0, environment) }))
        case let .whileLoop(condition, body):
            while true {
                guard case let .bool(test) = value(condition, environment) else {
                    fatalError("while: condition not Bool")
                }
                if !test { break }
                _ = value(body, environment)
            }
            return .value(.unit)
        case let .sequence(terms):
            guard let last = terms.last else { return .value(.unit) }
            for term in terms.dropLast() { _ = value(term, environment) }
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
        case "fieldAt": return data(args[0]).1[Int(int(args, 1))]
        case "__isTag__":
            guard case let .data(tag, fields) = args[0] else { return .bool(false) }
            let arity = int(args, 2)
            return .bool(tag == string(args, 1) && (arity < 0 || fields.count == Int(arity)))
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
        default: fatalError("swift runtime: unsupported primitive '\(operation)'")
        }
    }

    private func dynamicArithmetic(_ op: String, _ lhs: SscValue, _ rhs: SscValue) -> SscValue {
        if case .decimal = lhs { return decimalArithmetic(op, lhs, rhs) }
        if case .decimal = rhs { return decimalArithmetic(op, lhs, rhs) }
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
        }
        fatalError("__arith__: unsupported operation \(op) on \(sscShow(lhs)), \(sscShow(rhs))")
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
    case let .data("Nil", _): return "List()"
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
