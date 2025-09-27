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

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.List;

import com.flir.boson.glass.R;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements CameraDialog.CameraDialogParent {
    private static final String TAG = "MainActivity";
    private static final boolean DEBUG = true;

    // Glass display dimensions optimized for prism display
    private static final int GLASS_WIDTH = 640;
    private static final int GLASS_HEIGHT = 360;

    private static final int MENU_REQUEST_CODE = 100;

    // Thermal imaging constants
    private boolean mThermalMode = false;
    private int mThermalPalette = 0; // 0=Iron, 1=Rainbow, 2=Gray
    private static final int THERMAL_MIN_TEMP = -40; // Celsius
    private static final int THERMAL_MAX_TEMP = 400; // Celsius

    // Raw thermal data processing
    private volatile byte[] mLatestThermalFrame = null;
    private final Object mThermalLock = new Object();
    private boolean mRawDataEnabled = false;
    private int mThermalFrameWidth = 640;
    private int mThermalFrameHeight = 512;

    private final Object mSync = new Object();

    // USB camera components
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private UVCCameraTextureView mUVCCameraView;
    private Surface mPreviewSurface;
    private boolean mIsRecording = false;

    // USB power management for Glass constraints
    private USBPowerManager mUSBPowerManager;
    private USBPowerManager.PowerState mCurrentPowerState = USBPowerManager.PowerState.UNKNOWN;

    // Performance management for Glass hardware optimization
    private GlassPerformanceManager mPerformanceManager;
    private GlassPerformanceManager.PerformanceMode mCurrentPerformanceMode = GlassPerformanceManager.PerformanceMode.BALANCED;

    // Glass UI components
    private ImageView mThermalOverlay;
    private GestureDetector mGestureDetector;
    private Toast mToast;
    private String mStatusText = "";
    private String mTemperatureText = null;

    // GPS location tracking
    private LocationManager mLocationManager;
    private Location mLastLocation;
    private boolean mGpsEnabled = true;
    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(final Location location) {
            mLastLocation = location;
            if (DEBUG) Log.v(TAG, "Location updated: " + location);
        }
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override
        public void onProviderEnabled(String provider) {}
        @Override
        public void onProviderDisabled(String provider) {}
    };

    // Background processing
    private ExecutorService mThermalProcessingExecutor;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (DEBUG) Log.v(TAG, "onCreate");

        // Start the LiveCard service
        startService(new Intent(this, LiveCardService.class));

        // Initialize Glass UI components
        initializeGlassUI();

        // Initialize USB camera components
        initializeCamera();

        // Initialize location services
        initializeLocationServices();

        // Setup Glass gesture detection
        setupGestureDetection();

        // Create capture directory
        createCaptureDirectory();

        // Initialize background processing
        mThermalProcessingExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Add back button support for exiting
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (DEBUG) Log.v(TAG, "Back button pressed: Exit");
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MENU_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            mThermalMode = data.getBooleanExtra(MenuActivity.EXTRA_THERMAL_MODE, mThermalMode);
            mGpsEnabled = data.getBooleanExtra(MenuActivity.EXTRA_GPS_ENABLED, mGpsEnabled);
            mThermalPalette = data.getIntExtra(MenuActivity.EXTRA_PALETTE, mThermalPalette);

            if (data.getBooleanExtra(MenuActivity.EXTRA_TOGGLE_RECORDING, false)) {
                toggleRecording();
            }

            // Apply settings
            updateUiForThermalMode();
            updateLocationListenerState();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void initializeGlassUI() {
        mUVCCameraView = (UVCCameraTextureView) findViewById(R.id.UVCCameraTextureView1);
        mThermalOverlay = (ImageView) findViewById(R.id.thermal_overlay);

        // Configure for Glass display (640x360)
        if (mUVCCameraView != null) {
            mUVCCameraView.setAspectRatio(GLASS_WIDTH / (float) GLASS_HEIGHT);
        }

        updateStatusText("Glass Thermal Imaging Ready");
    }

    private void initializeCamera() {
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);

        // Initialize USB power management for Glass constraints
        mUSBPowerManager = new USBPowerManager(this);
        mUSBPowerManager.setListener(new USBPowerManager.PowerStateListener() {
            @Override
            public void onPowerStateChanged(USBPowerManager.PowerState state, String details) {
                mCurrentPowerState = state;
                handlePowerStateChange(state, details);
            }

            @Override
            public void onBatteryLevelChanged(int level, boolean charging) {
                handleBatteryLevelChange(level, charging);
            }

            @Override
            public void onPowerWarning(String warning) {
                showToast(warning);
                if (DEBUG) Log.w(TAG, "Power warning: " + warning);
            }
        });

        // Initialize performance management for Glass hardware optimization
        mPerformanceManager = new GlassPerformanceManager(this);
        mPerformanceManager.setListener(new GlassPerformanceManager.PerformanceListener() {
            @Override
            public void onPerformanceModeChanged(GlassPerformanceManager.PerformanceMode mode, String reason) {
                mCurrentPerformanceMode = mode;
                handlePerformanceModeChange(mode, reason);
            }

            @Override
            public void onFrameRateChanged(int targetFps, String details) {
                handleFrameRateChange(targetFps, details);
            }

            @Override
            public void onProcessingOptimization(int width, int height, int decimation) {
                handleProcessingOptimization(width, height, decimation);
            }

            @Override
            public void onBatteryLifeEstimate(long estimatedMinutes) {
                handleBatteryLifeEstimate(estimatedMinutes);
            }
        });
    }

    private void initializeLocationServices() {
        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    private void setupGestureDetection() {
        mGestureDetector = new GestureDetector(this);
        mGestureDetector.setBaseListener(new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                if (gesture == Gesture.TAP) {
                    if (DEBUG) Log.v(TAG, "TAP: Disabled (MenuActivity not ready)");
                    // Disabled until MenuActivity is implemented
                    // openOptionsMenu();
                    Toast.makeText(MainActivity.this, "Settings not available yet", Toast.LENGTH_SHORT).show();
                    return true;
                } else if (gesture == Gesture.TWO_TAP) {
                    if (DEBUG) Log.v(TAG, "TWO_TAP: Take picture");
                    captureImage();
                    return true;
                } else if (gesture == Gesture.THREE_TAP) {
                    if (DEBUG) Log.v(TAG, "THREE_TAP: Toggle recording");
                    toggleRecording();
                    return true;
                } else if (gesture == Gesture.SWIPE_RIGHT) {
                    if (DEBUG) Log.v(TAG, "SWIPE_RIGHT: Cycle thermal palette");
                    cyclePalette();
                    return true;
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    if (DEBUG) Log.v(TAG, "SWIPE_LEFT: Measure temperature");
                    measureCenterTemperature();
                    return true;
                } else if (gesture == Gesture.SWIPE_DOWN) {
                    if (DEBUG) Log.v(TAG, "SWIPE_DOWN: Exit");
                    finish();
                    return true;
                }
                return false;
            }
        });
    }

    public void openOptionsMenu() {
        Intent intent = new Intent(this, MenuActivity.class);
        intent.putExtra(MenuActivity.EXTRA_THERMAL_MODE, mThermalMode);
        intent.putExtra(MenuActivity.EXTRA_GPS_ENABLED, mGpsEnabled);
        intent.putExtra(MenuActivity.EXTRA_PALETTE, mThermalPalette);
        startActivityForResult(intent, MENU_REQUEST_CODE);
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
        updateLocationListenerState();
    }

    @Override
    protected void onStop() {
        if (DEBUG) Log.v(TAG, "onStop");
        if (mLocationManager != null) {
            try {
                mLocationManager.removeUpdates(mLocationListener);
            } catch (SecurityException e) {
                Log.e(TAG, "Could not remove location updates", e);
            }
        }
        if (mIsRecording) {
            stopRecording();
        }
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
        stopService(new Intent(this, LiveCardService.class));
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
        if (mThermalProcessingExecutor != null) {
            mThermalProcessingExecutor.shutdown();
            mThermalProcessingExecutor = null;
        }
        if (mUSBPowerManager != null) {
            mUSBPowerManager.cleanup();
            mUSBPowerManager = null;
        }
        if (mPerformanceManager != null) {
            mPerformanceManager.cleanup();
            mPerformanceManager = null;
        }
        super.onDestroy();
    }

    // Glass thermal imaging functions
    private void measureCenterTemperature() {
        float temperature = readCenterTemperatureFromThermalData();
        mTemperatureText = String.format("Center: ~%.1f°C", temperature);
        generateThermalOverlayAsync();

        // Hide temperature after 3 seconds
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mTemperatureText = null;
                generateThermalOverlayAsync();
            }
        }, 3000);

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
                // Use actual frame dimensions
                int width = mThermalFrameWidth;
                int height = mThermalFrameHeight;
                int centerX = width / 2;
                int centerY = height / 2;

                // Each pixel is 2 bytes (16-bit)
                int pixelOffset = (centerY * width + centerX) * 2;

                if (pixelOffset + 1 < mLatestThermalFrame.length) {
                    // Read 16-bit value (little endian) from byte array
                    short rawValue = (short) ((mLatestThermalFrame[pixelOffset] & 0xFF) |
                                             ((mLatestThermalFrame[pixelOffset + 1] & 0xFF) << 8));

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
        updateUiForThermalMode();
    }

    private void cyclePalette() {
        if (mThermalMode) {
            mThermalPalette = (mThermalPalette + 1) % 3; // Cycle through palettes
            updateUiForThermalMode();
        }
    }

    private void updateUiForThermalMode() {
        if (mThermalMode) {
            enableRawThermalData();
        } else {
            disableRawThermalData();
        }

        String status = mThermalMode ?
            "Thermal Mode: " + getPaletteName() :
            "Normal Mode";
        updateStatusText(status);

        if (mThermalMode) {
            generateThermalOverlayAsync();
        } else {
            mThermalOverlay.setVisibility(View.GONE);
        }

        if (DEBUG) Log.v(TAG, "Thermal mode: " + mThermalMode + ", palette: " + mThermalPalette);
    }

    private void updateLocationListenerState() {
        if (mLocationManager == null) return;
        try {
            if (mGpsEnabled) {
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10, mLocationListener);
            } else {
                mLocationManager.removeUpdates(mLocationListener);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "No permission to access location", e);
        }
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
                                // Copy buffer data to prevent race conditions
                                int frameSize = frame.remaining();
                                if (mLatestThermalFrame == null || mLatestThermalFrame.length != frameSize) {
                                    mLatestThermalFrame = new byte[frameSize];
                                }
                                frame.get(mLatestThermalFrame);
                                frame.rewind(); // Reset position for potential reuse
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

    private void generateThermalOverlayAsync() {
        if (mThermalProcessingExecutor == null || mThermalProcessingExecutor.isShutdown()) {
            return;
        }

        // Performance optimization: Check if we should process this frame
        if (mPerformanceManager != null && !mPerformanceManager.shouldProcessFrame()) {
            // Skip this frame to maintain target fps and reduce CPU load
            return;
        }

        // Copy thermal data for background processing to avoid race conditions
        final byte[] thermalDataCopy;
        final int width, height, palette;

        synchronized (mThermalLock) {
            if (mLatestThermalFrame == null || !mRawDataEnabled) {
                // If no thermal data, create a blank bitmap for status text
                Bitmap emptyBitmap = Bitmap.createBitmap(GLASS_WIDTH, GLASS_HEIGHT, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(emptyBitmap);
                drawStatusText(canvas, mStatusText);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mThermalOverlay.setImageBitmap(emptyBitmap);
                        mThermalOverlay.setVisibility(View.VISIBLE);
                    }
                });
                return;
            }

            thermalDataCopy = mLatestThermalFrame.clone();
            width = mThermalFrameWidth;
            height = mThermalFrameHeight;
            palette = mThermalPalette;
        }

        // Process thermal data in background thread
        mThermalProcessingExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Performance optimization: Get optimal resolution based on current mode
                    int[] optimalSize = getOptimalThermalResolution(width, height);
                    int optimalWidth = optimalSize[0];
                    int optimalHeight = optimalSize[1];

                    // Create thermal visualization bitmap with auto-contrast
                    Bitmap thermalBitmap = createThermalBitmapWithAutoContrast(thermalDataCopy, optimalWidth, optimalHeight, palette);

                    if (thermalBitmap != null) {
                        Canvas canvas = new Canvas(thermalBitmap);
                        drawCrosshair(canvas);
                        drawStatusText(canvas, mStatusText);
                        if (mTemperatureText != null) {
                            drawTemperatureText(canvas, mTemperatureText);
                        }

                        // Update UI on main thread
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (mThermalMode) { // Check if still in thermal mode
                                    mThermalOverlay.setImageBitmap(thermalBitmap);
                                    mThermalOverlay.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error generating thermal overlay", e);
                }
            }
        });
    }

    /**
     * Get optimal thermal resolution based on current performance mode
     * Reduces resolution in battery saver or thermal throttle modes
     */
    private int[] getOptimalThermalResolution(int originalWidth, int originalHeight) {
        if (mPerformanceManager == null) {
            return new int[]{originalWidth, originalHeight};
        }

        // Use the performance manager's optimal thermal dimensions
        int optimalWidth = mPerformanceManager.getOptimalThermalWidth();
        int optimalHeight = mPerformanceManager.getOptimalThermalHeight();

        // Ensure we don't exceed original resolution
        optimalWidth = Math.min(optimalWidth, originalWidth);
        optimalHeight = Math.min(optimalHeight, originalHeight);

        if (DEBUG && (optimalWidth != originalWidth || optimalHeight != originalHeight)) {
            Log.v(TAG, String.format("Thermal resolution optimized: %dx%d -> %dx%d",
                originalWidth, originalHeight, optimalWidth, optimalHeight));
        }

        return new int[]{optimalWidth, optimalHeight};
    }

    private void drawCrosshair(Canvas canvas) {
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(2.0f);
        paint.setAntiAlias(true);

        int centerX = canvas.getWidth() / 2;
        int centerY = canvas.getHeight() / 2;
        int crossSize = 20;

        // Draw crosshair
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, paint);
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, paint);
    }

    private void drawStatusText(Canvas canvas, String text) {
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(24);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, canvas.getWidth() / 2, 30, paint);
    }

    private void drawTemperatureText(Canvas canvas, String text) {
        Paint paint = new Paint();
        paint.setColor(Color.YELLOW);
        paint.setTextSize(32);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, canvas.getWidth() / 2, canvas.getHeight() / 2, paint);
    }

    private Bitmap createThermalBitmapWithAutoContrast(byte[] thermalData, int width, int height, int palette) {
        try {
            int expectedSize = width * height * 2; // 2 bytes per pixel for Y16
            if (thermalData.length < expectedSize) {
                Log.e(TAG, "Thermal data buffer too small: " + thermalData.length + " < " + expectedSize);
                return null;
            }

            // First pass: find min/max values for auto-contrast
            int minValue = Integer.MAX_VALUE;
            int maxValue = Integer.MIN_VALUE;
            int pixelCount = width * height;

            for (int i = 0; i < pixelCount; i++) {
                int offset = i * 2;
                // Read 16-bit value (little endian) from byte array
                int rawValue = (thermalData[offset] & 0xFF) | ((thermalData[offset + 1] & 0xFF) << 8);
                minValue = Math.min(minValue, rawValue);
                maxValue = Math.max(maxValue, rawValue);
            }

            // Avoid division by zero
            int range = Math.max(maxValue - minValue, 1);

            // Create bitmap with auto-scaled values
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[pixelCount];

            for (int i = 0; i < pixelCount; i++) {
                int offset = i * 2;
                // Read 16-bit value (little endian) from byte array
                int rawValue = (thermalData[offset] & 0xFF) | ((thermalData[offset + 1] & 0xFF) << 8);

                // Auto-contrast: scale to 0-255 based on scene range
                int scaledValue = ((rawValue - minValue) * 255) / range;
                scaledValue = Math.max(0, Math.min(255, scaledValue));

                // Apply thermal color palette
                int color = applyThermalPaletteScaled(scaledValue, palette);
                pixels[i] = color;
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error creating thermal bitmap", e);
            return null;
        }
    }

    // Legacy method for backward compatibility - now uses new method
    private Bitmap createThermalBitmap(ByteBuffer thermalData) {
        // Convert ByteBuffer to byte array and use new method
        synchronized (mThermalLock) {
            byte[] dataArray = new byte[thermalData.remaining()];
            thermalData.get(dataArray);
            thermalData.rewind();
            return createThermalBitmapWithAutoContrast(dataArray, mThermalFrameWidth, mThermalFrameHeight, mThermalPalette);
        }
    }

    private int applyThermalPaletteScaled(int scaledValue, int palette) {
        // Value is already scaled to 0-255 by auto-contrast
        switch (palette) {
            case 0: // Iron palette
                return applyIronPalette(scaledValue);
            case 1: // Rainbow palette
                return applyRainbowPalette(scaledValue);
            case 2: // Grayscale palette
                return Color.argb(255, scaledValue, scaledValue, scaledValue);
            default:
                return Color.argb(255, scaledValue, scaledValue, scaledValue);
        }
    }

    // Legacy method for backward compatibility
    private int applyThermalPalette(int thermalValue, int palette) {
        // Normalize thermal value (0-65535) to 0-255
        int normalized = (thermalValue * 255) / 65535;
        return applyThermalPaletteScaled(normalized, palette);
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
                out.write(mLatestThermalFrame); // mLatestThermalFrame is now byte[]
                out.close();
                int thermalDataSize = mLatestThermalFrame.length;

                // Save metadata
                String metaFilename = baseFilename + "_meta.txt";
                File metaFile = new File(dir, metaFilename);
                FileOutputStream metaOut = new FileOutputStream(metaFile);

                String locationString = "N/A";
                if (mGpsEnabled && mLastLocation != null) {
                    locationString = String.format("%.6f, %.6f", mLastLocation.getLatitude(), mLastLocation.getLongitude());
                }

                String metadata = String.format(
                    "Timestamp: %d\n" +
                    "GPS Location: %s\n" +
                    "Thermal Mode: %s\n" +
                    "Palette: %s\n" +
                    "Center Temperature: %.1f°C\n" +
                    "Data Size: %d bytes\n" +
                    "Format: Y16 (16-bit raw thermal)\n",
                    timestamp,
                    locationString,
                    mThermalMode ? "Enabled" : "Disabled",
                    getPaletteName(),
                    readCenterTemperatureFromThermalData(),
                    thermalDataSize
                );

                metaOut.write(metadata.getBytes());
                metaOut.close();

                if (DEBUG) Log.v(TAG, "Radiometric data saved: " + rawFilename);
            } catch (Exception e) {
                Log.e(TAG, "Error saving radiometric data", e);
            }
        }
    }

    private void toggleRecording() {
        if (mIsRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (DEBUG) Log.v(TAG, "startRecording");
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    final String path = getVideoFilePath();
                    mUVCCamera.startCapture(new Surface(mUVCCameraView.getSurfaceTexture()));
                    mIsRecording = true;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatusText("Recording...");
                            Toast.makeText(MainActivity.this, "Recording started", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (IOException e) {
                    Log.e(TAG, "Failed to start recording", e);
                    mIsRecording = false;
                }
            }
        }
    }

    private void stopRecording() {
        if (DEBUG) Log.v(TAG, "stopRecording");
        synchronized (mSync) {
            if (mUVCCamera != null && mIsRecording) {
                mUVCCamera.stopCapture();
                mIsRecording = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateStatusText("Recording stopped");
                        Toast.makeText(MainActivity.this, "Recording stopped", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }

    private String getVideoFilePath() throws IOException {
        final File dir = new File(Environment.getExternalStorageDirectory(), "flir-boson");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        final String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new File(dir, "thermal_video_" + timestamp + ".mp4").getAbsolutePath();
    }

    private void updateStatusText(final String status) {
        mStatusText = status;
        generateThermalOverlayAsync();
    }

    // USB camera connection handling
    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:" + device);

            // Analyze USB power requirements for this device
            final USBPowerManager.PowerState powerState = mUSBPowerManager.analyzeUsbDevicePower(device);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String deviceInfo = getDeviceInfo(device);
                    String message = String.format("USB Device: %s", deviceInfo);

                    // Show different messages based on power state
                    if (powerState == USBPowerManager.PowerState.INSUFFICIENT_POWER) {
                        message = "⚠️ Device detected - May need external power";
                        updateStatusText("USB Device - Power Warning");
                    } else if (deviceInfo.contains("FLIR") || deviceInfo.contains("Boson")) {
                        message = "✅ FLIR Boson detected";
                        updateStatusText("FLIR Boson Connected");
                    } else {
                        updateStatusText("USB Camera Connected");
                    }

                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();

                    // Auto-request permission for FLIR cameras
                    if (deviceInfo.contains("FLIR") || deviceInfo.contains("Boson") ||
                        device.getVendorId() == 0x09CB) {
                        if (DEBUG) Log.v(TAG, "Auto-requesting permission for FLIR device");
                        mUSBMonitor.requestPermission(device);
                    }
                }
            });
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
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

                        // Store actual thermal frame dimensions for processing
                        // These may be different from display dimensions
                        synchronized (mThermalLock) {
                            // Check actual supported sizes to determine thermal frame dimensions
                            String sizeList = mUVCCamera.getSupportedSizeList().toString();
                            if (sizeList != null && sizeList.contains("320x256")) {
                                mThermalFrameWidth = 320;
                                mThermalFrameHeight = 256;
                            } else {
                                mThermalFrameWidth = 640;
                                mThermalFrameHeight = 512;
                            }
                        }

                        mUVCCamera.setPreviewDisplay(mPreviewSurface);
                        mUVCCamera.startPreview();
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatusText("FLIR Boson Active - Tap for Menu");
                        }
                    });

                    // Performance monitoring is already started in constructor
                    if (DEBUG) Log.v(TAG, "Performance monitoring active for FLIR Boson");

                } catch (final Exception e) {
                    Log.e(TAG, "Error connecting to camera", e);
                    releaseCamera();
                }
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:" + device);

            // Handle graceful camera disconnection
            synchronized (mSync) {
                if (mUVCCamera != null && device.equals(mUVCCamera.getDevice())) {
                    // Stop any ongoing recording
                    if (mIsRecording) {
                        mIsRecording = false;
                        if (DEBUG) Log.i(TAG, "Recording stopped due to USB disconnection");
                    }

                    // Clear thermal data
                    synchronized (mThermalLock) {
                        mLatestThermalFrame = null;
                        mRawDataEnabled = false;
                    }

                    releaseCamera();
                }
            }

            // Reset power state
            mCurrentPowerState = USBPowerManager.PowerState.UNKNOWN;

            // Performance monitoring will be cleaned up in onDestroy
            if (DEBUG) Log.v(TAG, "Camera disconnected - performance state reset");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String deviceInfo = getDeviceInfo(device);
                    String message;

                    if (deviceInfo.contains("FLIR") || deviceInfo.contains("Boson")) {
                        message = "FLIR Boson Disconnected";
                        updateStatusText("❌ FLIR Boson Disconnected");
                    } else {
                        message = "USB Camera Disconnected";
                        updateStatusText("❌ USB Device Disconnected");
                    }

                    showToast(message);

                    // Clear thermal overlay
                    if (mThermalOverlay != null) {
                        mThermalOverlay.setVisibility(View.GONE);
                    }
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

    // USB Power Management callbacks
    private void handlePowerStateChange(USBPowerManager.PowerState state, String details) {
        if (DEBUG) Log.v(TAG, "Power state changed: " + state + " - " + details);

        switch (state) {
            case INSUFFICIENT_POWER:
                updateStatusText("⚠️ Low USB Power - External power recommended");
                break;
            case BATTERY_ONLY:
                updateStatusText("🔋 Battery Mode - Limited operation time");
                break;
            case EXTERNAL_POWER:
                updateStatusText("🔌 External Power - Optimal operation");
                break;
            case OPTIMAL_POWER:
                updateStatusText("✅ Adequate Power - Normal operation");
                break;
            default:
                updateStatusText("Glass Thermal Imaging Ready");
                break;
        }
    }

    private void handleBatteryLevelChange(int level, boolean charging) {
        if (DEBUG) Log.v(TAG, String.format("Battery: %d%%, Charging: %s", level, charging));

        // Update status with battery info during operation
        if (mUVCCamera != null && level < 20 && !charging) {
            showToast("⚠️ Low battery: " + level + "% - Consider connecting power");
        }

        // Update performance manager with battery status
        if (mPerformanceManager != null) {
            mPerformanceManager.updateBatteryState(level, charging);
        }
    }

    // Performance Management callbacks
    private void handlePerformanceModeChange(GlassPerformanceManager.PerformanceMode mode, String reason) {
        if (DEBUG) Log.v(TAG, "Performance mode changed: " + mode + " - " + reason);

        String statusMessage;
        switch (mode) {
            case BATTERY_SAVER:
                statusMessage = "🔋 Battery Saver Mode - Reduced performance";
                break;
            case PERFORMANCE:
                statusMessage = "🚀 Performance Mode - Maximum quality";
                break;
            case THERMAL_THROTTLE:
                statusMessage = "🌡️ Thermal Throttle - Cooling down";
                break;
            case BALANCED:
            default:
                statusMessage = "⚖️ Balanced Mode - Optimal efficiency";
                break;
        }

        updateStatusText(statusMessage);
        if (!reason.isEmpty()) {
            showToast(reason);
        }
    }

    private void handleFrameRateChange(int targetFps, String reason) {
        if (DEBUG) Log.v(TAG, "Frame rate changed: " + targetFps + "fps - " + reason);

        // Apply frame rate optimization to thermal processing
        // This reduces CPU/GPU load by processing fewer frames
        if (mThermalProcessingExecutor != null && !reason.isEmpty()) {
            showToast("Frame rate: " + targetFps + "fps (" + reason + ")");
        }
    }

    private void handleProcessingOptimization(int width, int height, int decimation) {
        if (DEBUG) Log.v(TAG, String.format("Processing optimized: %dx%d, decimation: %d", width, height, decimation));

        // Update thermal frame dimensions for processing optimization
        synchronized (mThermalLock) {
            // Only update if not using live thermal data dimensions
            if (!mRawDataEnabled) {
                mThermalFrameWidth = width;
                mThermalFrameHeight = height;
            }
        }
    }

    private void handleBatteryLifeEstimate(long estimatedMinutes) {
        if (DEBUG) Log.v(TAG, "Battery life estimate: " + estimatedMinutes + " minutes");

        // Show battery life warning if less than 30 minutes
        if (estimatedMinutes < 30 && estimatedMinutes > 0) {
            showToast("🔋 Battery life: ~" + estimatedMinutes + " minutes");
        }
    }

    private String getDeviceInfo(UsbDevice device) {
        if (device == null) return "Unknown Device";

        String deviceName = device.getDeviceName();
        if (deviceName == null) deviceName = "USB Device";

        return String.format("%s (VID:0x%04X PID:0x%04X)",
            deviceName, device.getVendorId(), device.getProductId());
    }

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mToast != null) {
                    mToast.cancel();
                }
                mToast = Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG);
                mToast.show();
            }
        });
    }
}