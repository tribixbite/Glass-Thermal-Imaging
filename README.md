# Glass Thermal: FLIR Boson Thermal Imaging for Google Glass

A comprehensive thermal imaging application for Google Glass Explorer Edition (XE24) that provides real-time thermal visualization and temperature measurement using FLIR Boson thermal cameras via USB OTG.

![Glass Thermal Demo](docs/demo.gif)
*Real-time thermal imaging on Google Glass with FLIR Boson camera*

## 🔥 Features

### Real Thermal Processing
- **Live Y16 Raw Data**: Direct 16-bit thermal data extraction from FLIR Boson cameras
- **Improved Temperature Estimation**: Uses T-Linear format conversion (centi-Kelvin based)
- **Multi-Resolution Support**: Compatible with Boson 640x512 and 320x256 models
- **Real-Time Visualization**: Live thermal data with minimal latency

### Advanced Thermal Visualization
- **Multiple Color Palettes**: Iron (heat signature), Rainbow (full spectrum), and Grayscale modes
- **Center Crosshair**: Visual reticle showing measurement point
- **Thermal Overlay**: Real-time false-color thermal imaging over camera view
- **Glass-Optimized Display**: 640x360 resolution optimized for Glass prism display

### Glass Gesture Controls
- **Single Tap**: Measure center point temperature (3-second display with ~ indicator)
- **Double Tap**: Toggle thermal modes and cycle color palettes
- **Swipe Down**: Capture both visual and radiometric data (FIXED: was detecting up)
- **Visual Feedback**: Status updates and temperature readings via Glass display

### Enhanced Data Capture
- **Dual Format Saving**: Visual images (.jpg) + Raw thermal data (.raw)
- **Metadata Export**: Timestamps, temperature readings, format specifications
- **Organized Storage**: Files saved to `/flir-boson/` directory with descriptive names
- **Scientific Analysis**: Raw Y16 data preserved for post-processing

## ⚠️ Temperature Accuracy

**IMPORTANT:** This application currently uses raw thermal data (Y16) over UVC interface. Temperature values displayed are **estimates** based on T-Linear format conversion and are **not radiometrically calibrated**.

For accurate temperature measurements, FLIR Boson requires specific calibration commands sent over a serial interface (SDK integration). This is planned for future releases. Current temperature readings should be used for:
- **Relative analysis** (identifying hot/cold spots)
- **Thermal pattern recognition**
- **Comparative temperature measurements**

**Not recommended for:** Absolute quantitative measurements, medical applications, or precision industrial use.

## 🔧 Hardware Requirements

### Essential Components
- **Google Glass Explorer Edition (XE24)** running Android 4.4 KitKat (API 19)
- **FLIR Boson Thermal Camera** (320x256 or 640x512) with UVC support
- **USB OTG Cable/Adapter** for camera connectivity
- **External Power Source** (recommended for stable thermal camera operation)

### Camera Compatibility
- FLIR Boson 320 (320x256 resolution)
- FLIR Boson 640 (640x512 resolution)
- Both radiometric and non-radiometric variants supported
- UVC (USB Video Class) compatible models

## 📱 Installation

### Prerequisites
1. **Enable Glass Development**:
   - Go to Glass Settings → Device info → Turn on debug
   - Enable "Unknown sources" for APK installation

2. **Verify USB OTG Support**:
   - Ensure Glass device supports USB host mode
   - Test USB OTG adapter with other USB devices

### APK Installation

#### Method 1: ADB Installation (Recommended)
```bash
# Connect Glass to computer via USB
adb install -r flir-boson-glass-thermal-debug.apk

# Verify installation
adb shell pm list packages | grep flir
```

#### Method 2: Direct Installation
1. Copy APK to Glass device (via file transfer or USB)
2. Use Glass file manager to navigate to APK
3. Tap APK file to install
4. Follow Glass installation prompts

### Initial Setup
1. Install APK following instructions above
2. Connect FLIR Boson camera via USB OTG adapter
3. Ensure camera has adequate power supply
4. Launch "Glass Thermal Imaging" from Glass apps menu

## 🎮 Usage Instructions

### Camera Connection
1. **Power On**: Connect FLIR Boson with external power (if needed)
2. **USB Connection**: Connect camera to Glass via USB OTG adapter
3. **Launch App**: Open "Glass Thermal Imaging" from apps
4. **Wait for Detection**: Glass displays "FLIR Boson Connected" when ready

### Operating the Application

| Gesture | Action | Description |
|---------|--------|-------------|
| **Single Tap** | Measure Temperature | Shows center point temperature (~XX.X°C) for 3 seconds |
| **Double Tap** | Toggle Thermal Mode | Enables/disables thermal visualization, cycles color palettes |
| **Swipe Down** | Capture Data | Saves visual image + raw thermal data + metadata |

