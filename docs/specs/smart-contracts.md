# ScalaScript Smart Contracts — Design Spec (DRAFT)

> Status: DRAFT — not implemented. This document explores the design space.
> Target backends: Cardano/Scalus (priority), WASM chains (future).
> Last updated: 2026-05-19.
> Decision: both UTxO (Cardano) and account (Near, EVM) models are supported
> via platform sub-packages in `std/contracts/`. Local simulation for both.

---

## Goals

1. **Safe by default** — contract code cannot accidentally call I/O, mutate global
   state, or do anything non-deterministic. The type system enforces this statically.
2. **Easy to write** — feels like normal ScalaScript. No new language, no new
   mental model. `@state`, `@view`, `@call` annotations are the only contract-specific
   additions.
3. **Debuggable locally** — `ssc run token.ssc` runs the contract in a local
   simulator. No blockchain node needed for development.
4. **Blockchain-independent API** — the same contract source compiles to Cardano,
   Near, or other chains by changing a single front-matter field (`chain: cardano`
   vs `chain: near`). Chain-specific quirks are hidden in the backend.
5. **Intrinsics for chain capabilities** — things that differ per chain
   (crypto primitives, transaction context, UTxO vs account model) are exposed
   as typed intrinsics that the contract can call, with a simulated version for
   local testing.

---

## Module manifest

A contract module declares itself via front-matter:

```yaml
---
name: token
kind: contract          # marks this as a contract module
chain: cardano          # target chain; controls which backend is used
version: 1.0.0
description: "Fungible token contract"
---
```

Supported `chain` values (planned):

| Value | Backend | Status |
|-------|---------|--------|
| `cardano` | Scalus (JVM → UPLC) | planned |
| `near` | WASM backend + Near SDK | future |
| `polkadot` | WASM backend + ink! | future |
| `evm` | EVM backend | future |

---

## Contract structure

A contract has three kinds of declarations:

### 1. `@state` — persistent storage type

```scalascript
@state
case class TokenState(
  balances:    Map[String, Int],
  totalSupply: Int,
  owner:       String
)
```

Exactly one `@state` type per contract. The runtime persists it between calls.
Immutable — every `@call` function returns a new state value.

### 2. `@view` — read-only query

```scalascript
@view
def balanceOf(account: String): Int =
  State.get[TokenState].balances.getOrElse(account, 0)

@view
def totalSupply: Int =
  State.get[TokenState].totalSupply
```

- May read `State`, `Tx` context, and chain intrinsics.
- May NOT mutate state or emit events.
- Called off-chain for free (no gas).

### 3. `@call` — state-mutating transaction

```scalascript
@call
def transfer(to: String, amount: Int): Unit =
  direct[Contract[TokenState]] {
    val s   = State.get[TokenState].!
    val from = Tx.caller.!
    require(s.balances.getOrElse(from, 0) >= amount, "insufficient balance")
    val newBalances = s.balances
      .updated(from, s.balances(from) - amount)
      .updated(to, s.balances.getOrElse(to, 0) + amount)
    State.set(s.copy(balances = newBalances)).!
    Event.emit(Transfer(from, to, amount)).!
  }
```

- Uses `direct[Contract[TokenState]]` — the existing do-notation over the contract monad.
- `State.get` / `State.set` — read/write the persistent state.
- `Tx.caller` — the address that initiated the transaction (chain intrinsic).
- `Event.emit` — emit an on-chain event (log entry).
- `require(cond, msg)` — abort the transaction if the condition fails.

---

## The `Contract[S]` monad

The contract monad is the execution context for `@call` functions:

```scalascript
// Conceptually (not user-written — built into the runtime):
type Contract[S] = State[S] & Tx & Events & Effect.Pure
```

- `State[S]` — read/write the persistent state of type `S`
- `Tx` — access transaction context (caller, value sent, block number, etc.)
- `Events` — emit typed events
- `Effect.Pure` — NO I/O, NO randomness, NO external calls (except approved intrinsics)

The `Effect.Pure` constraint is enforced at compile time. A `@call` function
that tries to call `println` or `readFile` is a type error.

---

## Chain intrinsics

