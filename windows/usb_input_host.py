#!/usr/bin/env python3
"""Receive A05s input events through an adb USB port-forward and inject them on Windows."""

from __future__ import annotations

import argparse
import base64
import ctypes
from ctypes import wintypes
import hashlib
import hmac
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import winsound
from pathlib import Path

PORT = 27183
CODE_FILE = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "InpuLync" / "usb_code.txt"
DEFAULT_ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
INPUT_KEYBOARD = 1
CLICK_SOUND = Path(r"C:\Windows\Media\Windows Navigation Start.wav")

VK = {
    "BACKSPACE": 0x08, "TAB": 0x09, "ENTER": 0x0D, "ESC": 0x1B,
    "SPACE": 0x20, "PGUP": 0x21, "PGDN": 0x22, "END": 0x23,
    "HOME": 0x24, "LEFT": 0x25, "UP": 0x26, "RIGHT": 0x27,
    "DOWN": 0x28, "PRINTSCREEN": 0x2C, "INSERT": 0x2D, "DELETE": 0x2E,
    "WIN": 0x5B,
    "VOLUME_MUTE": 0xAD, "VOLUME_DOWN": 0xAE, "VOLUME_UP": 0xAF,
    "MEDIA_NEXT": 0xB0, "MEDIA_PREV": 0xB1, "MEDIA_STOP": 0xB2, "MEDIA_PLAY_PAUSE": 0xB3,
    "NUMPAD0": 0x60, "NUMPAD1": 0x61, "NUMPAD2": 0x62, "NUMPAD3": 0x63,
    "NUMPAD4": 0x64, "NUMPAD5": 0x65, "NUMPAD6": 0x66, "NUMPAD7": 0x67,
    "NUMPAD8": 0x68, "NUMPAD9": 0x69, "MULTIPLY": 0x6A, "ADD": 0x6B,
    "SUBTRACT": 0x6D, "DECIMAL": 0x6E, "DIVIDE": 0x6F, "NUMLOCK": 0x90,
    "PAUSE": 0x13, "SCROLLLOCK": 0x91,
    "F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73,
    "F5": 0x74, "F6": 0x75, "F7": 0x76, "F8": 0x77,
    "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
}
MODIFIER_VK = {"CTRL": 0x11, "SHIFT": 0x10, "ALT": 0x12, "WIN": 0x5B}


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class INPUT_UNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", INPUT_UNION)]


class SYSTEM_POWER_STATUS(ctypes.Structure):
    _fields_ = [
        ("ACLineStatus", ctypes.c_ubyte),
        ("BatteryFlag", ctypes.c_ubyte),
        ("BatteryLifePercent", ctypes.c_ubyte),
        ("SystemStatusFlag", ctypes.c_ubyte),
        ("BatteryLifeTime", wintypes.DWORD),
        ("BatteryFullLifeTime", wintypes.DWORD),
    ]


user32 = ctypes.windll.user32


def battery_status() -> tuple[int, int]:
    status = SYSTEM_POWER_STATUS()
    if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status)):
        return (-1, 0)
    percent = -1 if status.BatteryLifePercent == 255 else int(status.BatteryLifePercent)
    return (percent, 1 if status.ACLineStatus == 1 else 0)


