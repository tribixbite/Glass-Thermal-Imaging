#!/bin/bash

# Build script for Glass Thermal Imaging app

# Set up Java environment
export JAVA_HOME=/home/will/.sdkman/candidates/java/17.0.12-tem
export PATH=$JAVA_HOME/bin:$PATH

# Set up Android SDK
export ANDROID_HOME=/home/will/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

echo "==========================="
echo "Glass Thermal Imaging Build"
echo "==========================="
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo

# Clean and build
echo "Building APK..."
./gradlew clean assembleDebug

if [ $? -eq 0 ]; then
    echo
    echo "Build successful!"
    echo "APK location: usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk"

    # Install if connected
    if adb devices | grep -q "device$"; then
        echo
        echo "Installing on Glass..."
        adb install -r usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk

        if [ $? -eq 0 ]; then
            echo "Installation successful!"
            echo "Starting app..."
            adb shell am start -n com.flir.boson.glass/com.serenegiant.usbcameratest3.MainActivity
        fi
    else
        echo "Glass not connected - skipping installation"
    fi
else
    echo "Build failed!"
    exit 1
fi