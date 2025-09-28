#!/bin/bash

# FLIR ONE USB Image Test Script
# Builds, pushes, and runs the image test program with logging

echo "=============================="
echo "FLIR ONE USB Image Testing Script"
echo "=============================="

# Build the test program
echo -e "\n[1/4] Building test program..."
/home/will/android-sdk/android-ndk-r16b/ndk-build APP_BUILD_SCRIPT=Android_image.mk NDK_APPLICATION_MK=Application_test.mk NDK_PROJECT_PATH=. -B

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo "[2/4] Pushing to device..."
adb push libs/armeabi-v7a/test_flir_with_image /data/local/tmp/

echo "[3/4] Setting permissions..."
adb shell chmod 755 /data/local/tmp/test_flir_with_image

# Make sure all USB devices are accessible
adb shell "chmod 666 /dev/bus/usb/001/*" 2>/dev/null

# Create log file with timestamp
LOGFILE="flir_image_test_$(date +%Y%m%d_%H%M%S).log"

echo "[4/4] Running test (output to $LOGFILE)..."
echo "=============================="
echo "FLIR ONE Image Test - $(date)" | tee $LOGFILE
echo "==============================" | tee -a $LOGFILE

# Run the test and capture output
adb shell /data/local/tmp/test_flir_with_image 2>&1 | tee -a $LOGFILE

echo -e "\n=============================="
echo "Test complete. Results saved to: $LOGFILE"

# Check if frames were captured
if grep -q "Complete frame received" $LOGFILE; then
    echo "✅ SUCCESS: Frames captured!"
    FRAME_COUNT=$(grep -c "Complete frame received" $LOGFILE)
    echo "   Total frames: $FRAME_COUNT"
else
    echo "❌ FAILED: No frames captured"

    # Show any errors
    if grep -q "Read error" $LOGFILE; then
        echo "   Error details:"
        grep "Read error" $LOGFILE | head -3
    fi
fi

echo "=============================="
