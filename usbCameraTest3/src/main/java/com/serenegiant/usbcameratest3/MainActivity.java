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
import com.serenegiant.widget.UVCCameraTextureView;
import com.flir.boson.glass.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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
                if (e1.getY() - e2.getY() > 100) { // Swipe down
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
        // Simulate thermal measurement at center of display
        // In real implementation, this would read thermal data from FLIR Boson
        float temperature = 20.0f + (float)(Math.random() * 60.0f); // Simulated 20-80°C

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTemperatureText.setText(String.format("Center: %.1f°C", temperature));
                mTemperatureText.setVisibility(View.VISIBLE);
            }
        });

        if (DEBUG) Log.v(TAG, "Center temperature: " + temperature + "°C");
    }

    private void toggleThermalMode() {
        mThermalMode = !mThermalMode;

        if (mThermalMode) {
            mThermalPalette = (mThermalPalette + 1) % 3; // Cycle through palettes
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String status = mThermalMode ?
                    "Thermal Mode: " + getPaletteName() :
                    "Normal Mode";
                updateStatusText(status);
            }
        });

        if (DEBUG) Log.v(TAG, "Thermal mode: " + mThermalMode + ", palette: " + mThermalPalette);
    }

    private String getPaletteName() {
        switch (mThermalPalette) {
            case 0: return "Iron";
            case 1: return "Rainbow";
            case 2: return "Gray";
            default: return "Unknown";
        }
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
            String filename = "thermal_capture_" + System.currentTimeMillis() + ".jpg";
            File file = new File(Environment.getExternalStorageDirectory() + "/flir-boson", filename);

            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Image saved: " + filename, Toast.LENGTH_SHORT).show();
                }
            });

            if (DEBUG) Log.v(TAG, "Image captured: " + filename);
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