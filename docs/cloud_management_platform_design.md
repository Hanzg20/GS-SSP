# GS-SSP Cloud Management Platform (CMP) Design Specification

This document defines the architecture for the GS-SSP Cloud Management Platform (CMP). Following the **"Lean & Extensible"** principle, this design focuses on a flexible foundation that provides immediate operational value without premature complexity, inspired by industry leaders like **Nayax Core**.

> **Reality check (2026-07-24)**: this is a target-state design for a web portal that does not exist yet as a single line of code -- the current repo is the IM30 Android app plus its Supabase schema, nothing else. Cross-checked against the actual live schema (`docs/supabase_full_schema.sql`) and annotated below wherever a described capability is (a) already built and matches, (b) already built but described inaccurately, or (c) pure vision with zero implementation. Keep this doc honest about which bucket each piece is in -- it's easy for "target architecture" and "current state" to blur together in a design doc like this, and that blurring is itself a risk (e.g. §5.2 below stated a security guarantee that, on inspection, wasn't actually true).

## 1. Design Strategy: "Core Skeleton, Scalable Muscle"

To avoid over-engineering while maintaining a vision compatible with large-scale fleet management, the CMP follows these strategies:

*   **Recursive Hierarchy** `[COLUMN DONE 2026-07-24, LOGIC NOT IMPLEMENTED]`: Instead of multiple tables for Distributors/Operators/Regions, a single `organizations` table with a `parent_id` (recursive relationship) allows for infinite levels of management with zero schema changes. `organizations.parent_id UUID REFERENCES organizations(id) ON DELETE SET NULL` now exists in `docs/supabase_full_schema.sql` (nullable, `NULL` = top-level -- every org today). It's inert on its own: nothing reads it yet -- `redeem_coupon()`/`issue_compensation_coupon()` and every RLS policy still match `org_id` by exact equality, not by walking this tree. Actually scoping a `DISTRIBUTOR` to "this org + its descendants" needs a `WITH RECURSIVE` lookup added at each of those call sites, plus adding `'DISTRIBUTOR'` to `org_members.role`'s CHECK -- separate, not-yet-designed work.
*   **JSONB Absorption**: Vertical-specific data (WASH pulses, LAUNDRY timers, EV kilowatts) are absorbed into the `attributes` (JSONB) column of the `products` table. The backend remains "vertical-agnostic." `[MATCHES CURRENT SCHEMA]` -- `products.attributes JSONB` already exists and is used exactly this way.
*   **Asynchronous "Shadow" Control**: Remote management is handled via the **Device Shadow** pattern (Desired vs. Reported state). This ensures "eventual consistency" where a command issued while a machine is offline is automatically applied the moment it reconnects. `[MATCHES CURRENT SCHEMA]` -- `device_shadows` table + `ShadowManager.kt` already implement this.
*   **Atomic Modules**: Build only what is needed for the current phase (Troubleshooting & Pricing) and leave hooks for future expansion (Settlement & Loyalty).

---

## 2. Hierarchical Identity and Access Management

`[MVP SCHEMA DONE 2026-07-24, PORTAL NOT IMPLEMENTED]` -- the DB-side foundation below now exists for `SYS_ADMIN`+`MERCHANT_ADMIN` only; there is still no actual portal/login page anywhere to use it from.

The CMP uses **Row Level Security (RLS)** to enforce a hierarchical permission model.

**What actually exists today**: every IM30 device authenticates via Supabase **anonymous** sign-in (no email/password, no human identity at all), mapped 1:1 to a device via `device_auth_map`. RLS is entirely device-centric -- every policy in the schema scopes access by `device_sn`/`org_id` looked up through that map. Alongside that, `docs/supabase_full_schema.sql` now also has a *human* identity foundation: `profiles` (linked 1:1 to `auth.users`, auto-provisioned by an `on_auth_user_created` trigger) and `org_members` (who has `SYS_ADMIN`/`MERCHANT_ADMIN` on which `org_id`, `org_id IS NULL` = global scope). Still missing: an actual human ever signing in through this (no portal, no login page), and `MFA`.

