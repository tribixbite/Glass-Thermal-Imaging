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

    /**
     * Attempts to open and claim interfaces on the FLIR ONE.
     * @param connection The UsbDeviceConnection
     * @return true if successful, false otherwise.
     */
    public boolean open(UsbDeviceConnection connection) {
        this.connection = connection;

        Log.d(TAG, "Opening FLIR ONE with " + device.getInterfaceCount() + " interfaces");

        int numInterfaces = device.getInterfaceCount();
        interfaces = new UsbInterface[numInterfaces];

        boolean claimedAnyInterface = false;

        // Iterate through all interfaces to find and claim them
        for (int i = 0; i < numInterfaces; i++) {
            UsbInterface iface = device.getInterface(i);
            
            // Only claim interfaces that have endpoints
            if (iface.getEndpointCount() > 0) {
                // Try with force flag first
                boolean claimed = connection.claimInterface(iface, true);
                
                if (!claimed) {
                    // If that fails, try without the force flag
                    claimed = connection.claimInterface(iface, false);
                }

                if (claimed) {
                    Log.d(TAG, "Interface " + i + " ID=" + iface.getId() + " claimed successfully.");
                    interfaces[i] = iface;
                    claimedAnyInterface = true;
                } else {
                    Log.w(TAG, "Interface " + i + " ID=" + iface.getId() + " failed to claim.");
                }
            } else {
                Log.d(TAG, "Interface " + i + " skipped (no endpoints)");
            }
        }

        if (!claimedAnyInterface) {
            Log.e(TAG, "Failed to claim any interfaces. Cannot continue.");
            return false;
        }

        // Find endpoints on the interfaces we successfully claimed
        findEndpoints();

        if (epVideo == null || epControlIn == null || epControlOut == null || epStatus == null) {
            Log.e(TAG, "Failed to find all required endpoints. Cannot continue.");
            return false;
        }

        return initializeCamera();
    }

    private void findEndpoints() {
        // Find all endpoints across successfully claimed interfaces
        for (UsbInterface iface : interfaces) {
            if (iface == null) continue;

            // Check endpoints
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                int addr = ep.getAddress();
                int direction = ep.getDirection();

                Log.d(TAG, String.format("Found endpoint: 0x%02X (dir %d) on interface %d",
                        addr & 0xFF, direction, iface.getId()));

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
            // Wait for interfaces to settle
            Thread.sleep(100);

            // Per the log, SET_CONFIGURATION fails. We cannot rely on that.
            // Instead, we perform a minimal initialization using only bulk transfers.
            Log.d(TAG, "Attempting minimal initialization on Google Glass...");

            // Try to perform a bulk transfer to each endpoint. This often "wakes up"
            // the device and gets it into a receptive state.
            byte[] testBuf = new byte[512];
            if (epControlOut != null) {
                int test = connection.bulkTransfer(epControlOut, testBuf, 0, 100);
                Log.d(TAG, "Test write to EP 0x02: " + test + " bytes");
            }

            if (epControlIn != null) {
                int test = connection.bulkTransfer(epControlIn, testBuf, testBuf.length, 100);
                Log.d(TAG, "Test read from EP 0x81: " + test + " bytes");
            }
            
            if (epStatus != null) {
                int test = connection.bulkTransfer(epStatus, testBuf, testBuf.length, 100);
                Log.d(TAG, "Test read from EP 0x83: " + test + " bytes");
            }

            // Wait for device to start streaming
            Thread.sleep(500);

            Log.d(TAG, "Camera initialization complete (Glass minimal mode)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
            return false;
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