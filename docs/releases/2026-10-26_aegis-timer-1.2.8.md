# Aegis Timer 1.2.8 -- next release after 1.2.7

1.2.7 is what 254 runs since 2026-10-25 night (AIDL card payments). Everything below came after
it (2026-10-26) -- first held as "still 1.2.7", renumbered 1.2.8 by the owner on 2026-10-27; the
store's 1.2.7 APK was rebuilt from f35791d, the code 254 actually has. Wash: 1.1.9 (payment page
countdown, below; `2026-10-27_aegis-wash-1.1.9.md`).

## Timer: $0.50 per pulse (owner, 2026-10-26)
Technician page "每脉冲" now offers $0.25 / $0.50 / $1.00 / $2.00 (single and dual-bay panels). At
$0.50 a $2 package sends 4 pulses, $3 sends 6. 254 went live 2026-10-26 on $0.25 x 500 ms; the
vacuum ran ~5 min for $2 and ~7 min for $3 (packages say 4 / 6). Switching 254 to $0.50 changes
the run time -- time it on site again and match the packages' minutes.

## Timer: two-unit home after the owner's layout (2026-10-26)
Per `C:\goldsky\info\VACCUM UI\NEW VACCUM UI.png`: unit 2 on the left, unit 1 on the right (where
they stand next to the terminal). Each title sits on its own colour -- 2 orange, 1 blue -- with an
arrow pointing out to that vacuum. Title 28sp -> 24sp so the arrow fits the column. Checked on 255.

## Timer: high-contrast colours for outdoor sun (owner, 2026-10-26)
The vacuum stands outdoors and the navy + amber screens washed out in full sun. Customer screens
are now a white page with near-black bold text and solid fills: unit 2's column deep orange, unit
1's deep blue (each unit's own colour, also on its payment pages), price buttons white with black
text, running frame black (amber in the last 30 s); single-unit cards solid amber with a black
START; darker green / red / amber for text on white. Technician pages keep the dark palette.

## Timer: payment page like Wash (owner, 2026-10-26)
Choosing a package no longer goes straight to the card. It opens Wash's payment page: "Pay $2 ·
4 min", **Pay by Card** / **Scan Coupon / VIP**, Cancel -- Wash's words and voice prompts. Scan
opens the camera; a VIP code or coupon goes to the existing Confirm page (nothing spent before
Confirm). No code read, camera down, balance short, coupon for another package, or a VIP / coupon
refused after Confirm: back to the payment page with the reason in red, the card one tap away
(before, a refused VIP went to Home with a 5 s line). KioskSettings.payment_method_mode CARD_ONLY
skips the page straight to the card, SCAN_ONLY straight to the camera. Untouched for 30 s: Home (60 s until 2026-10-27).
Home has no "Coupon / VIP code" button any more (owner): codes are scanned only on the payment
page, after the package is chosen. The Home-scan path (code waiting for a package, pulsing price
buttons, banner) is gone with it.

Checked on 255 (test org), 2026-10-26: unit 1 $2 -> payment page -> card, $0.10 test sale
approved (TEST_1793064176131), pulses sent, countdown on Home; another org's VIP code refused;
new test-org VIP card paid $3 for unit 1 (balance $100 -> $97, ACK_RECEIVED).

## Timer: key voice prompts three times (owner, 2026-10-26)
Every step that waits for the customer, and every outcome, is said up to three times (TtsManager
.speakRepeated): payment page, scan, card ("Please tap, insert or swipe your card."), Confirm
(new: "Pay $3.00 from your VIP balance? Tap Confirm." / "Coupon accepted…"), approved / unit on /
time added, payment cancelled or not completed (new), machine could not start, 30 s left (new on
the two-unit home, per unit), time is up. Repeats stop when the customer acts, when another prompt
starts, or when the page goes back to Home (the sentence being said finishes) -- an outcome on a
4 s result page is said once or twice, not over Home. Operator remote starts are said once.

## Timer: pulse adjust per unit (owner, 2026-10-26)
Technician page, each unit (and the single-unit coin mode): "脉冲增减" − / +, -20..+20, default 0.
Pulses sent per purchase = price / value per pulse + adjust, never under 1; the line below shows
what $2 / $3 send. Saved on the terminal (`timer_output` prefs, `pulse_adjust`). For 254 at $0.25 a
pulse, -2 should give ~4 / ~6 min for $2 / $3 (from the 5 / 7 min measured) -- time it on site,
and also time a purchase that adds to a running vacuum (the adjust applies there too).

## Timer: pulse animation (owner, 2026-10-26)
While coin pulses go out the time countdown is replaced by a pulse ring: one segment per pulse,
lit as each is sent, the one in flight blinking, a scan glint circling inside, the pulses still
to send counting down in the middle under a small ϟ. Shown on a new "Starting…" page right after
payment (the unit's colour tag on top), then on the result page and in the unit's column on Home
while the train lasts; its final state (all lit, 0) stays 1 s, so a 1-pulse train is seen too.
TimerOutput.start reports (sent, total); demo mode plays one pulse per 50c (2..12), 0.4 s each.
Checked on 255, 2026-10-26.

## VIP lookups scoped to the terminal's organization (database, 2026-10-26)
`docs/migrations/2026-10-26_vip_lookup_org_scoped.sql`, applied to prod: a member code / card of
another organization is "not found" -- no balance shown -- instead of showing the balance and
failing only at the deduction. No app change.

## Payment page: card starts by itself after 4 s (owner, 2026-10-27)
Customers used to tap their card right after choosing a package, while the payment page was
still waiting for a choice and the reader was off. Now, the first time on the page (both
methods allowed): a dark line "Please choose how to pay — otherwise card payment starts", the
card button blinking "Card reader on in 4… 3…" with a bar running out, spoken once "Please
choose how to pay. Otherwise, card payment starts." After 4 s the card sale starts (PAYWizard
asks for the card). Scan / Cancel / Pay by Card stop the countdown. A sale started this way and
cancelled on the card screen comes back to the payment page ("Payment cancelled. No charge was
made.", no countdown) -- the customer may have wanted Scan; a cancel after tapping Pay by Card
still goes Home. Coming back with a reason (no code read, declined, ...) never counts down.
Single-method stores (payment_method_mode CARD_ONLY / SCAN_ONLY) are unchanged.

Checked on 255, 2026-10-27 (Nuvei, no card tapped, nothing charged): Wash $4 and Timer
vacuum 1 $2 -- hint, countdown, card screen after 4 s, back arrow -> payment page with the
cancel line (CARD_1793134280013 / TIMER_1793134728082 CANCELLED); Pay by Card again started a
new sale at once. A sale while the other app's sale is still open on PAYWizard gets "system is
busy" (-11) -- only on a terminal with both apps (255).


## Payment page left alone goes Home after 30 s (owner, 2026-10-27)
After the card screen timed out the customer is back on the payment page; untouched it went Home
after 60 s, now 30 s (the same as Wash 1.1.9, whose page had no limit before).


## Install
254 via TMS (`aegis-timer-1.2.8-signed.apk`). After installing, on the technician page: set
each unit's pulse adjust (254 at $0.25 a pulse: try -2) and time $2, $3 and a purchase that adds
to a running vacuum. Check Default home in the system check.
