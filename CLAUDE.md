# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GS-SSP is a native Android kiosk app for the **PAX IM30** unattended payment terminal, running a self-service car wash system. It handles user interaction, card/QR payment, and RS-232 serial control of the wash relay board, while syncing configuration and telemetry with a Supabase backend. Package: `com.goldsky.carwash`.

Target hardware constraints (do not violate these when writing code):
- Android 7.1 (API 25) is the real deployment floor (`minSdk 25`) — avoid APIs unavailable below API 25.
- Screen is 640×480 physical pixels (`.cursorrules` specifies landscape; `AndroidManifest.xml` currently declares `screenOrientation="portrait"` for all activities — check current manifest state before assuming either). Always use `ConstraintLayout` percentage-based sizing (`layout_constraintWidth_percent`) or `dp`/`sp`, never raw pixels.
- Serial relay board is on `/dev/ttyS1` via the PAX NeptuneLite `IUart` API — 9600 baud, 8 data bits, no parity, 1 stop bit.

## Build & Run

Standard Gradle Android project (Groovy DSL, AGP 9.3.0, Kotlin 2.2.10, JVM target 1.8).

```bash
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # build release APK (minify disabled)
./gradlew installDebug         # install to connected device/emulator
./gradlew clean
```

There are no test source sets or test files in this repo currently (`app/src/test`, `app/src/androidTest` are unpopulated despite the JUnit/Espresso dependencies being declared). If you add tests, standard commands apply:

```bash
./gradlew test                             # unit tests
./gradlew connectedAndroidTest              # instrumented tests (requires device)
./gradlew test --tests "com.goldsky.carwash.SomeTest"
```

### Local configuration secrets

`local.properties` (gitignored) must contain `supabase.url` and `supabase.key`, injected into `BuildConfig` via `SUPABASE_URL`/`SUPABASE_KEY` fields (see `app/build.gradle`). The app will build without them (falls back to empty strings) but Supabase-backed features will fail.

### PAX SDK stubs

`app/src/main/java/com/pax/**` contains **local stub implementations** of the PAX NeptuneLite (`com.pax.dal.*`, `com.pax.neptunelite.api.*`) and POSLink (`com.pax.poslink.*`) SDKs — not the real vendor libraries. They exist so the project compiles without the proprietary `.aar`/`.jar` files, which are normally dropped into `app/libs/` (picked up automatically via `fileTree(dir: 'libs', ...)`) when building for real hardware. When editing code that touches these APIs, keep signatures compatible with both the stub and (as far as documented) the real SDK. Hardware-dependent managers detect SDK absence at runtime via `Class.forName(...)` checks and fall back to a mock/simulated code path (see `PaxScannerManager.checkPaxAvailability()`) — preserve this mock-fallback pattern for any new hardware integration so the app remains runnable/testable off-device.

## Architecture

MVVM-ish with a Repository layer between UI and hardware/network, though most "repositories" here are Kotlin `object` singletons rather than classes with DI. Full design rationale (sequence diagrams, protocol tables, DB schema) lives in `docs/system_architecture.md` and `docs/database_design.md` — read those before making non-trivial changes to payment, config sync, or hardware control flow.

### Kiosk shell & idle-ad flow
- `BaseAdActivity` is the base class for all screens. It applies immersive full-screen kiosk window flags (`onCreate`/`onResume`/`onWindowFocusChanged`) and globally intercepts `dispatchTouchEvent` to drive a 60s inactivity timer (Handler-based). On timeout it launches `AdActivity` unless the subclass reports `isCarWashSessionActive() == true` (override this hook to suppress ads mid-wash).
- `AdActivity` full-screen loops MP4/image ads from `res/raw` / downloaded assets; any tap on it calls `finish()` to return to the previous screen instantly.
- `AdSyncWorker` (WorkManager) periodically syncs the ad playlist from Supabase Storage into `context.filesDir/ads/`, using MD5 hashes to do incremental download/cleanup, with `res/raw` as the offline fallback.
- `BootReceiver` auto-launches `MainActivity` on `BOOT_COMPLETED` for unattended kiosk startup.

