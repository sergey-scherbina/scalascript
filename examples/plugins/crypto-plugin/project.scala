// Build the org.example.crypto plugin JAR with scala-cli.
//
// Prerequisites: locally-published SPI + IR artifacts.
//   sbt 'backendSpi/publishLocal' 'ir/publishLocal'
//
// Build:
//   scala-cli package . --assembly -o crypto-plugin-1.0.0.jar
//
// Pack into .sscpkg (after building the JAR):
//   mkdir -p _pkg/intrinsics _pkg/sources _pkg/runtime
//   cp manifest.yaml _pkg/
//   cp -r sources/  _pkg/sources/
//   cp -r runtime/  _pkg/runtime/
//   cp crypto-plugin-1.0.0.jar _pkg/intrinsics/
//   ssc plugin pack _pkg -o org.example.crypto-1.0.0.sscpkg
//
// Install and use:
//   ssc plugin install ./org.example.crypto-1.0.0.sscpkg
//   ssc --plugin ./org.example.crypto-1.0.0.sscpkg run examples/use-crypto.ssc

//> using scala 3.8.3
//> using repository m2local
//> using repository ivy2local
//> using dep io.scalascript::scalascript-backend-spi:0.1.0-SNAPSHOT
//> using dep io.scalascript::scalascript-ir:0.1.0-SNAPSHOT
//> using resourceDir src/main/resources
