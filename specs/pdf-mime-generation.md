# Native PDF generation + MIME assembly externs

**Status**: PDF (`std.pdf`) ✓ + MIME (`std.mime.buildMimeMessage`) ✓ Landed 2026-06-10 (JVM). `smtpSend` is the remaining slice. Requested by busi (invoice PDF + email), 2026-06-09.
**Priority**: medium. Workaround exists (HTTP relay), so this is a
self-containment / fewer-moving-parts improvement, not a hard blocker.
**Related**: there is a separate `std.pdf` PDF→Markdown *reader* task in SPRINT.md;
this spec is the inverse direction (generation) plus MIME, and is independent.

## 1  Motivation

busi issues invoices and emails them. Today:
- **PDF**: invoices render as browser-printable HTML (zero-config) or get a PDF
  attachment via an external HTTP relay (`BUSI_PDF_HTTP_URL`). There is no
  in-process way to produce PDF bytes from `.ssc`.
- **Email attachments**: multipart/MIME assembly is delegated to the email relay;
  busi cannot build a signed multipart body itself.

Both force an external service dependency for what is otherwise a self-contained
JVM-hosted app. A native extern removes the relay from the critical path for
operators who do not want to run one.

## 2  Proposed surface

```scalascript
// --- PDF generation (runtime/std/pdf.ssc) ---
// Render a subset of HTML (the invoice template surface) to PDF bytes (base64).
extern def htmlToPdfBase64(html: String): String

// Lower-level: draw from a structured page model if HTML coverage is too broad.
//   (deferred to a follow-up if htmlToPdf coverage is sufficient for invoices)

// --- MIME assembly (runtime/std/mime.ssc) ---
// Build a multipart/mixed MIME message with a text/html body and N attachments.
// attachments: List of (filename, mimeType, contentBase64).
extern def buildMimeMessage(
  from: String, to: String, subject: String,
  htmlBody: String,
  attachments: List[(String, String, String)]
): String  // full RFC 5322 message text, ready for SMTP DATA
```

### 2.1  Drop-in for busi's existing relay contract

`htmlToPdfBase64(html) -> base64 PDF` deliberately mirrors busi's current PDF
relay contract (`POST {"html":...} -> {"pdf_base64":...}`). busi already produces
the complete HTML; matching this signature means zero busi rewiring — just swap
the relay call for the extern. **The PDF half is the priority**;
`buildMimeMessage` / `smtpSend` can be a later slice.

### 2.2  Required HTML/CSS subset (busi invoice template — the whole need)

This is the exact surface the busi invoice uses. Covering it covers 100% of the
case; nothing beyond it is required for the first slice.

**Required:**
- Single-page **A4** with page margins; graceful overflow to a 2nd page (no
  pagination *control* needed — `@page` / `page-break-*` are nice-to-have, not required).
- Elements: `html, head, meta, title, style, body, div, p, h1–h3, strong, br`.
- **Tables are the layout**: `table, thead, tbody, tr, th, td` (invoice line items).
- CSS: `font-family / font-size / font-weight`, `margin`, `padding`, `border`
  (incl. table cell borders), `background-color`, `width`, one `@media print` block.

**Not required (now):**
- JavaScript, images / SVG / data-URI, web fonts, CSS grid, float, multi-column,
  any interactivity.
- Flexbox: the template has two `flex` uses for header alignment only; busi can
  convert those to a table if flexbox support is hard. **Do not block on flexbox.**

JVM / interpreter only — no JS backend lowering needed.

## 3  Implementation plan

- PDF (JVM): use a bundled HTML→PDF engine (OpenHTMLtoPDF / flying-saucer over
  PDFBox) confined to the invoice template's CSS subset. Ship as a plugin
  `pdf-plugin.sscpkg` so the dependency is opt-in (parallels `crypto-plugin`).
  Document the supported HTML/CSS subset explicitly.
- MIME (JVM): `jakarta.mail` `MimeMessage` / `MimeMultipart` writeTo a string;
  or a minimal hand-rolled RFC 5322 builder to avoid the dependency. Prefer the
  hand-rolled builder for attachments-only use (smaller surface).
- JS backend: out of scope (server-side only); document as JVM/interpreter-only.

## 4  Behavior checklist

- [x] `htmlToPdfBase64` of the busi invoice template returns valid PDF bytes
      (magic header `%PDF-`), ≥1 page (PDFBox parse-back).
- [x] Unsupported CSS (grid/float) degrades gracefully (no throw), documented subset honored.
- [x] `buildMimeMessage` with 0 attachments produces a valid HTML email.
- [x] With 1+ attachments produces valid multipart/mixed; each part base64-encoded
      with correct headers.
- [x] Round-trips through a standard MIME parser (Jakarta Mail / Angus, test scope).

## 5  Verification

`PdfGenTest` (PDF magic + page count) and `MimeAssemblyTest` (parse-back with a
reference MIME parser). busi re-points its PDF/email attachment path at these and
keeps the HTTP relay as an optional alternative.

## 6  busi context

busi Phase 56 (HTML invoice) and Phase 66a/66b (PDF/MIME via relay). With these
externs, `BUSI_PDF_HTTP_URL` becomes optional rather than required for attachments.
Pairs with [`smtp-send.md`](smtp-send.md) for a fully relay-free email path.
