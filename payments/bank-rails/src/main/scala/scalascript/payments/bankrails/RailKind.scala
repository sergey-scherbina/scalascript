package scalascript.payments.bankrails

/** The bank payment rail for a transfer or mandate.
 *  See docs/bank-rails.md §4.1 and docs/international-bank-rails.md §4.1. */
enum RailKind:
  case SEPA_CT    // SEPA Credit Transfer  — push, T+1
  case SEPA_DD    // SEPA Core Direct Debit — pull, T+2, mandate required
  case ACH_CREDIT // ACH Credit (push) — standard T+2 or same-day T+1
  case ACH_DEBIT  // ACH Debit (pull) — standard T+2 or same-day T+1, mandate required
  case PIX        // Pix instant (Brazil) — T+0, < 10 seconds
  case FEDNOW     // FedNow instant (US) — T+0, < 10 seconds
  // v1.55 additions
  case SCT_INST        // SEPA Instant Credit Transfer (SCT Inst) — T+0, < 10 seconds, TIPS/RT1
  case SWIFT_MT103     // SWIFT legacy ISO 15022 wire (deprecated, kept for aggregator compat)
  case SWIFT_PACS008   // SWIFT ISO 20022 CBPR+ pacs.008.001.10 (preferred for new integrations)
  case UK_FPS          // UK Faster Payments Service — T+0, < 2 seconds
  case UK_BACS_DD      // UK BACS Direct Debit (3-day cycle)
  case UK_CHAPS        // UK CHAPS same-day high-value RTGS (via aggregator)
  case IN_UPI          // India Unified Payments Interface (push + collect)
  case JP_ZENGIN       // Japan Zengin Data Telecommunication System
  case SG_PAYNOW       // Singapore PayNow (FAST + proxy resolution)
