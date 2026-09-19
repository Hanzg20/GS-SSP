# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GS-SSP is a multi-vertical Android platform (unattended car wash, vending, parking, EV charging, and attended retail POS), currently mid-way through a **multi-module Gradle restructure** (root project name `SSP`, package root `com.goldsky.ssp` — the old single-module `com.goldsky.carwash` app is gone). It handles card/QR payment across multiple acquirers and hardware vendors, and syncs configuration/telemetry with a shared Supabase backend.

Target hardware constraints (do not violate these when writing code):
- Android 7.1 (API 25) is the real deployment floor (`minSdk 25` on every module) — avoid APIs unavailable below API 25.
- Multiple vendor terminal hardware is in play (PAX IM30, WizarPOS Q3mini, ID TECH NEO2/NEO3), each behind its own `IHardwareProvider`/`IPaymentProvider` implementation — see Architecture below. Don't assume PAX-only or a fixed screen size across the whole codebase anymore; that constraint is per-app-shell, not global.

## Module layout

```
core/common    -- CoreConfig/HardwareConfig (isMock flags), QrUtils, TtsManager, FeedbackManager. No Android deps beyond core-ktx.
core/hardware  -- IHardwareProvider/IPaymentProvider/IScannerProvider/... interfaces + HardwareFactory/PaymentProviderFactory
                  (vendor-keyed registries -- see Architecture below). No concrete vendor code here.
core/data      -- Supabase client, DeviceRepository, TransactionRepository, ShadowManager, VipRepository, OfflineQueueManager,
                  DeviceAccessManager, KeyHealthMonitor, HeartbeatWorker, and friends. Package is still `com.goldsky.ssp.payment`
                  (unchanged from before the module split -- only the physical location moved).
core/payment   -- PaymentProviderFactory, WizarPOS P3 protocol (WizarPosP3Protocol/WizarPosSocketClient/WizarPosPaymentProvider).
core/ui        -- Shared Compose theme + legacy View-based kiosk UI (BaseAdActivity/AdActivity/VipActivity, FlipDigitView,
                  RotatingBrushView, WaterWaveView -- the last three are car-wash-specific visuals living in a module every
                  app shell depends on; don't assume a non-wash shell needs them).

feature/retail, feature/apex, feature/vending, feature/ev, feature/wash, feature/parking
               -- per-vertical screens/viewmodels. retail (checkout/dine-in/delivery/settings/staff-pin, Compose) and vending
                  are substantially built out; apex/wash/parking are single-file skeletons; ev is partial.

libs/pax, libs/wizarpos
               -- pure wrapper modules around vendor .aar/.jar files (PAX POSLink, ID TECH's SDK -- bundled inside
                  Universal_SDK_1.00.190_os.jar alongside cloudpos, not a separate artifact -- and WizarPOS's cloudpos SDK).
                  Zero Kotlin of their own. bundleDebugAar/bundleReleaseAar are deliberately disabled on both (AGP refuses to
                  re-package a local AAR inside another library's own AAR output; nothing needs that standalone artifact --
                  consumers use `implementation project(':libs:pax')`, which doesn't go through that task).

app/iris       -- holds the full counter checkout flow (cart/tip/payment/receipt/Insights, all three hardware vendors wired
                  -- see Architecture), i.e. the old monolith's `retail` flavor. Package/applicationId `com.goldsky.ssp.iris`.
                  **Confirmed 2026-08-27: this is correctly Iris's content** -- `feature:retail` belongs under Iris, full
                  stop, not something to duplicate elsewhere.
app/ourea      -- Desktop POS, first real build 2026-08-27 (see `docs/system_architecture.md` v2.33). Package
                  `com.goldsky.ssp.ourea`. Reuses `feature:retail`'s ViewModel/Repository/payment logic verbatim (same
                  hardware/payment/auth wiring as `app/iris`'s `MainActivity.kt`) behind a NEW UI (`OureaTheme.kt` +
                  `OureaScreens.kt`: dark sidebar-nav layout, product grid + cart panel, payment confirmation panel) styled
                  after 4 reference screenshots at `C:\goldsky\Requirment\Ourea` (visual reference from another product
                  called "Posly", not a written spec -- don't assume everything shown there is implemented; the rich
                  dashboard-analytics and in-app menu-category-management screens shown there were explicitly out of scope
                  and are NOT built). Only card-present payment is wired (the only one with real backing logic) -- the
                  reference's cash/QR/member-card payment buttons don't exist here, deliberately not built as non-functional
                  decoration. Verified: builds, installs, runs on emulator, no crash; full cart-to-real-payment flow not yet
                  exercised end-to-end (no synced catalog data on the test device yet).
app/aegis-wash, app/aegis-vend, app/aegis-ev
               -- the Aegis series (see taxonomy below): `com.goldsky.ssp.aegis.wash` (car wash, feature:wash),
                  `com.goldsky.ssp.aegis.vend` (self-service vending, feature:vending), `com.goldsky.ssp.aegis.ev`
                  (EV charging, feature:ev). Renamed 2026-08-27 from the old, wrongly-named `app/aegis`/`app/ourea`/
                  `app/sentinel` respectively -- content unchanged, only package/applicationId/module dir/label changed.
               -- `app/aegis-parking` does not exist yet; `feature/parking` is still a 1-file stub.
Vendor SDK stub types not backed by any real AAR (`com.pax.dal.*`, `com.pax.neptunelite.api.*` -- the NeptuneLite DAL API,
distinct from the POSLink AAR in libs/pax) live in `core/hardware/src/main/java/com/pax/**`, shared by every app shell.

### GoldSky brand taxonomy (authoritative, confirmed 2026-08-27 -- see `docs/system_architecture.md` v2.29)

| Code name | Meaning | Maps to |
|---|---|---|
| GoldSky Raqia | 天空结构 | GS-SSP itself (system foundation), not a specific app |
| GoldSky Cael | 天空空间 | CMP (`gs-ssp-cmp`, already correctly branded there) |
| GoldSky Ourea | 山神 | Desktop POS (attended counter checkout) -- `app/ourea`, first build 2026-08-27 (see above) |
| GoldSky Iris | 虹 | Handheld POS -- `app/iris`, confirmed to correctly hold `feature:retail`'s counter checkout flow |
| GoldSky Aegis | 盾 | Unattended-terminal **series brand** -- NOT a single app, see below |
| GoldSky Apex | 顶峰 | AI assistant |

**Aegis is a series, not one app.** wash/vending/parking/EV are each a SEPARATE, independently-installable app, all branded under "Aegis" -- not one app with flavors/modes selecting the vertical. Naming convention: sub-package form, package `com.goldsky.ssp.aegis.<vertical>` (e.g. `com.goldsky.ssp.aegis.wash`), module dir `app/aegis-<vertical>` (e.g. `app/aegis-wash`), applicationId matches the package, display name "GoldSky Aegis Wash" style. `app/aegis-parking` is the one vertical not yet built.

Don't guess names from whatever directories happen to exist -- check this table first.
```

`BootReceiver` (auto-launch on `BOOT_COMPLETED` for unattended kiosk startup) has been recreated in `app/aegis` (wash); if you're building out the still-pending `aegis-vend`/`aegis-ev`/`aegis-parking` shells, check whether it needs to be added there too rather than assuming it carries over automatically.

## Build & Run

Gradle project (Groovy DSL, AGP 8.7.3, Kotlin 2.0.0, JVM target 11 everywhere).

```bash
./gradlew assembleDebug                 # build every module's debug output
./gradlew :app:iris:assembleDebug       # build just one shell, e.g. Iris (retail counter checkout)
./gradlew :app:iris:installDebug        # install to connected device/emulator
./gradlew clean
```

Unit tests exist per-module now (`core/data`, `core/payment`, `app/iris` each have `src/test`; JUnit 4, `testOptions.unitTests.returnDefaultValues = true`, no Robolectric/Mockito needed so far):

```bash
./gradlew testDebugUnitTest                        # every module's unit tests
./gradlew :core:payment:testDebugUnitTest           # one module
./gradlew test --tests "com.goldsky.ssp.iris.hardware.idtech.SaleResolutionGuardTest"
```

### Local configuration secrets

`local.properties` (gitignored) must contain `supabase.url` and `supabase.key`. All five current app shells (`app/iris`, `app/ourea`, `app/aegis-wash`, `app/aegis-vend`, `app/aegis-ev`) wire these into `BuildConfig.SUPABASE_URL`/`SUPABASE_KEY` via the same `Properties`-loaded-from-`rootProject.file('local.properties')` pattern. Each app builds without them but Supabase-backed features fail.

### Vendor SDK stubs vs. real AARs

Two different things live under `com.pax.*`/`com.idtechproducts.*`/`com.cloudpos.*` package names and it matters which:
- **Real vendor code**: `com.pax.poslink.*` (from `libs/pax`'s `PAX_POSLinkAndroid_20260202.aar`), `com.cloudpos.*` (from `libs/wizarpos`'s `cloudpos_sdk.aar`), `com.idtechproducts.device.*` (bundled inside `libs/wizarpos`'s `Universal_SDK_1.00.190_os.jar`, despite the module name).
- **Local stubs, no real backing AAR in this repo**: `com.pax.dal.*` (NeptuneLite DAL — `IDAL`/`IUart`/`IScanner`/`ISys`/`IPed`/`IPicc`/`IDeviceControl`) and `com.pax.neptunelite.api.NeptuneLiteUser`, hand-written under `core/hardware/src/main/java/com/pax/**` (shared by every app shell, not just iris). They exist purely so the project compiles without the proprietary NeptuneLite AAR (which nobody has dropped in here) — same rationale as the pre-refactor monolith's PAX stub pattern. Keep signatures compatible with the real SDK if you ever get the real AAR.

Hardware-dependent providers detect mock mode via `BuildConfig.IS_MOCK` / `HardwareConfig.isMock` (not `Class.forName` SDK-absence detection anymore — that pattern got replaced during the module split) and branch internally; preserve this for any new vendor integration.

## Architecture

Two vendor-keyed registries in `core/hardware`/`core/payment`, both throwing `IllegalArgumentException` if nothing's registered for the requested vendor (fail-closed, not a silent no-op):
- `HardwareFactory.registerHardwareProvider(vendor, IHardwareProvider)` / `getHardwareProvider/getScannerProvider/getPrinterProvider/getSerialProvider/getMdbProvider/getGpioProvider(vendor)`.
- `PaymentProviderFactory.registerPaymentProvider(vendor, IPaymentProvider)` / `getPaymentProvider(context, vendor)`.

Nobody registers these automatically — it's each app shell's `MainActivity.onCreate()` job to construct every vendor's hardware provider, call `.init(context)` on it, register it into `HardwareFactory`, then pull `.getPaymentProvider()` off it (a vendor-specific accessor, not part of `IHardwareProvider`) and register that into `PaymentProviderFactory`. **`init()` must run before `getPaymentProvider()`**: WizarPOS's payment provider captures its `POSTerminal` handle by value the first time `getPaymentProvider()` is called, and that handle is null until `init()` sets it — call order matters, it's not just a style preference. See `app/iris/MainActivity.kt` for the reference wiring across all three vendors (PAX/IDTECH/WIZARPOS).

`MockHardwareProvider` classes exist per vendor but nothing currently instantiates or registers them — mock behavior lives inside each real provider's own `BuildConfig.IS_MOCK` branch instead (see above), not via factory-level provider swapping. Don't wire `MockHardwareProvider` in without checking whether that's actually still the intended pattern.

### Payment
- The intended fault-tolerance contract (per `docs/system_architecture.md`, written for the old monolith but still the right principle): if bank authorization succeeds but the hardware relay/dispense fails to ACK, void the transaction — never leave a charged-but-unfulfilled state. Verify this is actually wired for each vendor's payment provider rather than assuming it carried over.

### Cloud sync & telemetry (`core/data`, package `com.goldsky.ssp.payment`)
- `SupabaseClientProvider` / `SupabaseConfig` — Supabase client setup.
- `DeviceRepository` — device identity and auth token retrieval.
- `TransactionRepository` — writes transaction audit records to Supabase.
- `ShadowManager` — device shadow (desired/reported state) sync, versioned.
- `HeartbeatWorker` — periodic WorkManager heartbeat/telemetry reporting.
- `RemoteCommandManager` — subscribes to Supabase Realtime for remote commands (reboot, lock, etc.).
- `DiagnosticManager` — captures/reports error and maintenance events for remote troubleshooting.
- `DeviceAccessManager` — process-wide lock/unlock state machine (remote LOCK command + admin `devices.is_active`), persisted so a remote lock survives a process restart.
- `KeyHealthMonitor` — tracks PAX POSLink result codes for signs that DUKPT/PIN-pad key injection is unhealthy and gates further card-present transactions until reset.
- `OfflineQueueManager`, `VipRepository`, `AdManager`/`AdSyncWorker`, `AnalyticsManager`, `CouponRepository` — offline transaction queue, VIP/membership, ad playlist sync, analytics, coupons.

When adding a new cloud-synced feature, follow the existing pattern: a dedicated singleton in `core/data`, Ktor + `kotlinx.serialization` for the wire format, and graceful offline degradation (never crash or block the UI when Supabase is unreachable — log and continue with cached/default state). `ConfigManager`'s 3-tier degrade (cloud → `context.filesDir` cache → bundled `assets/default_config.json`) is still the model to follow for anything config-shaped.

### Data models
`AppConfig`, `Product`, `DeviceShadow` mirror the Supabase schema described in `docs/database_design.md` (multi-tenant: `organizations` → `locations` → `devices`, generic `products` table with a `vertical_type` discriminator and JSONB `attributes` for industry-specific params).

## UI / visual conventions (from `.cursorrules`)

`.cursorrules` still describes a `ConstraintLayout`-only, dark "kiosk" theme (`#121824` navy background, amber/emerald accents) with `BaseAdActivity`'s immersive full-screen flags on every Activity. **That's the unattended-kiosk convention (wash/vending/EV), not what `app/iris` actually uses** — `app/iris` is Compose + standard `MaterialTheme`, staff-operated (PIN-locked, not idle-ad-driven), and none of those color values appear anywhere in its UI. Follow `.cursorrules` for the `aegis-wash`/`aegis-vend`/`aegis-ev` unattended-kiosk shells; follow `app/iris`'s existing Compose/Material3 screens as the reference for retail-style, staff-operated UI instead.
