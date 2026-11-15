# InpuLync

<p align="center">
  <img src="docs/images/inpulync-logo.png" alt="InpuLync Logo" width="240" style="border-radius: 24px; box-shadow: 0 8px 24px rgba(0,0,0,0.4);">
</p>

<h3 align="center">Your Phone is the Peripheral</h3>

<p align="center">
  <b>A completely local, offline keyboard, multi-touch trackpad, and gamepad for Android.</b><br>
  <i>Zero Cloud • Zero Tracking • Driverless Bluetooth HID • Secure USB Loopback Companion</i>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%209.0%2B%20(API%2028%2B)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Bluetooth-Native%20HID%20Device-0082FC?style=flat-square&logo=bluetooth&logoColor=white" alt="Bluetooth HID">
  <img src="https://img.shields.io/badge/Privacy-100%25%20Local%20%2F%20Offline-success?style=flat-square" alt="Privacy">
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="License">
</p>

---

## Highlights

- **Driverless Bluetooth HID**: Uses Android's native Bluetooth HID Device profile. Windows sees your phone as a genuine composite USB keyboard, mouse, consumer media remote, and standard DirectInput gamepad without needing any PC software installed.
- **PS3-Inspired Gamepad**: Dual analog clickable sticks (L3/R3), 8-way directional cross D-pad with active highlights, four face buttons, L1/R1, analog-ramping L2/R2 triggers, Select, PS, and Start. Supports simultaneous multi-touch stick and button gameplay.
- **Full Compact Keyboard**: Modifiers (Shift, Caps, Ctrl, Alt, Win/Super) with visual latch and chord indicators. Auto-repeating Backspace and navigation keys. Supports US and UK keyboard layouts.
- **One-Handed Portrait Trackpad Remote**: Hold the phone upright for a full-screen vertical trackpad with haptic scroll strip, left/right click buttons, and quick media controls. Rotate horizontally into landscape for the full desktop keyboard or gamepad.
- **Dedicated NUMPAD**: Open **TOOLS ▾ → NUMPAD** for a dedicated numeric keypad (0–9, arithmetic operators `+`, `−`, `×`, `/`, `Enter`, and repeating `Backspace`).
- **Media Controls**: Integrated media buttons (Play/Pause, Next Track, Previous Track, Volume Up/Down, and Mute) over Bluetooth Consumer Control and USB.
- **10 Switchable Themes**: Liquid-glass and neobrutalist palettes (Aurora, Frost, Midnight, Sunset, Ocean, Forest, Sakura, AMOLED, Neo Classic, and Custom with a 360° HSV hue slider). Procedural animated drifting Aurora background with a battery-saving motion toggle.
- **Direct Hardware Haptics**: Calibrated millisecond vibration pulses directly controlling the hardware vibrator, ensuring tactile feedback even when global system touch-feedback is disabled.
- **Hardened USB Bridge**: Optional companion tool over ADB loopback (`127.0.0.1:27183`) featuring mutual HMAC-SHA256 challenge-response authentication. Provides host battery telemetry, laptop panel brightness adjustment via WMI, and Unicode text injection.

---

## Visual Tour

<p align="center">
  <img src="docs/images/keyboard.png" alt="Full InpuLync keyboard" width="100%">
</p>

| Wide Trackpad & Scroll Strip | Compact Tools Drawer |
| :---: | :---: |
| <img src="docs/images/trackpad.png" alt="InpuLync trackpad" width="100%"> | <img src="docs/images/tools-drawer.png" alt="InpuLync tools drawer" width="100%"> |

| Function & Navigation Keys | Volume, Brightness & Battery |
| :---: | :---: |
| <img src="docs/images/function-keys.png" alt="Function key drawer" width="100%"> | <img src="docs/images/system-controls.png" alt="System controls" width="100%"> |

| Input Tuning | Controller Settings |
| :---: | :---: |
| <img src="docs/images/settings.png" alt="InpuLync input settings" width="100%"> | <img src="docs/images/settings-controller.png" alt="InpuLync controller settings" width="100%"> |

