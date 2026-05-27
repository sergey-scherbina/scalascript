package scalascript.sql

/** Unix `pass` (password-store) secret resolver.
 *
 *  Reference format: `${pass:category/name}`
 *  Calls: `pass show category/name`, returns the first line (the password).
 *
 *  Relies on the user's GPG keyring and `~/.password-store`.
 *  Both `pass` and `gpg` must be on PATH.
 *  No environment variables required.
 */
class PassSecretResolver extends SecretResolver:
  val scheme = "pass"

  protected def command(ref: String): Seq[String] = Seq("pass", "show", ref)

  def resolve(ref: String): String =
    val cmd  = command(ref)
    val pb   = ProcessBuilder(cmd*)
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes())
    val err  = new String(proc.getErrorStream.readAllBytes()).strip()
    val code = proc.waitFor()
    if code != 0 then
      val hint =
        if err.contains("not in the password store") || err.contains("No such file") then
          s" — verify '$ref' exists in ~/.password-store"
        else if code == 127 then
          " — install pass: brew install pass (Mac) or apt install pass (Debian)"
        else if err.contains("gpg") || err.contains("decryption failed") then
          " — GPG decryption failed; check your keyring is available"
        else ""
      throw RuntimeException(s"pass show $ref failed (exit $code): $err$hint")
    out.linesIterator.nextOption().getOrElse(
      throw RuntimeException(s"pass show $ref returned empty output")
    )
