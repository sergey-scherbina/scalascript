# ScalaScript Smart Contracts — Design Spec (DRAFT)

> Status: DRAFT — not implemented. This document explores the design space.
> Target backends: Cardano/Scalus (priority), WASM chains (future).
> Last updated: 2026-05-19.

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

## Open questions

1. **UTxO vs account model**: Cardano's UTxO model is fundamentally different from
   account-based chains (Near, EVM). How much abstraction should the `Contract` monad
   provide? Option A: expose UTxO natively for Cardano, account natively for others
   (chain-specific API). Option B: abstract both into a "state + caller + value" model
   and hide the UTxO/account distinction in the backend.

2. **Multi-validator contracts**: Cardano often uses multiple validators together
   (minting policy + spending validator). How to express this in a single `.ssc` file?

3. **Token standards**: ERC-20/ERC-721 equivalents. Define standard interfaces
   (`Fungible`, `NonFungible`) that contract authors implement.

4. **Upgradability**: Can a deployed contract be upgraded? Cardano validators are
   immutable by design; Near supports upgradeable contracts. How to surface this?

5. **Formal verification hooks**: Long-term — can the typer emit Lean 4 proof
   obligations for `require` conditions? Keep the design open for this.