---

## Gamepad & Controller Layouts

| PlayStation Symbols | Xbox-Style ABXY | HID Numbers 1–4 |
| :---: | :---: | :---: |
| <img src="docs/images/controller-default.png" alt="PlayStation controller labels" width="100%"> | <img src="docs/images/controller-abxy.png" alt="Xbox-style controller labels" width="100%"> | <img src="docs/images/controller-1234.png" alt="Numbered controller labels" width="100%"> |

| Live Stick Position | Active Diagonal D-Pad | Swapped Pad / Sticks |
| :---: | :---: | :---: |
| <img src="docs/images/controller-input-live.png" alt="Left stick moving visibly" width="100%"> | <img src="docs/images/controller-dpad-active.png" alt="D-pad diagonal arms highlighted" width="100%"> | <img src="docs/images/controller-swapped.png" alt="Swapped controller layout" width="100%"> |

> [!TIP]
> Playing games on Steam? Follow our [Steam Input Setup and Exact Button Map](docs/steam-input.md).

---

## Bluetooth Mode

Bluetooth mode uses Android's native HID Device profile. Windows sees the phone as a standard Bluetooth composite device without needing any drivers or companion software:

1. Open **InpuLync** on the phone and grant Nearby Devices permissions.
2. Tap **Make phone visible**, then pair the phone in Windows Bluetooth Settings.
3. In the app, tap the paired PC and select **Bluetooth**.
4. The top status bar displays **INPUT CONNECTED ✓** once the HID session is active.

A lightweight background service (`HidService`) keeps the HID connection alive when switching apps or locking the screen, with a persistent notification and quick **Stop** action.

`windows/Pair-Bluetooth.ps1` is an optional local pairing script if Windows default discovery has trouble finding the phone.

---

## USB Mode & Windows Companion

USB mode sends a lightweight protocol through an `adb forward` tunnel. The communication takes place strictly on the localhost loopback interface over the physical USB cable:

1. Enable USB Debugging on the phone and connect the USB cable.
2. Open **InpuLync** and select **USB** mode.
3. Open **SET → USB PAIR CODE** on the phone and enter the 16-character code into `windows/Start-UsbInput.ps1` (remembered automatically after the first run).
4. The companion enables host battery percentage monitoring, laptop brightness control, laptop audio clicks, and Unicode typing.

Verify the USB connection without sending input:
```bash
python windows/usb_input_host.py --check
```

---

## Gestures & Controls Reference

- **One-Finger Tap**: Left mouse click.
- **Hold or Double-Tap & Drag**: Left button drag-and-drop.
- **Two-Finger Vertical Scroll**: Mouse wheel up/down.
- **Two-Finger Horizontal Scroll**: Horizontal AC Pan scroll.
- **Three-Finger Swipe Left / Right**: Switch virtual desktops (`Win+Ctrl+Left/Right`).
- **Three-Finger Swipe Up**: Windows Task View (`Win+Tab`).
- **Three-Finger Swipe Down**: Show Desktop (`Win+D`).
- **Dedicated Scroll Strip**: One-finger vertical scrolling with discrete haptic ticks.
- **Pointer Acceleration**: Configurable (OFF, LOW, MED, HIGH) with smooth velocity-based Hermite interpolation.

---

## Build from Source

### Android APK
Install JDK 17 and run:
```bash
# On Linux / macOS
./gradlew assembleDebug

# On Windows
.\gradlew.bat assembleDebug
```
The APK is generated at `app/build/outputs/apk/debug/`. GitHub Actions automatically builds and publishes the APK as a workflow artifact on every push.

---

## Contributing

Bug fixes, layout enhancements, translation improvements, and device compatibility reports are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request, and disclose any security issues responsibly according to [SECURITY.md](SECURITY.md).

---

## License

InpuLync is open-source software licensed under the [MIT License](LICENSE).
