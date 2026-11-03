# Aegis Vend: VMC simulator (no vending machine)

There is no vending machine to test with yet (owner, 2026-11-02), so we play the machine
ourselves. Two layers:

1. **Software VMC (this document)**: a build of Aegis Vend where the vending machine is
   simulated and driven over adb; the card payments are **real** (PAYWizard / Nuvei).
   Tests everything except the MDB wire protocol: sale, PreAuth / AuthCompletion /
   AuthVoid on Nuvei (and whether Interac accepts a pre-authorization), give-back on a
   jam or a silent machine, crash recovery, the transaction rows in Cael.
2. **Hardware VMC (later)**: WizarPOS's MDB master test board on a PC playing the machine
   on the Q3mini's MDB port, for Begin Session / Vend Request / Session Complete on the wire.

## WizarPOS material (downloaded 2026-11-02 to `C:\goldsky\WizerPOS SDK\MDB\`)

From the old revision of sdkwiki "MDB Communication Protocal" (oldid 3377; the live page
moved to gitbook `cloudpos-sdk/mdb-communication-protocal`):

- **Reader side**: the terminal is the cashless slave; the app starts a transaction with
  **Begin Session (MDB 03H + funds available, 2 bytes)** and may end it with **Session
  Cancel Request (04H)**; the VMC then sends Vend Request, the app approves / denies, the
  VMC reports Vend Success / Failure. POLL is answered by the board itself.
- Our `ExtBoardDevice` path (board v14) does this through events: `respondEvent` with
  `MDBEvent.TYPE_BEGIN_SESSION` (amount = funds) / `TYPE_VEND_CANCEL`. The mapping to
  03H / 04H is inferred, to confirm on the wire.
- Amount scale (sdkwiki "SDK杂记"): factor 1, decimal places 2 -- what 255's board has.
- `MDB_Version_4-3.pdf`: the MDB/ICP 4.3 spec.
- `MDBTest/`: WizarPOS's terminal demo (older raw-serial Q3V protocol, 115200 baud,
  frames 09 len mode data LRC 0D).
- `vmc_master_tool/`: **WizarPOS's VMC simulator for a PC** (Python + `dist/Q3V-test.exe`):
  talks Modbus RTU at 9600 baud over a COM port to an MDB master board, which plays the
  vending machine on the terminal's MDB port -- reset, vend request (item, price), dispense
  ok / failed, cashless status. Needs that board (sdkwiki "Hardware": "MDB板连到PC", RS232
  driver from WizarPOS) -- ask WizarPOS for it.
- `MDBForCoffeeDemousage20220725.pdf`: how they wire PC + board + terminal.

## WizarPOS answers (2026-11-02)

`ExtBoardDevice_MDB_Methods_Guide.docx` (same folder) and the demo
github.com/SmartPOSSamples/MDBTestForCloudSDK (cloudpossdk 1.8.2.28):

- Order: setCashlessAddress (1, default) -> setConfig / setOption -> enableAutoBeginSession
  -> **startLoop** -> pollEvent / respondEvent -> stopLoop. pollEvent timeout > 0 or FOREVER.
- `MDBConfig.currencyCode` is ISO 4217 numeric (840 = USD): we set 124 (CAD) at open; the
  board reads its MDB form back (0x1840 = USD before, 0x1124 = CAD).
- Demo answers: VEND_REQUEST -> a new event VEND_APPROVED, eventResult 1, eventAmount = the
  request's / VEND_DENIED, eventResult -1, amount 0. The machine's VEND_CANCEL -> answer
  VEND_CANCEL, eventResult 0. TRANSACTION_START -> BEGIN_SESSION with the funds, unless
  Always Idle on both sides. All done this way in WizarPosMdbProvider.
- WizarPOS's answers: VEND_CANCEL sent by us ends the session (board sends Session Cancel,
  VMC and reader back to idle). `setConfigStateCheck(false)` at open, as the demo: with the
  check on, a VMC with a non-standard SETUP / CONFIG handshake gets Begin Session and
  vends blocked. Both done.
- The MDB232 converter box for the PC simulator: on Taobao.
- `setConfig` is not kept by the board: after a restart it reads 0x1840 (USD) again, so
  the app sets CAD on every open.

## Build and install

```bash
./gradlew :app:aegis-vend:signRelease -Pvmcsim
# -> app/aegis-vend/build/outputs/apk/release/aegis-vend-<v>-VMCSIM-signed.apk
adb -s <terminal> install -r app/aegis-vend/build/outputs/apk/release/aegis-vend-<v>-VMCSIM-signed.apk
```

Same application id and signing key as the release, so it installs over it (device secret
kept) and the release goes back over it the same way:
`./gradlew :app:aegis-vend:signRelease` then `adb install -r ...-signed.apk`.

The simulator build shows a red **VMC SIMULATOR · TEST ONLY · REAL CARD PAYMENTS** bar on
every screen and `ver <v>-vmcsim`. Never install it on a store's terminal (no demo /
simulation modes on Aegis, owner 2026-10-31). The flag (`BuildConfig.VMC_SIM`) is false
in every build without `-Pvmcsim`.

## Playing the machine

```bash
tools/vmc-sim/vmc-sim.sh -s 10.0.0.168:5555 select 25 1   # customer chose item 1 at $0.25
tools/vmc-sim/vmc-sim.sh -s 10.0.0.168:5555 fail          # next vend jams
tools/vmc-sim/vmc-sim.sh -s 10.0.0.168:5555 silent        # machine never answers the next vend
tools/vmc-sim/vmc-sim.sh -s 10.0.0.168:5555 log           # follow the vend log
```

Without `fail` / `silent` the simulated machine reports the vend done 2 s after approval.

## Test plan (255, live Nuvei: small amounts; a person taps the card)

| # | Steps | Expect on screen | Expect in money / Cael |
|---|---|---|---|
| 1 | `select 25 1`, PAY, tap card | Take your item, charged $0.25 | sale PAID, row PAID / SUCCESS with card brand |
| 2 | `fail`, `select 25 2`, PAY, card | The item did not drop, not charged | sale reversed, row VOIDED / FAILED |
| 3 | `silent`, `select 25 3`, PAY, card, wait 30 s | The item did not drop | reversed, row VOIDED / FAILED |
| 4 | START, card, `select 25 4` | Approved → Take your item, charged $0.25 | **PreAuth $10 then AuthCompletion $0.25**; row PAID $0.25 |
| 5 | START, card, wait 60 s | No item was chosen, not charged | **AuthVoid**: hold gone from the card; no row |
| 6 | START, card, CANCEL | Cancelled, not charged | AuthVoid; no row |
| 7 | `fail`, START, card, `select 25 5` | The item did not drop | AuthVoid; row VOIDED / FAILED |
| 8 | START, card, `select 1500 6` | Over the $10.00 limit | AuthVoid; no row |
| 9 | #4 with an **Interac debit** card | ? | does Nuvei/Interac accept PreAuth at all? |
| 10 | START, card, then `adb shell am force-stop com.goldsky.ssp.aegis.vend`, reopen | idle | VendRecovery releases the hold on start (log `VendRecovery`) |
| 11 | `select 25 7`, wait 60 s without paying | Cancelled | nothing |
| 12 | START, card, `mcancel` | Cancelled, not charged | AuthVoid; no row |
| 13 | `select 25 8`, PAY, `mcancel` before tapping, then tap | Cancelled | if the card goes through: reversed |

Afterwards: check the card statement / Nuvei portal for #4–#10 (hold amounts and
releases), and the rows on 255's store in Cael.
