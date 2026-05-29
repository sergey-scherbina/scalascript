class Ssc < Formula
  desc "ScalaScript command-line tool"
  homepage "https://github.com/sergey-scherbina/scalascript"
  version "0.1.0"

  url "https://github.com/sergey-scherbina/scalascript/releases/download/v#{version}/ssc.jar"
  sha256 "REPLACE_WITH_RELEASE_SHA256"

  depends_on "openjdk"

  def install
    libexec.install "ssc.jar"
    (bin/"ssc").write <<~SH
      #!/usr/bin/env bash
      exec "#{Formula["openjdk"].opt_bin}/java" -jar "#{libexec}/ssc.jar" "$@"
    SH
  end

  test do
    system "#{bin}/ssc", "--help"
  end
end
