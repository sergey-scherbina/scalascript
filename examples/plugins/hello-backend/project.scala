// Must match the Scala version sbt publishes the SPI with — TASTy
// from a newer compiler isn't readable by older ones.
//> using scala 3.8.3
//> using option -Wunused:all -deprecation -feature

// The scalascript-backend-spi and scalascript-ir artifacts aren't on
// Maven Central yet (pre-1.0).  Build them locally first:
//
//   sbt 'backendSpi/publishLocal' 'ir/publishLocal'
//
// then this sample resolves them from the local ~/.ivy2 cache.
//> using repository m2local
//> using repository ivy2local
//> using dep io.scalascript::scalascript-backend-spi:0.1.0-SNAPSHOT
//> using dep io.scalascript::scalascript-ir:0.1.0-SNAPSHOT
//> using resourceDir src/main/resources