Things that differ per chain are exposed as typed intrinsics.
Each intrinsic has a **local simulator** implementation used by `ssc run`.

### Transaction context (`Tx`)

```scalascript
Tx.caller: String          // address of the transaction sender
Tx.value: Int              // coins/tokens sent with the transaction
Tx.blockNumber: Long       // current block height
Tx.timestamp: Long         // block timestamp (Unix seconds)
Tx.txHash: String          // current transaction hash
```

### Crypto primitives

```scalascript
Crypto.sha256(data: Bytes): Bytes
Crypto.verify(sig: Bytes, msg: Bytes, pubKey: Bytes): Boolean
Crypto.hash(data: String): String    // chain-appropriate hash
```

### Cross-contract calls (advanced)

```scalascript
Contract.call[R](address: String, method: String, args: Any*): R
```

Typed cross-contract calls. The callee's interface must be imported
as a `.scim` artifact. Reentrancy guard is automatically applied.

### Cardano-specific (via Scalus)

```scalascript
Cardano.datum: Data              // UTxO datum
Cardano.redeemer: Data           // spending redeemer
Cardano.ownHash: ScriptHash      // this validator's hash
Cardano.txInfo: TxInfo           // full Plutus TxInfo
```

Cardano uses the UTxO model — the `@call` / `@view` split maps to
validator / minting policy / staking credential contexts.

---

## Events

Typed events emitted from `@call` functions:

```scalascript
case class Transfer(from: String, to: String, amount: Int)
case class Approval(owner: String, spender: String, amount: Int)

@call
def approve(spender: String, amount: Int): Unit =
  direct[Contract[TokenState]] {
    val owner = Tx.caller.!
    Event.emit(Approval(owner, spender, amount)).!
    // update state...
  }
```

Events are indexed by the chain and queryable off-chain.
The backend serializes them to the chain's native log format.

---

## Error handling

```scalascript
@call
def withdraw(amount: Int): Unit =
  direct[Contract[TokenState]] {
    val s = State.get[TokenState].!
    // require aborts the whole transaction — no partial state update
    require(amount > 0,               "amount must be positive")
    require(amount <= s.totalSupply,  "amount exceeds supply")
    // ...
  }
```

- `require(cond, msg)` — revert the transaction with a message.
- `ContractError(msg)` — explicit error value for more complex logic.
- All state changes in a failed transaction are rolled back atomically.
- No partial state is ever committed.

---

## Local development and testing

### Run locally

```bash
ssc run token.ssc             # simulate the contract locally
```

The local simulator:
- Provides fake `Tx.caller`, `Tx.blockNumber`, etc.
- Keeps state in memory between calls.
- Prints events to stdout.
- Shows final state after each call.

### Unit tests

```scalascript
---
name: token-test
kind: contract-test
target: token
---

# Token Tests

```scalascript
import [TokenState, transfer, balanceOf](token.ssc)

test("transfer reduces sender balance"):
  val initial = TokenState(
    balances    = Map("alice" -> 100, "bob" -> 0),
    totalSupply = 100,
    owner       = "alice"
  )
  val final = simulateCall(transfer("bob", 40), caller = "alice", state = initial)
  assert(final.balances("alice") == 60)
  assert(final.balances("bob")   == 40)

test("transfer fails on insufficient balance"):
  assertReverts(
    transfer("bob", 999),
    caller = "alice",
    state  = TokenState(Map("alice" -> 10), 10, "alice"),
    msg    = "insufficient balance"
  )
```

`simulateCall` and `assertReverts` are built-in test intrinsics — no blockchain needed.

### Debug mode

```bash
ssc debug token.ssc           # step through contract execution
ssc profile token.ssc         # profile gas usage per function
```

`ssc debug` uses the existing DAP debugger infrastructure.
`ssc profile` uses the existing `ssc profile` command, extended to report
simulated gas costs per call.

---

## Deployment

```bash
ssc compile token.ssc --chain cardano -o token.uplc
ssc deploy token.uplc --network preview --signing-key payment.skey
```

Or for testing:

```bash
ssc deploy token.uplc --network local     # local cardano-node / ogmios
```

### Integration with wallet-spi + blockchain-spi

The `ssc deploy` and `ssc invoke` CLIs are thin orchestrations over
the runtime SPIs:

```
.ssc source
    │
    │  ssc compile  (this spec — authoring stack)
    ▼
