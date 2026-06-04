package scalascript.payments.swift

import scalascript.payments.bankrails.*
import java.time.Instant

/** Parses SWIFT GPI tracker webhook JSON events into typed BankRailsEvent values.
 *
 *  Supported event types (JSON "event" field):
 *    gpi.v4.credits.ValueDateChanged         → SwiftGpiAdvanced (ACSP hop)
 *    gpi.v4.credits.Completed                → SwiftPacs008Settled / SwiftMt103Booked
 *    gpi.v4.credits.CancellationCompleted    → SwiftRejected
 *    gpi.v4.credits.Rejected                 → SwiftRejected
 *
 *  See specs/international-bank-rails.md §7 SWIFT webhook events.
 */
object GpiTracker:

  /** Parse a GPI tracker webhook JSON payload into a BankRailsEvent.
   *
   *  @param json   raw JSON body from the GPI tracker webhook
   *  @param railKind  the rail kind for this tracker (SWIFT_MT103 or SWIFT_PACS008)
   *  @return       Right(event) or Left(error message)
   */
  def parseEvent(json: String, railKind: RailKind = RailKind.SWIFT_PACS008): Either[String, BankRailsEvent] =
    scala.util.Try(doParse(json, railKind)).toEither.left.map(_.getMessage)

  /** Parse a GPI hop list from a pacs.002 status update JSON.
   *  Returns one hop per "transactionStatusReport" entry.
   */
  def parseHops(json: String): List[GpiHop] =
    scala.util.Try(doParseHops(json)).getOrElse(Nil)

  // ── Internal parsing ────────────────────────────────────────────────────────

  private def doParse(json: String, railKind: RailKind): BankRailsEvent =
    val eventType = extractString(json, "event").orElse(extractString(json, "type")).getOrElse(
      throw new IllegalArgumentException("Missing 'event' or 'type' field in GPI webhook payload")
    )
    val uetr      = extractString(json, "uetr").getOrElse(
      throw new IllegalArgumentException("Missing 'uetr' field in GPI webhook payload")
    )
    eventType match
      case "gpi.v4.credits.ValueDateChanged" | "gpi.v4.credits.StatusUpdated" =>
        val hop = parseHopFromRoot(json)
        BankRailsEvent.SwiftGpiAdvanced(uetr, hop)

      case "gpi.v4.credits.Completed" | "gpi.v4.credits.Settled" =>
        val amount   = extractString(json, "amount").getOrElse("0")
        val currency = extractString(json, "currency").getOrElse("USD")
        if railKind == RailKind.SWIFT_MT103 then
          BankRailsEvent.SwiftMt103Booked(uetr, amount, currency)
        else
          BankRailsEvent.SwiftPacs008Settled(uetr, amount, currency)

      case "gpi.v4.credits.CancellationCompleted" | "gpi.v4.credits.Rejected" =>
        val statusCode = extractString(json, "statusCode").orElse(extractString(json, "status")).getOrElse("RJCT")
        val reason     = extractString(json, "reason").orElse(extractString(json, "reasonCode")).getOrElse("")
        BankRailsEvent.SwiftRejected(uetr, statusCode, reason)

      case other =>
        throw new IllegalArgumentException(s"Unknown GPI event type: $other")

  private def doParseHops(json: String): List[GpiHop] =
    // Extract repeated hop objects — simplified: look for agentBic/status pairs
    val bicPattern    = """"agentBic"\s*:\s*"([^"\\]*)"""".r
    val statusPattern = """"status"\s*:\s*"([^"\\]*)"""".r
    val tsPattern     = """"updatedAt"\s*:\s*"([^"\\]*)"""".r

    val bics      = bicPattern.findAllMatchIn(json).map(_.group(1)).toList
    val statuses  = statusPattern.findAllMatchIn(json).map(_.group(1)).toList
    val timestamps = tsPattern.findAllMatchIn(json).map(m =>
      scala.util.Try(Instant.parse(m.group(1))).getOrElse(Instant.now())
    ).toList

    bics.zipWithIndex.map { case (bic, i) =>
      GpiHop(
        agentBic  = bic,
        status    = statuses.lift(i).getOrElse("ACSP"),
        updatedAt = timestamps.lift(i).getOrElse(Instant.now()),
      )
    }

  private def parseHopFromRoot(json: String): GpiHop =
    val bic       = extractString(json, "agentBic").orElse(extractString(json, "bic")).getOrElse("UNKNOWN")
    val status    = extractString(json, "status").getOrElse("ACSP")
    val updatedAt = extractString(json, "updatedAt").flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
                      .getOrElse(Instant.now())
    GpiHop(
      agentBic  = bic,
      status    = status,
      updatedAt = updatedAt,
    )

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[swift] def extractString(json: String, key: String): Option[String] =
    val pattern = s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))
