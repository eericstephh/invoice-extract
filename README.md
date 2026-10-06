# InvoiceExtract

AI-powered invoice extraction and financial document processing for desktop.

![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.7.1-4285F4?logo=jetpackcompose&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Windows_64--bit-0078D4?logo=windows&logoColor=white)
![Languages](https://img.shields.io/badge/Languages-Persian_%7C_English-009485)
![Tests](https://img.shields.io/badge/Tests-350_passing-2E7D32)

InvoiceExtract reads Persian and international invoices from scans, photos and PDFs, validates them, archives them locally, and turns the archive into ledgers, statements and analytics — with an offline-first local AI engine and an optional cloud fallback.

- **Offline-first desktop app** (Windows 64-bit): OCR + local LLM extraction, no account, no subscription.
- **Bilingual FA/EN workspace** with one-tap switching, Persian-Indic digits and RTL/LTR mirroring.
- **Financial tooling**: receivables tracking, bank reconciliation, BI analytics with Excel export, QuickBooks/Xero plus Sepidar/Holoo outputs, formal A4 and commercial print sheets.
- **Donation-ware**: 100% free and unlimited; support via the in-app crypto dialog.

> **Status:** pre-1.0 (`0.1.0`). Screenshots are on the way — see [Known Limitations](#known-limitations).

## Overview

A typical run looks like this: drop a scan onto the window → the OCR layer reads the text → the local AI structures it into an invoice (seller, lines, totals, dates, tax IDs) → validators and checksum audits flag anything suspicious → you review, tag the client/project, and save → the archive feeds statements, analytics, reconciliation and every export format.

```
 Scan / Photo / PDF
        │  OCR (Tesseract · PDFBox text layer · ML Kit on Android)
        ▼
 Local AI structuring (Ollama · qwen2.5:3b — on your machine)
        │  ↳ Cloud failover (Gemini → DeepSeek → GitHub Models) only if local is down
        ▼
 Validate → Review → Archive → Ledger · Statements · Analytics · Exports
```

## Key Features

### Document Processing
- Drag-and-drop single files and batch folders; scanner hot-folder auto-watch with write-stability guard.
- Digital-PDF text fast path plus image OCR; Persian text normalization (Arabic/Persian digit and Yeh/Kaf unification, zero-width cleanup).
- Duplicate detection (exact number + amount/date fingerprint).

### OCR
- Desktop: Tesseract (`fas.traineddata`, auto-fetched once) + PDFBox text layer.
- Android: ML Kit on-device Latin recognition (note: ML Kit ships no Persian recognizer — see [Known Limitations](#known-limitations)).

### AI Extraction
- Local-first via Ollama (`qwen2.5:3b`, auto-provisioned on Windows: installer → service → model pull, all in-app).
- Strict JSON envelope with lenient decoding; cloud failover chain (Gemini → DeepSeek → GitHub Models) through a Cloudflare Worker proxy with per-device daily quota.

### Validation
- Iranian national ID / legal-entity Modulo-11 checksums, US EIN prefix checks, EU/UK VAT shape checks — each with live editor chips.
- Line-arithmetic cross-checks, duplicate warnings, national-ID audit warnings on every save path.

### Financial Management
- Payment tracking (`PAID` / `PENDING` / `OVERDUE`) with due dates; follow-up copilot drafting trilingual-tone reminders with WhatsApp handoff.
- Client statements with agency markup, petty-cash settlements, product-code mappings.

### Reconciliation
- Bank CSV import (UTF-8/Windows-1256, header-driven debit/credit/ Rial detection) matched against open receivables by exact Toman amount; one-tap batch settle.

### Analytics
- Mini-BI dashboard: KPIs (incl. unpaid receivables), Jalali **and** Gregorian monthly buckets, vendor/item leaderboards, fiscal-year filter — all recomputed live from the archive.

### Export
- SpreadsheetML ledgers/statements, RFC-4180 CSVs, QuickBooks + Xero import files, Sepidar/Holoo sheets, formal A4 (Section 169) and commercial HTML print sheets with spelled-out totals (Persian + English words).

### Automation
- Hot-folder watcher, keyboard shortcuts, backup/restore to zip, dark/light Claymorphic theme.

### Localization
- Full Persian/English UI with RTL/LTR mirroring, Jalali/Gregorian dates, Persian-Indic digits in FA mode, Western formatting in EN mode.

## Privacy / Offline-First

- The desktop app is **offline-first**: extraction runs against the Ollama daemon on your own machine; invoices persist as local JSON under `%APPDATA%/InvoiceExtract`.
- In local mode, document content is **never sent to any cloud** — this follows from the architecture: the pipeline calls `localhost` (Ollama) and the cloud proxy is only invoked on the explicit failover path when local is unavailable.
- No account, no telemetry, no tracking in the client. The Android cloud path sends OCR text to the proxy, which forwards it to the AI provider — that path inherently shares document text with the provider.
- End users need **no API key**: cloud failover authenticates worker-to-provider with server-side secrets. Operators deploying their own worker need provider keys (see [Configuration](#configuration)).

## Architecture

```
                    InvoiceExtract
                          │
          ┌───────────────┴───────────────┐
          │                               │
       Desktop (JVM)                  Android app
     Compose Multiplatform        Jetpack Compose
          │                               │
          └───────────────┬───────────────┘
                          ▼
                   :domain (pure Kotlin)
              models · validation · contracts
                          │
          ┌───────────────┼───────────────┐
          ↓               ↓               ↓
   Data layer      Presentation      Cloud proxy
 OCR · AI · stores ViewModel · UI   (Cloudflare Worker)
   │   (Ollama /        │           Failover chain:
   │    local AI)       │           Gemini → DeepSeek
   │                    │               → GitHub Models
   └─ File-backed JSON stores ──────────
```

Details: [docs/architecture.md](docs/architecture.md).

## Requirements

- **Windows 10/11 64-bit** (verified target; the Compose Desktop toolchain can also target macOS/Linux but those installers are untested — see [Known Limitations](#known-limitations)).
- **JDK 17** to build (Gradle 8.10.2 does not run on newer JVMs; the toolchain auto-provisions where possible).
- Gradle Wrapper (no manual install): `./gradlew.bat`.
- For local AI: Ollama is auto-provisioned by the app on first run (several GB download for the daemon + `qwen2.5:3b`).
- For Android builds: Android SDK + `local.properties` (see [Configuration](#configuration)); for the worker: Node 18+ and Wrangler.

## Installation

Prebuilt installers are not yet published. Build your own:

```bat
.\gradlew.bat :desktop:clean :desktop:packageDistributionForCurrentOS --no-configuration-cache
```

Installers land under `desktop/build/compose/binaries/main/` (`.exe`/`.msi` on Windows, package `InvoiceExtract` 1.0.0). This command is current as of this README — it mirrors the project's own packaging task.

## Configuration

No secrets are needed to build or run. All credentials stay out of source:

| File | Purpose | Committed? |
|---|---|---|
| `local.properties` | Android SDK path + dev proxy URL/client key (`INVOICE_*`) | **Never** (git-ignored) |
| `backend/.dev.vars` | Local worker secrets (copy from `.dev.vars.example`) | **Never** (git-ignored) |
| Cloudflare Secrets | Production worker credentials (`wrangler secret put <NAME>`) | N/A (server-side) |

```bash
# backend only — production secrets, never in files:
wrangler secret put GEMINI_API_KEY
wrangler secret put APP_CLIENT_KEY
```

See [Security](#security) and `backend/wrangler.toml.example`.

## Development

```bat
.\gradlew.bat :desktop:test :domain:test   # JVM suites (JDK 17)
```

- Full suite: 343 desktop + 7 domain tests, hermetic (stub Ollama daemon, throwaway folders), zero warnings policy for new code.
- Code style: official Kotlin style; KDoc on public API; Persian FA strings live beside EN in `AppStrings`.
- Deep guides: [docs/development.md](docs/development.md) · [docs/roadmap.md](docs/roadmap.md).

## Security

Never commit: API keys, tokens, passwords, `local.properties`, `.dev.vars`, environment secrets. Production credentials live in Cloudflare Secrets; development ones in git-ignored `.dev.vars`. See [SECURITY.md](SECURITY.md) for reporting and rotation policy.

## AI-Assisted Development

This project was designed and built with extensive AI-assisted development: architecture exploration, implementation, debugging, test authoring and documentation were all produced working with AI coding assistants, with human review at every step. No claim is made about any specific tool or model — what matters is that every line ships with tests and KDoc a human stands behind.

## Roadmap

See [docs/roadmap.md](docs/roadmap.md) — completed vs. planned, extracted from the shipped code only.

## Known Limitations

- No UI screenshots are bundled yet (only the app icon); store/Play listings will need them.
- ML Kit has no Persian recognizer — Persian OCR on Android falls back to other engines.
- macOS/Linux installers are unconfigured and untested.
- The shared Jalali converter in the exporters uses a simplified month table shared bit-for-bit across sheets (consistent everywhere, not almanac-grade for Shahrivar+ edge cases).
- App version is `0.1.0`; no signed release or update channel exists yet.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — setup, branching, style, tests, PRs.

## License

**TODO:** no license has been chosen yet — see the license comparison in the release notes. Until a `LICENSE` file lands, all rights are reserved by default.
