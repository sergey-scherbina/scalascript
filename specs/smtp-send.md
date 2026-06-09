# Native `smtpSend` extern

**Status**: spec — feature. Requested by busi (email delivery), 2026-06-09.
**Priority**: medium. Workaround exists (HTTP relay), so this removes an external
dependency rather than unblocking a hard requirement.
**Pairs with**: [`pdf-mime-generation.md`](pdf-mime-generation.md) (MIME assembly).

## 1  Motivation

busi delivers invoices by email. Today it has no native SMTP capability:
JavaMail / `jakarta.mail` is not exposed as a ScalaScript extern, so live email
goes through an HTTP relay (`BUSI_EMAIL_HTTP_URL` / `BUSI_EMAIL_API_KEY`). For a
JVM-hosted app this is an avoidable moving part — direct SMTP submission is a
standard capability.

## 2  Proposed surface (`runtime/std/smtp.ssc`)

```scalascript
// Submit a pre-assembled RFC 5322 message over SMTP with STARTTLS/SMTPS.
//   message: full message text (see buildMimeMessage in pdf-mime-generation.md)
// Returns the server's final reply line on success; throws/returns an error
// descriptor on permanent failure (4xx/5xx).
extern def smtpSend(
  host: String, port: Int,
  username: String, password: String,
  useTls: Boolean,
  envelopeFrom: String, envelopeTo: List[String],
  message: String
): String
```

Auth is plain LOGIN/PLAIN over TLS (the common case for transactional providers
— SendGrid, Mailgun SMTP, Postmark, Gmail app-passwords). OAuth2/XOAUTH2 is a
future follow-up.

## 3  Implementation plan

- JVM/interpreter: `jakarta.mail` `Transport.send` or a thin SMTP client
  (EHLO → STARTTLS → AUTH → MAIL FROM → RCPT TO → DATA). Ship as
  `smtp-plugin.sscpkg` (opt-in, parallels crypto/pdf plugins) so the dependency
  is not forced on programs that don't send mail.
- Errors: distinguish transient (4xx) from permanent (5xx) in the returned
  descriptor so busi's existing send/retry audit can classify them.
- JS backend: out of scope (no raw sockets in browser); JVM-only.

## 4  Behavior checklist

- [ ] `smtpSend` to a local test SMTP sink (e.g. an in-test server) delivers the message.
- [ ] STARTTLS path negotiates TLS before AUTH.
- [ ] Auth failure returns a permanent-error descriptor, no hang.
- [ ] Multiple RCPT TO recipients all receive the message.
- [ ] Malformed host/port returns an error, does not crash the interpreter.

## 5  Verification

`SmtpSendTest` against an embedded test SMTP server (GreenMail or a minimal
in-process listener): assert delivery, TLS, multi-recipient, and auth-failure
classification. busi re-points its email path at `smtpSend` and keeps the HTTP
relay as an optional alternative.

## 6  busi context

busi Phase 61 (email delivery, currently HTTP-relay only). With `smtpSend` +
`buildMimeMessage`, busi can deliver invoices with PDF attachments using only a
provider's SMTP credentials, no relay service.
