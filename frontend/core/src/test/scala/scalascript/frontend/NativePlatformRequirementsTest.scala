package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite

class NativePlatformRequirementsTest extends AnyFunSuite:

  test("NFC NDEF iOS requirements include usage description and NDEF entitlement") {
    val req = NativePlatformRequirements.forCapability(
      Capability.NfcNdef,
      Platform.Mobile(MobileOs.iOS)
    )

    assert(req.infoPlist.contains("NFCReaderUsageDescription"))
    assert(req.entitlements("com.apple.developer.nfc.readersession.formats") == List("NDEF"))
    assert(req.androidPermissions.isEmpty)
  }

  test("NFC NDEF Android requirements include NFC permission and optional hardware feature") {
    val req = NativePlatformRequirements.forCapability(
      Capability.NfcNdef,
      Platform.Mobile(MobileOs.Android)
    )

    assert(req.androidPermissions == Set("android.permission.NFC"))
    assert(req.androidFeatures == List(AndroidFeatureRequirement("android.hardware.nfc", required = false)))
  }

  test("NFC NDEF Android hardware requirement can be made required by packager config") {
    val req = NativePlatformRequirements.forCapability(
      Capability.NfcNdef,
      Platform.Mobile(MobileOs.Android),
      androidNfcRequired = true
    )

    assert(req.androidFeatures == List(AndroidFeatureRequirement("android.hardware.nfc", required = true)))
  }

  test("Web NFC requirements are permission-model only, with no native manifest entries") {
    val req = NativePlatformRequirements.forCapability(Capability.NfcNdef, Platform.Web)

    assert(req.webPermissions == Set("nfc"))
    assert(req.webRequirements.contains("secure-context"))
    assert(req.webRequirements.contains("user-activation"))
    assert(req.infoPlist.isEmpty)
    assert(req.androidPermissions.isEmpty)
  }

  test("NFC tag-tech extends NDEF with the iOS TAG entitlement format") {
    val req = NativePlatformRequirements.forCapability(
      Capability.NfcTagTech,
      Platform.Mobile(MobileOs.iOS)
    )

    assert(req.entitlements("com.apple.developer.nfc.readersession.formats") == List("NDEF", "TAG"))
  }

  test("NFC card emulation records Android HCE and keeps iOS behind explicit approval") {
    val android = NativePlatformRequirements.forCapability(
      Capability.NfcCardEmulation,
      Platform.Mobile(MobileOs.Android)
    )
    val ios = NativePlatformRequirements.forCapability(
      Capability.NfcCardEmulation,
      Platform.Mobile(MobileOs.iOS)
    )

    assert(android.androidPermissions == Set("android.permission.NFC"))
    assert(android.androidFeatures.contains(AndroidFeatureRequirement("android.hardware.nfc.hce", required = false)))
    assert(ios.notes.exists(_.contains("Apple NFC & SE Platform approval")))
  }

  test("combined native requirements deduplicate shared declarations") {
    val req = NativePlatformRequirements.forCapabilities(
      Set(Capability.NfcNdef, Capability.NfcTagTech),
      Platform.Mobile(MobileOs.Android)
    )

    assert(req.androidPermissions == Set("android.permission.NFC"))
    assert(req.androidFeatures == List(AndroidFeatureRequirement("android.hardware.nfc", required = false)))
  }

  test("geolocation native requirements preserve the existing documented declarations") {
    val ios = NativePlatformRequirements.forCapability(
      Capability.Geolocation,
      Platform.Mobile(MobileOs.iOS)
    )
    val android = NativePlatformRequirements.forCapability(
      Capability.Geolocation,
      Platform.Mobile(MobileOs.Android)
    )

    assert(ios.infoPlist.contains("NSLocationWhenInUseUsageDescription"))
    assert(android.androidPermissions.contains("android.permission.ACCESS_FINE_LOCATION"))
    assert(android.androidPermissions.contains("android.permission.ACCESS_COARSE_LOCATION"))
  }
