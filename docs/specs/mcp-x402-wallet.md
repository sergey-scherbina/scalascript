# MCP × x402 × Wallet — Plan

Status: **draft / planning**. Source of truth for the MCP integration
layer that connects the Model Context Protocol (Anthropic's JSON-RPC
tools/resources/prompts protocol — see [`docs/specs/mcp.md`](mcp.md)) with
[`wallet-spi`](wallet-spi.md) and the `x402-*` payment stack.

Until each phase below is checked off in
[`MILESTONES.md`](../MILESTONES.md), the code does not match this
design.

## 1. What we're enabling

Three distinct but composable integrations:

1. **Wallet over MCP** (`mcp-wallet-server`) — expose
   `wallet-spi` operations (sign, send, query balance) as MCP tools
   and resources so an LLM agent can act through a user's wallet
   under explicit user-controlled policies.
2. **x402-gated MCP** (`mcp-x402-server` / `mcp-x402-client`) —
   make MCP tool calls payment-gated. A server marks tools with a
   per-call cost; calling them returns an MCP error that carries an
   x402 `PaymentRequirements`; the client retries with a payment
   proof. This is HTTP 402 lifted into MCP's JSON-RPC envelope.
3. **Composed agent flow** — an agent reaches a paid MCP tool,
   uses its connected `mcp-wallet-server` to sign an x402 payment,
   retries, and the tool runs. End-to-end agent-to-agent (or
   agent-to-service) micropayments, no human in the request loop
   (but always a human in the **policy** loop — see §9).

The third item is the killer use case: M2M micropayments for
agentic tool use.

## 2. Goals

- **Bring x402 into MCP semantics natively.** Don't shoehorn HTTP
  402 — define an MCP-level "payment required" response that carries
  the same `PaymentRequirements` shape, and an MCP-level "X-Payment"
  request field.
- **Reuse, don't fork.** Build on the existing `mcp-common`
  (`McpServerCore` / `McpClientCore` / `JsonRpc`) and on
  `wallet-spi` / `x402-core` / `blockchain-spi`. No parallel
  protocol stacks.
- **Policy-first wallet exposure.** The wallet MCP server never
  signs unless the user-defined policy permits the call. Policies
  cover: allow-listed tools/origins, per-call amount caps,
  per-session budgets, curve / chain restrictions, optional
  out-of-band confirmation via MCP `elicitation`.
- **Transport-agnostic.** Works over stdio (most common — agent
  spawns the wallet as a subprocess), HTTP+SSE (remote agents),
  and the future WebSocket transport. Whatever `mcp-common`
  supports, this does too.
- **Replaceable wallet.** The MCP wallet server consumes
  `wallet-spi`'s `Vault` + `AccountStrategy`; encrypted-local /
  passkey / MPC / hardware wallet — all available without
  reimplementing the MCP surface.

## 3. Non-goals

- We are **not** writing a new payment protocol. x402 stays the
  protocol; this is its MCP framing.
- We are **not** specifying a wallet UX (browser PWA, mobile,
  CLI). The MCP server is the API surface; UX is the consumer.
- We are **not** integrating with non-MCP agent frameworks
  (LangChain tools, OpenAI function calling, etc.) in this
  milestone — only MCP. Other frameworks can adapt via existing
  MCP↔framework bridges.
- We are **not** implementing on-device biometric flows here. The
  spec defines a `confirm` hook that the host application
  satisfies however it likes.
- We are **not** building agent decision-making logic. The agent
  decides; this spec defines the rails.

## 4. Where this SPI sits

```
                   ┌──────────────────────────────────────┐
                   │  LLM / agent  (Claude, GPT, …)       │
                   └──────────────────┬───────────────────┘
                                      │ MCP (JSON-RPC)
                                      │
            ┌─────────────────────────┴─────────────────────────┐
            │                                                   │
            ▼                                                   ▼
   ┌─────────────────────┐                          ┌──────────────────────┐
   │ mcp-wallet-server   │                          │ mcp-x402-server      │
   │   sign, send,       │                          │   priced tools,      │
   │   balance, accounts │                          │   PaymentRequired    │
   │   (policy-gated)    │                          │   error responses    │
   └──────────┬──────────┘                          └──────────┬───────────┘
              │                                                │
       wallet-spi                                          x402-core
       blockchain-spi                                      x402-server
              │                                                │
              └──────────────────┬─────────────────────────────┘
                                 ▼
                         crypto / chain RPC

          ┌──────────────────────────────────────────────────────┐
          │  mcp-x402-client   (proxy/middleware in MCP client)  │
          │  on PaymentRequired error → sign via mcp-wallet →    │
          │  retry with X-Payment field                          │
          └──────────────────────────────────────────────────────┘
```

