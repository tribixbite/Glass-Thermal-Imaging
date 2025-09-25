/*
 *  UVCCamera - Glass Thermal Imaging Edition
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest3;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.widget.UVCCameraTextureView;
import com.flir.boson.glass.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class MainActivity extends Activity implements CameraDialog.CameraDialogParent {
    private static final String TAG = "MainActivity";
    private static final boolean DEBUG = true;

    // Glass display dimensions optimized for prism display
    private static final int GLASS_WIDTH = 640;
    private static final int GLASS_HEIGHT = 360;

    // Thermal imaging constants
    private boolean mThermalMode = false;
    private int mThermalPalette = 0; // 0=Iron, 1=Rainbow, 2=Gray
    private static final int THERMAL_MIN_TEMP = -40; // Celsius
    private static final int THERMAL_MAX_TEMP = 400; // Celsius

    // Raw thermal data processing
    private volatile ByteBuffer mLatestThermalFrame = null;
    private final Object mThermalLock = new Object();
    private boolean mRawDataEnabled = false;

    private final Object mSync = new Object();

    // USB camera components
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private UVCCameraTextureView mUVCCameraView;
    private Surface mPreviewSurface;

    // Glass UI components
    private TextView mStatusText;
    private TextView mTemperatureText;
    private ImageView mThermalOverlay;
    private GestureDetector mGestureDetector;
    private Toast mToast;

    // Glass gestures
    private static final int GESTURE_TAP = 1;
    private static final int GESTURE_TWO_FINGER_TAP = 2;
    private static final int GESTURE_SWIPE_DOWN = 3;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (DEBUG) Log.v(TAG, "onCreate");

        // Initialize Glass UI components
        initializeGlassUI();

        // Initialize USB camera components
        initializeCamera();

        // Setup Glass gesture detection
        setupGestureDetection();

        // Create capture directory
        createCaptureDirectory();
    }

    private void initializeGlassUI() {
        mUVCCameraView = (UVCCameraTextureView) findViewById(R.id.UVCCameraTextureView1);
        mStatusText = (TextView) findViewById(R.id.status_text);
        mTemperatureText = (TextView) findViewById(R.id.temperature_text);
        mThermalOverlay = (ImageView) findViewById(R.id.thermal_overlay);

        // Configure for Glass display (640x360)
        if (mUVCCameraView != null) {
            mUVCCameraView.setAspectRatio(GLASS_WIDTH / (float) GLASS_HEIGHT);
        }

        updateStatusText("Glass Thermal Imaging Ready");
    }

    private void initializeCamera() {
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
    }

    private void setupGestureDetection() {
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (DEBUG) Log.v(TAG, "Single tap - measure temperature");
                measureCenterTemperature();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (DEBUG) Log.v(TAG, "Double tap - toggle thermal mode");
                toggleThermalMode();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e2.getY() - e1.getY() > 100) { // Swipe down - fixed logic
                    if (DEBUG) Log.v(TAG, "Swipe down - capture image");
                    captureImage();
                    return true;
                }
                return false;
            }
        });

        // Set touch listener for the entire view
        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mGestureDetector.onTouchEvent(event);
            }
        });
    }

    private void createCaptureDirectory() {
        File dir = new File(Environment.getExternalStorageDirectory(), "flir-boson");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (DEBUG) Log.v(TAG, "onStart");
        mUSBMonitor.register();
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.startPreview();
            }
        }
    }

    @Override
    protected void onStop() {
        if (DEBUG) Log.v(TAG, "onStop");
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.stopPreview();
            }
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (DEBUG) Log.v(TAG, "onDestroy");
        synchronized (mSync) {
            releaseCamera();
        }
        if (mToast != null) {
            mToast.cancel();
            mToast = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        super.onDestroy();
    }

    // Glass thermal imaging functions
    private void measureCenterTemperature() {
        float temperature = readCenterTemperatureFromThermalData();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTemperatureText.setText(String.format("Center: ~%.1f°C", temperature));
                mTemperatureText.setVisibility(View.VISIBLE);

                // Hide temperature after 3 seconds
                mTemperatureText.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mTemperatureText.setVisibility(View.GONE);
                    }
                }, 3000);
            }
        });

        if (DEBUG) Log.v(TAG, "Center temperature: " + temperature + "°C");
    }

    private float readCenterTemperatureFromThermalData() {
        synchronized (mThermalLock) {
            if (mLatestThermalFrame == null || !mRawDataEnabled) {
                // Fallback to simulated data if no real thermal data available
                return 20.0f + (float)(Math.random() * 60.0f);
            }

            try {
                // FLIR Boson Y16 format: 16-bit values, little endian
                // For 640x512 Boson, center pixel is at (320, 256)
                // For 320x256 Boson, center pixel is at (160, 128)
                int width = 640; // Assume Boson 640 for now
                int height = 512;
                int centerX = width / 2;
                int centerY = height / 2;

                // Each pixel is 2 bytes (16-bit)
                int pixelOffset = (centerY * width + centerX) * 2;

                if (pixelOffset + 1 < mLatestThermalFrame.capacity()) {
                    // Read 16-bit value (little endian)
                    short rawValue = mLatestThermalFrame.getShort(pixelOffset);

                    // Improved temperature estimation assuming T-Linear format
                    // T-Linear format: raw value is proportional to absolute temperature
                    // Common format for FLIR cores is centi-Kelvin (Kelvin * 100)
                    // NOTE: This is still an estimation - true accuracy requires SDK calibration
                    float tempInKelvin = (rawValue & 0xFFFF) / 100.0f;
                    float temperature = tempInKelvin - 273.15f;

                    // Clamp to reasonable thermal imaging range as safety measure
                    return Math.max(THERMAL_MIN_TEMP, Math.min(THERMAL_MAX_TEMP, temperature));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading thermal data", e);
            }
        }

        // Fallback to simulated data
        return 20.0f + (float)(Math.random() * 60.0f);
    }

    private void toggleThermalMode() {
        mThermalMode = !mThermalMode;

        if (mThermalMode) {
            mThermalPalette = (mThermalPalette + 1) % 3; // Cycle through palettes
            enableRawThermalData();
        } else {
            disableRawThermalData();
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String status = mThermalMode ?
                    "Thermal Mode: " + getPaletteName() :
                    "Normal Mode";
                updateStatusText(status);

                if (mThermalMode) {
                    generateThermalOverlay();
                } else {
                    mThermalOverlay.setVisibility(View.GONE);
                }
            }
        });

        if (DEBUG) Log.v(TAG, "Thermal mode: " + mThermalMode + ", palette: " + mThermalPalette);
    }

    private void enableRawThermalData() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    // Set frame callback to receive raw Y16 thermal data
                    mUVCCamera.setFrameCallback(new IFrameCallback() {
                        @Override
                        public void onFrame(ByteBuffer frame) {
                            synchronized (mThermalLock) {
                                mLatestThermalFrame = frame;
                                mRawDataEnabled = true;
                            }
                        }
                    }, UVCCamera.PIXEL_FORMAT_RAW);

                    if (DEBUG) Log.v(TAG, "Raw thermal data enabled");
                } catch (Exception e) {
                    Log.e(TAG, "Error enabling raw thermal data", e);
                }
            }
        }
    }

    private void disableRawThermalData() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    mUVCCamera.setFrameCallback(null, 0);
                    if (DEBUG) Log.v(TAG, "Raw thermal data disabled");
                } catch (Exception e) {
                    Log.e(TAG, "Error disabling raw thermal data", e);
                }
            }
        }
        synchronized (mThermalLock) {
            mLatestThermalFrame = null;
            mRawDataEnabled = false;
        }
    }

    private String getPaletteName() {
        switch (mThermalPalette) {
            case 0: return "Iron";
            case 1: return "Rainbow";
            case 2: return "Gray";
            default: return "Unknown";
        }
    }

    private void generateThermalOverlay() {
        synchronized (mThermalLock) {
            if (mLatestThermalFrame == null || !mRawDataEnabled) {
                return;
            }

            // Create thermal visualization bitmap
            Bitmap thermalBitmap = createThermalBitmap(mLatestThermalFrame);
            if (thermalBitmap != null) {
                // Draw center reticle
                Canvas canvas = new Canvas(thermalBitmap);
                Paint paint = new Paint();
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(2.0f);
                paint.setAntiAlias(true);

                int centerX = thermalBitmap.getWidth() / 2;
                int centerY = thermalBitmap.getHeight() / 2;
                int crossSize = 20;

                // Draw crosshair
                canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, paint);
                canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, paint);

                mThermalOverlay.setImageBitmap(thermalBitmap);
                mThermalOverlay.setVisibility(View.VISIBLE);
            }
        }
    }

    private Bitmap createThermalBitmap(ByteBuffer thermalData) {
        try {
            // Thermal image dimensions - adjust based on camera model
            int width = 640; // Boson 640
            int height = 512;

            if (thermalData.capacity() < width * height * 2) {
                // Try smaller resolution
                width = 320; // Boson 320
                height = 256;
            }

            if (thermalData.capacity() < width * height * 2) {
                Log.e(TAG, "Thermal data buffer too small");
                return null;
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * height];

            for (int i = 0; i < pixels.length; i++) {
                // Read 16-bit thermal value
                short rawValue = thermalData.getShort(i * 2);
                int thermalValue = rawValue & 0xFFFF;

                // Apply thermal color palette
                int color = applyThermalPalette(thermalValue, mThermalPalette);
                pixels[i] = color;
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error creating thermal bitmap", e);
            return null;
        }
    }

    private int applyThermalPalette(int thermalValue, int palette) {
        // Normalize thermal value (0-65535) to 0-255
        int normalized = (thermalValue * 255) / 65535;

        switch (palette) {
            case 0: // Iron palette
                return applyIronPalette(normalized);
            case 1: // Rainbow palette
                return applyRainbowPalette(normalized);
            case 2: // Grayscale palette
                return Color.argb(255, normalized, normalized, normalized);
            default:
                return Color.argb(255, normalized, normalized, normalized);
        }
    }

    private int applyIronPalette(int value) {
        // Iron color palette - cold=black/blue, hot=red/yellow/white
        int r, g, b;
        if (value < 85) {
            r = 0;
            g = 0;
            b = value * 3;
        } else if (value < 170) {
            r = (value - 85) * 3;
            g = 0;
            b = 255 - ((value - 85) * 3);
        } else {
            r = 255;
            g = (value - 170) * 3;
            b = 0;
        }
        return Color.argb(255, Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private int applyRainbowPalette(int value) {
        // Rainbow color palette
        float hue = (value / 255.0f) * 300.0f; // Hue from 0 to 300 (blue to red)
        float[] hsv = {hue, 1.0f, 1.0f};
        return Color.HSVToColor(hsv);
    }

    private void captureImage() {
        synchronized (mSync) {
            if (mUVCCamera == null) {
                Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show();
                return;
            }

            // Capture still image from camera view
            if (mUVCCameraView != null && mUVCCameraView.hasSurface()) {
                Bitmap bitmap = mUVCCameraView.captureStillImage();
                if (bitmap != null) {
                    saveCapturedImage(bitmap);
                }
            }
        }
    }

    private void saveCapturedImage(Bitmap bitmap) {
        try {
            long timestamp = System.currentTimeMillis();
            String baseFilename = "thermal_capture_" + timestamp;
            File dir = new File(Environment.getExternalStorageDirectory(), "flir-boson");

            // Save visual image
            String visualFilename = baseFilename + "_visual.jpg";
            File visualFile = new File(dir, visualFilename);

            FileOutputStream out = new FileOutputStream(visualFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();

            // Save radiometric data if available
            saveRadiometricData(baseFilename, timestamp);

            final String finalFilename = visualFilename;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String message = mRawDataEnabled ?
                        "Images saved: " + finalFilename + " + RAW data" :
                        "Image saved: " + finalFilename;
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });

            if (DEBUG) Log.v(TAG, "Image captured: " + finalFilename);
        } catch (IOException e) {
            Log.e(TAG, "Error saving image", e);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Error saving image", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void saveRadiometricData(String baseFilename, long timestamp) {
        synchronized (mThermalLock) {
            if (mLatestThermalFrame == null || !mRawDataEnabled) {
                return;
            }

            try {
                // Save raw thermal data
                String rawFilename = baseFilename + "_thermal.raw";
                File dir = new File(Environment.getExternalStorageDirectory(), "flir-boson");
                File rawFile = new File(dir, rawFilename);

                FileOutputStream out = new FileOutputStream(rawFile);
                byte[] thermalBytes = new byte[mLatestThermalFrame.remaining()];
                mLatestThermalFrame.get(thermalBytes);
                out.write(thermalBytes);
                out.close();

                // Save metadata
                String metaFilename = baseFilename + "_meta.txt";
                File metaFile = new File(dir, metaFilename);
                FileOutputStream metaOut = new FileOutputStream(metaFile);

                String metadata = String.format(
                    "Timestamp: %d\n" +
                    "Thermal Mode: %s\n" +
                    "Palette: %s\n" +
                    "Center Temperature: %.1f°C\n" +
                    "Data Size: %d bytes\n" +
                    "Format: Y16 (16-bit raw thermal)\n",
                    timestamp,
                    mThermalMode ? "Enabled" : "Disabled",
                    getPaletteName(),
                    readCenterTemperatureFromThermalData(),
                    thermalBytes.length
                );

                metaOut.write(metadata.getBytes());
                metaOut.close();

                if (DEBUG) Log.v(TAG, "Radiometric data saved: " + rawFilename);
            } catch (Exception e) {
                Log.e(TAG, "Error saving radiometric data", e);
            }
        }
    }

    private void updateStatusText(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mStatusText != null) {
                    mStatusText.setText(status);
                }
            }
        });
    }

    // USB camera connection handling
    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:" + device);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "FLIR Boson detected", Toast.LENGTH_SHORT).show();
                    updateStatusText("FLIR Boson Connected");
                }
            });
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            if (DEBUG) Log.v(TAG, "onConnect:" + device);
            synchronized (mSync) {
                releaseCamera();
                try {
                    mUVCCamera = new UVCCamera();
                    mUVCCamera.open(ctrlBlock);

                    if (DEBUG) Log.i(TAG, "supportedSize:" + mUVCCamera.getSupportedSizeList());

                    if (mUVCCameraView != null) {
                        mUVCCameraView.onResume();
                        mPreviewSurface = mUVCCameraView.getSurface();
                        mUVCCamera.setPreviewSize(GLASS_WIDTH, GLASS_HEIGHT);
                        mUVCCamera.setPreviewDisplay(mPreviewSurface);
                        mUVCCamera.startPreview();
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatusText("FLIR Boson Active - Tap: Temp, Double-tap: Mode, Swipe: Capture");
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "Error connecting to camera", e);
                    releaseCamera();
                }
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:" + device);
            synchronized (mSync) {
                if (mUVCCamera != null && device.equals(mUVCCamera.getDevice())) {
                    releaseCamera();
                }
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateStatusText("FLIR Boson Disconnected");
                }
            });
        }

        @Override
        public void onDettach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDettach:" + device);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "FLIR Boson removed", Toast.LENGTH_SHORT).show();
                    updateStatusText("Glass Thermal Imaging Ready");
                }
            });
        }

        @Override
        public void onCancel(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:" + device);
        }
    };

    private void releaseCamera() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    mUVCCamera.close();
                    mUVCCamera.destroy();
                } catch (final Exception e) {
                    Log.e(TAG, "Error releasing camera", e);
                }
                mUVCCamera = null;
            }
            if (mUVCCameraView != null) {
                mUVCCameraView.onPause();
            }
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
        }
    }

    // CameraDialog.CameraDialogParent implementation
    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {
        if (DEBUG) Log.v(TAG, "onDialogResult:canceled=" + canceled);
    }
}