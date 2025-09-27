# Outstanding TODOs for Glass Thermal Imaging App

## 🔴 Critical - Must Fix Now

### 1. Build and Deploy Updated APK
**Status:** Ready to build with new WSL script
**Action Required:**
```bash
# Install Java if not present:
sudo apt update && sudo apt install openjdk-11-jdk

# Set up Android SDK (if needed):
export ANDROID_HOME=$HOME/Android/Sdk

# Build the APK:
./build-wsl.sh debug

# Install on Glass:
adb install -r glass-thermal-debug.apk
```

### 2. USB Permission & Camera Stream
**Current Issue:** FLIR ONE detected but no video stream
**What's Fixed:**
- ✅ Added FLIR ONE product ID (0x1996) recognition
- ✅ Added auto USB permission request on device attachment
- ✅ Power management confirms device is supported

**What Needs Testing:**
- [ ] USB permission dialog appears on Glass
- [ ] Camera stream initializes after permission granted
- [ ] Thermal video displays on Glass screen

### 3. Fix Missing MenuActivity Crash
**Issue:** TAP gesture crashes app (MenuActivity not in manifest)
**Quick Fix Options:**
1. Disable TAP gesture temporarily:
```java
// In MainActivity.java line 262-265, comment out:
// if (gesture == Gesture.TAP) {
//     openOptionsMenu();
//     return true;
// }
```
2. Or add MenuActivity to AndroidManifest.xml

## 🟡 Important - Core Functionality

### 4. Verify Thermal Data Processing
**Components to Test:**
- [ ] Frame callbacks receiving data
- [ ] Thermal processing pipeline working
- [ ] Temperature measurement accurate
- [ ] Color palette rendering

### 5. Glass-Specific Optimizations
**Already Implemented:**
- ✅ Performance manager with adaptive frame rates
- ✅ Battery monitoring and power states
- ✅ Memory optimization strategies

**Need to Verify:**
- [ ] Frame rate actually adapts under load
- [ ] Battery life acceptable (>30 min continuous use)
- [ ] No memory leaks during extended operation

## 🟢 Nice to Have - Future Enhancements

### 6. Complete Glass UI Integration
- [ ] Implement proper MenuActivity with CardScrollView
- [ ] Add voice commands for all functions
- [ ] Create timeline cards for captured images
- [ ] Add swipe gestures for all controls

### 7. Recording & Storage
- [ ] Test video recording to Glass storage
- [ ] Implement automatic file cleanup
- [ ] Add thermal data compression
- [ ] Create export functionality

### 8. Advanced Thermal Features
- [ ] Spot temperature measurement
- [ ] Area temperature statistics
- [ ] Thermal alerts and thresholds
- [ ] Multiple color palette options

## 📋 Current Working Features

### What's Already Working:
1. **USB OTG:** Glass successfully acts as USB host
2. **Device Detection:** FLIR ONE camera detected and enumerated
3. **Power Management:** Battery monitoring and adaptive performance
4. **Basic UI:** Touch gestures partially working (except TAP)
5. **GPS:** Location services integrated for metadata

### Available Gestures:
- **TWO_TAP:** Take picture
- **THREE_TAP:** Toggle recording
- **SWIPE_RIGHT:** Cycle thermal palette
- **SWIPE_LEFT:** Measure center temperature
- **SWIPE_DOWN:** Exit app

## 🔧 Development Setup Requirements

### For WSL Build Environment:
```bash
# Install Java JDK
sudo apt update
sudo apt install openjdk-11-jdk

# Install Android command line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
unzip commandlinetools-linux-9477386_latest.zip
export ANDROID_HOME=$HOME/Android/Sdk

# Install required SDK components
sdkmanager "platforms;android-19" "build-tools;25.0.2"

# Install NDK (for native libraries)
sdkmanager "ndk;21.4.7075529"
```

### For Glass Connection:
```bash
# Connect via WiFi (already configured)
adb connect 192.168.1.137:24

# Monitor logs
adb logcat | grep -E "MainActivity|USB|Camera"

# Install APK
adb install -r glass-thermal-debug.apk
```

## 📊 Testing Checklist

### After Next Build:
1. [ ] FLIR ONE auto-requests permission
2. [ ] Permission dialog appears on Glass
3. [ ] Camera stream starts after permission
4. [ ] Thermal image displays correctly
5. [ ] No crashes on gestures (except TAP if not fixed)
6. [ ] Temperature readings accurate
7. [ ] Recording saves files
8. [ ] Battery usage acceptable

## 🚀 Next Immediate Steps

1. **Build the APK** using `./build-wsl.sh debug`
2. **Install on Glass** and test USB permission flow
3. **Fix MenuActivity** crash or disable TAP
4. **Verify thermal stream** is displaying
5. **Test core features** (capture, record, temperature)

## 📝 Notes

- FLIR ONE requires ~200mA (within Glass capability)
- Glass running XE24 (API 19, Android 4.4.4)
- USB device at /dev/bus/usb/001/002 when connected
- App package: com.flir.boson.glass

---
*Last Updated: 2025-09-27*
*Glass IP: 192.168.1.137:24*
*FLIR ONE: VID 0x09CB, PID 0x1996*