The arrows are JSON-RPC over an MCP transport — stdio for the
local wallet, HTTP+SSE for remote priced tools, etc.

## 5. mcp-wallet-server

A `McpServerCore`-based server that exposes `wallet-spi` operations.

### 5.1 Tools

```yaml
wallet.listAccounts:
  description: List wallet accounts (id, label, public keys, derivation path).
  args: {}
  returns: AccountDescriptor[]

wallet.getAddress:
  description: Address of an account on a specific chain.
  args:
    accountId: string
    chainId:   string        # CAIP-2
  returns: string

wallet.getBalance:
  description: Native or token balance.
  args:
    accountId: string
    chainId:   string
    asset:     Asset?        # optional; absent = native
  returns: { value: string, decimals: int, symbol: string }

wallet.signMessage:
  description: Sign an arbitrary message. Policy-gated.
  args:
    accountId: string
    chainId:   string
    message:   string        # utf-8
  returns: { signature: hex, signer: address }

wallet.signTypedData:
  description: Sign EIP-712 / CIP-8 / Solana sign-doc typed data.
                Policy-gated.
  args:
    accountId: string
    chainId:   string
    typed:     TypedData
  returns: { signature: hex }

wallet.signTransaction:
  description: Sign (but do NOT broadcast) a transaction built from
                an intent. Policy-gated.
  args:
    accountId: string
    chainId:   string
    intent:    TxIntent
  returns: { rawSigned: hex, txHash: hex (predicted) }

wallet.sendTransaction:
  description: Build, sign, and broadcast in one call. Policy-gated;
                always confirm-required by default.
  args:
    accountId: string
    chainId:   string
    intent:    TxIntent
  returns: { txHash: hex, receipt: TxReceipt? }    # receipt if synchronous

wallet.payX402:
  description: Convenience for x402 client flows — given a
                PaymentRequirements, produce a signed PaymentPayload.
                Policy-gated by max-amount budget.
  args:
    accountId:    string
    requirements: PaymentRequirements
  returns: PaymentPayload
```

### 5.2 Resources

```yaml
wallet://accounts            # list of AccountDescriptor (JSON)
wallet://transactions/{addr} # paged tx history (JSON)
wallet://policy              # current policy view (read-only)
```

### 5.3 Policy model

The wallet server **does not** sign anything by default. A `Policy`
object — set at construction time, mutable only via the host
application, **not** via MCP — gates every signing operation.

```scala
package scalascript.mcp.wallet

case class Policy(
  allowedOrigins:    Set[String],            // MCP client identifiers
  allowedTools:      Set[String],            // which wallet tools are exposed
  allowedChains:     Set[ChainId],
  allowedCurves:     Set[Curve],
  maxPerCall:        Map[ChainId, BigInt],   // per-chain native-unit cap
  sessionBudget:     Option[Budget],         // cumulative cap across calls
  confirmation:      ConfirmationMode,
  // …
)

enum ConfirmationMode:
  /** No external confirmation. Used only with strict allowlists +
   *  small per-call caps + non-production keys (e.g. session keys). */
  case Implicit

  /** Server emits MCP `elicitation/create` for every signing op,
   *  paused until the host satisfies it. Production default. */
  case ElicitationPerCall

  /** Like ElicitationPerCall but cached for a configurable TTL after
   *  the first approval (e.g. "approve all transfers under N USDC
   *  for 5 minutes"). */
  case ElicitationCached(ttlSeconds: Int)
```

`ElicitationPerCall` is the **production default**. The host (a
desktop app, browser PWA, or CLI) implements `elicitation/create` to
show the user the proposed signing payload + decoded `TxIntent` and
returns approve/reject.

For unattended automation (CI, server-side agents) the host may set
`Implicit` and supply a session key with a strict allowlist.

### 5.4 Vault binding

`mcp-wallet-server` wraps a single `Vault` provided at construction.
Common bindings:

