---
name: emulator
description: Manage the Android emulator for this project — list AVDs (phonepixel, tablet10, tablet7), launch headless via Xvfb for agent-driven work, wait for boot, install builds, interact via adb (UI dump, taps, screenshots, logcat), and shut down. Use when asked to start/stop/launch an emulator, run or deploy the app on an emulator, verify a fix on device, or capture UI evidence (screenshots/logs).
---

# Emulator Workflow

Rules of thumb: agents always run the emulator **headless** — here that means the windowed binary under **Xvfb**, never `-no-window`. Verify boot with `sys.boot_completed`, not just `adb wait-for-device`.

## Environment

- AVDs: `phonepixel` (default phone), `tablet10`, `tablet7`. List: `"$ANDROID_HOME/emulator/emulator" -list-avds`.
- Emulator binary is **not in PATH**: use `"$ANDROID_HOME/emulator/emulator"` (fallback `~/AndroidSDK/emulator/emulator`).
- `adb` is in PATH.

## ⚠️ Never use `-no-window`

On this machine `qemu-system-x86_64-headless` (what `-no-window` selects) **segfaults 20–30 s after launch** — emulator 36.6.11, Fedora/Wayland, all GPU backends (auto/swiftshader/guest). `QT_QPA_PLATFORM=offscreen` also crashes. The working headless setup is the **windowed binary on a virtual X display** via `xvfb-run` (package `xorg-x11-server-Xvfb`, installed).

## Launch (headless — default)

Launch in its **own** bash call that returns immediately — never chain the boot-wait loop into the same command (aborting it would kill the emulator's process group):

```bash
nohup xvfb-run -a --server-args="-screen 0 1080x2400x24" \
  "$ANDROID_HOME/emulator/emulator" -avd phonepixel \
  -no-audio -no-boot-anim -no-snapshot-load \
  > /tmp/opencode/emulator.log 2>&1 &
echo "launched, pid $!"
```

- `-no-snapshot-load` = clean cold boot — use it when verifying bugs so state can't leak between runs. Drop it for faster warm starts while iterating.
- Only one instance per AVD at a time — kill the old one before relaunching (stale process holds the AVD lock).
- If the user explicitly asks to watch the emulator, drop the `xvfb-run` wrapper (plain windowed launch on the real display).

## Wait for boot

Separate bash call from the launch. `adb wait-for-device` returns as soon as adbd is up — long before Android is usable. Always poll `sys.boot_completed`, and always target the emulator with `-s` (a physical phone is often attached — **never run untargeted adb commands**, they'd hit the real device or fail with "more than one device"):

```bash
for i in $(seq 1 30); do
  [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 10
done
adb devices   # expect: emulator-5554  device
```

The polling loop is silent until it finishes — that's normal, cold boot takes 1–3 min. If it never reaches `1` within ~5 min, read `/tmp/opencode/emulator.log` and troubleshoot.

## Build & install

Use the repo scripts from AGENTS.md — never hand-roll gradle:

```bash
./deploy googlePlay debug    # build + adb install -r
```

`./deploy` targets the emulator when it's the only emulator; with multiple devices attached, check the script's device selection or install manually: `adb -s emulator-5554 install -r <apk>`.

## Interact (headless)

The window lives on a virtual display — drive the UI through adb. Prefer the UI hierarchy over blind taps:

1. **Dump UI**: `adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5554 shell cat /sdcard/ui.xml` — get `text`/`content-desc`/`bounds` of elements.
2. **Act**: `adb -s emulator-5554 shell input tap X Y` (center of bounds), `input swipe x1 y1 x2 y2 300`, `input text 'foo'` (spaces as `%s`), `input keyevent KEYCODE_BACK`.
3. **Confirm visually**: `adb -s emulator-5554 exec-out screencap -p > /tmp/opencode/screen.png` then Read the PNG.

Logs and crashes:

```bash
adb -s emulator-5554 logcat -d | grep -iE 'exception|fatal|AndroidRuntime'   # crashes
adb -s emulator-5554 logcat -c && <repro> && adb -s emulator-5554 logcat -d  # clean capture
```

## Shutdown

```bash
adb -s emulator-5554 emu kill
adb devices   # poll until emulator-5554 disappears
```

`emu kill` is graceful (saves state); the `xvfb-run` wrapper and its Xvfb exit with it. Don't `kill -9` unless wedged.

## Troubleshooting

- **qemu dies ~20–30 s after launch**: you're using `-no-window` or offscreen — see the warning above; use the `xvfb-run` recipe.
- **`offline` in `adb devices`**: `adb kill-server && adb start-server`, then re-wait for boot.
- **Boot hangs**: check `/tmp/opencode/emulator.log`.
- **App not installing**: check `adb -s emulator-5554 shell pm list packages | grep belyisklad`; signature conflicts need `adb -s emulator-5554 uninstall com.belyisklad` first (wipes app data).
- **`more than one device/emulator`**: a physical phone is attached; every adb call needs `-s emulator-5554`.
