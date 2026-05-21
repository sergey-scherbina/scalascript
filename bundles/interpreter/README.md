# scalascript-interpreter aggregate

sbt aggregate shortcut for the `ir + backendSpi + core + backendInterpreter` publishing unit.

Use `sbt scalascriptInterpreterAgg/publishLocal` to publish the embedding stack.

Note: full HTTP/actor decoupling (lean-core embedding without HTTP/actors) is deferred to
Phase 2 lazy loading (CapabilityLoader SPI).