chain-native bytecode (UPLC / WASM / EVM / …)
    │
    │  ssc deploy  ─────────────────────────────────────────┐
    │                                                       │
    │  builds:  TxIntent.Deploy(bytecode, args, salt?)      │
    │  signs:   wallet-spi.AccountStrategy.signTransaction  │  see
    │  sends:   blockchain-spi.ChainAdapter.broadcast       │  docs/
    │  yields:  deployed address                            │  wallet-spi.md
    │                                                       │  docs/
    ▼                                                       │  blockchain-spi.md
deployed contract                                           │
    │                                                       │
    │  ssc invoke / app code  ──────────────────────────────┤
    │                                                       │
    │  read:    ChainAdapter.call(addr, calldata)           │
    │  write:   TxIntent.ContractCall(addr, calldata, val)  │
    │  off-chain sign:  ChainAdapter.typedDataDigest(...)   │
    │                                                       │
    ▼                                                       │
host application (wallet UI, agent, x402 facilitator, …)    │
                                                            │
                                                  signs via │
                                                            ▼
                                                  Vault     (encrypted /
                                                             passkey / MPC /
                                                             Ledger hardware)
```

The CLI doesn't reimplement signing or RPC — it composes the
existing wallet + blockchain stack. The signing key flag
(`--signing-key payment.skey`) corresponds to a
`wallet-vault-encrypted` instance unlocked with the file's
password; you can equivalently pass `--wallet ledger` to use a
connected hardware device, or `--wallet mpc=https://provider`
for a managed-key provider.

### Clear-signing metadata (hardware wallets)

The contract authoring stack emits, **at compile time**, a
clear-signing descriptor alongside the bytecode:

```
token.uplc
token.clearsigning.json    ← parameter names + display labels per @call
```

When a Ledger wallet (or other clear-signing-capable hardware)
signs a transaction that invokes the deployed contract, the host
software passes the descriptor along with the calldata. The
device decodes parameters natively and shows the user "transfer
500.00 TOKEN to alice.eth" rather than "Sign opaque blob 0xa9059cbb...".

This makes contracts authored in ScalaScript automatically
compatible with hardware-wallet clear-signing — without the
contract author shipping a separate clear-signing PR to Ledger /
Trezor registries (though they can if they want broader
recognition; the on-device app accepts the descriptor at runtime
when the host provides it).

Integration spec details: [`docs/specs/wallet-spi.md`](wallet-spi.md)
§5.1 (Ledger transport + app routing) and
[`docs/specs/blockchain-spi.md`](blockchain-spi.md) §6.1 (smart contract
interaction primitives).

### .ssc app interacts with .ssc contract

If both the contract and the consuming application are written in
ScalaScript, the import mechanism gives the application a typed
interface to the contract at compile time:

```scalascript
---
name: my-app
---

# Import the contract module — produces a typed proxy

[token](./token.ssc)

# Call `@view` methods directly

```scala
val balance = token.balanceOf(myAddress)    // lowers to ChainAdapter.call
```

# Build a transaction for `@call` methods

```scala
val intent = token.transfer.intent(to = bobAddress, amount = 100)
val signed = await(wallet.signTransaction(chain)(intent))
await(chain.broadcast(signed))
```
```

No ABI JSON glue, no manual encoding. The same type system that
checked the contract's source also checks the app's calls to it.

---

## Security model

### What the type system prevents

- **Non-determinism**: `Async`, `IO`, `Random`, file I/O — all excluded from the
  `Contract` monad by the effect system.
- **Reentrancy**: cross-contract calls are wrapped in an automatic reentrancy guard
  (state is snapshotted before the call; reverted on reentrancy detection).
- **Integer overflow**: `Int` operations in `Effect.Pure` context use checked
  arithmetic by default (overflow → transaction revert).
- **Uninitialized state**: `@state` type must have a defined `initialState` value
  or the contract won't compile.

### What requires auditing (not preventable statically)