```scala
McpWalletServer(
  vault     = EncryptedLocalVault(path),
  policy    = Policy.production(),
  transport = StdioTransport,
)

McpWalletServer(
  vault     = LedgerVault(...),
  policy    = Policy.production(allowedTools = Set("wallet.payX402")),
  transport = HttpSseTransport(host = "localhost", port = 7390),
)
```

The Vault is unlocked **before** the MCP server starts; the MCP
server is not a credential-collection surface. Future phase may add
an `auth/unlock` MCP method for explicit unlock via host UI.

## 6. x402 over MCP

### 6.1 Server-side: priced tools

Two new fields on `ToolRegistration` (additive, optional):

```scala
case class ToolRegistration(
  name:        String,
  description: String,
  inputSchema: ujson.Value,
  // … existing fields …
  price:       Option[ToolPrice] = None,       // ← new
  paymentScope: Option[PaymentScope] = None,   // ← new
)

case class ToolPrice(
  amount:  BigInt,
  asset:   Asset,
  payTo:   String,           // recipient address (CAIP-10)
)

case class PaymentScope(
  oneShot:  Boolean = true,  // pay per call
  ttlSec:   Int = 300,       // session-level reuse window if !oneShot
)
```

When a client invokes a priced tool without a payment proof:

```jsonc
// Error response over the existing tools/call result envelope
{
  "jsonrpc": "2.0",
  "id": <call-id>,
  "error": {
    "code": -32402,                       // MCP-x402 error code
    "message": "Payment required",
    "data": {
      "x402Version": 1,
      "requirements": { /* PaymentRequirements from x402-core */ }
    }
  }
}
```

