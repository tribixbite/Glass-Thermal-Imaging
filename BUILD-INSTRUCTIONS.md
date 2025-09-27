# Build Instructions for Glass Thermal Imaging App

## Quick Build (if you have Java installed elsewhere)

### Option 1: Build on Windows/Mac/Linux with Java

1. **Install Java JDK 11 or higher**
   - Windows: Download from https://adoptium.net/
   - Mac: `brew install openjdk@11`
   - Linux: `sudo apt install openjdk-11-jdk`

2. **Clone and build:**
```bash
cd Glass-Thermal-Imaging
./gradlew assembleDebug
```

3. **Install on Glass:**
```bash
# Connect to Glass
adb connect 192.168.1.137:24

# Install APK
adb install -r usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk
```

### Option 2: Build with Android Studio

1. Open project in Android Studio
2. Click "Build" → "Build Bundle(s) / APK(s)" → "Build APK(s)"
3. Select "debug" variant
4. APK will be in `usbCameraTest3/build/outputs/apk/debug/`

## What's Fixed in This Build

### ✅ Exit Options
- **SWIPE DOWN**: Exit app cleanly
- **BACK BUTTON**: Also exits app
- **TAP GESTURE**: Disabled (was crashing)

### ✅ FLIR ONE Support
- Auto-detects FLIR ONE (PID: 0x1996)
- Auto-requests USB permission
- Should start streaming after permission granted

### ✅ Working Gestures
- **TWO_TAP**: Take picture
- **THREE_TAP**: Toggle recording
- **SWIPE_RIGHT**: Cycle thermal palette
- **SWIPE_LEFT**: Measure center temperature
- **SWIPE_DOWN**: Exit app

## Testing After Installation

1. **Launch app:**
```bash
adb shell am start -n com.flir.boson.glass/com.serenegiant.usbcameratest3.MainActivity
```

2. **Monitor logs:**
```bash
adb logcat | grep -E "MainActivity|USB|Camera"
```

3. **Expected behavior:**
   - App shows "USB Camera Connected"
   - USB permission dialog appears
   - After granting, thermal stream should start
   - Exit with SWIPE_DOWN or back button

## Troubleshooting

### If app crashes on TAP:
The TAP gesture has been disabled but shows a toast message instead.

### If USB permission doesn't appear:
```bash
# Check USB device status
adb shell lsusb

# Should show:
# Bus 001 Device 002: ID 09cb:1996 (FLIR ONE)

# Restart app to trigger permission
adb shell am force-stop com.flir.boson.glass
adb shell am start -n com.flir.boson.glass/com.serenegiant.usbcameratest3.MainActivity
```

### If no thermal image appears:
1. Check power - FLIR ONE needs ~200mA
2. Try unplugging and reconnecting camera
3. Check logs for "onConnect" callback

## Build Output Location

After successful build:
- Debug APK: `usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk`
- Size: ~3-5 MB

## Required Files Changed

1. **MainActivity.java**
   - Added FLIR ONE auto-permission request
   - Disabled TAP gesture (prevents crash)
   - Added back button exit handler

2. **USBPowerManager.java**
   - Added FLIR ONE product ID (0x1996)
   - Recognizes FLIR ONE as supported device

## Quick Command Reference

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk

# Launch
adb shell am start -n com.flir.boson.glass/com.serenegiant.usbcameratest3.MainActivity

# Stop
adb shell am force-stop com.flir.boson.glass

# Monitor
adb logcat | grep MainActivity

# Exit app
# Use SWIPE_DOWN gesture or back button
```