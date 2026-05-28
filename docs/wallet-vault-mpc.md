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
and construct the named vault (`FireblocksVault`, `CoinbaseVault`).

## Phases

- Phase 1: shared MPC vault core. Landed in `wallet-vault-mpc`.
- Phase 2: Fireblocks adapter. Landed 2026-05-28.
- Phase 3: Coinbase Prime MPC adapter. Landed 2026-05-28.
- Phase 4: production credential examples and env-gated provider integration
  tests. Planned; CI will not require live vendor accounts.

## Testing Strategy

- Shared core: unit tests for `McpVault`, signer delegation, serialization,
  and generic REST polling.
- Fireblocks: local `HttpServer` tests for auth headers/JWT payloads, account
  mapping, request body shape, success polling, failed statuses, poll timeout,
  HTTP errors, PEM parsing, and ServiceLoader discovery.
- Coinbase: local `HttpServer` tests for ECDSA header verification, wallet
  list decoding, request body shape, polling, failure modes, ServiceLoader.
- Live integration: env-gated only, using vendor sandbox/preprod credentials
  supplied by the developer or CI secret store.

## Open Questions

- Whether Fireblocks production usage should expose policy/approval metadata in
  a typed ScalaScript status model or keep it provider-specific.
- Whether provider adapters should share a common `MpcProviderPlugin` SPI once
  two or more adapters have landed.
- How much of provider account/public-key discovery can be normalized across
  vendors without losing important policy semantics.
