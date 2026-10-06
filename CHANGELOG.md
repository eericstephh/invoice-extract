# Changelog

All notable changes to this project are documented here, newest first.
Versioning is `0.x.y` until a 1.0.0 release decision is made.

## [Unreleased]

### Added
- Global workspace: English-first LTR default, USD manual-entry default.
- QuickBooks and Xero CSV import sheets (single + batch), gated to English mode.
- US EIN and EU/UK VAT checksum validation with live editor chips and audit warnings.
- Standard commercial/tax invoice HTML print sheet with English amount-in-words;
  print flow routes by window language (commercial in EN, Section 169 in FA).
- Gregorian analytics: Western month buckets, Gregorian year chips, `$` dashboard
  rendering, language-aware period scoping.
- Executive BI Excel export (KPIs, monthly trend, vendors, items).
- Bank statement reconciliation with one-tap batch settle.
- Receivables tracking with follow-up copilot (toned reminders, WhatsApp handoff).
- Donation-ware pivot: unlimited free use, crypto donation dialog, evaluation
  footers removed from all print sheets.

### Security
- Provider and client credentials moved out of `wrangler.toml` into Cloudflare
  Secrets / `.dev.vars`; fail-closed deploys; `.gitignore` secret coverage.

## [0.1.0] — Initial snapshot

First public-tree snapshot of the working product:

- Offline-first desktop extraction (Tesseract, PDFBox, local Ollama `qwen2.5:3b`).
- Cloud failover proxy (Gemini → DeepSeek → GitHub Models) with device quota.
- Bilingual FA/EN Claymorphic UI; Jalali/Gregorian support; Persian-Indic digits.
- Validation suite (Iranian checksums, duplicates, line arithmetic).
- Archive, backup/restore, hot-folder watcher, batch processing.
- Client statements, petty-cash settlements, product-code mappings.
- SpreadsheetML/CSV ledgers, Sepidar/Holoo sheets, formal A4 print.
- Android companion app (Room, DataStore, ML Kit Latin OCR) — in-tree experimental module, not released.
- 350 hermetic JVM tests, zero-warning policy.
