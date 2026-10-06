# Contributing

Thanks for your interest in InvoiceExtract. Small, well-tested contributions
beat large ones — start with an issue before a big PR.

## Development setup

1. Install **JDK 17** (Gradle 8.10.2 does not run on newer JVMs).
2. Clone and build with the wrapper — no manual Gradle install:
   ```bat
   .\gradlew.bat :desktop:test :domain:test
   ```
3. Android work additionally needs the SDK via `local.properties` (never
   committed — see [Security](SECURITY.md)). Backend work needs Node 18+
   (`cd backend && npm install`) and a `.dev.vars` copied from
   `.dev.vars.example`.

## Branching

Short-lived feature branches off the default branch; squash on merge.
Conventional Commits: `feat:`, `fix:`, `chore:`, `test:`, `docs:`.

## Code style

- Official Kotlin style; KDoc on public API (bilingual product copy inside
  KDoc is fine).
- FA/EN strings ship together in `AppStrings` — every key in both languages,
  enforced by test.
- TypeScript side: `tsc --noEmit` clean (`npm run typecheck`).

## Testing

- `:desktop:test :domain:test` must be green with zero Kotlin warnings.
- New behavior ships with hermetic tests (throwaway folders, stub daemons —
  never `%APPDATA%`, never the network).
- Money aggregates in Toman; exporters print, never recompute; dates stay
  raw strings until the funnel that reads them.

## Pull requests

- Green suite + no new warnings, or the PR waits.
- No secrets, no binaries, no build outputs (the `.gitignore` enforces most
  of this; double-check `wrangler.toml` and anything under `backend/`).
- Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.

## Issue reporting

Bugs: steps to reproduce, expected vs. actual, app/desktop version, log
excerpt. **Security issues are never filed as issues** — see
[SECURITY.md](SECURITY.md).
