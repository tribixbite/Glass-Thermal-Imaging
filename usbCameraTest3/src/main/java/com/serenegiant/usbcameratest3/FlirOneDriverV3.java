package com.serenegiant.usbcameratest3;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

/**
 * FLIR ONE driver - Clean implementation based on working C code
 * Sequential, blocking initialization without unnecessary state machines
 */
public class FlirOneDriverV3 {
    private static final String TAG = "FlirOneDriverV3";

    private static final int VENDOR_ID = 0x09CB;
    private static final int PRODUCT_ID = 0x1996;

    // Endpoints
    private static final int EP_CONTROL_IN = 0x81;
    private static final int EP_CONTROL_OUT = 0x02;
    private static final int EP_STATUS = 0x83;
    private static final int EP_VIDEO = 0x85;

    private final UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface iface0, iface1, iface2;
    private UsbEndpoint epControlIn, epControlOut, epStatus, epVideo;

    private boolean isStreaming = false;
    private Thread streamThread;
    private FrameCallback frameCallback;
    private int frameCount = 0;

    public interface FrameCallback {
        void onThermalFrame(byte[] data, int width, int height);
        void onJpegFrame(byte[] data);
    }

    public FlirOneDriverV3(UsbDevice device) {
        this.device = device;
    }

    public boolean open(UsbDeviceConnection conn) {
        this.connection = conn;
        Log.d(TAG, "Opening FLIR ONE...");

        // Step 1: Claim interfaces (0, 1, 2)
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
            // Android doesn't have direct setInterface, use control transfer
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

            // Now try to set interface 2 to alt 1 for video streaming
            // This is the critical step for high bandwidth
            Log.d(TAG, "Switching to high bandwidth mode...");

            // Android doesn't have direct alternate setting, use control transfer
            int result = connection.controlTransfer(
                0x01,  // bmRequestType: Interface
                0x0B,  // bRequest: SET_INTERFACE
                1,     // wValue: alternate setting 1
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
            Log.d(TAG, "Initializing camera...");

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

            // Step 4: Send CameraFiles.zip request
            sendCameraFilesRequest();
            Thread.sleep(100);

            // Step 5: Read initial status
            readInitialStatus();
            Thread.sleep(100);

            // Step 6: Start video stream
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
        Log.d(TAG, "Stream loop started");

        // Use smaller buffer size that's multiple of max packet size (512 for high-speed)
        byte[] videoBuffer = new byte[4096];  // Much smaller, multiple of 512
        byte[] statusBuffer = new byte[512];

        byte[] frameBuffer = new byte[512 * 1024]; // Frame assembly buffer
        int framePos = 0;

        while (isStreaming) {
            // Read video data from EP 0x85
            int videoLen = connection.bulkTransfer(epVideo, videoBuffer, videoBuffer.length, 200);
            if (videoLen > 0) {
                // Assemble frame data
                if (framePos + videoLen < frameBuffer.length) {
                    System.arraycopy(videoBuffer, 0, frameBuffer, framePos, videoLen);
                    framePos += videoLen;

                    // Check for complete frame (magic bytes at start)
                    if (framePos >= 4 &&
                        frameBuffer[0] == (byte)0xEF &&
                        frameBuffer[1] == (byte)0xBE &&
                        frameBuffer[2] == 0x00 &&
                        frameBuffer[3] == 0x00) {

                        // We have frame start, check if complete
                        if (framePos >= 28) {
                            int frameSize = getInt32(frameBuffer, 8);
                            if (framePos >= frameSize + 28) {
                                processFrame(frameBuffer, frameSize + 28);
                                // Reset for next frame
                                framePos = 0;
                            }
                        }
                    } else if (framePos > 0 && frameBuffer[0] != (byte)0xEF) {
                        // Bad data, reset
                        framePos = 0;
                    }
                }
            }

            // Keep connection alive - poll status endpoints
            connection.bulkTransfer(epControlIn, statusBuffer, statusBuffer.length, 10);
            connection.bulkTransfer(epStatus, statusBuffer, statusBuffer.length, 10);

            // Small delay to avoid overwhelming
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }

        Log.d(TAG, "Stream loop ended");
    }

    private void processFrame(byte[] data, int length) {
        frameCount++;

        if (length < 28) return;

        int frameSize = getInt32(data, 8);
        int thermalSize = getInt32(data, 12);
        int jpgSize = getInt32(data, 16);

        Log.d(TAG, "Frame #" + frameCount + " - Thermal:" + thermalSize + " JPG:" + jpgSize);

        // Extract thermal data (starts at offset 32)
        if (thermalSize > 0 && frameCallback != null && length >= 32 + thermalSize) {
            byte[] thermalData = new byte[thermalSize];
            System.arraycopy(data, 32, thermalData, 0, thermalSize);

            // FLIR ONE thermal is 160x120, 16-bit per pixel
            frameCallback.onThermalFrame(thermalData, 160, 120);
        }

        // Extract JPEG if present
        if (jpgSize > 0 && frameCallback != null) {
            int jpgOffset = 32 + thermalSize;
            if (length >= jpgOffset + jpgSize) {
                byte[] jpgData = new byte[jpgSize];
                System.arraycopy(data, jpgOffset, jpgData, 0, jpgSize);
                frameCallback.onJpegFrame(jpgData);
            }
        }
    }

    public void stopStream() {
        isStreaming = false;
        if (streamThread != null) {
            try {
                streamThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            streamThread = null;
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

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private int getInt32(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
               ((data[offset + 1] & 0xFF) << 8) |
               ((data[offset + 2] & 0xFF) << 16) |
               ((data[offset + 3] & 0xFF) << 24);
    }

    public static boolean isFlirOneDevice(UsbDevice device) {
        return device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID;
    }
}