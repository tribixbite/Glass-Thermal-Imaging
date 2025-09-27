#!/system/bin/sh
# Direct USB access test for FLIR ONE on Glass using root

echo "=== Direct USB Access Test ==="
echo "Checking for FLIR ONE device..."

# Find FLIR ONE device
DEVICE=$(ls -la /dev/bus/usb/001/ | grep -v "^d" | tail -1 | awk '{print $NF}')
DEVICE_PATH="/dev/bus/usb/001/$DEVICE"

if [ ! -e "$DEVICE_PATH" ]; then
    echo "No USB device found"
    exit 1
fi

echo "Found device: $DEVICE_PATH"

# Check if it's FLIR ONE
VENDOR=$(cat /sys/bus/usb/devices/1-1/idVendor 2>/dev/null)
PRODUCT=$(cat /sys/bus/usb/devices/1-1/idProduct 2>/dev/null)

if [ "$VENDOR" = "09cb" ] && [ "$PRODUCT" = "1996" ]; then
    echo "✓ FLIR ONE detected (VID: $VENDOR, PID: $PRODUCT)"
else
    echo "✗ Not a FLIR ONE device (VID: $VENDOR, PID: $PRODUCT)"
    exit 1
fi

# Set configuration 3
echo "Setting configuration 3..."
echo 3 > /sys/bus/usb/devices/1-1/bConfigurationValue 2>/dev/null
CONFIG=$(cat /sys/bus/usb/devices/1-1/bConfigurationValue)
echo "Current configuration: $CONFIG"

# Check interfaces
echo ""
echo "Available interfaces:"
for iface in /sys/bus/usb/devices/1-1:3.*; do
    if [ -d "$iface" ]; then
        INUM=$(cat $iface/bInterfaceNumber 2>/dev/null)
        CLASS=$(cat $iface/bInterfaceClass 2>/dev/null)
        echo "  Interface $INUM: Class $CLASS"
    fi
done

# Make device accessible
echo "Setting device permissions..."
chmod 666 $DEVICE_PATH
ls -la $DEVICE_PATH

echo ""
echo "Device is ready for direct access"
echo "Path: $DEVICE_PATH"
echo ""
echo "To prevent Android from interfering:"
echo "1. Keep the app stopped: am force-stop com.flir.boson.glass"
echo "2. Use libusb directly or custom C program"