### Thermal Visualization Modes

#### Color Palettes
- **Iron Palette**: Cold=black/blue → Hot=red/yellow/white (standard thermal imaging)
- **Rainbow Palette**: Full color spectrum mapping (intuitive visualization)
- **Grayscale Palette**: Direct intensity mapping (scientific analysis)

#### Display Modes
- **Normal Mode**: Standard camera view
- **Thermal Mode**: False-color thermal overlay with center crosshair

## 📁 Output File Structure

### Captured Data Location
```
/sdcard/flir-boson/
├── thermal_capture_[timestamp]_visual.jpg    # Visual RGB image
├── thermal_capture_[timestamp]_thermal.raw   # Raw Y16 thermal data
└── thermal_capture_[timestamp]_meta.txt      # Capture metadata
```

### Metadata Format Example
```
Timestamp: 1640995200000
Thermal Mode: Enabled
Palette: Iron
Center Temperature: ~23.4°C
Data Size: 655360 bytes
Format: Y16 (16-bit raw thermal)
```

### Raw Data Processing
The `.raw` files contain 16-bit thermal data that can be processed with:
- **Python**: numpy, opencv, matplotlib for analysis
- **MATLAB**: Image Processing Toolbox
- **FLIR Tools**: Official FLIR software (if compatible)

## 🛠️ Building from Source

### Development Environment (Termux ARM64)
```bash
# Install build dependencies
pkg install gradle openjdk-17 git

# Clone repository
git clone https://github.com/[username]/Glass-Thermal-Imaging.git
cd Glass-Thermal-Imaging

# Build APK
./build-on-termux.sh

# Output location
ls usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk
```

### Build Requirements
- **Termux**: ARM64 Android build environment
- **Java 17**: OpenJDK for modern build tools
- **Gradle 8.4**: Build system with Glass compatibility
- **Custom AAPT2**: Patched for Termux ARM64 support (`tools/aapt2`)
- **Android SDK**: API 19 (KitKat) for Glass compatibility

### Key Build Files
- `build-on-termux.sh`: Automated build script for Termux
- `tools/aapt2`: Patched AAPT2 for ARM64 Termux compatibility
- `gradle.properties`: Java 17 and Glass-specific configuration
- `usbCameraTest3/build.gradle`: App module with API 19 targeting

## 🏗️ Architecture & Technical Details

### Core Components
- **MainActivity.java**: Main thermal processing and Glass UI logic
- **IFrameCallback**: Raw Y16 data reception interface from UVC camera
- **UVCCamera Library**: USB camera communication layer (saki4510t/UVCCamera)
- **Custom Thermal Processing**: Y16 to temperature conversion and visualization

### Thermal Data Pipeline
```
FLIR Boson Camera → USB OTG → UVC Interface → Y16 Raw Data →
Temperature Estimation → Color Palette Mapping → Glass Display
```

### Performance Considerations
- **Memory Optimized**: Efficient ByteBuffer handling for 682MB Glass RAM
- **Real-Time Processing**: <100ms latency for thermal visualization
- **Battery Efficient**: Minimal background processing
- **Display Optimized**: 640x360 layout for Glass prism display

## 🔮 Future Roadmap

### Phase 1: Enhanced UVC Processing (Next Release)
- [ ] **Auto-Contrast Scaling**: Dynamic range adjustment for better visualization
- [ ] **Background Processing**: Move bitmap creation off UI thread for performance
- [ ] **Dynamic Resolution Detection**: Automatic camera resolution configuration
- [ ] **Manual FFC Trigger**: Flat-field correction via gesture control

### Phase 2: FLIR SDK Integration (Major Release)
- [ ] **Serial Communication**: USB-to-serial interface using `usb-serial-for-android`
- [ ] **FSLP Protocol**: Implement FLIR Fast Serial Link Protocol
- [ ] **True Radiometric Accuracy**: ±5°C accuracy via SDK calibration commands
- [ ] **Advanced Camera Control**: Gain mode, transmission settings, lookup table refresh

### Phase 3: Professional Features (Future)
- [ ] **Multiple Measurement Points**: Multi-spot temperature monitoring
- [ ] **Temperature Alerts**: Configurable threshold notifications
- [ ] **Thermal Recording**: Video capture of thermal data sequences
- [ ] **Cloud Integration**: Data upload and analysis capabilities

## 🚨 Troubleshooting

### Camera Connection Issues
**Symptom**: "Camera not connected" message
**Solutions**:
- Verify USB OTG adapter functionality with other devices
- Check FLIR Boson power supply (external power recommended)
- Ensure Glass USB host mode is properly enabled
- Try different high-quality USB OTG adapters

