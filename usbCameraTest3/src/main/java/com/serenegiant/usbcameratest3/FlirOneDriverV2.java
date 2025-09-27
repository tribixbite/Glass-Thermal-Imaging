package com.serenegiant.usbcameratest3;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Clean implementation of FLIR ONE driver based on flir_one_node ROS driver
 * Properly implements the state machine and initialization sequence
 */
public class FlirOneDriverV2 {
    private static final String TAG = "FlirOneDriverV2";
    private static final boolean DEBUG = true;

    // FLIR ONE USB IDs
    private static final int VENDOR_ID = 0x09CB;
    private static final int PRODUCT_ID = 0x1996;

    // Configuration
    private static final int USB_CONFIGURATION = 3;  // Critical: Must set config 3!

    // Endpoints (from flir_one_node)
    private static final int EP_CONTROL_OUT = 0x02; // Interface 0
    private static final int EP_CONTROL_IN = 0x81;  // Interface 0
    private static final int EP_STATUS = 0x83;      // Interface 1
    private static final int EP_VIDEO = 0x85;       // Interface 2

    // Magic bytes for frame start
    private static final byte[] MAGIC_BYTES = {(byte)0xEF, (byte)0xBE, 0x00, 0x00};

    // Frame buffer (1MB like ROS driver)
    private static final int FRAME_BUFFER_SIZE = 1024 * 1024;
    private static final int BUF85SIZE = FRAME_BUFFER_SIZE;
    private byte[] buf85 = new byte[BUF85SIZE];
    private int buf85pointer = 0;

    // State machine (matching ROS driver)
    private enum State {
        INIT,           // Stop interface 2
        INIT_1,         // Stop interface 1
        INIT_2,         // Start interface 1
        ASK_ZIP,        // Send CameraFiles.zip request
        ASK_VIDEO,      // Start video stream
        POOL_FRAME,     // Read frames
        ERROR
    }

    private State state = State.INIT;
    private long fps_t = 0;
    private int frameCount = 0;

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

    public FlirOneDriverV2(UsbDevice device) {
        this.device = device;
    }

    public boolean open(UsbDeviceConnection connection) {
        this.connection = connection;

        Log.d(TAG, "Opening FLIR ONE - Setting configuration 3...");

        // Critical: Set configuration 3 like ROS driver
        // Android doesn't have setConfiguration, but we can try control transfer
        boolean configSet = setConfiguration(3);
        if (!configSet) {
            Log.w(TAG, "Could not set configuration 3, continuing anyway...");
        }

        // Claim only interfaces 0, 1, 2 (not all 5)
        if (!claimInterfaces()) {
            Log.e(TAG, "Failed to claim interfaces");
            return false;
        }

        // Find endpoints
        if (!findEndpoints()) {
            Log.e(TAG, "Failed to find endpoints");
            return false;
        }

        // Start state machine initialization
        state = State.INIT;
        return true;
    }

    private boolean setConfiguration(int config) {
        try {
            // Android USB API doesn't expose setConfiguration directly
            // Try using control transfer: SET_CONFIGURATION
            int result = connection.controlTransfer(
                0x00,  // bmRequestType: Standard, Device
                0x09,  // bRequest: SET_CONFIGURATION
                config, // wValue: configuration value
                0,      // wIndex
                null,   // data
                0,      // length
                1000    // timeout
            );

            if (result >= 0) {
                Log.d(TAG, "Configuration set to " + config);
                return true;
            } else {
                Log.e(TAG, "SET_CONFIGURATION failed: " + result);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting configuration", e);
            return false;
        }
    }

    private boolean claimInterfaces() {
        try {
            // Debug: Log all interfaces
            int numInterfaces = device.getInterfaceCount();
            Log.d(TAG, "Device has " + numInterfaces + " interfaces");

            for (int i = 0; i < numInterfaces; i++) {
                UsbInterface iface = device.getInterface(i);
                Log.d(TAG, "Interface " + i + ": ID=" + iface.getId() +
                         ", Endpoints=" + iface.getEndpointCount() +
                         ", Class=" + iface.getInterfaceClass());
            }

            // Try claiming ALL interfaces, even alternates
            // Glass might need this approach
            boolean anyClaimed = false;
            UsbInterface[] claimedInterfaces = new UsbInterface[numInterfaces];

            for (int i = 0; i < numInterfaces; i++) {
                UsbInterface iface = device.getInterface(i);

                // Try with force flag first
                boolean claimed = connection.claimInterface(iface, true);
                if (!claimed) {
                    // Try without force flag
                    claimed = connection.claimInterface(iface, false);
                }

                Log.d(TAG, "Interface " + i + " (ID=" + iface.getId() +
                         ", EP=" + iface.getEndpointCount() + ") claimed: " + claimed);

                if (claimed) {
                    anyClaimed = true;
                    claimedInterfaces[i] = iface;

                    // Store interfaces by their ID
                    if (iface.getId() == 0 && iface.getEndpointCount() > 0) iface0 = iface;
                    else if (iface.getId() == 1 && iface.getEndpointCount() > 0) iface1 = iface;
                    else if (iface.getId() == 2 && iface.getEndpointCount() > 0) iface2 = iface;
                }
            }

            // Also try the interfaces at specific positions based on sysfs
            // The sysfs shows 3.0, 3.1, 3.2 which might mean configuration 3
            if (!anyClaimed) {
                Log.w(TAG, "Standard claiming failed, device might need kernel support");
            }

            return anyClaimed;

        } catch (Exception e) {
            Log.e(TAG, "Error claiming interfaces", e);
            return false;
        }
    }

    private boolean findEndpoints() {
        // Search all interfaces for the endpoints we need
        int numInterfaces = device.getInterfaceCount();

        for (int j = 0; j < numInterfaces; j++) {
            UsbInterface iface = device.getInterface(j);

            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                int addr = ep.getAddress();

                Log.d(TAG, String.format("Interface %d endpoint: 0x%02X", j, addr & 0xFF));

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

        Log.d(TAG, "Endpoints: ControlIN=" + (epControlIn != null) +
                  ", ControlOUT=" + (epControlOut != null) +
                  ", Status=" + (epStatus != null) +
                  ", Video=" + (epVideo != null));

        return epControlIn != null && epControlOut != null &&
               epStatus != null && epVideo != null;
    }

    public void startStream(FrameCallback callback) {
        this.frameCallback = callback;
        isStreaming = true;

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        });
        streamThread.start();
    }

