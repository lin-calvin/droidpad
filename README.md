# DroidPad

Turn an Android phone into a **Magic Trackpad–like** input device for a computer. The phone streams HID input reports; the host sees a real touchpad (kernel `hid-multitouch` + `libinput`) — cursor motion, tap-to-click, two-finger scroll, pinch/swipe gestures, and a barometer-based "force press".

> [!TIP]
> On Linux the host recognises it as a genuine `ID_INPUT_TOUCHPAD`, so pointer acceleration and multi-finger gestures are handled natively by the OS.

## Functions

- **Multi-touch touchpad** — up to 5 contacts, 2 buttons, streamed as a composite HID report descriptor.
- **Three transports**
  - **Wi‑Fi (UDP)** — no pairing; a small host daemon feeds reports into `/dev/uhid`. Most reliable and lowest-latency.
  - **Bluetooth LE** — HID over GATT (HOGP); the phone advertises a HID peripheral.
  - **Bluetooth Classic** — the system HID Device profile (`BluetoothHidDevice`).
- **Clicks & haptics** — single tap = left click, two-finger tap = right click, with configurable vibration.
- **Force press ("3D touch lite")** — uses the barometer + contact area to detect a firm press → click, with a distinct haptic. Includes:
  - **Adapt to my force** — fullscreen, guided calibration (finger-down + countdown + a "press now" window ×3) that picks your threshold.
  - **Landing-spike suppression** — ignores the pressure transient at the moment your finger lands (toggleable).
- **Material 3 UI** — Home (UDP + Bluetooth cards), Settings, and a fully dark, immersive pad.
- **Settings** — haptics on/off + strength, tap-to-click, two-finger right-click, force-press + threshold, landing suppression, a "Test your settings" surface, and shortcuts to battery-optimization / MIUI autostart.

## How it works

```
 phone                                   host (Linux)
 ─────                                   ────────────
 TouchpadView  ──packed report──▶  transport  ──▶  HID device  ──▶  hid-multitouch ──▶ libinput ──▶ cursor/gestures
   │                                              (uhid / GATT / HIDP)
   └ barometer + getTouchMajor ──▶ force ──▶ click + haptic
```

- **Descriptor** (`HidDescriptor`): a Digitizer/**Touch Pad** application collection with 5 per-finger collections (Tip Switch + Contact ID + X/Y), a Contact Count, and 2 buttons; Report ID 1, 27-byte reports.
- **Host chain**: `ContactID` usage → `HID_GROUP_MULTITOUCH` → `hid-multitouch` binds → Touch Pad collection → `INPUT_MT_POINTER` → `BTN_TOOL_FINGER`/`BTN_TOUCH` → udev `ID_INPUT_TOUCHPAD` → libinput touchpad.
- **UDP protocol** (one datagram each):
  - `0x01 HELLO` `[1][u16 descriptorLen LE][descriptor…]` — sent at start and re-sent ~1/s so a late-starting daemon still works.
  - `0x02 INPUT` `[2][27-byte report]`.

## Requirements

- **Phone**: Android 12+ (`minSdk 31`).
- **Host (Linux)**: `hid-multitouch`, `uhid` (for UDP/BLE). For UDP the daemon needs `/dev/uhid` access (root or a udev rule).

## Build

```bash
# JDK 17, Android SDK with platform 34 + build-tools 34
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Android Studio required (Gradle + AGP only).

## Usage

### Wi‑Fi (recommended)

On the host:

```bash
sudo tools/run_udp.sh          # listens on udp/9999, feeds /dev/uhid
```

In the app, **Wi‑Fi (UDP)** card → enter `<pc-ip>:9999` → **Start UDP touchpad**.

> [!NOTE]
> Allow `udp/9999` through the host firewall if one is active. To avoid `sudo` every time, add a udev rule so your user can open `/dev/uhid`:
> ```bash
> echo 'KERNEL=="uhid", GROUP="input", MODE="0660"' | sudo tee /etc/udev/rules.d/99-uhid-droidpad.rules
> sudo udevadm control --reload && sudo udevadm trigger /dev/uhid
> ```

### Bluetooth

1. Pair the computer in system Bluetooth settings.
2. In the app, **Bluetooth** card → pick **Bluetooth LE** (or **Classic**) → select the computer → **Start Bluetooth touchpad**.
3. Pair/accept from the host if prompted.

> [!WARNING]
> **Classic** HID depends on the host's BlueZ caching the phone's HID SDP record at pairing time; if it was paired before the app registered, the host may refuse the connection until re-paired. **BLE** (or UDP) is more reliable.

> [!IMPORTANT]
> Keep the app alive during Bluetooth use. On MIUI, enable **Autostart** and set the battery policy to **No restrictions** (Settings has shortcuts), and grant the battery-optimization exemption so the HID peripheral/GATT callbacks keep working.

## Settings

| Setting | Description |
|---|---|
| Haptics + strength | enable and set vibration amplitude for taps/clicks |
| Tap to click | single tap = left click |
| Two-finger tap = right click | two-finger tap = right click |
| Enable force press | barometer + contact-area pressure sensing |
| Block finger-landing spike | ignore the pressure transient when a finger lands |
| Press threshold | the force at which a click fires |
| Adapt to my force | guided fullscreen calibration |
| Test your settings | mini pad to feel the haptics and see live force |
| Battery optimization / Autostart | open the relevant system screens |

## Tools

Directory `tools/`:

| File | Purpose |
|---|---|
| `uhid_udp.c` | UDP → `/dev/uhid` bridge (the PC-side daemon) |
| `run_udp.sh` | builds + runs the UDP bridge |
| `uhid_test.c`, `run_uhid_test.sh` | local descriptor validator (creates a uhid device and drives synthetic touches — no phone needed) |
| `descriptor.hex`, `parse_hid.py`, `Dump.java`, `check_descriptor.sh` | regenerate/validate the HID report descriptor |

## Project layout

```
app/src/main/java/dev/calvin/droidpad/
  MainActivity.java      Home (transport picker)
  PadActivity.java       fullscreen dark pad
  TouchpadView.java      touch capture → HID reports; force press; haptics
  SettingsActivity.java  Material 3 settings
  CalibrateActivity.java fullscreen "Adapt to my force"
  ForceTestView.java     mini test/calibration pad
  HidDescriptor.java     report descriptor + report packing
  UdpSender.java         Wi‑Fi transport
  HogServer.java         BLE HOGP transport
  ClassicHid.java        Bluetooth Classic HID transport
  KeepAliveService.java  foreground service (keeps HID alive)
  Prefs.java             app preferences
tools/                   host daemon + descriptor tooling
```

## Tech

- Android (Java), Material 3, Gradle + AGP 8.5 · `minSdk 31` / `targetSdk 34`
- Host: Linux kernel `uhid` + `hid-multitouch`, `libinput`

## Caveats

- The barometer force-press is device-dependent (the BMP285 noise floor is close to the press signal); calibration sets one threshold and the **landing-spike** toggle helps. Most reliable on a sealed phone; finger contact area (`getTouchMajor`, in mm) is the main signal.
- Finger size affects the area-based force; calibrate per user.