### Configuration: 3-tier degrade strategy
`ConfigManager` (`payment/ConfigManager.kt`) loads app configuration in strict priority order and never blocks the UI on network:
1. **Cloud** — GET from Supabase (`app_configurations` table) via Ktor.
2. **Local cache** — `context.filesDir/app_config_cache.json`, written on every successful cloud fetch.
3. **Bundled assets** — `assets/default_config.json`, guaranteeing the device is usable even if it has never been online.

`isDatabaseOnline()` / `checkDatabaseHealth()` expose connectivity state for UI health indicators. Apply this same fallback pattern to any other feature that depends on remote config.

### Payment
- `PaymentService` wraps PAX POSLink (`com.pax.poslink.PosLink`) for card SALE/VOID transactions over local AIDL (`127.0.0.1:10009`), and generates payment QR codes locally via ZXing (`generateQrCode`) for scan-to-pay flows, polling backend status (`pollPaymentStatus`).
- `PaxScannerManager` wraps the NeptuneLite scanner (barcode) and PICC (NFC/Mifare) hardware, with mock-mode fallbacks (simulated scan/card-tap results after a delay) when the SDK class isn't present — used for development off real hardware.
- The intended fault-tolerance contract (per `docs/system_architecture.md`): if bank authorization succeeds but the hardware relay fails to ACK, the app must issue a `PaymentService.voidTransaction()` to reverse the charge — never leave a charged-but-unwashed state.

### Serial / hardware control
- `SerialPortManager` (`serial/SerialPortManager.kt`) is a singleton wrapping `IUart` for the relay board. Commands are 4-byte hex frames: `[0xAA][Mode][Value][0x55]`, e.g. `AA 01 0A 55` for a wash mode, `AA 00 00 55` to stop/end. Use `sendHexString`/`sendBytes`; `openPort(context)` must be called (e.g. from `MainActivity.onCreate`) before sending.
- Product-to-command mapping is intended to be data-driven from cloud config (`Product.attributes`), not hardcoded per package — see `docs/database_design.md` `products.attributes` (e.g. `{pulse: 12, mode: 'COM'}`).

### Cloud sync & telemetry (`payment/` package)
Despite the package name, `payment/` holds most of the app's cloud-integration singletons, not just payment code:
- `SupabaseClientProvider` / `SupabaseConfig` — Supabase client setup, reading `BuildConfig.SUPABASE_URL`/`SUPABASE_KEY`.
- `DeviceRepository` — device identity (PAX serial number) and auth token retrieval.
- `TransactionRepository` — writes transaction audit records to Supabase.
- `ShadowManager` — device shadow (desired/reported state) sync, versioned.
- `HeartbeatWorker` — periodic WorkManager heartbeat/telemetry reporting.
- `RemoteCommandManager` — subscribes to Supabase Realtime for remote commands (reboot, lock, etc.).
- `DiagnosticManager` — captures/reports error and maintenance events for remote troubleshooting.
- `BrandingManager`, `AdManager`, `VipRepository` — branding assets, ad playlist, and VIP membership data respectively.

When adding a new cloud-synced feature, follow the existing pattern: a dedicated singleton in `payment/`, Ktor + `kotlinx.serialization` for the wire format, and graceful offline degradation (never crash or block the UI when Supabase is unreachable — log and continue with cached/default state).

### Data models (`model/`)
`AppConfig`, `Product`, `DeviceShadow` are `kotlinx.serialization`-annotated data classes mirroring the Supabase schema described in `docs/database_design.md` (multi-tenant: `organizations` → `locations` → `devices`, generic `products` table with a `vertical_type` discriminator and JSONB `attributes` for industry-specific params like wash pulse/mode).

## UI / visual conventions (from `.cursorrules`)

These are enforced project conventions for any UI work, originally written for AI-assisted generation — still apply:
- `ConstraintLayout` only, no absolute pixel coordinates; use percentage constraints or `dp`/`sp`.
- Dark, high-contrast "kiosk" theme: near-black/navy backgrounds (`#121824`), slightly lighter card surfaces (`#1E293B`), amber (`#F59E0B`) or emerald (`#10B981`) for primary actions/highlights, white/light-gray high-contrast text (Roboto/Inter).
- Every top-level Activity must apply the immersive kiosk window flags in both `onCreate()` and `onResume()` — already centralized in `BaseAdActivity`; extend it rather than reimplementing.
