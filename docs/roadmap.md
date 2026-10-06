# Roadmap

> Extracted from the shipped code only — no speculation. Items under Planned
> are gaps visible in the tree (missing tests, unconfigured targets, TODO-grade
> limitations named in docs), not promises.

## Completed

- Offline-first desktop extraction (Tesseract + PDFBox + local Ollama `qwen2.5:3b` with Windows auto-provisioning).
- Cloud failover proxy (Gemini → DeepSeek → GitHub Models) with per-device quota.
- Bilingual FA/EN workspace with RTL/LTR mirroring and Jalali/Gregorian support.
- Validation suite: Iranian checksums, US EIN, EU/UK VAT, duplicates, line arithmetic.
- Archive with atomic JSON store, backup/restore, hot-folder watcher.
- Financial tooling: receivables + follow-up copilot, bank reconciliation, client statements, petty-cash settlements, product-code mappings.
- BI analytics (dual-calendar) with Excel export; QuickBooks/Xero/Sepidar/Holoo outputs; formal A4 + commercial print sheets with spelled-out totals.
- Donation-ware licensing with in-app crypto dialog; Claymorphic dark/light theme.
- 350 hermetic tests, zero-warning policy.

## Planned

- UI screenshots for store listings (only the app icon ships).
- macOS/Linux installer targets (toolchain supports them; unconfigured, untested).
- Backend worker tests (none ship today).
- `:app` test coverage beyond the normalizer suite.
- Signed releases + update channel (currently debug-signed local builds only).
- Persian OCR recognizer option on Android (ML Kit has none; desktop uses Tesseract).

## Future

- Plugin/extraction-provider registry, additional accounting integrations,
  and mobile feature parity — tracked as issues once the repository is public.
