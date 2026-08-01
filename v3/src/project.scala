//> using scala 3.8.3
//> using options -deprecation -feature -Wunused:imports

// Invariant I-1: the v3 kernel has ZERO external dependencies. There is deliberately no
// `//> using dep` line here, and adding one is the thing this file exists to make visible.
// Everything outside the JDK reaches the language through `Prim` and the plugin SPI.
