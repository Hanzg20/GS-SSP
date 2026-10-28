# Aegis Timer 1.2.9 -- next release after 1.2.8

1.2.8 was already out (owner, 2026-10-28), so this fix gets its own number. Everything in
1.2.8 (`2026-10-26_aegis-timer-1.2.8.md`) is included. Wash's copy of the fix is in 1.1.9.

## PAYWizard reachable again after an OPC update (2026-10-28) -- urgent
WizarPOS pushed OPC 2.19.5-global by TMS on 2026-10-28 (255 at 09:10). After that every card sale
failed at once: "Payment service busy (P3 handshake no reply)" (CARD_READER_FAULT; 253 at 10:06,
255 too). Android said the OPC AIDL service was "not found" -- the apps never declared
com.wizarpos.opc under <queries> (package visibility, Android 11+); the old OPC install was
visible only through earlier interaction, which the reinstall reset. The socket fallback can't
help (OPC on Default has no port 6031). Fix: com.wizarpos.opc added to <queries> (core/data
manifest). A reboot does not fix it.

Checked on 255, 2026-10-28 10:28 (no card tapped, nothing charged): $4 -> OPC bound -> Nuvei asked
for the card -> timed out (CARD_1793197692982, -139 cancelled).

## Install
254 via TMS (`aegis-timer-1.2.9-signed.apk`) -- urgent if 254 has OPC 2.19.5: with 1.2.8 or
older no card sale gets through. After installing: check Default home in the system check,
then one small card sale.
