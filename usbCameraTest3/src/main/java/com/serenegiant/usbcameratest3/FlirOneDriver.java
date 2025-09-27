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

    // FLIR ONE USB IDs (already correct based on previous analysis)
    private static final int VENDOR_ID = 0x09CB;
    private static final int PRODUCT_ID = 0x1996;

    // Endpoints
    private static final int EP_VIDEO = 0x85;      // Video frames bulk IN
    private static final int EP_CONTROL_IN = 0x81;  // Control bulk IN
    private static final int EP_CONTROL_OUT = 0x02; // Control bulk OUT
    private static final int EP_STATUS = 0x83;      // Status bulk IN

    // Magic bytes for frame start
    private static final byte[] MAGIC_BYTES = {(byte)0xEF, (byte)0xBE, 0x00, 0x00};

    // Frame buffer - smaller size for Glass compatibility
    private static final int FRAME_BUFFER_SIZE = 512 * 1024; // 512KB buffer
    private byte[] frameBuffer = new byte[FRAME_BUFFER_SIZE];
    private int frameBufferPos = 0;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface iface0, iface1, iface2;
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

    /**
     * Opens and initializes the FLIR ONE using the proven sequence from C code
     */
    public boolean open(UsbDeviceConnection connection) {
        this.connection = connection;
        Log.d(TAG, "Opening FLIR ONE...");

        // Step 1: Claim interfaces 0, 1, 2 with delays (proven to work)
        if (!claimInterfaces()) {
            Log.e(TAG, "Failed to claim interfaces");
            return false;
        }

        // Step 2: Find endpoints
        if (!findEndpoints()) {
            Log.e(TAG, "Failed to find endpoints");
            return false;
        }

        // Step 3: Set alternate interfaces for streaming
        if (!setAlternateInterfaces()) {
            Log.e(TAG, "Failed to set alternate interfaces");
            return false;
        }

        // Step 4: Initialize camera with sequential commands
        if (!initializeCamera()) {
            Log.e(TAG, "Failed to initialize camera");
            return false;
        }

        Log.i(TAG, "FLIR ONE opened successfully");
        return true;
    }

    private boolean claimInterfaces() {
        try {
            // Only claim the 3 interfaces we need
            iface0 = device.getInterface(0);
            iface1 = device.getInterface(1);
            iface2 = device.getInterface(2);

            boolean claimed0 = connection.claimInterface(iface0, true);
            Thread.sleep(50); // Delay between claims

            boolean claimed1 = connection.claimInterface(iface1, true);
            Thread.sleep(50);

            boolean claimed2 = connection.claimInterface(iface2, true);
            Thread.sleep(200); // Stabilization delay

            Log.d(TAG, "Interface claiming: 0=" + claimed0 + " 1=" + claimed1 + " 2=" + claimed2);
            return claimed0 && claimed1 && claimed2;

        } catch (Exception e) {
            Log.e(TAG, "Error claiming interfaces", e);
            return false;
        }
    }

    private boolean findEndpoints() {
        // Search interfaces for the endpoints we need
        for (int j = 0; j < 3; j++) {
            UsbInterface iface = device.getInterface(j);

            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                int addr = ep.getAddress();

                if (addr == EP_CONTROL_IN) {
                    epControlIn = ep;
                    Log.d(TAG, "Found EP 0x81 on interface " + j);
                } else if (addr == EP_CONTROL_OUT) {
                    epControlOut = ep;
                    Log.d(TAG, "Found EP 0x02 on interface " + j);
                } else if (addr == EP_STATUS) {
                    epStatus = ep;
                    Log.d(TAG, "Found EP 0x83 on interface " + j);
                } else if (addr == EP_VIDEO) {
                    epVideo = ep;
                    Log.d(TAG, "Found EP 0x85 on interface " + j);
                }
            }
        }

        return epControlIn != null && epControlOut != null &&
               epStatus != null && epVideo != null;
    }

    private boolean setAlternateInterfaces() {
        try {
            // Set interface 1 to alt 0, interface 2 to alt 1 for video streaming
            // Using control transfers as Android doesn't have direct setInterface

            // Set interface 1 to alt 0
            int result1 = connection.controlTransfer(
                0x01,  // bmRequestType: Interface
                0x0B,  // bRequest: SET_INTERFACE
                0,     // wValue: alternate setting 0
                1,     // wIndex: interface 1
                null,  // no data
                0,     // length
                500    // timeout
            );
            Log.d(TAG, "Interface 1 alt 0: " + (result1 >= 0 ? "SUCCESS" : "FAILED"));
            Thread.sleep(100);

            // Set interface 2 to alt 0 first
            int result2 = connection.controlTransfer(
                0x01,  // bmRequestType: Interface
                0x0B,  // bRequest: SET_INTERFACE
                0,     // wValue: alternate setting 0
                2,     // wIndex: interface 2
                null,  // no data
                0,     // length
                500    // timeout
            );
            Log.d(TAG, "Interface 2 alt 0: " + (result2 >= 0 ? "SUCCESS" : "FAILED"));
            Thread.sleep(100);

            // CRITICAL: Set interface 2 to alt 1 for high bandwidth video streaming
            Log.d(TAG, "Switching to high bandwidth mode...");
            int result = connection.controlTransfer(
                0x01,  // bmRequestType: Interface
                0x0B,  // bRequest: SET_INTERFACE
                1,     // wValue: alternate setting 1 - THIS IS THE KEY!
                2,     // wIndex: interface 2
                null,  // no data
                0,     // length
                1000   // timeout
            );

            if (result >= 0) {
                Log.d(TAG, "Interface 2 alt 1: SUCCESS");
                Thread.sleep(200); // Stabilization
                return true;
            } else {
                Log.w(TAG, "Alt 1 failed, continuing with alt 0");
                return true; // Continue with alt 0
            }

        } catch (Exception e) {
            Log.e(TAG, "Error setting alternates", e);
            return false;
        }
    }

    private boolean initializeCamera() {
        try {
            Log.d(TAG, "Initializing camera with full sequence...");

            // Step 1: Stop interface 2 FRAME
            int result = connection.controlTransfer(
                0x01,  // bmRequestType
                0x0B,  // bRequest
                0,     // wValue: stop
                2,     // wIndex: interface 2
                null,  // no data
                0,
                500
            );
            Log.d(TAG, "Stop interface 2 FRAME: " + result);
            Thread.sleep(50);

            // Step 2: Stop interface 1 FILEIO
            result = connection.controlTransfer(
                0x01,  // bmRequestType
                0x0B,  // bRequest
                0,     // wValue: stop
                1,     // wIndex: interface 1
                null,
                0,
                500
            );
            Log.d(TAG, "Stop interface 1 FILEIO: " + result);
            Thread.sleep(50);

            // Step 3: Start interface 1 FILEIO
            result = connection.controlTransfer(
                0x01,  // bmRequestType
                0x0B,  // bRequest
                1,     // wValue: start
                1,     // wIndex: interface 1
                null,
                0,
                500
            );
            Log.d(TAG, "Start interface 1 FILEIO: " + result);
            Thread.sleep(100);

            // Step 4: Send CameraFiles.zip request (REQUIRED by FLIR ONE protocol)
            sendCameraFilesRequest();
            Thread.sleep(100);

            // Step 5: Read initial status to clear buffers
            readInitialStatus();
            Thread.sleep(100);

            // Step 6: Start video stream on interface 2
            result = connection.controlTransfer(
                0x01,  // bmRequestType
                0x0B,  // bRequest
                1,     // wValue: start
                2,     // wIndex: interface 2
                null,
                0,
                500
            );
            Log.d(TAG, "Start video stream: " + result);
            Thread.sleep(200);

            return result >= 0;

        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
            return false;
        }
    }

    private void sendCameraFilesRequest() {
        try {
            // These headers and JSON are from the proven ROS driver
            // Header 1
            byte[] header1 = hexStringToByteArray("cc0100000100000041000000F8B3F700");
            int ret = connection.bulkTransfer(epControlOut, header1, header1.length, 500);
            Log.d(TAG, "Header1 sent: " + ret + " bytes");

            // JSON 1
            String json1 = "{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}";
            byte[] json1Bytes = (json1 + "\0").getBytes();
            ret = connection.bulkTransfer(epControlOut, json1Bytes, json1Bytes.length, 500);
            Log.d(TAG, "JSON1 sent: " + ret + " bytes");

            // Header 2
            byte[] header2 = hexStringToByteArray("cc0100000100000033000000efdbc1c1");
            ret = connection.bulkTransfer(epControlOut, header2, header2.length, 500);
            Log.d(TAG, "Header2 sent: " + ret + " bytes");

            // JSON 2
            String json2 = "{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":10}}";
            byte[] json2Bytes = (json2 + "\0").getBytes();
            ret = connection.bulkTransfer(epControlOut, json2Bytes, json2Bytes.length, 500);
            Log.d(TAG, "JSON2 sent: " + ret + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "Error sending CameraFiles request", e);
        }
    }

    private void readInitialStatus() {
        byte[] buffer = new byte[1024];

        // Read status from EP 0x81 a few times to clear buffer
        for (int i = 0; i < 5; i++) {
            int len = connection.bulkTransfer(epControlIn, buffer, buffer.length, 500);
            if (len > 0) {
                Log.d(TAG, "Status read " + (i+1) + ": " + len + " bytes");
                // Check for JSON response
                if (len > 16 && buffer[16] == '{') {
                    String json = new String(buffer, 16, Math.min(len - 16, 100));
                    Log.d(TAG, "JSON: " + json);
                }
            } else {
                break;
            }
        }
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
        // CRITICAL: Use 4KB buffer - smaller size that works on Glass's kernel
        byte[] buffer = new byte[4096]; // 4KB chunks - multiple of 512
        byte[] statusBuffer = new byte[512];
        int timeoutCount = 0;
        int framesReceived = 0;

        Log.d(TAG, "Stream loop starting, epVideo=" + epVideo);

        while (isStreaming) {
            if (epVideo == null) {
                Log.e(TAG, "Video endpoint not found!");
                break;
            }

            // Read from video endpoint - use 200ms timeout like ROS driver
            int bytesRead = connection.bulkTransfer(epVideo, buffer, buffer.length, 200);

            if (bytesRead > 0) {
                Log.d(TAG, "Got " + bytesRead + " bytes from video endpoint");
                processVideoData(buffer, bytesRead);
                timeoutCount = 0;
                framesReceived++;
            } else if (bytesRead == -110) {  // Timeout
                timeoutCount++;
                if (timeoutCount % 10 == 0) {
                    Log.d(TAG, "Timeout " + timeoutCount + ", frames=" + framesReceived);
                }
            } else {
                Log.w(TAG, "Bulk transfer returned " + bytesRead);
            }

            // ALWAYS poll EP 0x81 and 0x83 regardless of success - this is key!
            // ROS driver does this after every iteration
            if (epControlIn != null) {
                connection.bulkTransfer(epControlIn, statusBuffer, statusBuffer.length, 10);
            }

            if (epStatus != null) {
                connection.bulkTransfer(epStatus, statusBuffer, statusBuffer.length, 10);
            }
            
            // Break if too many timeouts
            if (timeoutCount > 100) {
                Log.e(TAG, "Too many timeouts, stopping stream");
                break;
            }
        }

        Log.d(TAG, "Stream loop ended");
    }

    private void processVideoData(byte[] data, int length) {
        // ... (remaining code is correct, no changes needed) ...
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

        if (connection != null) {
            if (iface0 != null) connection.releaseInterface(iface0);
            if (iface1 != null) connection.releaseInterface(iface1);
            if (iface2 != null) connection.releaseInterface(iface2);
        }
    }

    public static boolean isFlirOneDevice(UsbDevice device) {
        return device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID;
    }
}