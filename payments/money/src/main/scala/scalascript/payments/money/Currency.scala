package scalascript.payments.money

opaque type Currency = String

object Currency:
  val USD: Currency = "USD"; val EUR: Currency = "EUR"; val GBP: Currency = "GBP"
  val CHF: Currency = "CHF"; val AUD: Currency = "AUD"; val CAD: Currency = "CAD"
  val JPY: Currency = "JPY"; val KRW: Currency = "KRW"; val VND: Currency = "VND"
  val KWD: Currency = "KWD"; val BHD: Currency = "BHD"; val OMR: Currency = "OMR"
  val BTC: Currency = "BTC"; val ETH: Currency = "ETH"
  val USDC: Currency = "USDC"; val ADA: Currency = "ADA"

  private val zeroDecimalCodes = Set(
    "BIF","CLP","DJF","GNF","JPY","KMF","KRW","MGA","PYG","RWF",
    "UGX","VND","VUV","XAF","XOF","XPF",
  )
  private val threeDecimalCodes = Set("BHD","JOD","KWD","OMR","TND")
  private val cryptoCodes       = Set("BTC","ETH","USDC","USDT","ADA","SOL","MATIC","BNB","AVAX","DOT")

  private val knownFiat: Set[String] = Set(
    "USD","EUR","GBP","CHF","AUD","CAD","NZD","SGD","HKD","DKK","NOK","SEK",
    "PLN","CZK","HUF","RON","TRY","ZAR","BRL","MXN","COP","PEN","ARS","INR",
    "CNY","THB","MYR","PHP","TWD","ILS",
  ) ++ zeroDecimalCodes ++ threeDecimalCodes

  def apply(code: String): Currency =
    val upper = code.toUpperCase
    if knownFiat.contains(upper) || cryptoCodes.contains(upper) then upper
    else throw IllegalArgumentException(s"Unknown currency code: $code")

  def minorUnitsPower(c: Currency): Int =
    val s: String = c
    if zeroDecimalCodes.contains(s)  then 0
    else if threeDecimalCodes.contains(s) then 3
    else if s == "BTC"               then 8
    else if s == "ETH"               then 18
    else 2

  def isFiat(c: Currency):   Boolean = knownFiat.contains(c: String)
  def isCrypto(c: Currency): Boolean = cryptoCodes.contains(c: String)

  extension (c: Currency) def code: String = c
