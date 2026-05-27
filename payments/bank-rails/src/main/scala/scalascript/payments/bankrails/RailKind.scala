package scalascript.payments.bankrails

/** The bank payment rail for a transfer or mandate.
 *  See docs/bank-rails.md §4.1. */
enum RailKind:
  case SEPA_CT   // SEPA Credit Transfer  — push, T+1
  case SEPA_DD   // SEPA Core Direct Debit — pull, T+2, mandate required
  case ACH_CREDIT // ACH Credit (push) — standard T+2 or same-day T+1
  case ACH_DEBIT  // ACH Debit (pull) — standard T+2 or same-day T+1, mandate required
  case PIX        // Pix instant (Brazil) — T+0, < 10 seconds
  case FEDNOW     // FedNow instant (US) — T+0, < 10 seconds