- **Business logic errors** (wrong amounts, wrong access control)
- **Oracle manipulation** (feeding bad external data)
- **Economic attacks** (flash loans, sandwich attacks on AMMs)

These require unit tests + formal verification (future: Lean 4 proof obligations).

---

## Comparison with existing approaches

| Feature | Solidity | Plutus | Scalus | ScalaScript contracts |
|---------|----------|--------|--------|----------------------|
| Language | JS-like | Haskell | Scala 3 | ScalaScript |
| Type safety | Medium | High | High | High |
| Effect enforcement | None | Manual | Manual | **Automatic (effect system)** |
| Local testing | Hardhat | plutus-simple-model | Scalus simulator | **`ssc run` / `ssc debug`** |
| Debugging | Remix | Difficult | Moderate | **DAP debugger** |
| Multi-chain | EVM only | Cardano only | Cardano only | **Planned: Cardano + WASM chains** |
| Learning curve | Low | Very high | Medium | **Low (familiar ScalaScript)** |

---

## Execution models — UTxO and Account

**Decision (2026-05-19):** Support both models. Do not hide the distinction —
surface both via platform sub-packages. Contracts can be written against the
chain-agnostic `std/contracts/core` API, or use platform packages for
chain-specific features. Both models are simulatable locally.

### The two models

| | UTxO (Cardano) | Account (Near, EVM) |
|-|---------------|---------------------|
| State lives in | UTxOs locked at script address | Account storage keyed by address |
| Transaction | Consumes inputs, produces outputs | Mutates account state in-place |
| Validator role | "Can this UTxO be spent?" | "Run this function, update state" |
| Concurrency | Parallel (no shared mutable state) | Sequential per account |
| Main advantage | Predictable, parallelizable | Simpler mental model |

### Standard library layout

```
std/
  contracts/
    core.ssc          # chain-agnostic: State, Event, require, Contract monad
    cardano.ssc       # UTxO model: Cardano.*, TxInfo, Datum, Redeemer, ScriptHash
    near.ssc          # account model: Near.*, Promise, Gas, StorageUsage
    evm.ssc           # account model: Evm.*, Solidity-compat events, ABI encoding
    simulate.ssc      # local simulator intrinsics: simulateCall, assertReverts, etc.
    token.ssc         # std Fungible / NonFungible interfaces (chain-agnostic)
```

A contract imports what it needs:

```scalascript
import [State, require, Event, Contract](std/contracts/core.ssc)
// chain-agnostic contract — compiles to any chain

import [State, require, Event, Contract](std/contracts/core.ssc)
import [Cardano, TxInfo, Datum](std/contracts/cardano.ssc)
// Cardano-specific contract — uses UTxO intrinsics
```

### Chain-agnostic contracts (account-style abstraction)

For most contracts (token, auction, voting), the account model abstraction works
on both UTxO and account chains. The backend maps it:

```scalascript
// Works on Near, EVM, AND Cardano (Scalus wraps it in a spending validator)
@state
case class TokenState(balances: Map[String, Int], totalSupply: Int)

@call
def transfer(to: String, amount: Int): Unit =
  direct[Contract[TokenState]] {
    val from = Tx.caller.!
    val s    = State.get[TokenState].!
    require(s.balances.getOrElse(from, 0) >= amount, "insufficient")
    State.set(s.copy(balances =
      s.balances.updated(from, s.balances(from) - amount)
               .updated(to, s.balances.getOrElse(to, 0) + amount))).!
  }
```

On Cardano, `Tx.caller` maps to the first required signer in `TxInfo.signatories`.
On Near, it maps to `env::predecessor_account_id()`.
On EVM, it maps to `msg.sender`.

### UTxO-native contracts (Cardano-specific)

For contracts that need full UTxO power (multi-asset, reference inputs,
inline datums, Plutus V3 features):

```scalascript
import [Cardano, TxInfo, ScriptHash, Value](std/contracts/cardano.ssc)

// A spending validator: "can this UTxO be spent?"
@validator
def spend(datum: MyDatum, redeemer: MyRedeemer, ctx: TxInfo): Boolean =
  val sigs = ctx.signatories
  redeemer match
    case Withdraw(amount) =>
      sigs.contains(datum.owner) && amount <= datum.locked
    case Close =>
      sigs.contains(datum.owner)

// A minting policy: "can these tokens be minted/burned?"
@mintingPolicy
def mint(redeemer: MintAction, ctx: TxInfo): Boolean =
  redeemer match
    case MintTokens(n) => n > 0 && ctx.signatories.contains(adminKey)
    case BurnTokens(n) => true
```

