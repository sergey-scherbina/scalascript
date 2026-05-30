# MPC Wallet Vault

MPC wallet vault support lets ScalaScript delegate signing to external
multi-party-computation providers while preserving the same `Vault` /
`RawSigner` surface used by local and hardware wallets.

## Goals

- Keep `wallet-vault-mpc` vendor-neutral: account discovery, health checks,
  and remote signing are expressed through `RemoteSigningClient`.
- Add provider adapters as separate modules, one per vendor, so provider
  authentication, request bodies, and polling details do not leak into the
  shared abstraction.
- Support secp256k1 and Ed25519 raw-message signing where the provider exposes
  those curves.
- Make adapters testable without live vendor credentials through mock HTTP
  servers and deterministic wire-codec tests.

## Non-goals

- Implementing an MPC protocol locally. Providers own their TSS / FROST /
  GG18 / CMP quorum.
- Managing provider-side users, policies, or approval workflows.
- Normalizing every provider-specific signature envelope. Adapters return the
  raw signature bytes expected by `RawSigner`.

## Architecture

```
McpVault
  -> RemoteSigningClient
       -> HttpRemoteSigningClient        (generic JSON-over-HTTP reference)
       -> FireblocksRemoteSigningClient  (provider adapter)
       -> CoinbaseRemoteSigningClient    (provider adapter)
```

The shared module is `payments/wallet/vault-mpc/`.

| Type | Role |
|------|------|
| `RemoteSigningClient` | Provider-neutral `health`, `listAccounts`, `sign` contract |
| `McpVault` | `Vault` SPI adapter that exposes provider accounts as signers |
| `McpRemoteSigner` | `RawSigner` implementation delegating signatures to the client |
| `HttpRemoteSigningClient` | Reference REST client with bearer auth and async polling |
| `MpcSerialization` | Base64, curve/hash naming, and JSON helpers |

Provider modules live beside the shared module:

| Module | Status | Provider |
|--------|--------|----------|
| `wallet-vault-mpc-fireblocks` | Implemented | Fireblocks RAW transaction signing |
| `wallet-vault-mpc-coinbase` | Implemented | Coinbase Prime MPC signing requests |

## Fireblocks

Module: `payments/wallet/wallet-vault-mpc-fireblocks/`

Public surface:

```scala
import scalascript.wallet.vault.mpc.fireblocks.*

val vault = FireblocksVault(
  apiKey        = sys.env("FIREBLOCKS_API_KEY"),
  privateKeyPem = sys.env("FIREBLOCKS_PRIVATE_KEY_PEM"),
  baseUrl       = sys.env.getOrElse("FIREBLOCKS_BASE_URL", "https://api.fireblocks.io"),
  options       = FireblocksOptions(vaultAccountId = "0", assetId = "ETH"),
)
```

`FireblocksRemoteSigningClient` extends the shared HTTP client contract but
owns the Fireblocks-specific transport:

- `GET /v1/vault/accounts_paged?limit=1` for `health()`.
- `GET /v1/vault/accounts/{vaultAccountId}` for `listAccounts()`.
- `POST /v1/transactions` with `operation = RAW` and `rawMessageData`.
- `GET /v1/transactions/{id}` polling until `COMPLETED`, failed status, or
  configured timeout.
- JWT authentication with `RS256`, `iat`, `exp`, `nonce`, `uri`, and
  `bodyHash`; each request also sends `X-API-Key`.

Fireblocks curve mapping:

| ScalaScript curve | Fireblocks algorithm |
|-------------------|----------------------|
| `Curve.Secp256k1` | `MPC_ECDSA_SECP256K1` |
| `Curve.Ed25519` | `MPC_EDDSA_ED25519` |

Unsupported curves fail before making an HTTP request.

## Coinbase

Module: `payments/wallet/wallet-vault-mpc-coinbase/`  
sbt: `walletVaultMpcCoinbase`

