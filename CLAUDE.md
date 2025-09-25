# CLAUDE.md

This file provides guidance to Claude Code when working with the FLIR UVC-Boson camera project for Google Glass compatibility.

## Critical First Actions

**ALWAYS before doing anything:**
1. Review `/memory/todo.md` and update with user's request as structured todos
2. Check project structure and gradle configuration
3. After EVERY fix/feat/chore: Update todo.md and perform lowercase conventional commit

## Project Overview

FLIR UVC-Boson is a USB Video Class (UVC) camera library for Android that provides live capture from FLIR Boson thermal cameras over USB. Our goal is to adapt this project to work with Google Glass Explorer Edition (XE24) as both a display device and USB OTG host for thermal imaging capabilities.

## Build & Development Commands

```bash
# Build APK for Glass
./build-on-termux.sh [debug|release]

# Environment Setup
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=~/android-sdk

# View logs
adb logcat | grep -i uvccamera
```

## Project Architecture

### Original Project Structure
- **Core Library**: `libuvccamera/` - Native UVC camera library with JNI bindings
- **Common Module**: `usbCameraCommon/` - Shared Android utilities and UI components
- **Sample Apps**: `usbCameraTest[0-8]/` - Various demonstration applications
- **Native Components**:
  - `libuvc` - USB Video Class library (C)
  - `libusb` - USB communication library (C)
  - `libjpeg-turbo` - JPEG compression (C)

### Current Configuration (Legacy)
- **Target SDK**: API 23 (Android 6.0)
- **Min SDK**: API 12 (Android 3.1)
- **Build Tools**: 25.0.2 (very old)
- **Gradle**: 2.3.0 (deprecated)
- **Java**: 1.7 compatibility
- **Dependencies**: jcenter() repos (deprecated)

## Google Glass Integration Goals

### Display Adaptation
- **Target Device**: Google Glass XE24 (API 19/KitKat)
- **Display**: 640×360 pixel prism display
- **Input**: Glass touchpad gestures and voice commands
- **UI**: Minimal, gesture-driven interface optimized for Glass

### USB OTG Host Capability
- **Connection**: USB OTG adapter to connect FLIR Boson camera
- **Power**: May require external power for Boson camera
- **Compatibility**: Ensure UVC drivers work on Glass kernel
- **Performance**: Optimize for Glass hardware constraints

### Key Challenges
1. **Legacy Codebase**: Very old Android APIs and build tools
2. **API Compatibility**: Target API 19 while using modern libraries
3. **Hardware Constraints**: Glass limited processing power and battery
4. **USB OTG**: Verify Glass OTG support and power delivery
5. **Display Optimization**: Adapt UI for Glass's unique display

## Migration Plan

### Phase 1: Modernization
- Update Gradle build system to modern version
- Migrate from deprecated jcenter to Google/Maven repositories
- Update Android SDK targets for Glass compatibility
- Replace deprecated APIs with Glass-compatible alternatives

### Phase 2: Glass Integration
- Implement Glass-specific UI components
- Add gesture detection for Glass touchpad
- Integrate with Glass voice commands
- Optimize display for prism screen geometry

### Phase 3: USB OTG Testing
- Test USB OTG connectivity with sample cameras
- Verify power requirements and delivery
- Implement device detection and enumeration
- Add error handling for connection issues

### Phase 4: Thermal Imaging Features
- Optimize thermal image processing for Glass
- Add temperature measurement overlays
- Implement recording and capture features
- Create Glass-specific thermal visualization modes

## Sample Applications Analysis

### Recommended Starting Point
- **usbCameraTest3**: Most complete example with audio/video recording
- **Features**: Live preview, recording, still capture
- **API Level**: Requires API 18+ (needs adaptation for Glass API 19)

### Key Components to Adapt
1. **MainActivity**: Main camera interface
2. **UVCPreview**: Camera preview rendering
3. **UVCCamera**: Core camera control
4. **Native Libraries**: UVC/USB communication

## Glass-Specific Considerations

### Hardware Limitations
- **RAM**: 682MB available to apps (optimize memory usage)
- **CPU**: OMAP 4430 dual-core (optimize processing)
- **Storage**: Limited internal storage
- **Battery**: 570mAh (optimize power consumption)

### UI/UX Guidelines
- **Gestures**: TAP, TWO_FINGER_TAP, SWIPE_DOWN
- **Cards**: Use Glass card-based UI metaphors
- **Voice**: "OK Glass" voice trigger integration
- **Timeline**: Consider Glass timeline integration

### Technical Requirements
- **Min SDK**: API 19 (KitKat) - Glass requirement
- **Target SDK**: API 19 for Glass compatibility
- **Permissions**: USB_PERMISSION, CAMERA (if applicable)
- **Features**: USB host support declaration

## Development Workflow

1. **Analysis**: Review existing sample apps and identify best starting point
2. **Modernization**: Update build tools and dependencies for Glass compatibility
3. **Adaptation**: Modify UI and interaction patterns for Glass
4. **Testing**: Test on Glass hardware with USB OTG
5. **Optimization**: Performance and battery life improvements
6. **Documentation**: Update usage instructions for Glass

## Important Notes

- **Legacy Code**: Project uses very old Android APIs - careful migration needed
- **USB OTG**: Glass OTG support may be limited - thorough testing required
- **Thermal Imaging**: FLIR Boson produces thermal data - special visualization needed
- **Power Requirements**: External power may be needed for camera operation
- **Glass Constraints**: Respect Glass's minimal UI and gesture-based interaction

## Memory Management

Always update `/memory/todo.md` with:
- Migration tasks and priorities
- Technical decisions and rationale
- Glass-specific implementation notes
- Testing results and issues found
- Performance optimization opportunities

This project represents a unique combination of thermal imaging, USB OTG, and Glass display technology.