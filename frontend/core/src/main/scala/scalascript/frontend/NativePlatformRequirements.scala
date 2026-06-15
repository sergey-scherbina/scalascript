package scalascript.frontend

/** Android <uses-feature> declaration required by a native package. */
final case class AndroidFeatureRequirement(name: String, required: Boolean)

/** Platform declarations implied by frontend/platform capabilities.
 *
 *  This is a packaging contract, not an intrinsic implementation. Native
 *  packagers can resolve a set of capabilities to target-specific manifest
 *  entries without text-scanning std modules or exposing platform APIs to
 *  `.ssc` user code.
 */
final case class NativePlatformRequirement(
    infoPlist:          Map[String, String] = Map.empty,
    entitlements:       Map[String, List[String]] = Map.empty,
    androidPermissions: Set[String] = Set.empty,
    androidFeatures:    List[AndroidFeatureRequirement] = Nil,
    webPermissions:     Set[String] = Set.empty,
    webRequirements:    Set[String] = Set.empty,
    notes:              List[String] = Nil
):
  def ++(other: NativePlatformRequirement): NativePlatformRequirement =
    NativePlatformRequirement(
      infoPlist = infoPlist ++ other.infoPlist,
      entitlements = mergeEntitlements(entitlements, other.entitlements),
      androidPermissions = androidPermissions ++ other.androidPermissions,
      androidFeatures = (androidFeatures ++ other.androidFeatures).distinct,
      webPermissions = webPermissions ++ other.webPermissions,
      webRequirements = webRequirements ++ other.webRequirements,
      notes = (notes ++ other.notes).distinct
    )

  private def mergeEntitlements(
      left:  Map[String, List[String]],
      right: Map[String, List[String]]
  ): Map[String, List[String]] =
    (left.keySet ++ right.keySet).map { key =>
      val merged = (left.getOrElse(key, Nil) ++ right.getOrElse(key, Nil)).distinct
      key -> merged
    }.toMap

object NativePlatformRequirements:
  val Empty: NativePlatformRequirement = NativePlatformRequirement()

  def forCapabilities(
      capabilities:        Set[Capability],
      platform:            Platform,
      androidNfcRequired:  Boolean = false
  ): NativePlatformRequirement =
    capabilities.foldLeft(Empty) { (acc, capability) =>
      acc ++ forCapability(capability, platform, androidNfcRequired)
    }

  def forCapability(
      capability:          Capability,
      platform:            Platform,
      androidNfcRequired:  Boolean = false
  ): NativePlatformRequirement =
    capability match
      case Capability.Geolocation =>
        geolocation(platform)
      case Capability.NfcNdef =>
        nfcNdef(platform, androidNfcRequired)
      case Capability.NfcTagTech =>
        nfcTagTech(platform, androidNfcRequired)
      case Capability.NfcCardEmulation =>
        nfcCardEmulation(platform, androidNfcRequired)
      case _ =>
        Empty

  private def geolocation(platform: Platform): NativePlatformRequirement =
    val ios =
      if includesIos(platform) then
        NativePlatformRequirement(
          infoPlist = Map(
            "NSLocationWhenInUseUsageDescription" ->
              "This app uses your location while it is open."
          )
        )
      else Empty
    val android =
      if includesAndroid(platform) then
        NativePlatformRequirement(
          androidPermissions = Set(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION"
          )
        )
      else Empty
    val macos =
      if includesMacos(platform) then
        NativePlatformRequirement(
          infoPlist = Map(
            "NSLocationUsageDescription" ->
              "This app uses your location while it is open."
          )
        )
      else Empty
    val web =
      if includesWeb(platform) then
        NativePlatformRequirement(webPermissions = Set("geolocation"))
      else Empty
    ios ++ android ++ macos ++ web

  private def nfcNdef(platform: Platform, androidRequired: Boolean): NativePlatformRequirement =
    val ios =
      if includesIos(platform) then
        NativePlatformRequirement(
          infoPlist = Map(
            "NFCReaderUsageDescription" ->
              "This app uses NFC to read and write nearby NDEF tags."
          ),
          entitlements = Map(
            "com.apple.developer.nfc.readersession.formats" -> List("NDEF")
          )
        )
      else Empty
    val android =
      if includesAndroid(platform) then
        NativePlatformRequirement(
          androidPermissions = Set("android.permission.NFC"),
          androidFeatures = List(AndroidFeatureRequirement("android.hardware.nfc", androidRequired))
        )
      else Empty
    val web =
      if includesWeb(platform) then
        NativePlatformRequirement(
          webPermissions = Set("nfc"),
          webRequirements = Set("secure-context", "top-level-context", "user-activation")
        )
      else Empty
    ios ++ android ++ web

  private def nfcTagTech(platform: Platform, androidRequired: Boolean): NativePlatformRequirement =
    val base = nfcNdef(platform, androidRequired)
    val iosTag =
      if includesIos(platform) then
        NativePlatformRequirement(
          entitlements = Map(
            "com.apple.developer.nfc.readersession.formats" -> List("TAG")
          )
        )
      else Empty
    base ++ iosTag

  private def nfcCardEmulation(platform: Platform, androidRequired: Boolean): NativePlatformRequirement =
    val android =
      if includesAndroid(platform) then
        NativePlatformRequirement(
          androidPermissions = Set("android.permission.NFC"),
          androidFeatures = List(
            AndroidFeatureRequirement("android.hardware.nfc", androidRequired),
            AndroidFeatureRequirement("android.hardware.nfc.hce", androidRequired)
          )
        )
      else Empty
    val ios =
      if includesIos(platform) then
        NativePlatformRequirement(
          notes = List(
            "iOS NFC/card-presentment requires Apple NFC & SE Platform approval and is not an ordinary reader-session entitlement."
          )
        )
      else Empty
    android ++ ios

  private def includesWeb(platform: Platform): Boolean =
    platform match
      case Platform.Web | Platform.All => true
      case _                           => false

  private def includesIos(platform: Platform): Boolean =
    platform match
      case Platform.Mobile(MobileOs.iOS | MobileOs.All) | Platform.All => true
      case _                                                           => false

  private def includesAndroid(platform: Platform): Boolean =
    platform match
      case Platform.Mobile(MobileOs.Android | MobileOs.All) | Platform.All => true
      case _                                                               => false

  private def includesMacos(platform: Platform): Boolean =
    platform match
      case Platform.Desktop(DesktopOs.MacOS | DesktopOs.All) | Platform.All => true
      case _                                                                => false