Before any of the role table below can mean anything to an actual user, this still needs building:
1. Real Supabase Auth sign-in for humans (email/password or SSO -- **not** anonymous). `[STILL OPEN]`
2. ~~A `profiles` table linked to `auth.users`~~ `[DONE 2026-07-24]` -- exists in `docs/supabase_full_schema.sql`, auto-provisioned on sign-up.
3. ~~An org-membership table~~ `[DONE 2026-07-24, MVP SCOPE]` -- `org_members`, `SYS_ADMIN` (global, `org_id IS NULL`) + `MERCHANT_ADMIN` (org-scoped) only. `DISTRIBUTOR`/`LOC_MANAGER` deferred -- `organizations.parent_id` now exists (item below) but `org_members.role`'s CHECK doesn't allow those roles yet and no recursive-scoping logic has been written; still no real multi-site/distributor customer needing them.
4. A second, parallel set of RLS policies for these human-portal roles that coexists with the existing device-centric policies without either weakening device isolation or blocking legitimate admin access. `[PARTIALLY SUPERSEDED]` -- rather than broad per-table RLS, the one thing actually needed so far (compensation-coupon issuance, §3.3.1) does its authorization check inside a `SECURITY DEFINER` RPC (`issue_compensation_coupon()`) instead, matching how `redeem_coupon()`/`deduct_vip_balance()` already work. `profiles`/`org_members` themselves only have minimal self-select RLS (a signed-in human can see their own row). Whether more modules end up needing real per-table RLS instead of RPC-internal checks is a case-by-case call as each one gets built, not resolved wholesale here.

None of this is hard, individually, but it's a real subsystem, not a config flag -- worth sizing accordingly rather than treating "add IAM" as one line item alongside "add a price editor."