- `@validator` replaces `@call` for UTxO-native contracts
- `datum` and `redeemer` are typed (serialized via Scalus's `Data` encoding)
- Returns `Boolean` — the validator either approves or rejects the spend
- No `State.get/set` — UTxO state is explicit in datum/outputs

### Local simulation

Both models are simulatable locally without a blockchain node:

```scalascript
import [simulateAccount, simulateUTxO, assertReverts](std/contracts/simulate.ssc)

// Simulate account model
test("token transfer"):
  val sim = simulateAccount[TokenState](
    initial  = TokenState(Map("alice" -> 100), 100),
    network  = Network.local
  )
  sim.call(transfer("bob", 40), caller = "alice")
  assert(sim.state.balances("alice") == 60)

// Simulate UTxO model
test("spending validator"):
  val utxo = UTxO(datum = MyDatum(owner = "alice", locked = 50))
  val ctx  = TxInfo.mock(signatories = List("alice"))
  assert(spend(utxo.datum, Withdraw(30), ctx) == true)
  assert(spend(utxo.datum, Withdraw(999), ctx) == false)
```

The simulator tracks:
- **Account model**: account state map, caller, attached value, block number, events log
- **UTxO model**: UTxO set, transaction inputs/outputs, redeemer, signatories

`ssc run token.ssc` starts an interactive REPL against the local simulator:

```
$ ssc run token.ssc --model account
Contract: token  Chain: local-simulator  Model: account
> call transfer("bob", 40) as "alice"
  ✓ Transfer("alice", "bob", 40) emitted
  State: TokenState(balances = Map(alice -> 60, bob -> 40), totalSupply = 100)
> view balanceOf("alice")
  60
> ^C
```

### Platform intrinsics summary

| Intrinsic | core | cardano | near | evm |
|-----------|------|---------|------|-----|
| `Tx.caller` | ✓ | (mapped) | (mapped) | (mapped) |
| `Tx.value` | ✓ | (mapped) | (mapped) | (mapped) |
| `Tx.blockNumber` | ✓ | (mapped) | (mapped) | (mapped) |
| `Cardano.datum` | — | ✓ | — | — |
| `Cardano.redeemer` | — | ✓ | — | — |
| `Cardano.txInfo` | — | ✓ | — | — |
| `Near.promise` | — | — | ✓ | — |
| `Near.storageUsage` | — | — | ✓ | — |
| `Evm.gasLeft` | — | — | — | ✓ |
| `Evm.abiEncode` | — | — | — | ✓ |
| `Crypto.sha256` | ✓ | ✓ | ✓ | ✓ |
| `Crypto.verify` | ✓ | ✓ | ✓ | ✓ |

---

## Open questions

1. **Multi-validator contracts**: Cardano often uses multiple validators together
   (minting policy + spending validator). How to express this in a single `.ssc` file?
   Candidate: multiple `@validator` / `@mintingPolicy` annotations in one module,
   each compiled to a separate UPLC script with a shared datum type.

2. **Token standards**: ERC-20/ERC-721 equivalents. Define standard interfaces
   (`Fungible`, `NonFungible`) in `std/contracts/token.ssc` that contract authors
   implement. Chain backends enforce the correct serialization.

3. **Upgradability**: Cardano validators are immutable by design. Near supports
   upgradeable contracts via a privileged `upgrade` call. How to surface this?
   Candidate: `@upgradeable` annotation on the `@state` type — only valid for
   chains that support it; compile error on Cardano.

4. **Cross-chain contracts**: A contract that spans Cardano + Near (e.g., a bridge).
   Out of scope for now — document as a non-goal.

5. **Formal verification hooks**: Long-term — can the typer emit Lean 4 proof
   obligations for `require` conditions and validator return values?
   Keep the design open for this — don't encode anything that blocks it.
