package scalascript.codegen

object JvmRuntimeMutualTco:
  val source: String =
    """|
       |// ── Mutual tail-call trampoline ────────────────────────────────────────
       |final class _TailCall(val k: () => Any)
       |def _trampoline(start: () => Any): Any =
       |  var r: Any = start()
       |  while r.isInstanceOf[_TailCall] do
       |    r = r.asInstanceOf[_TailCall].k()
       |  r
       |
       |""".stripMargin
