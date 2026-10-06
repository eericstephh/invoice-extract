# Security Policy

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1   | :x:                |

Pre-1.0 software: patch releases are not guaranteed; the supported target is
always the latest commit on the default branch.

## Reporting a vulnerability

**Do not open a public issue for security reports.** Until a dedicated contact
is published, report privately:

- **TODO:** publish a security contact (e.g. a dedicated email or a private
  vulnerability-reporting channel) before the first public release. This
  placeholder exists so the process gap is visible, not hidden.

Include: affected component, steps to reproduce, and impact assessment. Expect
an acknowledgement within 7 days and a fix-or-mitigation plan within 30 days
for confirmed issues.

## Secrets discipline

This repository must never contain credentials:

- No API keys, tokens, passwords, certificates or private keys in any file.
- `local.properties`, `.dev.vars`, `.env` and `backend/.wrangler/` are
  git-ignored for exactly this reason — if you need a value, it belongs in
  Cloudflare Secrets (production) or `.dev.vars` (local), never in a commit.
- The worker fails closed without keys (401/503), so a misconfigured deploy
  cannot leak or burn provider quota.

## If a secret leaks

1. **Rotate/revoke it immediately** at the provider (Google AI Studio,
   DeepSeek, GitHub, Cloudflare) — assume anything committed is compromised,
   even if the commit is later deleted (history keeps it).
2. Purge it from history (`git filter-repo` or BFG) and force-push, then
   rotate again — filtering rewrites history, it does not un-publish clones.
3. Add the pattern to `.gitignore`/review so the class cannot recur.

## Security expectations

- Client Release builds are debug-signed local artifacts; production uploads
  require a proper upload key (see `app/build.gradle.kts`).
- The Android cloud path sends OCR text to the AI provider by design; the
  desktop local path never leaves the machine. Threat reports should state
  which path they concern.
