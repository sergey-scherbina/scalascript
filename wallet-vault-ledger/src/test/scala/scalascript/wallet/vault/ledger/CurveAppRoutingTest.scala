package scalascript.wallet.vault.ledger

import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockchain.spi.ChainId
import scalascript.crypto.Curve

class CurveAppRoutingTest extends AnyFunSuite:

  test("EVM chains route to the Ethereum app"):
    assert(CurveAppRouting.appFor(Curve.Secp256k1, ChainId.EthereumMainnet) == Some("Ethereum"))
    assert(CurveAppRouting.appFor(Curve.Secp256k1, ChainId.Base)            == Some("Ethereum"))
    assert(CurveAppRouting.appFor(Curve.Secp256k1, ChainId.Polygon)         == Some("Ethereum"))
    assert(CurveAppRouting.appFor(Curve.Secp256k1, ChainId.Arbitrum)        == Some("Ethereum"))

  test("Solana ed25519 routes to the Solana app"):
    val solChain = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w")
    assert(CurveAppRouting.appFor(Curve.Ed25519, solChain) == Some("Solana"))

  test("Cardano routes to the Cardano app"):
    assert(CurveAppRouting.appFor(Curve.Ed25519, ChainId.CardanoMainnet) == Some("Cardano ADA"))

  test("Bitcoin secp256k1 routes to the Bitcoin app"):
    val btcChain = ChainId("bip122:000000000019d6689c085ae165831e93")
    assert(CurveAppRouting.appFor(Curve.Secp256k1, btcChain) == Some("Bitcoin"))

  test("unsupported pairs return None"):
    assert(CurveAppRouting.appFor(Curve.Sr25519, "eip155") == None)
    assert(CurveAppRouting.appFor(Curve.P256,    "eip155") == None)

  test("appForPath recognises BIP-44 coin types"):
    assert(CurveAppRouting.appForPath(Curve.Secp256k1, "m/44'/60'/0'/0/0")  == Some("Ethereum"))
    assert(CurveAppRouting.appForPath(Curve.Secp256k1, "m/44'/0'/0'/0/0")   == Some("Bitcoin"))
    assert(CurveAppRouting.appForPath(Curve.Ed25519,   "m/44'/501'/0'/0'")  == Some("Solana"))
    assert(CurveAppRouting.appForPath(Curve.Ed25519,   "m/1852'/1815'/0'/0/0") == Some("Cardano ADA"))
    assert(CurveAppRouting.appForPath(Curve.Secp256k1, "m/0/0") == None)
