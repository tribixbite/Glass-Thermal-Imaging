#!/bin/bash

# Build script for Glass Thermal Imaging app on WSL
# This script sets up the environment and builds the APK

set -e

echo "======================================"
echo "Glass Thermal Imaging Build Script (WSL)"
echo "======================================"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

# Check for required tools
check_requirements() {
    echo "Checking build requirements..."

    # Check for Java
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1)
        print_status "Java found: $JAVA_VERSION"
    else
        print_error "Java not found. Please install JDK 8 or higher:"
        echo "  sudo apt update && sudo apt install openjdk-11-jdk"
        exit 1
    fi

    # Check for Android SDK
    if [ -z "$ANDROID_HOME" ]; then
        print_warning "ANDROID_HOME not set. Checking common locations..."

        # Common Android SDK locations
        POSSIBLE_SDK_PATHS=(
            "$HOME/Android/Sdk"
            "$HOME/android-sdk"
            "/usr/local/android-sdk"
            "/opt/android-sdk"
        )

        for SDK_PATH in "${POSSIBLE_SDK_PATHS[@]}"; do
            if [ -d "$SDK_PATH" ]; then
                export ANDROID_HOME="$SDK_PATH"
                print_status "Found Android SDK at: $ANDROID_HOME"
                break
            fi
        done

        if [ -z "$ANDROID_HOME" ]; then
            print_error "Android SDK not found. Please install Android SDK and set ANDROID_HOME"
            echo "  Download from: https://developer.android.com/studio#command-tools"
            exit 1
        fi
    else
        print_status "Android SDK found at: $ANDROID_HOME"
    fi

    # Check for required SDK components
    if [ ! -d "$ANDROID_HOME/platforms/android-19" ]; then
        print_warning "Android API 19 not installed. Installing..."
        if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
            "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-19" "build-tools;25.0.2"
        else
            print_error "SDK Manager not found. Please install Android API 19 manually"
            exit 1
        fi
    fi

    # Check for NDK
    if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$NDK_HOME" ]; then
        print_warning "NDK not found in environment. Checking common locations..."

        POSSIBLE_NDK_PATHS=(
            "$ANDROID_HOME/ndk-bundle"
            "$ANDROID_HOME/ndk/21.4.7075529"
            "$ANDROID_HOME/ndk/20.1.5948944"
            "$HOME/android-ndk"
        )

        for NDK_PATH in "${POSSIBLE_NDK_PATHS[@]}"; do
            if [ -d "$NDK_PATH" ]; then
                export ANDROID_NDK_HOME="$NDK_PATH"
                export NDK_HOME="$NDK_PATH"
                print_status "Found NDK at: $ANDROID_NDK_HOME"
                break
            fi
        done

        if [ -z "$ANDROID_NDK_HOME" ]; then
            print_warning "NDK not found. Native libraries may not build correctly"
            echo "  Install with: sdkmanager 'ndk;21.4.7075529'"
        fi
    else
        print_status "NDK found at: ${ANDROID_NDK_HOME:-$NDK_HOME}"
    fi
}

# Set up gradle properties
setup_gradle() {
    echo "Setting up Gradle configuration..."

    # Create local.properties
    cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=${ANDROID_NDK_HOME:-$NDK_HOME}
EOF

    print_status "Created local.properties"

    # Ensure gradle wrapper is executable
    chmod +x gradlew
    print_status "Gradle wrapper is executable"
}

# Build the project
build_project() {
    BUILD_TYPE=${1:-debug}

    echo ""
    echo "Building APK (${BUILD_TYPE})..."
    echo "======================================"

    # Clean previous builds
    print_status "Cleaning previous builds..."
    ./gradlew clean

    # Build based on type
    if [ "$BUILD_TYPE" = "release" ]; then
        print_status "Building release APK..."
        ./gradlew assembleRelease

        APK_PATH="usbCameraTest3/build/outputs/apk/release/usbCameraTest3-release-unsigned.apk"
    else
        print_status "Building debug APK..."
        ./gradlew assembleDebug

        APK_PATH="usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk"
    fi

    # Check if build succeeded
    if [ -f "$APK_PATH" ]; then
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        print_status "Build successful! APK size: $APK_SIZE"
        echo ""
        echo "APK location:"
        echo "  $APK_PATH"

        # Copy to easy location
        cp "$APK_PATH" "glass-thermal-${BUILD_TYPE}.apk"
        print_status "Copied to: glass-thermal-${BUILD_TYPE}.apk"

        # Show install instructions
        echo ""
        echo "To install on Glass:"
        echo "  adb install -r glass-thermal-${BUILD_TYPE}.apk"

        # If Glass is connected, offer to install
        if adb devices | grep -q "192.168.1.137:24"; then
            echo ""
            read -p "Glass is connected. Install now? (y/n) " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                print_status "Installing to Glass..."
                adb install -r "glass-thermal-${BUILD_TYPE}.apk"
                print_status "Installation complete!"

                # Offer to launch
                read -p "Launch app on Glass? (y/n) " -n 1 -r
                echo
                if [[ $REPLY =~ ^[Yy]$ ]]; then
                    adb shell am start -n com.flir.boson.glass/com.serenegiant.usbcameratest3.MainActivity
                    print_status "App launched!"
                fi
            fi
        fi
    else
        print_error "Build failed! Check the error messages above."
        exit 1
    fi
}

# Main execution
main() {
    check_requirements
    setup_gradle
    build_project "$1"

    echo ""
    print_status "Build complete!"
}

# Run main function with argument (debug/release)
main "$1"