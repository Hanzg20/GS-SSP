#!/bin/bash
# Plays the vending machine (VMC) for Aegis Vend's VMC-simulator build, over adb.
#
#   tools/vmc-sim/vmc-sim.sh [-s <adb serial>] <command>
#
#   select <cents> <item>   the customer chose <item> on the machine at <cents>
#                           (card first: after START + card; item first: on the idle screen)
#   fail                    the next vend jams (the money goes back)
#   silent                  the machine never answers the next vend (30 s, then money back)
#   mcancel                 the customer presses cancel on the machine
#   pair <code>             pair the network big screen showing <code> (Aegis Vend Screen)
#   start | pay             press START / PAY on the screen
#   log                     follow the vend log (Ctrl-C to stop)
#
# The card payments are REAL (live acquirer on 255): test with $0.10: select 10 1 (owner, 2026-11-02).
# Build / install the simulator: see docs/vmc_simulator.md.
set -e
ADB=${ADB:-adb}
SERIAL=()
if [ "$1" = "-s" ]; then SERIAL=(-s "$2"); shift 2; fi
APP=com.goldsky.ssp.aegis.vend/com.goldsky.ssp.MainActivity
# As HOME: a plain -n start opens a second instance in its own task beside the HOME one.
send() { "$ADB" "${SERIAL[@]}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "$APP" --es sim "$1" >/dev/null && echo "sent: $1"; }
case "$1" in
  select) [ -n "$2" ] && [ -n "$3" ] || { echo "usage: select <cents> <item>"; exit 1; }; send "select:$2:$3" ;;
  fail|silent|mcancel|start|pay) send "$1" ;;
  pair) [ -n "$2" ] || { echo "usage: pair <code>"; exit 1; }; send "pair:$2" ;;
  log) "$ADB" "${SERIAL[@]}" logcat -v time -s VendingVM MockMdb VendPayment VendRecovery WizarPosPayment OrderRepo VendScreenLink AegisVend ;;
  *) sed -n 2,17p "$0"; exit 1 ;;
esac
