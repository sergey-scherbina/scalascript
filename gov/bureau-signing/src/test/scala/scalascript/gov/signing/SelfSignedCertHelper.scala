package scalascript.gov.signing

import java.security.KeyPair
import java.security.cert.X509Certificate
import java.nio.file.{Files, Path}

object SelfSignedCertHelper:

  def generatePfxFile(dn: String, password: String): Path =
    val tmp = Files.createTempFile("bureau-test-", ".pfx")
    Files.delete(tmp)
    val keytool = ProcessHandle.current().info().command()
      .map(cmd => java.nio.file.Paths.get(cmd).getParent.resolve("keytool").toString)
      .orElse("keytool")
    val proc = new ProcessBuilder(
      keytool, "-genkeypair",
      "-alias", "test",
      "-keyalg", "RSA", "-keysize", "2048",
      "-validity", "365",
      "-dname", dn,
      "-storetype", "PKCS12",
      "-keystore", tmp.toString,
      "-storepass", password,
      "-keypass", password,
      "-noprompt"
    ).redirectErrorStream(true).start()
    val output = new String(proc.getInputStream.readAllBytes())
    val code   = proc.waitFor()
    if code != 0 then
      throw new RuntimeException(s"keytool failed (exit $code): $output")
    tmp

  def generate(kp: KeyPair, dn: String): X509Certificate =
    throw new UnsupportedOperationException(
      "Use generatePfxFile instead — avoids sun.* internal API"
    )