def set_brightness(delta: int) -> None:
    command = f"""
$level = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness -ErrorAction Stop | Select-Object -First 1
$method = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods -ErrorAction Stop | Select-Object -First 1
$target = [math]::Max(0, [math]::Min(100, [int]$level.CurrentBrightness + ({delta})))
Invoke-CimMethod -InputObject $method -MethodName WmiSetBrightness -Arguments @{{Timeout=0; Brightness=[byte]$target}} | Out-Null
"""
    subprocess.Popen(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", command],
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def telemetry_loop(connection: socket.socket, stop: threading.Event) -> None:
    while not stop.wait(2.0):
        try:
            battery, plugged = battery_status()
            line = f"BATTERY {battery} {plugged}\n"
            connection.sendall(line.encode("ascii"))
        except (ConnectionError, OSError):
            return


def send_unicode(text: str) -> None:
    raw = text.encode("utf-16-le")
    for index in range(0, len(raw), 2):
        scan = int.from_bytes(raw[index:index + 2], "little")
        for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
            event = INPUT(type=INPUT_KEYBOARD)
            event.ki = KEYBDINPUT(0, scan, flags, 0, None)
            user32.SendInput(1, ctypes.byref(event), ctypes.sizeof(INPUT))


def send_key(name: str) -> None:
    code = VK.get(name)
    if code is None:
        return
    user32.keybd_event(code, 0, 0, 0)
    user32.keybd_event(code, 0, KEYEVENTF_KEYUP, 0)


def send_hotkey(chord: str) -> None:
    names = chord.split("+")
    if not names:
        return
    modifier_codes = [MODIFIER_VK[name] for name in names[:-1] if name in MODIFIER_VK]
    key_name = names[-1]
    key_code = VK.get(key_name)
    if key_code is None and len(key_name) == 1 and key_name.isalnum():
        key_code = ord(key_name.upper())
    if key_code is None:
        return
    for code in modifier_codes:
        user32.keybd_event(code, 0, 0, 0)
    user32.keybd_event(key_code, 0, 0, 0)
    user32.keybd_event(key_code, 0, KEYEVENTF_KEYUP, 0)
    for code in reversed(modifier_codes):
        user32.keybd_event(code, 0, KEYEVENTF_KEYUP, 0)


def handle(line: str, dry_run: bool) -> None:
    parts = line.strip().split()
    if not parts:
        return
    if dry_run:
        print(f"received: {line.strip()}")
        return
    command = parts[0]
    if command == "SOUND" and len(parts) == 2:
        if CLICK_SOUND.exists():
            winsound.PlaySound(str(CLICK_SOUND), winsound.SND_FILENAME | winsound.SND_ASYNC)
        else:
            winsound.MessageBeep(winsound.MB_OK)
    elif command == "MOVE" and len(parts) == 3:
        user32.mouse_event(MOUSEEVENTF_MOVE, int(parts[1]), int(parts[2]), 0, 0)
    elif command == "SCROLL" and len(parts) == 2:
        user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, int(parts[1]) * 120, 0)
    elif command == "HSCROLL" and len(parts) == 2:
        # MOUSEEVENTF_HWHEEL: positive is right, matching the phone's swipe direction.
        user32.mouse_event(MOUSEEVENTF_HWHEEL, 0, 0, int(parts[1]) * 120, 0)
    elif command == "CLICK" and len(parts) == 2:
        if parts[1] == "LEFT":
            user32.mouse_event(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
        elif parts[1] == "RIGHT":
            user32.mouse_event(MOUSEEVENTF_RIGHTDOWN | MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)
    elif command == "BUTTON" and len(parts) == 3:
        flags = {
            ("LEFT", "DOWN"): MOUSEEVENTF_LEFTDOWN,
            ("LEFT", "UP"): MOUSEEVENTF_LEFTUP,
            ("RIGHT", "DOWN"): MOUSEEVENTF_RIGHTDOWN,
            ("RIGHT", "UP"): MOUSEEVENTF_RIGHTUP,
        }.get((parts[1], parts[2]))
        if flags is not None:
            user32.mouse_event(flags, 0, 0, 0, 0)
    elif command == "TEXT" and len(parts) == 2:
        send_unicode(base64.b64decode(parts[1]).decode("utf-8"))
    elif command == "KEY" and len(parts) == 2:
        send_key(parts[1])
    elif command == "HOTKEY" and len(parts) == 2:
        send_hotkey(parts[1])
    elif command == "MEDIA" and len(parts) == 2:
        print(f"System control: {parts[1]}", flush=True)
        if parts[1] == "BRIGHTNESS_DOWN":
            set_brightness(-10)
        elif parts[1] == "BRIGHTNESS_UP":
            set_brightness(10)
        else:
            send_key(parts[1])


def configure_adb(adb: Path) -> None:
    if not adb.exists():
        raise FileNotFoundError(f"adb not found: {adb}")
    subprocess.run([str(adb), "start-server"], check=True, capture_output=True)
    devices = subprocess.run(
        [str(adb), "devices"], check=True, capture_output=True, text=True
    ).stdout
    if "\tdevice" not in devices:
        raise RuntimeError("No authorized Android device is connected over USB.")
    subprocess.run(
        [str(adb), "forward", f"tcp:{PORT}", f"tcp:{PORT}"],
        check=True, capture_output=True,
    )
    subprocess.run(
        [str(adb), "shell", "am", "start", "-n", "com.inpulync.app/.MainActivity"],
        check=True, capture_output=True,
    )


class AuthError(Exception):
    """The phone and this PC do not share the same pair code."""


def normalize_code(value: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", value.upper())


def load_code(cli_code: str | None, reset: bool) -> str:
    """Pair code from --code, INPULYNC_CODE, the saved file, or a one-time prompt."""
    code = cli_code or os.environ.get("INPULYNC_CODE")
    if not code and not reset and CODE_FILE.exists():
        code = CODE_FILE.read_text(encoding="utf-8")
    if not code:
        code = input("Enter the USB pair code shown in InpuLync > SET > USB PAIR CODE: ")
    code = normalize_code(code)
    if len(code) != 16:
        raise AuthError("The pair code must be 16 letters/digits (shown on the phone).")
    CODE_FILE.parent.mkdir(parents=True, exist_ok=True)
    CODE_FILE.write_text(code, encoding="utf-8")
    return code


def _mac(code: str, message: str) -> str:
    return hmac.new(code.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def authenticate(connection: socket.socket, incoming, code: str) -> None:
    """Mutual proof of the pair code. Only after this do we obey commands from the phone."""
    hello = incoming.readline().strip().split()
    if (hello[:2] != ["HELLO", "INPULYNC"] and hello[:2] != ["HELLO", "A05S_INPUT"]) or len(hello) < 3:
        raise ConnectionError(f"Unexpected phone response: {' '.join(hello)!r}")
    if hello[2] != "2" or len(hello) != 4:
        raise AuthError("The phone app is an older version without pair-code security. Update it.")
    server_nonce = hello[3]
    client_nonce = secrets.token_hex(16)
    proof = _mac(code, f"C|{server_nonce}|{client_nonce}")
    connection.sendall(f"AUTH {client_nonce} {proof}\n".encode("ascii"))
    reply = incoming.readline().strip().split()
    if not reply:
        raise AuthError("The phone rejected the pair code. Check it or run with --reset-code.")
    expected = _mac(code, f"S|{server_nonce}|{client_nonce}")
    if len(reply) != 2 or reply[0] != "OK" or not hmac.compare_digest(reply[1], expected):
        raise AuthError(
            "The app on port 27183 did not prove it knows the pair code. "
            "Another app may be impersonating InpuLync; input was NOT accepted."
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, default=DEFAULT_ADB)
    parser.add_argument("--check", action="store_true", help="Verify the USB link without injecting input")
    parser.add_argument("--code", help="USB pair code from the phone (otherwise saved or prompted)")
    parser.add_argument("--reset-code", action="store_true", help="Forget the saved pair code and ask again")
    args = parser.parse_args()

    try:
        code = load_code(args.code, args.reset_code)
    except AuthError as error:
        print(error, file=sys.stderr)
        return 2
    configure_adb(args.adb)
    print("InpuLync local input and laptop-battery helper ready. Press Ctrl+C to stop.")
    while True:
        try:
            with socket.create_connection(("127.0.0.1", PORT), timeout=3) as connection:
                connection.settimeout(None)
                with connection.makefile("r", encoding="utf-8") as incoming:
                    authenticate(connection, incoming, code)
                    print("Phone connected over USB; pair code verified; no Wi-Fi is in use.")
                    if args.check:
                        return 0
                    telemetry_stop = threading.Event()
                    telemetry = threading.Thread(
                        target=telemetry_loop,
                        args=(connection, telemetry_stop),
                        daemon=True,
                    )
                    telemetry.start()
                    try:
                        for line in incoming:
                            handle(line, dry_run=False)
                    finally:
                        telemetry_stop.set()
        except AuthError as error:
            print(error, file=sys.stderr)
            return 2
        except (ConnectionError, OSError) as error:
            print(f"Waiting for phone app: {error}", file=sys.stderr)
            time.sleep(1)
        except KeyboardInterrupt:
            return 0


if __name__ == "__main__":
    raise SystemExit(main())
