package com.serenegiant.usbcameratest3;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class FlirOneDriver {
    private static final String TAG = "FlirOneDriver";
    private static final boolean DEBUG = true;

    // FLIR ONE USB IDs
    private static final int VENDOR_ID = 0x09CB;
    private static final int PRODUCT_ID = 0x1996;

    // Endpoints
    private static final int EP_VIDEO = 0x85;      // Video frames bulk IN
    private static final int EP_CONTROL_IN = 0x81;  // Control bulk IN
    private static final int EP_CONTROL_OUT = 0x02; // Control bulk OUT
    private static final int EP_STATUS = 0x83;     // Status bulk IN

    // Magic bytes for frame start
    private static final byte[] MAGIC_BYTES = {(byte)0xEF, (byte)0xBE, 0x00, 0x00};

    // Frame buffer
    private static final int FRAME_BUFFER_SIZE = 512 * 1024; // 512KB buffer
    private byte[] frameBuffer = new byte[FRAME_BUFFER_SIZE];
    private int frameBufferPos = 0;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface[] interfaces;
    private UsbEndpoint epVideo, epControlIn, epControlOut, epStatus;

    private boolean isStreaming = false;
    private Thread streamThread;
    private FrameCallback frameCallback;

    public interface FrameCallback {
        void onThermalFrame(byte[] thermalData, int width, int height);
        void onVisibleFrame(byte[] jpegData);
        void onError(String error);
    }

    public FlirOneDriver(UsbDevice device) {
        this.device = device;
    }

    public boolean open(UsbDeviceConnection connection) {
        this.connection = connection;

        Log.d(TAG, "Opening FLIR ONE with " + device.getInterfaceCount() + " interfaces");

        // FLIR ONE has 3 interfaces
        int numInterfaces = Math.min(device.getInterfaceCount(), 3);
        interfaces = new UsbInterface[numInterfaces];

        // Try to claim available interfaces (may fail for some)
        for (int i = 0; i < numInterfaces; i++) {
            interfaces[i] = device.getInterface(i);
            boolean claimed = connection.claimInterface(interfaces[i], true);
            Log.d(TAG, "Interface " + i + " claimed: " + claimed);
            // Don't fail completely if one interface fails
        }

        // Find endpoints
        findEndpoints();

        // Initialize camera
        return initializeCamera();
    }

    private void findEndpoints() {
        // Find all endpoints across available interfaces
        for (UsbInterface iface : interfaces) {
            if (iface == null) continue;

            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                int addr = ep.getAddress();

                Log.d(TAG, String.format("Found endpoint: 0x%02X on interface %d",
                          addr & 0xFF, iface.getId()));

                if (addr == EP_CONTROL_IN) {
                    epControlIn = ep;
                } else if (addr == EP_CONTROL_OUT) {
                    epControlOut = ep;
                } else if (addr == EP_STATUS) {
                    epStatus = ep;
                } else if (addr == EP_VIDEO) {
                    epVideo = ep;
                }
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Endpoints found - Control IN: " + (epControlIn != null) +
                      ", Control OUT: " + (epControlOut != null) +
                      ", Video: " + (epVideo != null) +
                      ", Status: " + (epStatus != null));
        }
    }

    private boolean initializeCamera() {
        try {
            byte[] data = new byte[2];

            // Step 1: Control transfer to interface 2
            int r = connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT,
                0x0b,  // bRequest
                0,     // wValue
                2,     // wIndex (interface 2)
                null,  // data
                0,     // length
                100    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Init step 1 failed: " + r);
                return false;
            }

            // Step 2: Control transfer to interface 1
            r = connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT,
                0x0b,  // bRequest
                0,     // wValue
                1,     // wIndex (interface 1)
                null,  // data
                0,     // length
                100    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Init step 2 failed: " + r);
                return false;
            }

            // Step 3: Control transfer to interface 1 with value 1
            r = connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT,
                0x0b,  // bRequest
                1,     // wValue
                1,     // wIndex (interface 1)
                null,  // data
                0,     // length
                100    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Init step 3 failed: " + r);
                return false;
            }

            // Send configuration commands via bulk transfer
            sendConfigCommands();

            // Step 4: Start video stream
            data[0] = 0x00;
            data[1] = 0x00;
            r = connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT,
                0x0b,  // bRequest
                1,     // wValue
                2,     // wIndex (interface 2)
                data,  // data
                2,     // length
                200    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Start stream failed: " + r);
                return false;
            }

            if (DEBUG) Log.d(TAG, "Camera initialized successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
            return false;
        }
    }

    private void sendConfigCommands() {
        // Send configuration strings (from the C++ code)
        byte[] config1 = hexStringToByteArray("0c0001000100000000000000");
        byte[] config2 = hexStringToByteArray("0c0001000300000000000000");

        if (epControlOut != null) {
            connection.bulkTransfer(epControlOut, config1, config1.length, 100);
            connection.bulkTransfer(epControlOut, config2, config2.length, 100);
        }
    }

    public void startStream(FrameCallback callback) {
        this.frameCallback = callback;
        isStreaming = true;

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                streamLoop();
            }
        });
        streamThread.start();
    }

    private void streamLoop() {
        byte[] buffer = new byte[16384]; // 16KB chunks

        while (isStreaming) {
            if (epVideo == null) {
                Log.e(TAG, "Video endpoint not found!");
                break;
            }

            // Read from video endpoint
            int bytesRead = connection.bulkTransfer(epVideo, buffer, buffer.length, 200);

            if (bytesRead > 0) {
                processVideoData(buffer, bytesRead);
            }

            // Also poll status endpoint (non-blocking)
            if (epStatus != null) {
                byte[] statusBuffer = new byte[512];
                connection.bulkTransfer(epStatus, statusBuffer, statusBuffer.length, 10);
            }
        }
    }

    private void processVideoData(byte[] data, int length) {
        // Check for magic bytes at start of new frame
        if (length >= 4 &&
            data[0] == MAGIC_BYTES[0] &&
            data[1] == MAGIC_BYTES[1] &&
            data[2] == MAGIC_BYTES[2] &&
            data[3] == MAGIC_BYTES[3]) {
            // New frame starts, reset buffer
            frameBufferPos = 0;
        }

        // Don't overflow buffer
        if (frameBufferPos + length > FRAME_BUFFER_SIZE) {
            frameBufferPos = 0;
            return;
        }

        // Copy data to frame buffer
        System.arraycopy(data, 0, frameBuffer, frameBufferPos, length);
        frameBufferPos += length;

        // Check if we have a complete frame
        if (frameBufferPos >= 28) { // Minimum header size
            // Parse header
            int frameSize = getInt32(frameBuffer, 8);
            int thermalSize = getInt32(frameBuffer, 12);
            int jpgSize = getInt32(frameBuffer, 16);
            int statusSize = getInt32(frameBuffer, 20);

            // Check if we have the complete frame
            if (frameBufferPos >= frameSize + 28) {
                // Extract thermal data (16-bit raw)
                if (thermalSize > 0 && frameCallback != null) {
                    byte[] thermalData = new byte[thermalSize];
                    System.arraycopy(frameBuffer, 28, thermalData, 0, thermalSize);

                    // FLIR ONE thermal is 160x120 for Gen 2, 80x60 for Gen 1
                    // We'll auto-detect based on data size
                    int pixels = thermalSize / 2; // 16-bit data
                    int width = 160;
                    int height = 120;
                    if (pixels == 80 * 60) {
                        width = 80;
                        height = 60;
                    }

                    frameCallback.onThermalFrame(thermalData, width, height);
                }

                // Extract JPEG data if present
                if (jpgSize > 0 && frameCallback != null) {
                    byte[] jpegData = new byte[jpgSize];
                    System.arraycopy(frameBuffer, 28 + thermalSize, jpegData, 0, jpgSize);
                    frameCallback.onVisibleFrame(jpegData);
                }

                // Reset buffer for next frame
                frameBufferPos = 0;
            }
        }
    }

    private int getInt32(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) |
               ((buffer[offset + 1] & 0xFF) << 8) |
               ((buffer[offset + 2] & 0xFF) << 16) |
               ((buffer[offset + 3] & 0xFF) << 24);
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public void stopStream() {
        isStreaming = false;
        if (streamThread != null) {
            try {
                streamThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    public void close() {
        stopStream();

        if (connection != null && interfaces != null) {
            for (UsbInterface iface : interfaces) {
                if (iface != null) {
                    connection.releaseInterface(iface);
                }
            }
        }
    }

    public static boolean isFlirOneDevice(UsbDevice device) {
        return device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID;
    }
}