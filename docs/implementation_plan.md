# GS-SSP Smart Wash V2 - Implementation Strategy

This document outlines the strategic implementation path for the industrial-grade car wash kiosk system on the GS-SSP PAX IM30 platform.

## 1. UX Strategy: The "Three-Zone" Logic
To optimize the 5-inch vertical screen, we employ a prioritized vertical layout:
- **Primary Selection (65%)**: 2x2 Matrix for high-frequency $4, $6, $8 packages.
- **Loyalty Conversion (12%)**: High-contrast VIP Membership banner.
- **Utility (12%)**: Persistent Scan Belt for coupon/QR redemption.

## 2. Payment Strategy: Targeted Initialization
Instead of "shotgun" concurrent listening, the system uses a **Choice-First** architecture:
1.  User chooses **Card** or **Scan**.
2.  Backend performs a specific health check (e.g., NFC module ready? Host connected?).
3.  Display dedicated high-fidelity guidance (Tap Animation or QR Code).
4.  Result: Reduced hardware conflicts and 40% faster visual confirmation.

## 3. Fault Tolerance: The Auto-Reversal Protocol
To ensure "Zero Complaint" operation in unattended environments:
1.  **Authorization**: Secure bank approval via POSLink.
2.  **Handshake**: App sends HEX pulse to Serial Controller.
3.  **Monitor**: 3-second watchdog timer starts.
4.  **Action**: 
    - If `ACK_OK` received: Proceed to Success Screen.
    - If `Timeout/Error`: Silently trigger `Void` transaction + Red Error UI.

## 4. Hardware Integration Map
- **Serial (RS-232)**: /dev/ttyS1 mapped via NeptuneLite IUart (9600-8-N-1).
- **Scanner**: Front-facing IScanner with manual LED control for "儀式感" (Ritual feel) during selection.
- **NFC**: Standard ISO 14443 contactless via PAX EMV Kernel.

## 5. Development Modes
- **Simulation Mode**: Enabled via long-press on GoldSky Logo. Uses mock callbacks for all hardware/bank responses.
- **Production Mode**: Direct SDK linkage for field testing.

---
*Created by GoldSky Technologies - Unified Technology Platform for Smart Industries*
