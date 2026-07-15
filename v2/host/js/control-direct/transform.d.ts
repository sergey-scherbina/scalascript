import type ts from "typescript"

export type DirectDiagnosticCode =
  | "JS_DIRECT_OUTSIDE_RESET"
  | "JS_DIRECT_CAPTURE_BARRIER"
  | "JS_DIRECT_UNSUPPORTED"
  | "JS_DIRECT_PROMPT_MISMATCH"

export interface DirectDiagnostic {
  readonly code: DirectDiagnosticCode
  readonly message: string
  readonly fileName: string
  readonly start: number
  readonly length: number
  readonly line: number
  readonly column: number
}

export interface DirectTransform {
  readonly diagnostics: readonly DirectDiagnostic[]
  readonly transformedFiles: readonly string[]
  readonly transformers: ts.CustomTransformers
}

export function createDirectTransform(
  typescript: typeof ts,
  program: ts.Program
): DirectTransform

export function formatDirectDiagnostic(diagnostic: DirectDiagnostic): string
