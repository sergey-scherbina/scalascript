package scalascript.compiler.plugin.scljetjdbc

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.SQLFeatureNotSupportedException

/** Shared plumbing for the `java.sql.*` shims.
 *
 *  The `java.sql` interfaces are huge (ResultSet alone has ~190 methods) and
 *  drift across JDK versions.  Rather than hand-implement every abstract method
 *  as a concrete class — which must enumerate the full, version-specific method
 *  set to compile — each interface is served by a `java.lang.reflect.Proxy`
 *  backed by one [[InvocationHandler]] that dispatches the supported subset and
 *  throws `SQLFeatureNotSupportedException` for everything else.  This keeps the
 *  shim compact and immune to JDBC-version method churn, exactly the
 *  "unsupported methods are one-line throws" route recommended in
 *  `specs/scljet-jdbc.md` §"Open decisions".
 */
abstract class ProxyHandler(private val label: String) extends InvocationHandler:

  /** Handle a supported call; return the (boxed) result.  Implementations should
   *  fall through to `super.invoke` (via `objectOrThrow`) for anything they
   *  don't recognize. */
  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef

  final def invoke(proxy: AnyRef, method: Method, argsRaw: Array[AnyRef]): AnyRef =
    val args = if argsRaw == null then ProxySupport.NoArgs else argsRaw
    val name = method.getName
    // java.lang.Object + java.sql.Wrapper are handled uniformly for every proxy.
    name match
      case "hashCode"     => Integer.valueOf(System.identityHashCode(proxy))
      case "equals"       => java.lang.Boolean.valueOf(proxy eq args(0))
      case "toString"     => label
      case "isWrapperFor" =>
        java.lang.Boolean.valueOf(args(0).asInstanceOf[Class[?]].isInstance(proxy))
      case "unwrap"       =>
        val iface = args(0).asInstanceOf[Class[?]]
        if iface.isInstance(proxy) then proxy else throw nse("unwrap")
      case _ => dispatch(proxy, name, args)

  protected def nse(name: String): SQLFeatureNotSupportedException =
    ProxySupport.nse(s"$label.$name")

object ProxySupport:
  val NoArgs: Array[AnyRef] = Array.empty

  def nse(what: String): SQLFeatureNotSupportedException =
    SQLFeatureNotSupportedException(s"scljet JDBC: $what is not supported")

  def proxy[A](iface: Class[A], handler: InvocationHandler): A =
    Proxy.newProxyInstance(iface.getClassLoader, Array[Class[?]](iface), handler).asInstanceOf[A]

  // ── boxed-primitive helpers ───────────────────────────────────────────────
  def boxL(n: Long): AnyRef    = java.lang.Long.valueOf(n)
  def boxI(n: Int): AnyRef     = Integer.valueOf(n)
  def boxD(d: Double): AnyRef  = java.lang.Double.valueOf(d)
  def boxF(f: Float): AnyRef   = java.lang.Float.valueOf(f)
  def boxB(b: Boolean): AnyRef = java.lang.Boolean.valueOf(b)
  val unit: AnyRef             = null

  // ── argument extraction (args are already boxed by the proxy) ─────────────
  def argInt(args: Array[AnyRef], k: Int): Int       = args(k).asInstanceOf[java.lang.Number].intValue
  def argLong(args: Array[AnyRef], k: Int): Long     = args(k).asInstanceOf[java.lang.Number].longValue
  def argDouble(args: Array[AnyRef], k: Int): Double = args(k).asInstanceOf[java.lang.Number].doubleValue
  def argBool(args: Array[AnyRef], k: Int): Boolean  = args(k).asInstanceOf[java.lang.Boolean].booleanValue
  def argStr(args: Array[AnyRef], k: Int): String    = args(k).asInstanceOf[String]
  def isInt(args: Array[AnyRef], k: Int): Boolean    = args(k).isInstanceOf[java.lang.Integer]
