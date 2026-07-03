package scalascript.compiler

/** Interface between the CLI and the Scala 3 compiler driver.
 *
 *  Lives in `core` (no scala3-compiler dependency) so `cli` can reference
 *  it without pulling dotty onto the startup classpath.  The implementation
 *  (`Scala3DriverImpl` in the `compiler-driver` module) is loaded lazily via
 *  ServiceLoader from `lib/compiler/jars/` only when a compile command runs. */
trait Scala3CompilerService:

  /** Compile `sources` to `outDir` with the given `classpath` and extra
   *  scalac `options`.  Returns `Right(())` on success or `Left(errors)` on
   *  failure, where `errors` is a human-readable message. */
  def compile(
    sources:   Seq[os.Path],
    outDir:    os.Path,
    classpath: Seq[os.Path],
    options:   Seq[String] = Nil
  ): Either[String, Unit]
