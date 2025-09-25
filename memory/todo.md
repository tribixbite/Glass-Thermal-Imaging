# UVC-Boson Glass Migration Todo

## Project Analysis (COMPLETE)

### Current State Assessment
- **Legacy Build System**: Gradle 2.3.0 (2017) - needs complete modernization
- **Target APIs**: minSdk=18, targetSdk=23 - incompatible with Glass (API 19)
- **Repositories**: jcenter() deprecated - must migrate to Google/Maven
- **Java**: 1.7 compatibility - can upgrade to 1.8 for Glass
- **Build Tools**: 25.0.2 - very outdated

### Sample Apps Analysis
- **usbCameraTest3**: Best candidate - has recording, preview, still capture
- **usbCameraTest6**: Two-camera support - could be useful for Glass/thermal overlay
- **usbCameraTest8**: Camera controls - good for thermal camera settings
- **Recommendation**: Start with usbCameraTest3, adapt for Glass

## Phase 1: Build System Modernization (HIGH PRIORITY)

### 1.1 Root build.gradle Updates
- [ ] Update Gradle wrapper to 8.4 (compatible with modern Android tools)
- [ ] Replace com.android.tools.build:gradle:2.3.0 → 8.3.0
- [ ] Replace jcenter() with google() and mavenCentral()
- [ ] Update support library versions to AndroidX equivalents
- [ ] Add Glass-compatible SDK versions

### 1.2 Sample App build.gradle Updates (Focus on usbCameraTest3)
- [ ] Update compileSdkVersion to 34 (for build tools)
- [ ] Set minSdkVersion to 19 (Glass requirement)
- [ ] Set targetSdkVersion to 19 (Glass compatibility)
- [ ] Replace 'compile' with 'implementation'
- [ ] Update applicationId to glass-specific namespace
- [ ] Add USB host feature requirements

### 1.3 Dependency Management
- [ ] Migrate from Support Library to AndroidX
- [ ] Update libcommon repository URL (if still needed)
- [ ] Add Glass GDK dependencies if available
- [ ] Ensure USB host support libraries

## Phase 2: Glass-Specific Adaptations (MEDIUM PRIORITY)

### 2.1 UI/UX Adaptations for Glass
- [ ] Replace standard Android UI with Glass-optimized layouts
- [ ] Implement Glass gesture detection (tap, two-finger tap, swipe)
- [ ] Create Glass card-based UI metaphor
- [ ] Optimize layout for 640×360 prism display
- [ ] Remove incompatible UI elements (complex menus, etc.)

### 2.2 Glass Integration Features
- [ ] Add voice trigger support ("OK Glass, start thermal camera")
- [ ] Implement Glass timeline integration for captures
- [ ] Add Glass-specific permissions and features
- [ ] Create Glass-optimized camera preview sizing

### 2.3 Thermal Imaging Optimizations
- [ ] Optimize thermal data visualization for Glass display
- [ ] Add temperature measurement overlays
- [ ] Implement false color mapping for thermal data
- [ ] Create temperature scale indicators suitable for Glass

## Phase 3: Technical Implementation (MEDIUM PRIORITY)

### 3.1 USB OTG Integration
- [ ] Verify Glass USB OTG host capabilities
- [ ] Test power requirements for FLIR Boson camera
- [ ] Implement USB device detection and enumeration
- [ ] Add error handling for OTG connection issues
- [ ] Create power management for battery optimization

### 3.2 Camera Integration
- [ ] Adapt UVCCamera for Glass hardware constraints
- [ ] Optimize frame rates for Glass performance
- [ ] Implement thermal-specific camera controls
- [ ] Add FLIR Boson-specific format support (I420, etc.)
- [ ] Memory optimization for Glass RAM limitations

### 3.3 Performance Optimizations
- [ ] Optimize for OMAP 4430 dual-core processor
- [ ] Implement efficient memory management (682MB limit)
- [ ] Battery life optimizations for 570mAh battery
- [ ] Reduce processing overhead for real-time display

## Phase 4: Testing & Validation (LOW PRIORITY)

### 4.1 Build Validation
- [ ] Verify APK builds successfully with modern tools
- [ ] Test APK installation on Glass hardware
- [ ] Validate USB OTG detection and connection
- [ ] Performance benchmarking on Glass

### 4.2 Functional Testing
- [ ] Test thermal camera live preview on Glass
- [ ] Verify gesture controls work correctly
- [ ] Test recording and capture functionality
- [ ] Validate temperature measurement accuracy

### 4.3 Integration Testing
- [ ] Test with actual FLIR Boson camera hardware
- [ ] Verify power consumption and battery life
- [ ] Test USB OTG cable and power delivery
- [ ] End-to-end thermal imaging workflow

## Technical Decisions Log

### Build System Migration
- **Decision**: Use Gradle 8.4 + Android Gradle Plugin 8.3.0
- **Rationale**: Latest stable versions compatible with modern Android SDK
- **Glass Impact**: Maintains API 19 target while using modern build tools

### Sample App Selection
- **Decision**: Start with usbCameraTest3 as base
- **Rationale**: Most complete feature set (preview, recording, capture)
- **Modifications Needed**: Extensive UI overhaul for Glass, API downgrade to 19

### Repository Migration
- **Decision**: Replace jcenter() with google() + mavenCentral()
- **Rationale**: jcenter() is deprecated and will be shut down
- **Glass Impact**: Ensures long-term build stability

## Glass Hardware Constraints

### Display Limitations
- **Resolution**: 640×360 pixels (non-standard aspect ratio)
- **Type**: Prism display (unique visualization requirements)
- **UI Paradigm**: Card-based, minimal information density

### Processing Limitations
- **CPU**: OMAP 4430 dual-core (older architecture)
- **RAM**: 682MB available (strict memory management needed)
- **Storage**: Limited space (optimize APK size)

### Power Limitations
- **Battery**: 570mAh (aggressive power management required)
- **USB OTG**: May require external power for thermal camera
- **Thermal**: Heat generation concerns with intensive processing

## Success Criteria

### Minimum Viable Product (MVP)
- [x] APK builds successfully on modern toolchain
- [ ] APK installs and runs on Google Glass XE24
- [ ] Basic thermal camera connection via USB OTG
- [ ] Live thermal preview on Glass display
- [ ] Basic Glass gesture controls (tap to capture)

### Full Feature Set
- [ ] Voice command integration ("OK Glass" triggers)
- [ ] Glass timeline integration for thermal captures
- [ ] Temperature measurement overlays
- [ ] Recording and playback functionality
- [ ] Glass-optimized thermal visualization modes
- [ ] Power-efficient operation for extended use

## Current Priority: Build System Modernization

**Next Steps:**
1. Update root build.gradle to modern versions
2. Modify usbCameraTest3 for Glass compatibility
3. Update build-on-termux.sh for new project structure
4. Test build and create initial APK

**Risk Assessment:**
- **High**: Native library compatibility with modern NDK
- **Medium**: USB OTG power requirements for Boson camera
- **Low**: Glass UI adaptation (well-documented patterns)

**Estimated Timeline:**
- Phase 1: 2-3 days (build modernization)
- Phase 2: 3-4 days (Glass adaptation)
- Phase 3: 2-3 days (technical implementation)
- Phase 4: 1-2 days (testing and validation)

**Total**: ~8-12 days for complete Glass-compatible thermal imaging app