# Aegis Vend: VMC simulator (no vending machine)

There is no vending machine to test with yet (owner, 2026-11-02), so we play the machine
ourselves. Two layers:

1. **Software VMC (this document)**: a build of Aegis Vend where the vending machine is
   simulated and driven over adb; the card payments are **real** (PAYWizard / Nuvei).
   Tests everything except the MDB wire protocol: sale, PreAuth / AuthCompletion /
   AuthVoid on Nuvei (and whether Interac accepts a pre-authorization), give-back on a
   jam or a silent machine, crash recovery, the transaction rows in Cael.
2. **Hardware VMC (later)**: an MDB master adapter on a PC playing the machine on the
   Q3mini's MDB port, for Begin Session / Vend Request / Session Complete on the wire.

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

Afterwards: check the card statement / Nuvei portal for #4–#10 (hold amounts and
releases), and the rows on 255's store in Cael.
