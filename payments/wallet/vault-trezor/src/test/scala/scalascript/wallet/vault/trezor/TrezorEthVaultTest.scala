package scalascript.wallet.vault.trezor

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scalascript.crypto.Curve
import scalascript.wallet.spi.{UnlockCredential, VaultKind}
import ExecutionContext.Implicits.global

class TrezorEthVaultTest extends AnyFunSuite:

  private def defaultBridge: MockTrezorBridge =
    val b = MockTrezorBridge()
    b.enqueueFeatures(initialized = true, pinCached = true)
    b

  private val pubKeyHex = "02" + "ab" * 32
  private val sigHex    = "aa" * 65

  test("kind is Hardware") {
    val vault = TrezorEthVault(MockTrezorBridge())
    assert(vault.kind == VaultKind.Hardware)
  }

  test("isLocked is true before unlock") {
    val vault = TrezorEthVault(MockTrezorBridge())
    assert(vault.isLocked)
  }

  test("unlock: no device → Future failure") {
    val emptyBridge = new MockTrezorBridge:
      override def enumerate() = scala.concurrent.Future.successful(Seq.empty)

    val vault = TrezorEthVault(emptyBridge)
    val ex = intercept[RuntimeException] {
      Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    }
    assert(ex.getMessage.contains("No Trezor device"))
  }

  test("unlock: success → isLocked becomes false") {
    val bridge = defaultBridge
    val vault  = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    assert(!vault.isLocked)
  }

  test("unlock: Failure response → future failed with TrezorDeviceFailure") {
    val bridge = MockTrezorBridge()
    bridge.enqueueFailure(2, "Device busy")
    val vault = TrezorEthVault(bridge)
    val ex = intercept[TrezorDeviceFailure] {
      Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    }
    assert(ex.message == "Device busy")
  }

  test("lock: releases session") {
    val bridge = defaultBridge
    val vault  = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    vault.lock()
    assert(vault.isLocked)
  }

  test("listAccounts: returns default Ethereum account") {
    val vault    = TrezorEthVault(MockTrezorBridge())
    val accounts = Await.result(vault.listAccounts(), 5.seconds)
    assert(accounts.size == 1)
    assert(accounts.head.derivationPath == Bip32.DefaultEthereum)
  }

  test("getSigner: Secp256k1 → returns TrezorEthSigner with correct publicKey") {
    val bridge = defaultBridge
    bridge.enqueuePublicKey("xpub6test", pubKeyHex)
    val vault = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, Bip32.DefaultEthereum), 5.seconds)
    assert(signer.curve == Curve.Secp256k1)
    assert(signer.publicKey.bytes.sameElements(
      pubKeyHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    ))
  }

  test("getSigner: wrong curve → Future failure") {
    val bridge = defaultBridge
    val vault  = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    val ex = intercept[UnsupportedOperationException] {
      Await.result(vault.getSigner(Curve.Ed25519, Bip32.DefaultEthereum), 5.seconds)
    }
    assert(ex.getMessage.contains("Secp256k1"))
  }

  test("getSigner: locked vault → Future failure") {
    val vault = TrezorEthVault(MockTrezorBridge())
    val ex = intercept[IllegalStateException] {
      Await.result(vault.getSigner(Curve.Secp256k1, Bip32.DefaultEthereum), 5.seconds)
    }
    assert(ex.getMessage.contains("locked"))
  }

  test("sign: returns signature bytes from bridge response") {
    val bridge = defaultBridge
    bridge.enqueuePublicKey("xpub6test", pubKeyHex)
    bridge.enqueueEthSignature("0xDeAdBeEf", sigHex)
    val vault = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, Bip32.DefaultEthereum), 5.seconds)
    val sig = Await.result(signer.sign("test message".getBytes("UTF-8")), 5.seconds)
    val expected = sigHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    assert(sig.sameElements(expected))
  }

  test("sign: Failure from device → TrezorDeviceFailure") {
    val bridge = defaultBridge
    bridge.enqueuePublicKey("xpub6test", pubKeyHex)
    // Queue failure for sign call
    bridge.enqueueResponse(
      TrezorMessageType.EthereumSignMessage,
      TrezorMessageType.Failure,
      ujson.Obj("code" -> ujson.Num(3), "message" -> ujson.Str("User rejected")),
    )
    val vault = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, Bip32.DefaultEthereum), 5.seconds)
    val ex = intercept[TrezorDeviceFailure] {
      Await.result(signer.sign("test".getBytes), 5.seconds)
    }
    assert(ex.message == "User rejected")
  }

  test("ButtonRequest auto-ack: Initialize followed by ButtonRequest then Features") {
    val bridge = MockTrezorBridge()
    // Push ButtonRequest first, then Features
    bridge.enqueueResponse(
      TrezorMessageType.Initialize, TrezorMessageType.ButtonRequest, ujson.Obj("code" -> ujson.Num(8)),
    )
    bridge.enqueueFeatures()
    // ButtonAck response
    bridge.enqueueResponse(
      TrezorMessageType.ButtonAck, TrezorMessageType.Features, ujson.Obj(
        "initialized" -> ujson.Bool(true), "pin_cached" -> ujson.Bool(true),
      ),
    )
    val vault = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    assert(!vault.isLocked)
    // Verify ButtonAck was sent
    assert(bridge.calls.exists(_.messageType == TrezorMessageType.ButtonAck))
  }

  test("getSigner: address_n correctly encodes BIP-32 path") {
    val bridge = defaultBridge
    bridge.enqueuePublicKey("xpub6test", pubKeyHex)
    val vault = TrezorEthVault(bridge)
    Await.result(vault.unlock(UnlockCredential.None), 5.seconds)
    Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 5.seconds)
    val getKeyCall = bridge.calls.find(_.messageType == TrezorMessageType.GetPublicKey).get
    val addressN   = getKeyCall.message.obj("address_n").arr.map(_.num.toInt)
    assert(addressN.length == 5)
    assert(addressN(0) == (44 | Bip32.Hardened))
    assert(addressN(1) == (60 | Bip32.Hardened))
    assert(addressN(3) == 0)
    assert(addressN(4) == 0)
  }
