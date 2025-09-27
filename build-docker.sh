#!/bin/bash

# Docker-based build script for Glass Thermal Imaging app
# This script uses a Docker container with Android SDK to build the APK

set -e

echo "======================================"
echo "Glass Thermal Imaging Docker Build"
echo "======================================"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_status() { echo -e "${GREEN}✓${NC} $1"; }
print_error() { echo -e "${RED}✗${NC} $1"; }
print_warning() { echo -e "${YELLOW}⚠${NC} $1"; }

BUILD_TYPE=${1:-debug}

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    print_error "Docker not found. Trying alternative build method..."

    # Alternative: Use GitHub Actions or manual instructions
    echo ""
    echo "Alternative Build Instructions:"
    echo "==============================="
    echo ""
    echo "Since Java/Docker aren't available, you can:"
    echo ""
    echo "1. Build on a machine with Android Studio:"
    echo "   - Open the project in Android Studio"
    echo "   - Build > Generate Signed Bundle/APK > APK"
    echo "   - Choose 'debug' build variant"
    echo ""
    echo "2. Build on a machine with Java installed:"
    echo "   - Install JDK 11: apt/brew install openjdk-11-jdk"
    echo "   - Run: ./gradlew assembleDebug"
    echo ""
    echo "3. Use pre-built APK (if available):"
    echo "   - Check releases folder for pre-built APKs"
    echo ""

    # Create a minimal build file for manual compilation
    cat > manual-build-steps.txt <<'EOF'
Manual Build Steps for Glass Thermal Imaging App
================================================

Prerequisites:
- JDK 8 or higher
- Android SDK with API 19
- Android NDK (optional, for native libraries)

Steps:
1. Set environment variables:
   export ANDROID_HOME=/path/to/android-sdk
   export JAVA_HOME=/path/to/java

2. Create local.properties:
   echo "sdk.dir=$ANDROID_HOME" > local.properties

3. Build debug APK:
   ./gradlew clean assembleDebug

4. Find APK at:
   usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk

5. Install on Glass:
   adb install -r usbCameraTest3-debug.apk

Notes:
- The app auto-requests USB permission for FLIR cameras
- Exit with SWIPE_DOWN gesture or back button
- TAP gesture disabled to prevent crashes
EOF

    print_status "Created manual-build-steps.txt with instructions"
    exit 1
fi

# Use Docker to build
print_status "Building with Docker..."

# Create Dockerfile if it doesn't exist
cat > Dockerfile.build <<'EOF'
FROM openjdk:11-jdk

# Install Android SDK
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME /opt/android-sdk
ENV PATH ${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

# Download Android command line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    cd ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip && \
    unzip -q commandlinetools-linux-9477386_latest.zip && \
    mv cmdline-tools latest && \
    rm commandlinetools-linux-9477386_latest.zip

# Accept licenses and install required packages
RUN yes | sdkmanager --licenses && \
    sdkmanager "platforms;android-19" "build-tools;25.0.2" "platform-tools"

# Set working directory
WORKDIR /app

# Copy project files
COPY . .

# Create local.properties
RUN echo "sdk.dir=${ANDROID_HOME}" > local.properties

# Make gradlew executable
RUN chmod +x gradlew

# Build command will be run when container starts
CMD ["./gradlew", "assembleDebug"]
EOF

print_status "Building Docker image..."
docker build -f Dockerfile.build -t glass-thermal-build .

print_status "Running build in container..."
docker run --rm -v $(pwd):/app glass-thermal-build

# Check if build succeeded
APK_PATH="usbCameraTest3/build/outputs/apk/debug/usbCameraTest3-debug.apk"
if [ -f "$APK_PATH" ]; then
    print_status "Build successful!"
    cp "$APK_PATH" "glass-thermal-debug.apk"
    print_status "APK copied to: glass-thermal-debug.apk"

    # Install if Glass is connected
    if adb devices | grep -q "192.168.1.137:24"; then
        echo ""
        print_status "Installing on Glass..."
        adb install -r glass-thermal-debug.apk
        print_status "Installation complete!"
    fi
else
    print_error "Build failed!"
    exit 1
fi