### Build Failures
**Symptom**: Gradle build errors
**Solutions**:
- Ensure Java 17 is installed: `java -version`
- Verify AAPT2 path: `ls tools/aapt2` (should exist and be executable)
- Check Android SDK: Ensure API 19 (KitKat) is installed
- Clean build cache: `./gradlew clean`

### Thermal Display Issues
**Symptom**: Poor thermal image quality or no thermal overlay
**Solutions**:
- Verify camera supports Y16 format (check camera specifications)
- Ensure adequate scene temperature variation for contrast
- Try different thermal color palettes (double-tap to cycle)
- Check thermal data buffer size in logs

### Glass Performance Issues
**Symptom**: App lag or crashes on Glass
**Solutions**:
- Close other Glass applications to free memory
- Ensure adequate ventilation for Glass device cooling
- Use external power for camera to reduce Glass power load
- Monitor Glass temperature during extended thermal imaging sessions

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

### Acknowledgments
Based on the UVCCamera library by saki4510t, with custom thermal imaging extensions for Google Glass integration.

### Third-Party Components
- **UVCCamera Library**: saki4510t/UVCCamera (Apache 2.0)
- **FLIR Boson Documentation**: FLIR Systems thermal imaging specifications
- **Android USB Host APIs**: Google Android Open Source Project

## 🤝 Contributing

Contributions welcome! Areas of particular interest:
- **Performance Optimization**: Thermal bitmap processing improvements
- **FLIR SDK Integration**: Serial communication and radiometric accuracy
- **Glass UX Enhancement**: Gesture recognition and display optimization
- **Documentation**: Installation guides and troubleshooting

## 📞 Support

