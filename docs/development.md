# Development

> Companion to the [README](../README.md). Commands verified against this tree
> (Gradle 8.10.2, JDK 17).

## Setup

1. Install **JDK 17** and point Gradle at it. Gradle 8.10.2 does not run on
   newer JVMs (e.g. Java 25 fails at configuration time):
   ```bat
   set JAVA_HOME=C:\path\to\jdk-17
   ```
   or pass `-Dorg.gradle.java.home=<jdk17>` on every invocation.
2. No Gradle install needed — use the wrapper: `.\gradlew.bat`.
3. Android work (experimental module only) additionally needs the SDK: create `local.properties`
   (git-ignored, never commit) with:
   ```properties
   sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
   INVOICE_BASE_URL=https://your-worker.workers.dev
   INVOICE_CLIENT_KEY=
   ```
   Leave values empty until you run your own worker; empty keys fail closed.
4. Backend: `cd backend && npm install`; copy `.dev.vars.example` to
   `.dev.vars` and fill it (also git-ignored).

## Running tests

```bat
.\gradlew.bat :desktop:test :domain:test --console=plain -Dorg.gradle.java.home=<jdk17>
```

- 343 desktop + 7 domain tests, all hermetic: stub Ollama daemon on
  loopback, throwaway folders, no network, no device.
- New code ships with tests; CI expectation is zero failures and zero
  Kotlin warnings (`^w: ` clean).
- The `:app` module holds a small JVM suite (`PersianTextNormalizerTest`);
  it needs the Android SDK present and covers the experimental Android
  module only.

## Building

```bat
.\gradlew.bat :desktop:clean :desktop:packageDistributionForCurrentOS --no-configuration-cache
```

Installers land under `desktop/build/compose/binaries/main/` (`.exe`/`.msi`
on Windows). These outputs are git-ignored build artifacts, never committed.

## Code style

- Official Kotlin style; KDoc on public API (Persian product copy inside
  KDoc is normal here — the product is bilingual).
- FA/EN strings live together in `AppStrings`; every key must exist in both
  languages (enforced by `AppStringsTest`).
- Money: aggregate in Toman via `effectiveTomanTotal`; exporters print,
  never recompute. Dates stay raw strings until the funnel that reads them.
- One edit funnel (`emitEdited`), pure views, lenient decoding — see
  [architecture](architecture.md).

## Branching & PRs

Short-lived feature branches off `master`; squash on merge; commit messages
in Conventional Commits style (`feat:`, `fix:`, `chore:`, `test:`, `docs:`).
PRs need a green `:desktop:test :domain:test` and no new warnings.
