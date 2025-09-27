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

    // Frame buffer (1MB like ROS driver)
    private static final int FRAME_BUFFER_SIZE = 1024 * 1024; // 1MB buffer
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

        // FLIR ONE shows 5 interfaces on Glass (0-4)
        // Interface 0,2,4 have the endpoints we need
        int numInterfaces = device.getInterfaceCount();
        interfaces = new UsbInterface[numInterfaces];

        // Claim all interfaces and find the ones with endpoints
        for (int i = 0; i < numInterfaces; i++) {
            interfaces[i] = device.getInterface(i);
            Log.d(TAG, "Interface " + i + " ID=" + interfaces[i].getId() +
                     " endpoints=" + interfaces[i].getEndpointCount());

            // Only claim interfaces with endpoints
            if (interfaces[i].getEndpointCount() > 0) {
                boolean claimed = connection.claimInterface(interfaces[i], true);
                Log.d(TAG, "Interface " + i + " claimed: " + claimed);
                if (!claimed) {
                    // Try without force flag
                    claimed = connection.claimInterface(interfaces[i], false);
                    Log.d(TAG, "Interface " + i + " retry claimed: " + claimed);
                }
            } else {
                Log.d(TAG, "Interface " + i + " skipped (no endpoints)");
            }
        }

        // Find endpoints
        findEndpoints();

        // Initialize camera
        return initializeCamera();
    }

    private void findEndpoints() {
        // Find all endpoints across available interfaces
        for (int j = 0; j < interfaces.length; j++) {
            UsbInterface iface = interfaces[j];
            if (iface == null) continue;

            // Check endpoints
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
            // Wait a moment after claiming interfaces
            Thread.sleep(100);

            // Step 1: Stop interface 2 FRAME
            Log.d(TAG, "Sending stop command to interface 2...");
            // Use exact values from working C code: 0x01 for bRequestType
            int r = connection.controlTransfer(
                0x01,  // bRequestType: Vendor OUT (0x01 worked in C)
                0x0b,  // bRequest
                0,     // wValue (stop)
                2,     // wIndex (interface 2)
                null,  // data
                0,     // length
                500    // timeout increased
            );
            if (r < 0) {
                Log.e(TAG, "Stop interface 2 failed: " + r);
                // Try alternative approach with empty byte array
                byte[] dummy = new byte[0];
                r = connection.controlTransfer(
                    0x01,  // Vendor OUT
                    0x0b, 0, 2, dummy, 0, 500);
                if (r < 0) {
                    Log.e(TAG, "Stop interface 2 retry also failed: " + r);
                    // Continue anyway as device might already be stopped
                }
            } else {
                Log.d(TAG, "Stop interface 2 succeeded");
            }

            // Step 2: Stop interface 1 FILEIO
            Log.d(TAG, "Sending stop command to interface 1...");
            r = connection.controlTransfer(
                0x01,  // bRequestType: Vendor OUT
                0x0b,  // bRequest
                0,     // wValue (stop)
                1,     // wIndex (interface 1)
                null,  // data
                0,     // length
                500    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Stop interface 1 failed: " + r);
                // Continue anyway
            } else {
                Log.d(TAG, "Stop interface 1 succeeded");
            }

            // Step 3: Start interface 1 FILEIO
            Log.d(TAG, "Sending start command to interface 1...");
            r = connection.controlTransfer(
                0x01,  // bRequestType: Vendor OUT
                0x0b,  // bRequest
                1,     // wValue (start)
                1,     // wIndex (interface 1)
                null,  // data
                0,     // length
                500    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Start interface 1 failed: " + r);
                // Continue anyway
            } else {
                Log.d(TAG, "Start interface 1 succeeded");
            }

            // Send CameraFiles.zip request
            sendConfigCommands();

            // Wait for initialization
            Thread.sleep(200);

            // Step 4: Start video stream (simplified - no data)
            Log.d(TAG, "Sending start command to interface 2 (video)...");
            r = connection.controlTransfer(
                0x01,  // bRequestType: Vendor OUT
                0x0b,  // bRequest
                1,     // wValue (start)
                2,     // wIndex (interface 2)
                null,  // No data - critical for Glass
                0,     // length = 0
                500    // timeout
            );
            if (r < 0) {
                Log.e(TAG, "Start stream failed: " + r);
                // Continue anyway, might still work
            } else {
                Log.d(TAG, "Start stream succeeded");
            }

            // Wait for stream to stabilize
            Thread.sleep(500);

            // Even if control transfers fail, try to proceed
            // The camera might already be in the right state
            Log.d(TAG, "Camera initialization complete (ignoring control transfer errors)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
            return false;
        }
    }

    private void sendConfigCommands() {
        Log.d(TAG, "sendConfigCommands: epControlOut=" + epControlOut + ", epControlIn=" + epControlIn);
        if (epControlOut == null) {
            Log.e(TAG, "Control OUT endpoint not found");
            return;
        }

        // Try a simple test first - just read from EP 0x81
        byte[] testRead = new byte[512];
        int test = connection.bulkTransfer(epControlIn, testRead, testRead.length, 100);
        Log.d(TAG, "Test read from 0x81: " + test + " bytes");

        // Send CameraFiles.zip request (required for initialization)
        // Header 1
        byte[] header1 = hexStringToByteArray("cc0100000100000041000000F8B3F700");
        int ret = connection.bulkTransfer(epControlOut, header1, header1.length, 100);
        Log.d(TAG, "Header1 sent: " + ret + " bytes (error means USB not ready)");

        // JSON 1
        String json1 = "{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}";
        byte[] json1Bytes = json1.getBytes();
        ret = connection.bulkTransfer(epControlOut, json1Bytes, json1Bytes.length, 100);
        Log.d(TAG, "JSON1 sent: " + ret + " bytes");

        // Header 2
        byte[] header2 = hexStringToByteArray("cc0100000100000033000000efdbc1c1");
        ret = connection.bulkTransfer(epControlOut, header2, header2.length, 100);
        Log.d(TAG, "Header2 sent: " + ret + " bytes");

        // JSON 2
        String json2 = "{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":10}}";
        byte[] json2Bytes = json2.getBytes();
        ret = connection.bulkTransfer(epControlOut, json2Bytes, json2Bytes.length, 100);
        Log.d(TAG, "JSON2 sent: " + ret + " bytes");

        if (DEBUG) Log.d(TAG, "Sent CameraFiles.zip request");

        // Read response from EP 0x81 (like the C test does)
        if (epControlIn != null) {
            byte[] response = new byte[65536];
            int resp = connection.bulkTransfer(epControlIn, response, response.length, 500);
            if (resp > 0) {
                Log.d(TAG, "Got " + resp + " bytes response from EP 0x81");
                // Check for battery JSON response
                if (resp > 16 && response[16] == '{') {
                    String json = new String(response, 16, Math.min(resp - 16, 200));
                    Log.d(TAG, "Response JSON: " + json);
                }
            }
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
        byte[] statusBuffer = new byte[512];
        int timeoutCount = 0;
        int framesReceived = 0;

        Log.d(TAG, "Stream loop starting, epVideo=" + epVideo);

        while (isStreaming) {
            if (epVideo == null) {
                Log.e(TAG, "Video endpoint not found!");
                break;
            }

            // Read from video endpoint with longer timeout
            int bytesRead = connection.bulkTransfer(epVideo, buffer, buffer.length, 2000);

            if (bytesRead > 0) {
                Log.d(TAG, "Got " + bytesRead + " bytes from video endpoint");
                processVideoData(buffer, bytesRead);
                timeoutCount = 0;
                framesReceived++;
            } else {
                // On timeout, poll status endpoints to keep connection alive
                timeoutCount++;
                if (timeoutCount % 10 == 0) {
                    Log.d(TAG, "Timeout " + timeoutCount + ", frames=" + framesReceived);
                }

                // Poll EP 0x81
                if (epControlIn != null) {
                    connection.bulkTransfer(epControlIn, statusBuffer, statusBuffer.length, 10);
                }

                // Poll EP 0x83
                if (epStatus != null) {
                    connection.bulkTransfer(epStatus, statusBuffer, statusBuffer.length, 10);
                }

                // Break if too many timeouts
                if (timeoutCount > 100) {
                    Log.e(TAG, "Too many timeouts, stopping stream");
                    break;
                }
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