package ssc3.plugins

import ssc3.{Expr, Plugins}

/** A MARKER THAT EXISTS ONLY TO PROVE THE DOOR, and only when asked for by name.
  *
  * `specs/60-compile-time-extension.md` R1 lands the node, the registry and both fronts asking it.
  * With no plugin claiming anything, none of that is observable — no name is a marker, every file
  * parses exactly as before, and a green gate would be green for the wrong reason. This registers
  * ONE name so the plumbing can be exercised end to end:
  *
  *   SSC3_MARKER_PROBE=1 ssc3 ast f.ssc     `probe[Int](1)` is an `Expr.Marker`, on BOTH fronts
  *   ssc3 ast f.ssc                         the same file is an ordinary call, as it always was
  *
  * OFF BY DEFAULT, and by an environment variable rather than a build flag, because the control it
  * provides has to be available in the same tree and the same binary as the experiment. A probe
  * that requires a rebuild to switch is a probe nobody runs twice.
  *
  * It deliberately does NOT rewrite yet: the pass is R2. Until then a registered marker reaches
  * `Lower` and is refused there, by position and by name — which is the assertion R1 can make and
  * R2 replaces.
  */
object MarkerProbe:
  /** `SSC3_MARKER_PROBE=1` claims `probe`; `SSC3_MARKER_PROBE=Focus` claims `Focus` instead.
    *
    * THE SECOND FORM IS NOT A CONVENIENCE. `probe` is a name the GRAMMAR knows nothing about, so it
    * reaches the projection as an ordinary call; `Focus`, `Prism` and `direct` are marked by
    * ScalaSpike itself and arrive as marker nodes carrying their type arguments. Those are two
    * different paths into the same node, and a probe that could only exercise one would prove the
    * door for the easy half. Pointing it at a real name is how the two fronts are compared on the
    * shape the actual clients will use. */
  def names: List[String] = sys.env.get("SSC3_MARKER_PROBE") match
    case None            => Nil
    case Some("1")       => List("probe")
    case Some(spec)      => spec.split(",").toList.map(_.trim).filter(_.nonEmpty)

  def install(): Unit =
    names.foreach(n => if !Plugins.hasRewrite(n) then Plugins.registerRewrite(n, (e, _) => Right(e)))
