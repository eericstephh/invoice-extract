# Architecture

> Companion to the [README](../README.md). Everything here describes the
> shipped code — module, package and file names are real.

## Modules

| Module | Type | Role |
|---|---|---|
| `:domain` | Pure Kotlin/JVM | `model` (Invoice, InvoiceItem, CurrencyType, PaymentStatus), `validation` (InvoiceValidator, national-ID checksums), `extractor` interfaces, `repository` contracts, `export` (CSV emitter). Zero Android/desktop dependencies. |
| `:desktop` | Compose Multiplatform (JVM) | The desktop app: OCR, AI, stores, use-cases, ViewModel, full UI. Depends on `:domain`. |
| `:app` | Android (AGP 8.7.2), experimental | In-tree future target sharing `:domain`: Jetpack Compose UI, Room persistence, ML Kit OCR, DataStore session. **Not part of the current public release**, which is the Windows desktop app only. |
| `backend/` | TypeScript + Wrangler | `invoice-extract-proxy`: Cloudflare Worker failover reverse proxy (Gemini → DeepSeek → GitHub Models). Not part of the Gradle build. |

## Desktop layering (`com.invoiceextract.desktop`)

```
presentation/ ── DesktopViewModel (StateFlows, single edit funnel)
    │                 │
    │            DesktopMainScreen + dialogs (pure views of state)
    ▼
domain/ ── DesktopProcessInvoiceUseCase (extract → structure → validate)
    │       analytics (pure reductions) · validation · util
    ▼
data/ ── ai/ (LocalOllamaAiExtractor, OllamaStatus)
      ── ocr/ (DesktopImageOcrEngine: Tesseract + fas.traineddata)
      ── document/ (DesktopDocumentProcessor: PDFBox fast path)
      ── engine/ (DesktopOllamaLifecycleManager: install → serve → pull)
      ── storage/ (DesktopInvoiceRepository: atomic JSON store)
      ── mapping/ (ProductMappingRepository)
      ── export/ (CSV / SpreadsheetML / HTML emitters, all streaming)
      ── backup/ (zip snapshots) · batch/ · automation/ (hot-folder watcher)
      ── licensing/ (donation-ware state) · preview/ · text/ (normalizers)
di/ ── DesktopModule (Koin wiring)
```

Key contracts:

- **One edit funnel.** Every invoice mutation goes through `DesktopViewModel.emitEdited`
  (validate → national-ID audit → global tax-ID audit), so banner, table, totals
  and analytics can never disagree.
- **Views are pure.** Screens read `StateFlow`s and emit callbacks; the VFX/UI
  layers are asserted inert (driving them must not move run numbers).
- **Money is Toman-normalized once** (`Invoice.effectiveTomanTotal`); exporters
  *print* but never recompute.
- **Dates are raw strings** (`YYYY/MM/DD` Jalali contract); parsing lives in
  the analytics funnel and the exporters, never in the model.
- **Lenient decode everywhere**: unknown JSON keys and bad rows degrade to
  defaults/empty, never crash — a corrupt store opens as empty history.

## Data & AI flow

1. **Ingest**: drag-and-drop, batch folder, or hot-folder watcher (with
   write-stability guard so half-written scans never enter the pipeline).
2. **OCR**: digital PDFs take the PDFBox text fast path; images go to
   Tesseract (`fas.traineddata` auto-fetched once, best-effort); Persian
   normalization unifies digits/ Yeh-Kaf and strips zero-widths.
3. **Structure**: Ollama daemon (`qwen2.5:3b`, auto-provisioned on Windows)
   returns a strict JSON envelope, decoded leniently; on local failure the
   Cloudflare proxy tries Gemini → DeepSeek → GitHub Models with a per-device
   daily quota (10/day, 3 without device ID).
4. **Validate & audit**: arithmetic cross-checks, duplicate fingerprinting,
   Iranian/EIN/VAT checksum verdicts with live editor chips.
5. **Archive**: atomic JSON store (`invoices.json` + fsync + atomic rename),
   newest-first reactive flow.
6. **Use**: statements, settlements, reconciliation, analytics, and a dozen
   export formats read the same archive.

## Android module (`com.invoiceextract.app`) — experimental

> Not part of the current public release (InvoiceExtract Desktop for Windows).
> Documented here because it exists in the tree, not because it is supported.

An in-progress future target sharing the `:domain` contracts, with platform
pieces (Room database, DataStore session, ML Kit Latin OCR). Cloud extraction
goes through the worker proxy with the `X-App-Client-Key` header
(BuildConfig-injected from `local.properties`, never committed).

## Backend worker (`backend/src/index.ts`)

Single `POST /api/v1/extract` route: verifies the client key (timing-safe),
then fans out across providers with per-device quota. All credentials arrive
via `env` (Cloudflare Secrets in production, `.dev.vars` locally) — see
`wrangler.toml.example`. No tests ship for the worker yet.