    /**
     * Main polling loop - implements state machine like ROS driver
     */
    private void pollLoop() {
        byte[] data = new byte[2];
        byte[] buf = new byte[16384];
        int actual_length;
        int r;

        while (isStreaming) {
            switch (state) {
                case INIT:
                    Log.d(TAG, "State: INIT - Stop interface 2 FRAME");
                    r = connection.controlTransfer(1, 0x0b, 0, 2, data, 0, 100);
                    if (r < 0) {
                        Log.e(TAG, "Control Out error " + r + ", continuing anyway");
                    }
                    state = State.INIT_1;
                    break;

                case INIT_1:
                    Log.d(TAG, "State: INIT_1 - Stop interface 1 FILEIO");
                    r = connection.controlTransfer(1, 0x0b, 0, 1, data, 0, 100);
                    if (r < 0) {
                        Log.e(TAG, "Control Out error " + r + ", continuing anyway");
                    }
                    state = State.INIT_2;
                    break;

                case INIT_2:
                    Log.d(TAG, "State: INIT_2 - Start interface 1 FILEIO");
                    r = connection.controlTransfer(1, 0x0b, 1, 1, data, 0, 100);
                    if (r < 0) {
                        Log.e(TAG, "Control Out error " + r + ", continuing anyway");
                    }
                    state = State.ASK_ZIP;
                    break;

                case ASK_ZIP:
                    Log.d(TAG, "State: ASK_ZIP - Request CameraFiles.zip");
                    sendCameraFilesRequest();
                    state = State.ASK_VIDEO;
                    break;

                case ASK_VIDEO:
                    Log.d(TAG, "State: ASK_VIDEO - Start video stream");
                    byte[] startData = new byte[]{0, 0};
                    r = connection.controlTransfer(1, 0x0b, 1, 2, startData, 2, 200);
                    if (r < 0) {
                        Log.e(TAG, "Control Out error " + r + ", continuing anyway");
                    }
                    state = State.POOL_FRAME;
                    break;

                case POOL_FRAME:
                    // Poll video endpoint
                    actual_length = connection.bulkTransfer(epVideo, buf, buf.length, 200);
                    if (actual_length > 0) {
                        Log.d(TAG, "Frame data: " + actual_length + " bytes");
                        processFrameData(buf, actual_length);
                    }
                    break;

                case ERROR:
                    isStreaming = false;
                    break;
            }

            // ALWAYS poll EP 0x81 and 0x83 after each iteration (critical!)
            if (state == State.POOL_FRAME) {
                actual_length = connection.bulkTransfer(epControlIn, buf, buf.length, 10);
                if (actual_length > 0 && DEBUG) {
                    Log.d(TAG, "EP 0x81: " + actual_length + " bytes");
                }

                actual_length = connection.bulkTransfer(epStatus, buf, buf.length, 10);
                if (actual_length > 0 && DEBUG) {
                    Log.d(TAG, "EP 0x83: " + actual_length + " bytes");
                }
            }

            // Small delay to avoid overwhelming the system
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }

        Log.d(TAG, "Poll loop ended");
    }

