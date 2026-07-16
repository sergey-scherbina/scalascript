import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
import {
  createDirectTransform,
  formatDirectDiagnostic,
  type DirectDiagnostic,
  type DirectDiagnosticCode,
  type DirectTransform
} from "@scalascript/control-direct/transform"
import ts from "typescript"

const result: number = freshPrompt<number, number>(prompt => Eff.runPure(
  direct.reset(prompt, (): number => {
    const value: number = direct.shift(prompt, continuation =>
      continuation.resume(41)
    )
    return value + 1
  })
))

const program = ts.createProgram([], {})
const transform: DirectTransform = createDirectTransform(ts, program)
const code: DirectDiagnosticCode = "JS_DIRECT_UNSUPPORTED"
const diagnostic: DirectDiagnostic = {
  code,
  message: "message",
  fileName: "input.ts",
  start: 0,
  length: 1,
  line: 1,
  column: 1
}
const rendered: string = formatDirectDiagnostic(diagnostic)

void result
void transform
void rendered
