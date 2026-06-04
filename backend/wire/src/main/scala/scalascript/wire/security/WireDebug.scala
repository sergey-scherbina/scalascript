package scalascript.wire.security

import scalascript.wire.{WireEnvelope, WireValue}

/** Debug formatting utilities for wire envelopes.
 *
 *  Spec: specs/distributed-wire-protocol.md §Observability */
object WireDebug:

  /** Single-line summary of an envelope (protocol/kind/format/flags). */
  def summary(env: WireEnvelope): String =
    val flagStr = if env.flags.isEmpty then "" else s" flags=[${env.flags.toList.sorted.mkString(",")}]"
    val corrStr = env.correlationId.fold("")(c => s" corr=$c")
    val seqStr  = env.headers.get("seq").fold("")(s => s" seq=$s")
    val sidStr  = env.headers.get("session-id").fold("")(s => s" sid=${s.take(8)}")
    s"${env.protocol}/${env.kind} fmt=${env.format} ver=${env.protocolVer}$flagStr$corrStr$seqStr$sidStr"

  /** Multi-line human-readable dump of an envelope for debugging. */
  def dump(env: WireEnvelope): String =
    val sb = StringBuilder()
    sb.append(s"WireEnvelope {\n")
    sb.append(s"  protocol    = ${env.protocol}\n")
    sb.append(s"  protocolVer = ${env.protocolVer}\n")
    sb.append(s"  format      = ${env.format}\n")
    sb.append(s"  kind        = ${env.kind}\n")
    env.correlationId.foreach(c => sb.append(s"  correlation = $c\n"))
    env.schemaId.foreach(s => sb.append(s"  schemaId    = $s\n"))
    if env.flags.nonEmpty then
      sb.append(s"  flags       = [${env.flags.toList.sorted.mkString(", ")}]\n")
    if env.headers.nonEmpty then
      sb.append(s"  headers:\n")
      env.headers.toList.sortBy(_._1).foreach { case (k, v) =>
        sb.append(s"    $k = $v\n")
      }
    sb.append(s"  payload     = ${dumpValue(env.payload, indent = 4)}\n")
    sb.append("}")
    sb.toString

  private def dumpValue(v: WireValue, indent: Int): String =
    val pad = " " * indent
    v match
      case WireValue.Null          => "null"
      case WireValue.Unit          => "unit"
      case WireValue.Bool(b)       => b.toString
      case WireValue.Int64(n)      => n.toString
      case WireValue.Float64(d)    => d.toString
      case WireValue.Str(s)        =>
        val preview = if s.length > 80 then s.take(80) + "…" else s
        s""""$preview""""
      case WireValue.Bytes(b)      =>
        s"<bytes[${b.length}]>"
      case WireValue.Lst(vs)       =>
        if vs.isEmpty then "[]"
        else if vs.length == 1 then s"[${dumpValue(vs.head, 0)}]"
        else s"[${vs.length} items]"
      case WireValue.Map(entries)  =>
        s"{${entries.length} entries}"
      case WireValue.Object(t, fs) =>
        if fs.isEmpty then s"$t {}"
        else
          val fields = fs.map { case (k, v) =>
            s"\n$pad  $k: ${dumpValue(v, indent + 4)}"
          }.mkString
          s"$t {$fields\n$pad}"
      case WireValue.Tuple(vs)     =>
        s"(${vs.map(dumpValue(_, 0)).mkString(", ")})"
      case WireValue.Enum(t, c, v) =>
        v.fold(s"$t.$c")(vv => s"$t.$c(${dumpValue(vv, 0)})")
      case WireValue.Pid(n, l)     =>
        s"<$n:$l>"
      case WireValue.Error(c, m, _) =>
        s"Error($c: $m)"