Wraps the [Coinbase Prime](https://docs.cdp.coinbase.com/prime/reference) signing API behind `RemoteSigningClient`.
Authentication uses EC P-256 request signing: each HTTP request carries three headers computed from a PKCS#8 EC private key.

### Authentication headers

| Header | Value |
|---|---|
| `X-CB-ACCESS-KEY` | API key (the opaque key ID from Coinbase Prime portal) |
| `X-CB-ACCESS-TIMESTAMP` | Unix timestamp in seconds |
| `X-CB-ACCESS-SIGNATURE` | `base64(SHA256withECDSA(timestamp + METHOD + path + body, privateKey))` |

### Endpoints

| Operation | Method | Path |
|---|---|---|
| Health | `GET` | `/v1/portfolios/{portfolio_id}` |
| List wallets | `GET` | `/v1/portfolios/{portfolio_id}/wallets` |
| Create signing request | `POST` | `/v1/portfolios/{portfolio_id}/signing_requests` |
| Poll signing request | `GET` | `/v1/portfolios/{portfolio_id}/signing_requests/{id}` |

### Usage

```scala
import scalascript.wallet.vault.mpc.coinbase.*

given ExecutionContext = scala.concurrent.ExecutionContext.global

val vault = CoinbaseVault(
  apiKey        = "your-api-key",
  privateKeyPem = "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
  baseUrl       = "https://api.prime.coinbase.com",
  options       = CoinbaseOptions(
    portfolioId = "your-portfolio-id",
    assetId     = "ETH",
    networkId   = "ethereum-mainnet",
  ),
)
```

`CoinbaseOptions` defaults:

| Field | Default |
|---|---|
| `portfolioId` | `"default"` |
| `assetId` | `"ETH"` |
| `networkId` | `"ethereum-mainnet"` |
| `pollIntervalMs` | `500` |
| `pollMaxAttempts` | `60` (~30 s total) |
| `timeoutMs` | `10000` |

### Wire format

Signing request body:

```json
{
  "wallet_id": "<accountId>",
  "asset_id":  "ETH",
  "network_id": "ethereum-mainnet",
  "signing_target": {
    "payload":         "<hex-encoded bytes>",
    "derivation_path": "m/44'/60'/0'/0/0",
    "algorithm":       "SECP256K1",
    "hash_algorithm":  "keccak256"
  }
}
```

Supported algorithms: `SECP256K1`, `ED25519`, `P256`.

Polling response — completed:

```json
{
  "status":    "SIGNED",
  "signature": { "value": "<hex-encoded DER signature>" }
}
```

## Migration

Existing users of `wallet-vault-mpc` do not need changes. The generic
`HttpRemoteSigningClient` remains available for custom/internal providers.
Applications that want provider-specific support add the relevant module
and construct the named vault (`FireblocksVault`, `CoinbaseVault`, `LitVault`).

## Lit Protocol

Module: `payments/wallet/wallet-vault-mpc-lit/`
sbt: `walletVaultMpcLit`

Wraps the [Lit Protocol](https://developer.litprotocol.com/) PKP (Programmable Key Pair) signing
network behind `RemoteSigningClient`. Authentication uses a pre-computed SIWE-style `AuthSig`
passed as a field in every request body — no request-signing or JWT generation happens
in this adapter.

### Usage

```scala
import scalascript.wallet.vault.mpc.lit.*

val authSigJson = """{"sig":"0x...","derivedVia":"web3.eth.personal.sign","signedMessage":"...","address":"0x..."}"""
val vault = LitVault(
  baseUrl      = "https://rpc.litprotocol.com",
  pkpPublicKey = "0x04...",
  authSig      = authSigJson,
  options      = LitOptions(sigName = "myApp-sig"),
)
```

### Endpoints

| Operation | Method | Path |
|---|---|---|
| Health | `GET` | `/health` |
| List PKPs | `GET` | `/web3/pkp/list?authSig=...` |
| Sign | `POST` | `/web3/pkp/sign` |

### Sign request body

```json
{
  "pkpPublicKey": "0x04...",
  "toSign": [1, 2, 3, ...],
  "authSig": { "sig": "0x...", "derivedVia": "web3.eth.personal.sign", ... },
  "sigName": "sig1",
  "curve": "K256"
}
```

### Sign response

```json
{
  "signatures": {
    "sig1": {
      "r": "0x...", "s": "0x...", "recid": 0,
      "signature": "0x<r><s>",
      "publicKey": "0x04...",
      "dataSigned": "0x..."
    }
  }
}
```

### Curve mapping

| ScalaScript curve | Lit curve name |
|---|---|
| `Curve.Secp256k1` | `K256` |
| `Curve.Ed25519` | `ed25519` |
| `Curve.P256` | `P256` |

## ZenGo X Enterprise

Module: `payments/wallet/wallet-vault-mpc-zengo/`
sbt: `walletVaultMpcZengo`

Wraps the ZenGo X Enterprise MPC service behind `RemoteSigningClient`. Every request is
authenticated with an HMAC-SHA256 per-request signature. The message format is:

```
timestamp|METHOD|/path|sha256hex(requestBody)
```

Three headers carry the auth proof: `X-ZENGO-KEY`, `X-ZENGO-TIMESTAMP`, `X-ZENGO-SIGNATURE`.

### Usage

```scala
import scalascript.wallet.vault.mpc.zengo.*

val vault = ZenGoVault(
  apiKey    = sys.env("ZENGO_API_KEY"),
  secretKey = sys.env("ZENGO_SECRET_KEY"),
  baseUrl   = "https://api.zengo.com/mpc",
)
```

### Endpoints

| Operation | Method | Path |
|---|---|---|
| Health | `GET` | `/v1/health` |
| List accounts | `GET` | `/v1/accounts` |
| Create signing request | `POST` | `/v1/signing/requests` |
| Poll signing result | `GET` | `/v1/signing/requests/{id}` |

### Signing request body

```json
{
  "account_id": "acct-1",
  "algorithm": "ECDSA_SECP256K1",
  "derivation_path": "m/44'/60'/0'/0/0",
  "payload": "deadbeef",
  "hash_algorithm": "keccak256"
}
```

### Signing response (polling)

```json
{ "status": "SIGNED", "signature": "11223344..." }
```

Terminal statuses: `SIGNED`, `COMPLETED`, `SUCCESS` (success); `FAILED`, `REJECTED`,
`CANCELLED`, `ERROR` (failure). All other statuses are polled.

### Curve mapping

| ScalaScript curve | ZenGo algorithm |
|---|---|
| `Curve.Secp256k1` | `ECDSA_SECP256K1` |
| `Curve.Ed25519` | `EDDSA_ED25519` |
| `Curve.P256` | `ECDSA_P256` |

## Phases

- Phase 1: shared MPC vault core. Landed in `wallet-vault-mpc`.
- Phase 2: Fireblocks adapter. Landed 2026-05-28.
- Phase 3: Coinbase Prime MPC adapter. Landed 2026-05-28.
- Phase 4: Lit Protocol adapter. Landed 2026-05-30.
- Phase 5: ZenGo X Enterprise adapter. Landed 2026-05-30.
- Phase 6: production credential examples and env-gated provider integration
  tests. Planned; CI will not require live vendor accounts.

## Testing Strategy

- Shared core: unit tests for `McpVault`, signer delegation, serialization,
  and generic REST polling.
- Fireblocks: local `HttpServer` tests for auth headers/JWT payloads, account
  mapping, request body shape, success polling, failed statuses, poll timeout,
  HTTP errors, PEM parsing, and ServiceLoader discovery.
- Coinbase: local `HttpServer` tests for ECDSA header verification, wallet
  list decoding, request body shape, polling, failure modes, ServiceLoader.
- Lit Protocol: local `HttpServer` tests for health, PKP list parsing, sign
  body shape, authSig passthrough, signature parsing, error handling, curve
  mapping, and fallback account discovery.
- ZenGo: local `HttpServer` tests for HMAC-SHA256 auth header shape and
  correctness, account list decoding, request body fields, async poll loop
  (immediate and multi-round PENDING), failure statuses, HTTP error handling,
  deterministic signature generation, and curve mapping.
- Live integration: env-gated only, using vendor sandbox/preprod credentials
  supplied by the developer or CI secret store.

## Open Questions

- Whether Fireblocks production usage should expose policy/approval metadata in
  a typed ScalaScript status model or keep it provider-specific.
- Whether provider adapters should share a common `MpcProviderPlugin` SPI once
  two or more adapters have landed.
- How much of provider account/public-key discovery can be normalized across
  vendors without losing important policy semantics.
