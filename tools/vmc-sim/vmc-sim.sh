#!/bin/bash
# Plays the vending machine (VMC) for Aegis Vend's VMC-simulator build, over adb.
#
#   tools/vmc-sim/vmc-sim.sh [-s <adb serial>] <command>
#
#   select <cents> <item>   the customer chose <item> on the machine at <cents>
#                           (card first: after START + card; item first: on the idle screen)
#   fail                    the next vend jams (the money goes back)
#   silent                  the machine never answers the next vend (30 s, then money back)
#   start | pay             press START / PAY on the screen
#   log                     follow the vend log (Ctrl-C to stop)
#
# The card payments are REAL (live acquirer on 255): use small prices, e.g. select 25 1.
# Build / install the simulator: see docs/vmc_simulator.md.
set -e
ADB=${ADB:-adb}
SERIAL=()
if [ "$1" = "-s" ]; then SERIAL=(-s "$2"); shift 2; fi
APP=com.goldsky.ssp.aegis.vend/com.goldsky.ssp.MainActivity
send() { "$ADB" "${SERIAL[@]}" shell am start -n "$APP" --es sim "$1" >/dev/null && echo "sent: $1"; }
case "$1" in
  select) [ -n "$2" ] && [ -n "$3" ] || { echo "usage: select <cents> <item>"; exit 1; }; send "select:$2:$3" ;;
  fail|silent|start|pay) send "$1" ;;
  log) "$ADB" "${SERIAL[@]}" logcat -v time -s VendingVM MockMdb VendPayment VendRecovery WizarPosPayment OrderRepo ;;
  *) sed -n 2,15p "$0"; exit 1 ;;
esac