For technical support:
1. Check [troubleshooting section](#-troubleshooting) above
2. Verify hardware compatibility and power requirements
3. Review Glass development setup and USB OTG functionality
4. Open GitHub issue with detailed logs and device specifications

---

**🔥 Built for Google Glass Explorer Edition (XE24) | Optimized for FLIR Boson Thermal Cameras | Real-Time Thermal Imaging**
===============================

Updated UVC Camera for live capture of a FLIR Boson camera over USB.

Changes by Jim K to run CameraTest3 sucessfully on a Google Pixel phone:

 - Added I420 format option to stream.c

   src/main/jni/libuvc/src/stream.c

	FMT(UVC_FRAME_FORMAT_I420,  // jimk
		{'I', '4', '2', '0', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71})

- Added I420 to RGB conversion to frame.c

    src/main/jni/libuvc/src/frame.c
 
- Change default frame height to 512 in UVCPreview.h
 
    src/main/jni/UVCCamera/UVCPreview.h

- Change PREVIEW_HEIGHT to 512 in mainActivity.java

    usbCameraTest3/src/main/java/com/serenegiant/usbcameratest3/MainActivity.java




UVCCamera
=========

library and sample to access to UVC web camera on non-rooted Android device

Copyright (c) 2014-2017 saki t_saki@serenegiant.com

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

All files in the folder are under this Apache License, Version 2.0.
Files in the jni/libjpeg, jni/libusb and jin/libuvc folders may have a different license,
see the respective files.

How to compile library  
=========
The Gradle build system will build the entire project, including the NDK parts. If you want to build with Gradle build system,

1. make directory on your favorite place (this directory is parent directory of `UVCCamera` project).
2. change directory into the directory.
3. clone this repository with `git  clone https://github.com/saki4510t/UVCCamera.git`
4. change directory into `UVCCamera` directory with `cd UVCCamera`
5. build library with all sample projects using `gradle build`

It will takes several minutes to build. Now you can see apks in each `{sample project}/build/outputs/apks` directory.  
Or if you want to install and try all sample projects on your device, run `gradle installDebug`.  

Note: Just make sure that `local.properties` contains the paths for `sdk.dir` and `ndk.dir`. Or you can set them as enviroment variables of you shell. On some system, you may need add `JAVA_HOME` envairoment valiable that points to JDK directory.  

If you want to use Android Studio(unfortunately NDK supporting on Android Studio is very poor though),
1. make directory on your favorite place (this directory is parent directory of `UVCCamera` project).
2. change directory into the directory.
3. clone this repository with `git  clone https://github.com/saki4510t/UVCCamera.git`
4. start Android Studio and open the cloned repository using `Open an existing Android Studio project`
5. Android Studio raise some errors but just ignore now. Android Studio generate `local.properties` file. Please open `local.properties` and add `ndk.dir` key to the end of the file. The contents of the file looks like this.
```
sdk.dir={path to Android SDK on your storage}
ndk.dir={path to Android SDK on your storage}
```
Please replace actual path to SDK and NDK on your storage.  
Of course you can make `local.properties` by manually instead of using automatically generated ones by Android Studio.
6. Synchronize project
7. execute `Make project` from `Build` menu.

If you want to use build-in VCS on Android Studio, use `Check out project from Version Control` from `https://github.com/saki4510t/UVCCamera.git`. After cloning, Android Studio ask you open the project but don't open now. Instead open the project using `Open an existing Android Studio project`. Other procedures are same as above.

If you still need to use Eclipse or if you don't want to use Gradle with some reason, you can build suing `ndk-build` command.

1. make directory on your favorite place.
2. change directory into the directory.
3. clone this repository with `git  clone https://github.com/saki4510t/UVCCamera.git`
4. change directory into `{UVCCamera}/libuvccamera/build/src/main/jni` directory.
5. run `ndk-build`
6. resulted shared libraries are available under `{UVCCamera}/libuvccamera/build/src/main/libs` directory and copy them into your project with directories by manually.
7. copy files under `{UVCCamera}/libuvccamera/build/src/main/java` into your project source directory by manually.

How to use
=========
Please see sample project and/or our web site(but sorry web site is Japanese only).
These sample projects are IntelliJ projects, as is the library.
This library works on at least Android 3.1 or later(API >= 12), but Android 4.0(API >= 14)
or later is better. USB host function must be required.
If you want to try on Android 3.1, you will need some modification(need to remove
setPreviewTexture method in UVCCamera.java etc.), but we have not confirm whether the sample
project run on Android 3.1 yet.
Some sample projects need API>=18 though.

###2014/07/25
Add some modification to the library and new sample project named "USBCameraTest2".
This new sample project demonstrate how to capture movie using frame data from
UVC camera with MediaCodec and MediaMuxer.
New sample requires at least Android 4.3(API>=18).
This limitation does not come from the library itself but from the limitation of
MediaMuxer and MediaCodec#createInputSurface.

###2014/09/01
Add new sample project named `USBCameraTest3`
This new sample project demonstrate how to capture audio and movie simultaneously
using frame data from UVC camera and internal mic with MediaCodec and MediaMuxer.
This new sample includes still image capturing as png file.(you can easily change to
save as jpeg) This sample also requires at least Android 4.3(API>=18).
This limitation does not come from the library itself but from the limitation of
MediaMuxer and MediaCodec#createInputSurface.

###2014/11/16
Add new sample project named `USBCameraTest4`
This new sample project mainly demonstrate how to use offscreen rendering
and record movie without any display.
The communication with camera execute as Service and continue working
even if you stop app. If you stop camera communication, click "stop service" button.

###2014/12/17
Add bulk transfer mode and update sample projects.

###2015/01/12
Add wiki page, [HowTo](https://github.com/saki4510t/UVCCamera/wiki/howto "HowTo")

###2015/01/22
Add method to adjust preview resolution and frame data mode.

###2015/02/12
Add IFrameCallback interface to get frame data as ByteArray
and new sample project `USBCameraTest5` to demonstrate how to use the callback method.

###2015/02/18
Add `libUVCCamera` as a library project(source code is almost same as previous release except Android.mk).
All files and directories under `library` directory is deprecated.

###2015/05/25
libraryProject branch merged to master.

###2015/05/30
Fixed the issue that DeviceFilter class could not work well when providing venderID, productID etc.

###2015/06/03
Add new sample project named `USBCameraTest6`
This new sample project mainly demonstrate how to show video images on two TextureView simultaneously, side by side.

###2015/06/10
Fixed the issue of pixel format is wrong when NV21 mode on calling IFrameCallback#onFrame(U and V plane was swapped) and added YUV420SP mode.

###2015/06/11
Improve the issue of `USBCameraTest4` that fails to connect/disconnect.

###2015/07/19
Add new methods to get/set camera features like brightness, contrast etc.  
Add new method to get supported resolution from camera as json format.  

###2015/08/17
Add new sample project `USBCameraTest7` to demonstrate how to use two camera at the same time.  

###2015/09/20
Fixed the issue that building native libraries fail on Windows.

###2015/10/30
Merge pull request(add status and button callback). Thanks Alexey Pelykh.

###2015/12/16
Add feature so that user can request fps range from Java code when negotiating with camera. Actual resulted fps depends on each UVC camera. Currently there is no way to get resulted fps(will add on future).

###2016/03/01
update minoru001 branch, experimentaly support streo camera.

###2016/06/18
replace libjpeg-turbo 1.4.0 with 1.5.0

###2016/11/17
apply bandwidth factor setting of usbcameratest7 on master branch

###2016/11/21
Now this repository supports Android N(7.x) and dynamic permission model of Android N and later.

###2017/01/16
Add new sample app `usbCameraTest8` to show how to set/get uvc control like brightness 
