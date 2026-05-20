package scalascript.wallet.connector.eip1193

/** Standard EIP-1193 / EIP-1474 error codes and exception shapes
 *  consumed by dApp JS via the `request({method, params})` rejection
 *  path. The codes mirror MetaMask's published error table — using the
 *  same numbers means existing dApps see identical UX when our
 *  provider rejects a call. */
object Eip1193Errors:

  case class ProviderError(code: Int, message: String, data: Option[ujson.Value] = None)
      extends Exception(s"[$code] $message")

  /** 4001: user rejected (popup dismiss, MCP elicitation rejected, …). */
  def userRejected(reason: String = "User rejected the request"): ProviderError =
    ProviderError(4001, reason)

  /** 4100: caller has no permission (origin not on allow-list,
   *  account locked, etc.). */
  def unauthorized(reason: String = "The requested account or method is unauthorized"): ProviderError =
    ProviderError(4100, reason)

  /** 4200: the provider doesn't implement this method. */
  def unsupportedMethod(method: String): ProviderError =
    ProviderError(4200, s"The Provider does not support the requested method: $method")

  /** 4900: provider is not connected to any chain. */
  def disconnected: ProviderError =
    ProviderError(4900, "The Provider is disconnected from all chains")

  /** 4901: provider is connected to a different chain than requested. */
  def chainDisconnected: ProviderError =
    ProviderError(4901, "The Provider is disconnected from the requested chain")

  /** -32602: invalid params (JSON-RPC 2.0 standard). */
  def invalidParams(reason: String): ProviderError =
    ProviderError(-32602, s"Invalid params: $reason")

  /** -32603: internal error. */
  def internal(reason: String): ProviderError =
    ProviderError(-32603, s"Internal error: $reason")