    private void sendCameraFilesRequest() {
        if (epControlOut == null) {
            Log.e(TAG, "Control OUT endpoint not found");
            return;
        }

        try {
            // Header 1
            byte[] header1 = hexStringToByteArray("cc0100000100000041000000F8B3F700");
            int ret = connection.bulkTransfer(epControlOut, header1, header1.length, 0);
            Log.d(TAG, "Header1: " + ret + " bytes");

            // JSON 1 with null terminator
            String json1 = "{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}";
            byte[] json1Bytes = new byte[json1.length() + 1];
            System.arraycopy(json1.getBytes(), 0, json1Bytes, 0, json1.length());
            json1Bytes[json1.length()] = 0;
            ret = connection.bulkTransfer(epControlOut, json1Bytes, json1Bytes.length, 0);
            Log.d(TAG, "JSON1: " + ret + " bytes");

            // Header 2
            byte[] header2 = hexStringToByteArray("cc0100000100000033000000efdbc1c1");
            ret = connection.bulkTransfer(epControlOut, header2, header2.length, 0);
            Log.d(TAG, "Header2: " + ret + " bytes");

            // JSON 2 with null terminator
            String json2 = "{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":10}}";
            byte[] json2Bytes = new byte[json2.length() + 1];
            System.arraycopy(json2.getBytes(), 0, json2Bytes, 0, json2.length());
            json2Bytes[json2.length()] = 0;
            ret = connection.bulkTransfer(epControlOut, json2Bytes, json2Bytes.length, 0);
            Log.d(TAG, "JSON2: " + ret + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "Error sending CameraFiles request", e);
        }
    }

    private void processFrameData(byte[] buf, int actual_length) {
        // Check for magic bytes at start of new frame
        boolean hasMagic = (actual_length >= 4 &&
            buf[0] == MAGIC_BYTES[0] &&
            buf[1] == MAGIC_BYTES[1] &&
            buf[2] == MAGIC_BYTES[2] &&
            buf[3] == MAGIC_BYTES[3]);

        // Reset buffer if new frame or buffer overflow
        if (hasMagic || (buf85pointer + actual_length >= BUF85SIZE)) {
            buf85pointer = 0;
        }

        // Copy data to frame buffer
        System.arraycopy(buf, 0, buf85, buf85pointer, actual_length);
        buf85pointer += actual_length;

        // Check if buffer has magic bytes at start
        if (buf85pointer >= 4) {
            boolean bufferHasMagic = (buf85[0] == MAGIC_BYTES[0] &&
                                      buf85[1] == MAGIC_BYTES[1] &&
                                      buf85[2] == MAGIC_BYTES[2] &&
                                      buf85[3] == MAGIC_BYTES[3]);

            if (!bufferHasMagic) {
                buf85pointer = 0;
                Log.w(TAG, "Reset buffer - bad magic bytes");
                return;
            }
        }

        // Check if we have enough data for header
        if (buf85pointer < 28) {
            return; // Wait for more data
        }

        // Parse frame header
        int frameSize = getInt32(buf85, 8);
        int thermalSize = getInt32(buf85, 12);
        int jpgSize = getInt32(buf85, 16);
        int statusSize = getInt32(buf85, 20);

        // Check if we have complete frame
        if (buf85pointer < frameSize + 28) {
            return; // Wait for more data
        }

        frameCount++;
        Log.d(TAG, "Frame #" + frameCount + " - Thermal:" + thermalSize + " JPG:" + jpgSize);

        // Extract thermal data
        if (thermalSize > 0 && frameCallback != null) {
            // Thermal data starts at offset 32 (28 header + 4 padding)
            byte[] thermalData = new byte[thermalSize];

            // FLIR ONE specific unpacking (160x120, split into two 80x120 blocks)
            int index = 0;
            for (int y = 0; y < 120; y++) {
                for (int x = 0; x < 160; x++) {
                    int offset;
                    if (x < 80) {
                        offset = 32 + 2 * (y * 164 + x);
                    } else {
                        offset = 32 + 2 * (y * 164 + x) + 4;
                    }

                    if (index + 1 < thermalData.length && offset + 1 < buf85.length) {
                        thermalData[index++] = buf85[offset];
                        thermalData[index++] = buf85[offset + 1];
                    }
                }
            }

            frameCallback.onThermalFrame(thermalData, 160, 120);
        }

        // Extract JPEG data
        if (jpgSize > 0 && frameCallback != null) {
            byte[] jpegData = new byte[jpgSize];
            System.arraycopy(buf85, 28 + thermalSize, jpegData, 0, jpgSize);
            frameCallback.onVisibleFrame(jpegData);
        }

        // Reset buffer for next frame
        buf85pointer = 0;
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