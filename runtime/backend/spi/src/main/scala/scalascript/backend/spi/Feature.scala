package scalascript.backend.spi

/** Language and platform features a Backend declares it supports.
 *  See docs/backend-spi.md §11 — Capabilities.  Core's CapabilityCheck
 *  pass (Stage 4) walks the IR, collects required features, and refuses
 *  to invoke `Backend.compile` on programs that need features the
 *  backend doesn't declare.
 *
 *  The list MUST grow in lockstep with what core's normalisation pass
 *  recognises — adding a language construct without a Feature flag
 *  silently accepts unsupported programs. */
enum Feature:
  // Language features
  case AlgebraicEffects
  case MutableState
  case PatternMatching
  case TypeClasses
  case ExtensionMethods
  case DefaultParameters
  case ForComprehensions
  case WhileLoops
  case TailCallOptimization
  case StringInterpolators       // s"", html"", css"", md""
  case ModuleImports

  // Platform capabilities — each gates a std.* intrinsic package (§8).
  case ConsoleIO                 // std.io
  case HttpServer                // std.http
  case WebSockets                // std.ws
  case Auth                      // std.auth
  case FileSystem                // std.fs
  case Crypto                    // std.crypto
  case Database                  // std.db (future)
  case McpServer                 // std.mcp server-side (jvm, js)
  case McpClient                 // std.mcp client-side (jvm, js)
  case Dataset                   // std.mapreduce Dataset[T] (jvm, js, int)
  case PaymentRequest            // std.payment — W3C Payment Request API (browser) + Apple/Google Pay server
  case Streams                   // std.streams — backpressured Source/Sink/Flow (jvm, int, js)
