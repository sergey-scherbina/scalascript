package scalascript.sql

/** 1Password secret resolver via the `op` CLI.
 *
 *  Reference format: `${op:vault/item/field}`
 *  Calls: `op read "op://vault/item/field" --no-newline`
 *
 *  Auth is handled entirely by the `op` CLI:
 *  - Desktop integration (Touch ID, system keychain)
 *  - `OP_SERVICE_ACCOUNT_TOKEN` env for CI/server use
 *  - `OP_CONNECT_HOST` + `OP_CONNECT_TOKEN` for 1Password Connect
 *
 *  `op` must be on PATH (install via `brew install 1password-cli`).
 */
class OpSecretResolver extends SecretResolver:
  val scheme = "op"

  protected def command(ref: String): Seq[String] =
    Seq("op", "read", s"op://$ref", "--no-newline")

  def resolve(ref: String): String =
    val cmd  = command(ref)
    val pb   = ProcessBuilder(cmd*)
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes()).strip()
    val err  = new String(proc.getErrorStream.readAllBytes()).strip()
    val code = proc.waitFor()
    if code != 0 then
      val hint =
        if err.contains("not found") || err.contains("doesn't exist") then
          s" — verify the vault/item/field path 'op://$ref' exists in 1Password"
        else if err.contains("not signed in") || err.contains("unauthorized") then
          " — run `op signin` or set OP_SERVICE_ACCOUNT_TOKEN"
        else if code == 127 then
          " — install 1Password CLI: brew install 1password-cli"
        else ""
      throw RuntimeException(s"op read failed for op://$ref (exit $code): $err$hint")
    out