The error code `-32402` is reserved for "payment required" in this
spec (no MCP-standard code today; we'll propose upstream if useful).

On a paid call, the client adds an `_meta.x402.payment` field to the
`tools/call` params:

```jsonc
{
  "jsonrpc": "2.0",
  "id": <call-id>,
  "method": "tools/call",
  "params": {
    "name": "premium.search",
    "arguments": { "query": "..." },
    "_meta": {
      "x402": {
        "payment": "<base64-encoded PaymentPayload>"
      }
    }
  }
}
```

`_meta` is the MCP-standard extension namespace, so this stays
forward-compatible.

The server verifies the payment via the configured `Facilitator`
(same one as the HTTP x402 server) before executing the tool. On
success it MAY return an `_meta.x402.settled` field in the result.

### 6.2 Client-side: auto-payment interceptor

`McpClientCore` gains an optional `x402AutoPay` middleware:

```scala
val agent = McpClient.connect(server,
  x402AutoPay = Some(X402AutoPay(
    wallet     = mcpWalletConnection,        // or a local Wallet
    maxAmount  = BigInt(10_000_000),         // 10 USDC ceiling
    onCharge   = (req, payload) => log(...),  // observability hook
  )),
)
```

On `-32402` errors the middleware:

1. Parses `PaymentRequirements` from `error.data`.
2. Calls `wallet.payX402(accountId, requirements)` via the MCP
   wallet connection (or directly invokes a local `Wallet`).
3. Retries the original `tools/call` with `_meta.x402.payment`
   attached.
4. Bubbles the eventual success (or hard-failure) to the agent.

This mirrors `X402HttpClient` for HTTP exactly — same algorithm,
different envelope.

### 6.3 Resources, prompts

`resources/read` and `prompts/get` get the same treatment: a
`Resource` or `Prompt` registration may carry an optional `ToolPrice`,
and the same `-32402` / `_meta.x402.payment` exchange applies.

## 7. Composed flow — agent paying for an MCP tool

End-to-end:

```
Host application                                          MCP layer
  │
  ├─ start mcp-wallet-server (local stdio)
  │     vault = EncryptedLocalVault(unlocked)
  │     policy = ElicitationPerCall + 50 USDC session cap
  │
  ├─ connect agent to:
  │     - mcp-wallet-server  (stdio)         ← signing
  │     - example.com/mcp    (HTTP+SSE)      ← priced premium tools
  │
  └─ agent runs:
     1. agent: tools/call premium.search { query: "..." }
        server (HTTP+SSE): { error: -32402, data: requirements }
     2. client middleware: detects -32402; calls
        wallet.payX402(acct, requirements)
        wallet server: elicits user approval, signs, returns PaymentPayload
     3. client middleware: retries tools/call with _meta.x402.payment
        server: verifies via facilitator, runs tool, returns result
     4. agent: receives result transparently
```

The agent's perspective: it called `premium.search` and got a
result. The 402 round-trip is invisible to the agent.

The user's perspective: they saw one MCP elicitation pop up showing
"premium.search wants 0.01 USDC on Base, approve?".

## 8. Module layout

```
mcp-wallet                  # cross-compile types: Policy, ToolPrice, …
mcp-wallet-server           # McpServerCore-based server; wraps a Vault
mcp-wallet-client           # convenience wrapper for connecting to
                            # a mcp-wallet-server as a sub-wallet

mcp-x402                    # cross-compile: -32402 error code, _meta
                            # shape, X402AutoPay middleware
mcp-x402-server-glue        # ToolRegistration price/scope plumbing
                            # registered with mcp-common server core
```

All of these depend on `mcp-common`, `wallet-spi`, `x402-core`, and
(for `mcp-wallet-server`) on `blockchain-spi`.

## 9. Security considerations

This section governs the design more than any other.

1. **Vault never travels over MCP.** The Vault is constructed and
   unlocked in the host process; MCP only exposes operations, never
   keys or seed material. There is no `wallet.exportSeed` tool —
   that's the host's responsibility through a different channel.

2. **Policy is host-controlled.** Policy mutation is not an MCP
   method. An MCP client cannot loosen policy. If it could, the
   policy would be useless.

3. **Elicitation is the default consent channel.** For any signing
   tool, `ElicitationPerCall` (or `ElicitationCached` for explicitly
   batched operations) is the default. `Implicit` mode requires
   explicit opt-in at construction and is intended for
   pre-authorized session keys with tight budgets.

4. **Payment caps are per-chain, per-call, and per-session.** A
   single misconfigured policy field should not drain a wallet.

5. **The MCP wallet server is local-by-default.** Stdio transport
   means the wallet runs in a subprocess of the host; remote
   transports require explicit configuration and OAuth/OIDC (via
   `mcp-common.oauth` / `mcp-common.oidc` — already in
   `mcp-common`).

6. **Replay protection for x402 payments stays in x402-core.** The
   MCP envelope does not weaken nonce semantics. The MCP server
   delegates verification to the same `Facilitator` + `NonceStore`
   used by the HTTP x402 server.

7. **Confused-deputy risk.** An agent that has both a wallet
   connection and a priced-tool connection must not be tricked by
   the priced server into signing more than it intended. The
   `X402AutoPay` middleware enforces a `maxAmount` ceiling
   **independently** of any policy in the wallet server — defence
   in depth.

8. **Audit trail.** Every signing operation through
   `mcp-wallet-server` is logged with: timestamp, tool name,
   originating MCP client id (when known), policy decision,
   resulting signature hash (not the signature itself in logs;
   the txHash for sends). The host can subscribe via an audit
   resource (`wallet://audit`).

## 10. Phases

Each phase independently shippable per
[`AGENTS.md`](../AGENTS.md) Rule 3.

### Phase 1 — mcp-wallet read-only

Depends on wallet-spi Phase 1 and blockchain-spi Phase 1.

- [ ] `mcp-wallet` module (cross-compile)
- [ ] `mcp-wallet-server` module — read-only tools only:
      `listAccounts`, `getAddress`, `getBalance`; resources:
      `wallet://accounts`
- [ ] No signing yet — the policy framework is in but no
      signature-producing tools are wired in
- [ ] Stdio transport (via `mcp-common`)
- [ ] Integration test: spawn server, call `listAccounts` over JSON-RPC

### Phase 2 — mcp-wallet signing with ElicitationPerCall

Depends on wallet-spi Phase 1 (EoaStrategy).

- [ ] `wallet.signMessage` / `signTypedData` / `payX402` tools
- [ ] `Policy` + `ConfirmationMode.ElicitationPerCall` enforced
- [ ] MCP `elicitation/create` plumbing
- [ ] Audit log resource
- [ ] Test: signing call blocked without elicitation response;
      allowed after approval

### Phase 3 — x402 over MCP (server side)

Depends on blockchain-spi Phase 1.

- [ ] `mcp-x402` module: `-32402` error code, `_meta.x402` shape,
      registration glue
- [ ] `ToolRegistration.price` + `paymentScope` fields
- [ ] Server emits `-32402` on unpaid calls to priced tools
- [ ] Server verifies `_meta.x402.payment` via configured
      `Facilitator` before executing tool
- [ ] Unit test: priced tool returns -32402; tool runs with valid
      `_meta.x402.payment`

### Phase 4 — x402 over MCP (client side)

- [ ] `X402AutoPay` middleware in `mcp-x402` for
      `McpClientCore`
- [ ] Supports both local `Wallet` and remote `mcp-wallet-client`
      as the signer
- [ ] `maxAmount` ceiling, charge hook
- [ ] Test: end-to-end -32402 round-trip via in-process server +
      client

### Phase 5 — Composed agent flow + sample

Depends on Phases 1–4.

- [ ] `wallet.sendTransaction` tool (build + sign + broadcast)
- [ ] `ConfirmationMode.ElicitationCached(ttlSeconds)` impl
- [ ] `examples/mcp-paid-agent.ssc` — full demo: stdio wallet,
      HTTP+SSE priced server, an agent program that uses both
- [ ] Run against a local Anvil chain for deterministic settle

### Phase 6 — Resources & prompts pricing

- [ ] `ResourceRegistration.price` (analogous to tools)
- [ ] `PromptRegistration.price`
- [ ] Server emits -32402 on unpaid `resources/read` and
      `prompts/get`
- [ ] Client middleware handles them via the same path

### Phase 7 — OAuth/OIDC integration for remote mcp-wallet

Depends on `mcp-common.oauth` / `oidc` (already implemented).

- [ ] `HttpSseTransport` support in `mcp-wallet-server`
- [ ] OAuth/OIDC required for any remote connection
- [ ] Per-OAuth-scope policy mapping (different clients can have
      different `Policy`)

### Phase 8 — Stream payments via MCP

Depends on x402 Phase 7 (`PaymentScheme.Stream`) — not yet landed
in x402; sequenced after.

- [ ] MCP server supports streaming-cost tools (e.g. token-by-token
      pricing on an LLM proxy)
- [ ] `_meta.x402.streamCharge` interim charges
- [ ] Client middleware budget tracking

## 11. Interop with existing mcp-common

`mcp-common` already provides `McpServerCore`, `McpClientCore`,
`JsonRpc`, OAuth/OIDC. This SPI does **not** modify them. It adds:

- New `_meta` namespace conventions (`_meta.x402.*`) — uses the
  existing `_meta` extension mechanism.
- New optional fields on `ToolRegistration` / `ResourceRegistration`
  / `PromptRegistration` — additive, default `None`, no breakage to
  callers that don't use them.
- A new error code `-32402` — sits in the application-defined error
  range, no conflict with existing codes.
- New server module (`mcp-wallet-server`) — composes
  `McpServerCore` as a peer to whatever else the host runs (or as
  the only server).

No changes to v1.17 milestone scope or implementation order.

## 12. Open questions

1. **`_meta.x402` namespace finalization.** The MCP `_meta`
   convention encourages domain-qualified keys. Should we use
   `_meta.x402.payment` or `_meta["x402:payment"]`? Confirm with
   MCP spec maintainers' direction; default to dotted form unless
   they push back.
2. **Error-code coordination with upstream MCP.** `-32402` is
   inside the application-defined range, but if MCP standardises
   a "payment required" code in future, we'd want to align.
   Propose to upstream once Phase 3 lands.
3. **Wallet routing across multiple accounts.** When a host has
   multiple unlocked accounts and the agent calls `wallet.payX402`
   without an `accountId`, what's the default? Proposed: policy-
   driven default-account-per-chain map. Resolve before Phase 2.
4. **Cross-process elicitation latency.** For stdio transport the
   `elicitation/create` round-trip is fast; for HTTP+SSE remote
   wallets it's bounded by network. Timeout policy needs spelling
   out — proposed default 60s with policy-configurable override.

## 13. References

- MCP spec: `modelcontextprotocol.io`
- See [`docs/specs/mcp.md`](mcp.md) for the in-project MCP design
- [`docs/specs/x402.md`](x402.md) for HTTP x402 spec
- [`docs/specs/wallet-spi.md`](wallet-spi.md) for wallet operations
- [`docs/specs/blockchain-spi.md`](blockchain-spi.md) for chain ops
