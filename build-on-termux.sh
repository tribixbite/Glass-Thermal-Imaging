#!/bin/bash

echo "Building APK for Google Glass XE24 with FLIR UVC-Boson support..."

# Set up Termux-specific environment
echo "Configuring for Termux build environment..."
if [ -f "gradle-termux.properties" ]; then
    cp gradle-termux.properties gradle.properties
    echo "✅ Applied Termux-specific gradle properties"
else
    echo "❌ gradle-termux.properties not found"
fi

BUILD_TYPE="${1:-debug}"
BUILD_TYPE_LOWER=$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')

# Validate build type
if [[ "$BUILD_TYPE_LOWER" != "debug" && "$BUILD_TYPE_LOWER" != "release" ]]; then
    echo "Error: Invalid build type. Use 'debug' or 'release'"
    echo "Usage: $0 [debug|release]"
    exit 1
fi

# Set environment variables for Termux
if [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
    export PATH=$PATH:$JAVA_HOME/bin
fi

if [ -z "$ANDROID_HOME" ]; then
    export ANDROID_HOME=/data/data/com.termux/files/usr/share/android-sdk
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
    export PATH=$PATH:$ANDROID_HOME/platform-tools
fi

# Verify environment
echo "Java version:"
java -version
echo ""
echo "Android SDK location: $ANDROID_HOME"
if [ ! -d "$ANDROID_HOME" ]; then
    echo "❌ Android SDK not found at $ANDROID_HOME"
    echo "Please install Android SDK: pkg install android-sdk"
    exit 1
fi
echo ""

# Check project structure and determine target app
echo "Checking project structure..."
if [ ! -f "usbCameraTest3/build.gradle" ]; then
    echo "❌ usbCameraTest3/build.gradle not found. Not in UVC-Boson project root?"
    exit 1
fi

TARGET_MODULE="usbCameraTest3"
echo "Building target module: $TARGET_MODULE"

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Determine gradle task and output path
if [ "$BUILD_TYPE_LOWER" = "release" ]; then
    echo "Building release APK..."
    GRADLE_TASK="${TARGET_MODULE}:assembleRelease"
    APK_PATTERN="${TARGET_MODULE}/build/outputs/apk/release/*.apk"
else
    echo "Building debug APK..."
    GRADLE_TASK="${TARGET_MODULE}:assembleDebug"
    APK_PATTERN="${TARGET_MODULE}/build/outputs/apk/debug/*.apk"
fi

# Build APK
echo "Building $BUILD_TYPE_LOWER APK..."
./gradlew $GRADLE_TASK

# Check if build was successful
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "APK location:"
    find $TARGET_MODULE/build/outputs/apk -name "*.apk" -type f
    echo ""
    echo "To install on Google Glass:"
    echo "1. Enable Developer Options on your Glass"
    echo "2. Enable USB Debugging"
    echo "3. Connect Glass via USB"
    echo "4. Run: adb install <apk_path>"
    echo ""
    echo "For FLIR UVC-Boson thermal imaging:"
    echo "- Ensure USB OTG adapter is connected"
    echo "- FLIR Boson camera may require external power"
    echo "- Check USB host permissions in app settings"
else
    echo "❌ Build failed. Please check the error messages above."
    echo ""
    echo "Common issues:"
    echo "1. Missing Android SDK components"
    echo "2. Java version compatibility"
    echo "3. NDK not configured (needed for native libraries)"
    echo "4. Gradle sync issues"
    exit 1
fi