The target role model itself (item 1's human sign-in aside, the rest below is reasonable and can stay as designed):

| Role | Scope | Key Permissions |
| :--- | :--- | :--- |
| **SYS_ADMIN** (GoldSky) | Global | Organization onboarding, global billing rates, system-wide version control. |
| **OPS_STAFF** (GoldSky) | Global | **Penetrative Diagnostics**: Remote reboot, fetching Logcat, auditing hardware logs. |
| **MERCHANT_ADMIN** (Operator) | Org-level | Full control over an organization's machines, pricing, staff, and branding. |
| **DISTRIBUTOR** (Partner) | Sub-fleet | Visibility and management over multiple merchants within a specific region. `organizations.parent_id` exists now (§1) -- still blocked on adding `'DISTRIBUTOR'` to `org_members.role`'s CHECK and writing the recursive (`WITH RECURSIVE`) scoping logic everywhere org-scoped access is checked. |
| **LOC_MANAGER** (Site) | Site-level | Monitoring health and alerts for a specific set of machines at one location. |

For an MVP, consider whether all five roles are needed on day one versus starting with just `SYS_ADMIN` + `MERCHANT_ADMIN` and adding `DISTRIBUTOR`/`LOC_MANAGER` when a real distributor/multi-site operator actually onboards -- matches this doc's own "Lean & Extensible" principle better than building the full hierarchy against zero current customers who need it.

---

## 3. Functional Modules

### 3.1 [MVP] Remote Troubleshooting ("The Black Box")
The highest priority module to reduce maintenance travel costs.
*   **Remote Reboot**: One-click system recovery.
*   **Log Retrieval (FETCH_LOGS)**: CMP triggers the terminal to upload its latest Logcat to Cloud Storage. Engineers can debug field issues from their desks.
*   **Live Vitals**: Real-time display of signal strength, disk space, and hardware ACK status.

### 3.2 [MVP] Config & Pricing Manager
Eliminates the need for on-site visits for simple operational changes. This module is correctly scoped against the current schema: `products` (editable catalog) vs. `app_configurations` (frozen per-version snapshot devices actually read) already exist as two deliberately separate concepts (see `docs/system_architecture.md` v2.9 changelog) specifically so that editing the catalog never retroactively changes a config version already published to devices -- this module is the "publish" tool that gap has been waiting for.
*   **Price Editor**: Modify the `products` catalog draft.
*   **One-Click Sync**: "Publishing" a change updates the versioned `app_configurations` and broadcasts a `SYNC_CONFIG` command via WebSockets. Must write through `service_role` or a dedicated `SECURITY DEFINER` RPC -- **not** a device's own authenticated session, and not a portal user's session directly either, now that `app_configurations` is locked down the same way `vip_cards` is (`REVOKE UPDATE, INSERT, DELETE ... FROM anon, authenticated`, added 2026-07-24 after this doc's review surfaced that it was previously wide open).
*   **Branding Control**: Remotely update machine Logos and theme colors.

### 3.3 [MVP] Voucher & Coupon Hub (Lifecycle Management)
The Voucher Hub is designed to drive consumer traffic and resolve field service issues. It implements a strictly audited issuance and redemption loop.

#### 3.3.1 Issuance Workflows
*   **Marketing Campaigns (Bulk)**: 
    *   Operators define campaign parameters (e.g., "Summer Splash 2026").
    *   Support for **Static Codes** (one code like `WASH20` used many times) and **Dynamic Codes** (a batch of 1,000 unique single-use codes).
    *   Parameters: `org_id` scope, `vertical_type` restriction, `expires_at`, and `max_uses`.
*   **Service Compensation (One-Click)**: `[REVISED 2026-07-24]` Issuer is `MERCHANT_ADMIN` (the tenant's own admin), **not** GoldSky `OPS_STAFF` -- keeps issuance inside the same org-isolation boundary as everything else in §5.2. No approval step; the admin fills in the amount and use-count themselves at issuance time (no fixed $-preset). See `docs/coupon_redemption_integration.md` §5 for the full decision record.
    *   Surfaced in the **Dynamic Transaction Monitor** so a `MERCHANT_ADMIN` can spot a failed hardware activation (missing ACK) and click "Compensate" against that specific transaction.
    *   The system generates a `FIXED_OFF` coupon with `issued_reason='COMPENSATION'`, `related_transaction_id` set to the failed order, and admin-entered `value`/`max_uses`/`expires_at`; optionally emails/SMS it to the customer.
    *   `coupons.issued_by_profile_id` records who issued it. `[SCHEMA DONE 2026-07-24]` -- `profiles`/`org_members` (MVP scope: `SYS_ADMIN`+`MERCHANT_ADMIN` only, see §2) and the `issue_compensation_coupon()` SECURITY DEFINER RPC (checks the caller's `org_members` row, generates the code server-side, writes both `coupons` and `audit_logs`) are live in `docs/supabase_full_schema.sql`. Still missing: the actual portal "Compensate" button (§3.3.1's Dynamic Transaction Monitor UI) and human Supabase Auth sign-in itself (§2 item 1) to call it through.
*   **Member QR Enrollment**:
    *   Automated generation of 12-character alphanumeric codes (text, not numeric-only -- revised 2026-07-24) for every `vip_cards` record. **Confirmed distinct from `card_uid`** (existing seed data like `"VIP_CARD_UID_6789"` doesn't match this format either) -- this needs its own new column, e.g. `vip_cards.qr_code TEXT UNIQUE`, resolved to `card_uid` by the terminal before calling `deduct_vip_balance()`. See `docs/coupon_redemption_integration.md` §2.1 for the terminal-side lookup this implies.
    *   Provides a digital alternative to physical NFC cards, allowing "Scan-to-Identify" at the terminal.

#### 3.3.2 Operational Controls
*   **Inventory Status**: Real-time tracking of "Remaining vs. Total" uses for each batch.
*   **Administrative Kill-Switch**: One-click revocation (deactivation) of a specific coupon or an entire campaign if fraud is detected.
*   **Audit Trail**: Every voucher created is linked to the `profiles.id` of the issuer, preventing internal abuse of compensation credits. `[SCHEMA DONE 2026-07-24]` -- `coupons.issued_by_profile_id` now has a real FK to `profiles(id)`, and `issue_compensation_coupon()` writes a matching `audit_logs` row on every issuance (see §3.3.1). A portal view to actually browse `audit_logs` is separate, not-yet-built work.

#### 3.3.3 Redemption & Funnel Analytics
*   **Conversion Tracking**: CMP calculates the "Redemption Rate" (Issued vs. Redeemed) to measure marketing effectiveness.
*   **Location Heatmaps**: Visualizes which sites have the highest voucher usage, helping operators optimize regional promotions.
*   **Fraud Detection Dashboard**: Flags suspicious patterns, such as the same code being scanned across different cities within minutes.

### 3.4 [MVP] VIP Membership & Loyalty Ledger
The CMP provides operators with a full digital ledger for their VIP card program, replacing physical front-desk terminals with a centralized web interface.

#### 3.4.1 Provisioning & Lifecycle
*   **Card Issuance**: Admins register physical NFC cards by their UID and optionally link them to a consumer's email/name.
*   **Digital Onboarding**: For consumers without physical cards, the system generates a **Member QR** code (§3.3.1) that can be scanned at any terminal within the organization.
*   **Balance Management**: 
    *   **Top-ups**: Admins can credit a card balance (e.g., "Add $50") via a `SECURITY DEFINER` RPC that ensures the transaction is atomic and audited.
    *   **Freezes/Deactivations**: Instant remote deactivation of lost or compromised cards to protect consumer funds.

#### 3.4.2 Financial Auditing & Loyalty Analytics
*   **Deduction History**: Real-time view of every "Spent At" event, showing the specific terminal SN, amount, and timestamp.
*   **Merchant Reconciliation**: Aggregated reports showing total VIP liability (unspent balance across all cards) and monthly revenue recognized via VIP deductions.
*   **Churn Prediction**: Highlighting members who haven't used their card in 30+ days for targeted marketing re-engagement.

### 3.5 [MVP] Multi-Vertical Media Engine (Advertising)
The media module manages the distribution of high-definition marketing content across the fleet, optimized for low-bandwidth 4G environments.

#### 3.5.1 Content Distribution Pipeline
*   **Asset Management**: Centralized repository of videos and images. The CMP automatically calculates **MD5 Fingerprints** for every file to enable incremental terminal-side syncing and integrity verification.
*   **Playlist Orchestration**: 
    *   Operators group assets into playlists.
    *   Targeting can be set at the **Vertical level** (e.g., "All Car Wash machines") or **Site level** (e.g., "Only Toronto Downtown").
    *   `play_order` allows for precise sequencing of marketing loops.
*   **Delta Sync Control**: The system only notifies terminals to download a file if the cloud MD5 differs from the last-reported local MD5, significantly reducing data usage.

#### 3.5.2 Playback Monitoring & Reporting
*   **Proof of Play**: CMP aggregates `play_event` signals from terminals to provide reach and exposure reports to advertisers.
*   **Operational Health**: Visual indicators for machines that have failed to download their assigned assets (e.g., due to "Storage Full" or "Low Signal").

### 3.6 [Future] Dynamic Transaction Monitor (DTM)
*   **Real-time Stream**: Watch sales as they happen across all locations.
*   **Fault Detection**: Automatically highlight "Charged but not Vended" transactions (Bank PAID vs. Hardware ACK mismatch).

### 3.7 [Future] Financial Settlement
*   **Payout Calculation**: Aggregating transactions into merchant settlement batches.
*   **Commission Engine**: Automated fee splitting between GoldSky and distributors.

---

## 4. Dashboard & Visualization Design (Inspired by Nayax Core)

> **Detailed Specification**: For full mathematical formulas, Bento-box grid specifications, real-time WebSocket protocol flows, and interactive drill-down drawers, refer to [dashboard_design_specification.md](file:///c:/workspace/gs-ssp/docs/dashboard_design_specification.md).

The CMP dashboard is designed as a centralized "Command Center" that provides actionable insights through hierarchical views. It differentiates between long-term strategic analysis and immediate field operations.

### 4.1 Global Operational Dashboard (The "MoMa" Style)
Focused on real-time fleet health and immediate field-service needs. Useful for GoldSky staff and Merchant Technicians.
*   **Fleet Status Summary**: High-visibility cards showing `TOTAL DEVICES`, `ONLINE`, `OFFLINE`, and `FAULT`.
*   **Live Transaction Ticker**: A scrolling feed of authorizations. Each row includes a "Hardware Proof" badge (Green check if Serial ACK received, Red 'X' if pulse failed).
*   **Smart Alert Carousel**: Prioritized list of system-generated alerts (e.g., "Device SN_XYZ: High temperature", "Device SN_ABC: Power Loss").
*   **Active Map View**: Geographic distribution of terminals with color-coded pins. Clicking a pin opens a "Quick-Action" drawer for `REBOOT` or `LOCK`.

### 4.2 Financial & Strategic Dashboard (The "Core" Style)
Focused on revenue performance and strategic growth for Merchant Owners and Regional Managers.
*   **Sales Performance**: Multi-line charts comparing "Today" vs "Same Day Last Week" or "Monthly Average".
*   **Payment Method Mix**: Distribution chart showing `CREDIT CARD` vs `MOBILE QR` vs `VIP CARD` vs `VOUCHER`. Helps merchants decide which payment types to promote.
*   **Underperformer Insight**: Automated detection of machines with zero sales in the last 4 peak hours, suggesting potential hardware or placement issues.
*   **Loyalty Conversion**: Funnel visualization showing `Coupon Issued` -> `Scan Detected` -> `Successful Wash`.

### 4.3 Diagnostic Terminal View (The "Black Box" Drill-down)
Deep-dive view for a single machine, providing the granularity needed for "desk-side" repair.
*   **Hardware Vitals Radar**: Real-time signal strength (RSSI), storage availability, and memory usage.
*   **Command Execution History**: Audited list of all remote commands sent to the machine and their exact completion time.
*   **Remote Console**: A window displaying the live `Logcat` stream when requested via the `FETCH_LOGS` command.

### 4.4 Advanced UI & UX Design System Specification (正式纳入的先进 UI 设计规范体系)

The CMP formally incorporates next-generation industrial UI/UX design standards—combining modern SaaS minimalism (Vercel/Linear), Nayax Core operational density, and Smart Command Center (智慧大屏) aesthetics—into the core system specification:

1. **Bento Box Modular Architecture (便当盒高密度模块化布局)**:
   - Grid-based, high-density card container layout. Replaces sprawling traditional admin tables with scannable, prioritized visual modules.
   - Fixed aspect-ratio cards with clear typographic hierarchy and unified border radii (`rounded-xl` / `rounded-2xl`).

2. **Glassmorphism & Technological Spatial Depth (毛玻璃材质与高科技空间深度)**:
   - Layered translucent containers (`bg-slate-900/80 backdrop-blur-md`) with ultra-fine borders (`border-white/10` or `border-slate-800`).
   - Multi-layer drop shadows (`shadow-2xl shadow-black/50`) providing clear Z-index spatial depth between background telemetry feeds and foreground control drawers.

3. **Curated Color System & High-Contrast Status Tokens (精调 HSL 色彩体系与高对比度状态语义)**:
   - **Base Canvas**: Deep Slate `#0F172A` / Industrial Midnight `#0B0F17`.
   - **Emerald `#10B981`**: ACK Verified, Healthy Device, Positive Sales Growth.
   - **Amber `#F59E0B`**: System Alerts, Low Sales Warning, Compensation Pending.
   - **Rose/Red `#EF4444`**: ACK Missing (扣款未洗车), Hardware Fault, Communication Offline.
   - **Electric Blue `#3B82F6`**: Active Command Dispatch, WebSocket Telemetry Feed, Primary Action Triggers.

4. **Dynamic Micro-animations & Psychological Feedback (动态微交互与脉冲反馈)**:
   - **Telemetry Pulses (`animate-pulse`)**: Real-time visual pulses triggered whenever a successful hardware ACK or WebSocket transaction arrives.
   - **Odometer Odometer Counter**: Mechanical flip / smooth digital animation on top-level revenue counters to reinforce live telemetry activity.
   - **Hover Scaling (`hover:scale-102`)**: Micro-physics feedback on interactive device nodes and command buttons.

5. **Contextual Slide-Over Drawers & Non-Disruptive Modals (上下文非中断抽框与模态交互)**:
   - Slide-over drawers (Device Black-Box Drawer) keep the user grounded in their macro operational dashboard view while performing deep-dive Logcat troubleshooting or remote command execution.

---

## 5. Technical Architecture (Technical Route)

The CMP follows a "Serverless-First" technical route to ensure rapid scalability, low maintenance, and high availability.

### 4.1 Frontend Stack (The Management Portal)
*   **Framework**: **Next.js (React)** using the App Router.
*   **Component Architecture**: **Tailwind CSS + Shadcn UI** for a clean, industrial-grade interface.
*   **State & Sync**: **TanStack Query (React Query)** for high-performance data fetching and real-time UI synchronization.
*   **Interactive Maps**: Mapbox or Google Maps SDK for device location tracking.

### 4.2 Backend & Communication
*   **Compute**: **Supabase Edge Functions (Deno)** for processing webhooks and orchestrating multi-step commands. `[MATCHES CURRENT SCHEMA]` -- `supabase/functions/create-qr-session` and `payment-webhook` already exist and are deployed as of 2026-07-23, using this exact pattern (service_role writes, gateway-agnostic adapter interface). Follow that precedent rather than a new pattern.
*   **Database**: **PostgreSQL** with logical partitioning for high-volume telemetry tables (`heartbeats`, `transactions`). `[NOT IMPLEMENTED]` -- no partitioning exists today. The current interim approach is `cleanup_old_telemetry()`, a retention-window DELETE (default 90 days) that isn't yet wired to run automatically (needs `pg_cron`, commented out pending plan/extension availability). Partitioning is a reasonable answer *if* telemetry volume actually grows enough to need it -- don't build it ahead of that, per this doc's own "avoid premature complexity" principle; wiring up the existing cleanup function's cron schedule is the cheaper near-term fix for unbounded table growth.
*   **Downlink (Push)**: **Supabase Realtime (WebSocket)** for instant command delivery to IM30 terminals. `[MATCHES CURRENT SCHEMA]` -- `device_commands` table + `RemoteCommandManager.kt` + `ALTER PUBLICATION supabase_realtime ADD TABLE device_commands` already implement exactly this.
*   **Uplink (Pull)**: RESTful API for reliable status reporting and log uploads.

---

## 5. Security Design (Defense-in-Depth)

Security is woven into the fabric of the CMP to protect financial data and hardware integrity.

### 5.1 Identity & Access Management (IAM)
*   **Web Access**: Multi-Factor Authentication (MFA) is required for GoldSky admins and encouraged for merchant owners. `[NOT IMPLEMENTED]` -- see §2; there is no web login of any kind yet to apply MFA to.
*   **Device Access**: Every IM30 terminal uses PAX Serial Number (SN) as its hardware root-of-trust, mapped to a unique anonymous auth session via `device_auth_map`. `[MATCHES CURRENT SCHEMA]` -- accurately describes the `sync_device_identity()` RPC + `device_auth_map` mechanism already built and verified end-to-end (2026-07-23).

### 5.2 The RLS Security Pillar
The core of data isolation is **PostgreSQL Row Level Security (RLS)**:
*   **Tenant Isolation**: Policies ensure that a merchant can *never* query or modify data belonging to another `org_id`. `[MOSTLY TRUE, ONE GAP FOUND AND FIXED]` -- true for `transactions`/`heartbeats`/`app_error_logs`/`device_shadows`/`qr_payment_sessions`/`products` (org-scoped RLS via `device_auth_map`). **Was false for `app_configurations`**: this table had neither RLS nor a `REVOKE`, so any authenticated device session could write to *any* org's config row, not just its own -- found during this doc's review, fixed 2026-07-24 with the same `REVOKE UPDATE, INSERT, DELETE FROM anon, authenticated` pattern `vip_cards` already used. Worth an explicit pass over every table before writing more sections like this one that assert a security property as fact -- `ENABLE ROW LEVEL SECURITY` and `REVOKE` are both opt-in per table in Postgres, so "the platform enforces this" needs to be checked per table, not assumed from the general design intent.
*   **Privilege Minimization**: Terminals use limited-scope tokens. Only administrative accounts (GoldSky/Merchant Admin) can write to configuration tables. Now true for `app_configurations` (see above); still depends on §2's human-auth system existing before "administrative accounts" means anything concrete rather than just "service_role."

### 5.3 Hardware Command Security
*   **Payload Integrity**: Commands are signed to ensure they originated from the CMP. `[NOT IMPLEMENTED]` -- `device_commands`/`RemoteCommandManager.kt` currently trust anything delivered via the Realtime subscription with no signature check. Not exploitable by an outside attacker today only because writing to `device_commands` already requires an authenticated session scoped by existing RLS -- but that's tenant isolation, not payload authenticity from "the CMP" specifically. Worth building before this doc claims it as done.
*   **Replay Protection**: Versioning and timestamps are used in the Device Shadow to prevent stale or duplicate commands from executing on the hardware. `[PARTIAL]` -- `device_shadows.version` exists and increments, but nothing in `ShadowManager.kt` currently rejects an out-of-order/duplicate apply based on it; the column is there, the enforcement logic isn't yet.

### 5.4 Audit & Compliance
*   **Insert-Only Audit Trail**: All administrative actions (e.g., locking a device, changing a price) are recorded in an immutable `audit_logs` table. `[TABLE DONE 2026-07-24, MOSTLY UNUSED]` -- `audit_logs` exists in `docs/supabase_full_schema.sql` (RLS enabled, no policies, `SECURITY DEFINER`-RPC-write-only, same lockdown pattern as `coupons`). Only one writer so far: `issue_compensation_coupon()` (§3.3.1). Every other admin action this bullet describes (locking a device, changing a price) still has no portal/RPC to perform it in the first place, so there's nothing yet to log for those.
*   **Financial Reconciliation**: Automated background workers cross-reference bank success signals with hardware ACK receipts to detect fraud or hardware faults. `[PARTIAL]` -- the *data* this needs already exists (`transactions.payment_status` vs `hardware_status`, plus the VOID/REFUND fault-tolerance logic in `PaymentService.voidOrRefund()`), and it's handled reactively on-device per-transaction. A fleet-wide background worker that proactively scans for mismatches across all locations is what's actually missing -- this is real, scoped work, not a restatement of something already done.

---

## 7. Development Readiness Checklist (2026-07-24)

This design is a target architecture, not yet a buildable spec. Before an engineer can pick up a CMP ticket, the following gaps need closing -- grouped by whether they block everything else or just need more detail written down.

### 7.1 Blocking prerequisites
Nothing else in this doc has a subject/foundation to attach to until these exist:
1. **Human IAM subsystem (§2)** -- `[PARTIALLY DONE 2026-07-24]` `profiles`/`org_members` (MVP: `SYS_ADMIN`+`MERCHANT_ADMIN`) exist in `docs/supabase_full_schema.sql`; **still missing**: actual Supabase Auth login for humans (no portal exists to sign in from) and MFA (§5.1). Broad parallel RLS was deliberately not built wholesale -- see §2 item 4's note on doing authorization inside RPCs instead, module by module.
2. **`organizations.parent_id` migration (§1)** -- `[DONE 2026-07-24]` column exists (nullable, `ON DELETE SET NULL`). The `DISTRIBUTOR` role (§2) and any regional/multi-level view are still unbuildable, though -- the column alone doesn't add the role to `org_members.role`'s CHECK or write the recursive queries that would actually use it.
3. **`audit_logs` table (§5.4)** -- `[DONE 2026-07-24]` exists, written by `issue_compensation_coupon()`. "Who issued this compensation coupon" is now answerable; every *other* admin action this doc describes still has no writer for it, since the actions themselves (price publish, remote lock, etc.) aren't built yet either.

### 7.2 Spec-level gaps (module named, not yet specified enough to build against)
4. No **API/RPC contract** for the portal-facing Edge Functions (price publish, voucher issuance, remote command trigger, log fetch) -- §4.2 names the pattern but not the request/response shapes.
5. **Voucher Hub (§3.3)** depends on the `coupons`/`coupon_redemptions` schema and `redeem_coupon()`/`issue_compensation_coupon()` RPCs, and **Member QR (§3.3.1)** depends on `vip_cards.qr_code` -- `[SCHEMA + IM30 REDEMPTION SIDE DONE 2026-07-24]` all live in `docs/supabase_full_schema.sql`, and the IM30 app-side scan/redeem/apply-discount flow is wired up (see `docs/coupon_redemption_integration.md`). Still open: the portal-side issuance UI (campaign builder, the "Compensate" button in the Dynamic Transaction Monitor) -- the RPCs exist to call, nothing calls them yet.
6. **Fraud Detection Dashboard (§3.3.3)** -- "flags suspicious patterns" has no defined rule (query, threshold, or background job) behind it yet.
7. No **page/route inventory** for the Next.js frontend -- §4.1 lists the stack but not the actual page list (device list, device detail, pricing editor, voucher campaign builder, etc.).
8. No stated **auth strategy for the portal's own Supabase calls** -- devices use anon key + RLS; the portal should go through Edge Functions with service_role only (never expose service_role to the browser), but this isn't written down anywhere in the doc yet.

### 7.3 Real but deferrable (already correctly marked below, listed here for traceability)
`device_commands` payload signing (§5.3), device-shadow version replay enforcement in `ShadowManager.kt` (§5.3), `pg_cron` wiring for `cleanup_old_telemetry()` (§4.2), telemetry partitioning (§4.2), fleet-wide reconciliation worker (§5.4) -- these harden an already-running system rather than gate CMP's first build.

---

## 8. Conclusion
This design ensures that GS-SSP remains **agile**. We build the "Remote Black Box," "Pricing Manager," and "Voucher Hub" first to solve 90% of operational headaches, while the recursive hierarchy and JSONB attributes ensure the system can grow into a 100,000-device global platform when needed.
