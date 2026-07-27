package scalascript.uniml.dialect.markdown.corpus

/** Small dependency-free SHA-256 used only to authenticate generated corpus
  * data on both JVM and Scala.js. */
private[corpus] object MarkdownCorpusSha256:
  private val roundConstants: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  )

  def canonical(cases: Vector[MarkdownCorpusCase]): String =
    fields(cases.flatMap { testCase =>
      Vector(
        testCase.corpus,
        testCase.version,
        testCase.profile,
        testCase.example.toString,
        testCase.section,
        testCase.markdown,
        testCase.html,
        testCase.extension,
      )
    })

  def fields(values: Vector[String]): String =
    val bytes = Array.newBuilder[Byte]
    values.foreach { value =>
      val encoded = utf8(value)
      appendLength(bytes, encoded.length.toLong)
      bytes ++= encoded
    }
    digest(bytes.result())

  def ofUtf8(value: String): String =
    digest(utf8(value))

  private def appendLength(target: scala.collection.mutable.Builder[Byte, Array[Byte]], size: Long): Unit =
    var shift = 56
    while shift >= 0 do
      target += ((size >>> shift) & 0xffL).toByte
      shift -= 8

  private def utf8(value: String): Array[Byte] =
    val result = Array.newBuilder[Byte]
    var index = 0
    while index < value.length do
      val first = value.charAt(index)
      val codePoint =
        if first >= '\uD800' && first <= '\uDBFF' && index + 1 < value.length then
          val second = value.charAt(index + 1)
          if second >= '\uDC00' && second <= '\uDFFF' then
            index += 1
            0x10000 + ((first.toInt - 0xD800) << 10) + (second.toInt - 0xDC00)
          else 0xFFFD
        else if first >= '\uDC00' && first <= '\uDFFF' then 0xFFFD
        else first.toInt
      if codePoint <= 0x7f then result += codePoint.toByte
      else if codePoint <= 0x7ff then
        result += (0xc0 | (codePoint >>> 6)).toByte
        result += (0x80 | (codePoint & 0x3f)).toByte
      else if codePoint <= 0xffff then
        result += (0xe0 | (codePoint >>> 12)).toByte
        result += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
        result += (0x80 | (codePoint & 0x3f)).toByte
      else
        result += (0xf0 | (codePoint >>> 18)).toByte
        result += (0x80 | ((codePoint >>> 12) & 0x3f)).toByte
        result += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
        result += (0x80 | (codePoint & 0x3f)).toByte
      index += 1
    result.result()

  private def rotateRight(value: Int, bits: Int): Int =
    (value >>> bits) | (value << (32 - bits))

  private def digest(input: Array[Byte]): String =
    val bitLength = input.length.toLong * 8L
    val paddedLength = ((input.length + 9 + 63) / 64) * 64
    val padded = new Array[Byte](paddedLength)
    Array.copy(input, 0, padded, 0, input.length)
    padded(input.length) = 0x80.toByte
    var lengthIndex = 0
    while lengthIndex < 8 do
      padded(paddedLength - 1 - lengthIndex) =
        ((bitLength >>> (lengthIndex * 8)) & 0xffL).toByte
      lengthIndex += 1

    var h0 = 0x6a09e667
    var h1 = 0xbb67ae85
    var h2 = 0x3c6ef372
    var h3 = 0xa54ff53a
    var h4 = 0x510e527f
    var h5 = 0x9b05688c
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19
    val words = new Array[Int](64)

    var block = 0
    while block < padded.length do
      var word = 0
      while word < 16 do
        val offset = block + word * 4
        words(word) =
          ((padded(offset) & 0xff) << 24) |
            ((padded(offset + 1) & 0xff) << 16) |
            ((padded(offset + 2) & 0xff) << 8) |
            (padded(offset + 3) & 0xff)
        word += 1
      while word < 64 do
        val x = words(word - 15)
        val y = words(word - 2)
        val s0 = rotateRight(x, 7) ^ rotateRight(x, 18) ^ (x >>> 3)
        val s1 = rotateRight(y, 17) ^ rotateRight(y, 19) ^ (y >>> 10)
        words(word) = words(word - 16) + s0 + words(word - 7) + s1
        word += 1

      var a = h0
      var b = h1
      var c = h2
      var d = h3
      var e = h4
      var f = h5
      var g = h6
      var h = h7
      var round = 0
      while round < 64 do
        val sum1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
        val choose = (e & f) ^ (~e & g)
        val temp1 = h + sum1 + choose + roundConstants(round) + words(round)
        val sum0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
        val majority = (a & b) ^ (a & c) ^ (b & c)
        val temp2 = sum0 + majority
        h = g
        g = f
        f = e
        e = d + temp1
        d = c
        c = b
        b = a
        a = temp1 + temp2
        round += 1

      h0 += a
      h1 += b
      h2 += c
      h3 += d
      h4 += e
      h5 += f
      h6 += g
      h7 += h
      block += 64

    Vector(h0, h1, h2, h3, h4, h5, h6, h7)
      .map(value => f"$value%08x")
      .mkString
