package scalascript.backend.spi

/** Language and platform features a Backend declares it supports.
 *  See specs/backend-spi.md §11 — Capabilities.  Core's CapabilityCheck
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
  case DistributedStreams        // std.dstreams — DStream[T] / Pipeline Beam-model (native, spark, kafka, flink)
  case Payments                  // std.payments — PaymentProvider SPI (fiat PSPs: Stripe, PayPal, Adyen, Square)
  case BankRails                 // std.bank-rails — BankRailsProvider SPI (SEPA, ACH, Pix, FedNow, SWIFT, SCT Inst, UK FPS/BACS/CHAPS, UPI, Zengin, PayNow)
  case Markup                    // std.markup — xml"..." interpolator + fenced xml blocks (jvm, int)
  case Xslt                      // std.markup — XSLT 1.0 transform via javax.xml.transform (jvm, int)
  case GraphQL                   // std.graphql — schema-first GraphQL server + client (jvm, int, js, node)
  case NfcNdef                   // std.nfc — NDEF read/write/status
  case NfcTagTech                // std.nfc — raw tag technologies (deferred)
  case NfcCardEmulation          // std.nfc — card emulation / presentment (deferred)
