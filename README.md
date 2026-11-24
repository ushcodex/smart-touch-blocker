# 🛡 Smart Touch Blocker

Block ghost touches from a malfunctioning touchscreen digitizer while keeping your Bluetooth/USB mouse fully functional — no root required.

## Two Solutions in One Repo

| Component | Tech | Use case |
|-----------|------|----------|
| **APK** (`apk/`) | Android Java | Standalone — install on phone, works forever |
| **Native binary** (`touch_blocker_flat.rs`) | Rust/ARM64 | ADB-based — instant, no install needed |

---

## APK (Recommended for Standalone Use)

### How It Works

The APK draws a **transparent fullscreen overlay** using `WindowManager.TYPE_APPLICATION_OVERLAY`. The overlay's custom view (`TouchFilterView`) inspects every `MotionEvent`:

```
MotionEvent.getSource() == SOURCE_TOUCHSCREEN  →  return true  (event consumed = BLOCKED)
MotionEvent.getSource() == SOURCE_MOUSE        →  return false + toggle FLAG_NOT_TOUCHABLE for 80ms
                                                   so the click reaches the app behind the overlay
```

The **mouse click pass-through trick**: when a mouse button press is detected (via `onGenericMotionEvent` button state), `FLAG_NOT_TOUCHABLE` is atomically set on the overlay window for 80 ms, making it event-transparent for that click, then removed — keeping the touchscreen blocked again.

```
Normal state:  overlay BLOCKS touches  │  overlay PASSES mouse hover
On mouse click: overlay → NOT_TOUCHABLE for 80ms → click hits underlying app → TOUCHABLE again
```

### Install

1. Download `TouchBlocker-debug.apk` from [**Releases**](../../releases)
2. On your phone: *Settings → Security → Install unknown apps* → enable for your browser/Files app
3. Install the APK
4. Open **Touch Blocker** → tap **Grant Permission** → allow overlay permission
5. Tap **Turn ON**

A red ✕ floating button appears on screen. Tap it (or use the notification) to stop.

### Permissions Required

| Permission | Why |
|-----------|-----|
| `SYSTEM_ALERT_WINDOW` | Draw the transparent overlay over all apps |
| `FOREGROUND_SERVICE` | Keep the blocker alive in the background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after phone reboot |

---

## Native Binary (ADB Method)

Requires a PC with ADB connected — but no install, no permissions, works instantly.

### Build

```powershell
cd "touch blocker"
& "C:\Users\PC\.cargo\bin\rustc.exe" --target aarch64-linux-android `
    -C linker-flavor=ld.lld -C linker=rust-lld `
    -C panic=abort -C opt-level=z `
    touch_blocker_flat.rs -o touch_blocker_arm64
```

### Deploy & Use

```powershell
adb push touch_blocker_arm64 /data/local/tmp/touch_blocker
adb shell "chmod +x /data/local/tmp/touch_blocker"

# Start blocking (runs in background)
adb shell "nohup /data/local/tmp/touch_blocker on > /dev/null 2>&1 &"

# Check status
adb shell "/data/local/tmp/touch_blocker status"

# Stop
adb shell "/data/local/tmp/touch_blocker off"
```

### How It Works

Uses **`EVIOCGRAB`** ioctl — a Linux kernel exclusive-lock on the touchscreen device node (`/dev/input/event4`). Once grabbed:
- All touchscreen events are captured by our process and drained (discarded)
- The mouse (`/dev/input/event5`) is on a completely separate device node and is unaffected
- No root required: `adb shell` runs as uid=2000 (shell) which is in the `input` group

```
/dev/input/event4  huawei,ts_kit   →  EVIOCGRAB'd → events drained → BLOCKED
/dev/input/event5  BT5.2 Mouse     →  untouched                     → ALLOWED
```

### Commands

| Command | Description |
|---------|-------------|
| `touch_blocker status` | Show detected devices + current state |
| `touch_blocker on` | Start blocking (background daemon) |
| `touch_blocker off` | Stop blocking, restore touchscreen |
| `touch_blocker run` | Foreground mode with ghost-touch counter |
| `touch_blocker toggle` | Flip current state |
| `touch_blocker help` | Show usage |

---

## Device Tested On

- **Huawei JNY-LX1** (Android 10, no root)
- Touchscreen: `huawei,ts_kit` at `/dev/input/event4`
- Mouse: `BT5.2 Mouse` at `/dev/input/event5`

---

## Building the APK Locally

Requires JDK 17+ and Android SDK.

```bash
cd apk
./gradlew assembleDebug
# APK at: apk/app/build/outputs/apk/debug/app-debug.apk
```

Or just push to the `main` branch — GitHub Actions builds and publishes it automatically.

---

## Architecture

```
apk/
├── app/src/main/
│   ├── java/com/touchblocker/
│   │   ├── MainActivity.java       # Toggle UI
│   │   ├── TouchBlockerService.java # Foreground service + overlay management
│   │   ├── TouchFilterView.java    # Core filtering logic (SOURCE_TOUCHSCREEN vs SOURCE_MOUSE)
│   │   └── BootReceiver.java       # Auto-start on boot
│   └── res/                        # Layouts, drawables, strings
├── .github/workflows/build.yml     # CI: build + release APK
└── build.gradle / settings.gradle

touch_blocker_flat.rs               # Rust standalone binary (ADB method)
lib64/                              # Android libc stubs for linking
```
