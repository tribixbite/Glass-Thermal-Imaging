package com.serenegiant.usbcameratest3;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Performance Management for Google Glass thermal imaging
 * Optimizes frame rates and processing based on Glass hardware constraints
 */
public class GlassPerformanceManager {
    private static final String TAG = "GlassPerformanceManager";
    private static final boolean DEBUG = true;

    // Glass XE24 hardware specifications
    private static final int GLASS_CPU_CORES = 2;  // OMAP 4430 dual-core
    private static final int GLASS_RAM_MB = 682;   // Available to apps
    private static final int GLASS_BATTERY_MAH = 570;

    // Performance thresholds
    private static final long TARGET_FRAME_TIME_MS = 33;  // ~30 FPS target
    private static final long MAX_FRAME_TIME_MS = 66;     // 15 FPS minimum
    private static final int PERFORMANCE_HISTORY_SIZE = 30;
    private static final long THERMAL_THROTTLE_TEMP = 50; // Celsius

    // Performance modes
    public enum PerformanceMode {
        BATTERY_SAVER,    // Minimum processing for maximum battery life
        BALANCED,         // Balanced performance and battery
        PERFORMANCE,      // Maximum performance
        THERMAL_THROTTLE  // Reduced performance due to heat
    }

    // Frame processing statistics
    private final Queue<Long> mFrameTimeHistory = new LinkedList<>();
    private long mLastFrameTime = 0;
    private long mAverageFrameTime = TARGET_FRAME_TIME_MS;
    private int mDroppedFrames = 0;
    private int mTotalFrames = 0;

    // Performance state
    private PerformanceMode mCurrentMode = PerformanceMode.BALANCED;
    private boolean mIsThrottling = false;
    private long mLastPerformanceCheck = 0;
    private final Handler mPerformanceHandler = new Handler();

    // Thermal processing optimization
    private int mOptimalThermalWidth = 320;   // Reduced from 640 for performance
    private int mOptimalThermalHeight = 256;  // Reduced from 512 for performance
    private int mCurrentDecimation = 1;       // Frame decimation factor
    private boolean mUseGpuAcceleration = true;

    // Battery optimization
    private int mBatteryLevel = 100;
    private boolean mIsCharging = false;
    private long mEstimatedBatteryLife = 0;

    private PerformanceListener mListener;

    public interface PerformanceListener {
        void onPerformanceModeChanged(PerformanceMode mode, String reason);
        void onFrameRateChanged(int targetFps, String details);
        void onProcessingOptimization(int width, int height, int decimation);
        void onBatteryLifeEstimate(long estimatedMinutes);
    }

    public GlassPerformanceManager(Context context) {
        initializePerformanceBasedOnHardware();
        startPerformanceMonitoring();
    }

    public void setListener(PerformanceListener listener) {
        mListener = listener;
    }

    /**
     * Initialize performance settings based on Glass hardware detection
     */
    private void initializePerformanceBasedOnHardware() {
        // Detect if running on actual Glass hardware
        boolean isActualGlass = isRunningOnGlass();

        if (isActualGlass) {
            // Conservative settings for real Glass hardware
            mOptimalThermalWidth = 320;
            mOptimalThermalHeight = 256;
            mCurrentMode = PerformanceMode.BALANCED;
            if (DEBUG) Log.i(TAG, "Optimized for Google Glass XE24 hardware");
        } else {
            // More aggressive settings for development/emulation
            mOptimalThermalWidth = 640;
            mOptimalThermalHeight = 512;
            mCurrentMode = PerformanceMode.PERFORMANCE;
            if (DEBUG) Log.i(TAG, "Optimized for development environment");
        }

        notifyPerformanceChange("Hardware detection");
    }

    /**
     * Detect if running on actual Google Glass hardware
     */
    private boolean isRunningOnGlass() {
        // Glass XE24 characteristics
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        String product = Build.PRODUCT.toLowerCase();

        return manufacturer.contains("google") &&
               (model.contains("glass") || product.contains("glass"));
    }

    /**
     * Record frame processing time for performance analysis
     */
    public void recordFrameTime(long processingTimeMs) {
        long currentTime = SystemClock.elapsedRealtime();

        if (mLastFrameTime > 0) {
            long frameInterval = currentTime - mLastFrameTime;

            // Add to history
            mFrameTimeHistory.offer(frameInterval);
            if (mFrameTimeHistory.size() > PERFORMANCE_HISTORY_SIZE) {
                mFrameTimeHistory.poll();
            }

            // Calculate average
            long total = 0;
            for (Long time : mFrameTimeHistory) {
                total += time;
            }
            mAverageFrameTime = total / mFrameTimeHistory.size();

            // Check for dropped frames
            if (frameInterval > MAX_FRAME_TIME_MS) {
                mDroppedFrames++;
            }
        }

        mLastFrameTime = currentTime;
        mTotalFrames++;

        // Check if performance adjustment needed
        if (currentTime - mLastPerformanceCheck > 5000) { // Check every 5 seconds
            analyzeAndOptimizePerformance();
            mLastPerformanceCheck = currentTime;
        }
    }

    /**
     * Analyze current performance and optimize settings
     */
    private void analyzeAndOptimizePerformance() {
        float dropRate = mTotalFrames > 0 ? (float) mDroppedFrames / mTotalFrames : 0;

        if (DEBUG) {
            Log.v(TAG, String.format("Performance: avg=%.1fms, drops=%.1f%%, mode=%s",
                mAverageFrameTime, dropRate * 100, mCurrentMode));
        }

        PerformanceMode newMode = mCurrentMode;

        // Performance-based mode switching
        if (dropRate > 0.3 && mAverageFrameTime > MAX_FRAME_TIME_MS) {
            // Too many dropped frames - reduce performance
            if (mCurrentMode == PerformanceMode.PERFORMANCE) {
                newMode = PerformanceMode.BALANCED;
            } else if (mCurrentMode == PerformanceMode.BALANCED) {
                newMode = PerformanceMode.BATTERY_SAVER;
            }
        } else if (dropRate < 0.1 && mAverageFrameTime < TARGET_FRAME_TIME_MS) {
            // Good performance - can increase if on battery saver
            if (mCurrentMode == PerformanceMode.BATTERY_SAVER && mBatteryLevel > 50) {
                newMode = PerformanceMode.BALANCED;
            } else if (mCurrentMode == PerformanceMode.BALANCED && mBatteryLevel > 80 && mIsCharging) {
                newMode = PerformanceMode.PERFORMANCE;
            }
        }

        // Battery-based mode switching
        if (!mIsCharging) {
            if (mBatteryLevel < 20) {
                newMode = PerformanceMode.BATTERY_SAVER;
            } else if (mBatteryLevel < 50 && newMode == PerformanceMode.PERFORMANCE) {
                newMode = PerformanceMode.BALANCED;
            }
        }

        if (newMode != mCurrentMode) {
            setPerformanceMode(newMode, "Auto-optimization");
        }

        // Update processing parameters based on current mode
        updateProcessingParameters();

        // Reset counters
        mDroppedFrames = 0;
        mTotalFrames = 0;
    }

    /**
     * Update thermal processing parameters based on performance mode
     */
    private void updateProcessingParameters() {
        int newWidth, newHeight, newDecimation;

        switch (mCurrentMode) {
            case BATTERY_SAVER:
                newWidth = 160;
                newHeight = 128;
                newDecimation = 4;  // Process every 4th frame
                break;

            case BALANCED:
                newWidth = 320;
                newHeight = 256;
                newDecimation = 2;  // Process every 2nd frame
                break;

            case PERFORMANCE:
                newWidth = 640;
                newHeight = 512;
                newDecimation = 1;  // Process every frame
                break;

            case THERMAL_THROTTLE:
                newWidth = 160;
                newHeight = 128;
                newDecimation = 6;  // Process every 6th frame
                break;

            default:
                return;
        }

        if (newWidth != mOptimalThermalWidth || newHeight != mOptimalThermalHeight ||
            newDecimation != mCurrentDecimation) {

            mOptimalThermalWidth = newWidth;
            mOptimalThermalHeight = newHeight;
            mCurrentDecimation = newDecimation;

            if (mListener != null) {
                mListener.onProcessingOptimization(newWidth, newHeight, newDecimation);
            }

            int targetFps = 30 / newDecimation;
            if (mListener != null) {
                mListener.onFrameRateChanged(targetFps,
                    String.format("Mode: %s, Resolution: %dx%d", mCurrentMode, newWidth, newHeight));
            }
        }
    }

    /**
     * Set performance mode manually or automatically
     */
    public void setPerformanceMode(PerformanceMode mode, String reason) {
        if (mode != mCurrentMode) {
            mCurrentMode = mode;

            if (DEBUG) Log.i(TAG, "Performance mode changed to " + mode + " - " + reason);

            if (mListener != null) {
                mListener.onPerformanceModeChanged(mode, reason);
            }

            updateProcessingParameters();
        }
    }

    /**
     * Update battery state for performance optimization
     */
    public void updateBatteryState(int level, boolean charging) {
        mBatteryLevel = level;
        mIsCharging = charging;

        // Estimate battery life based on current usage
        estimateBatteryLife();
    }

    /**
     * Estimate remaining battery life with thermal imaging
     */
    private void estimateBatteryLife() {
        if (mIsCharging) {
            mEstimatedBatteryLife = -1; // Unlimited when charging
            return;
        }

        // Rough estimation based on Glass battery capacity and thermal processing load
        float drainRate;
        switch (mCurrentMode) {
            case BATTERY_SAVER:
                drainRate = 0.8f; // 80% of normal drain
                break;
            case BALANCED:
                drainRate = 1.2f; // 120% of normal drain
                break;
            case PERFORMANCE:
                drainRate = 1.8f; // 180% of normal drain
                break;
            case THERMAL_THROTTLE:
                drainRate = 0.6f; // 60% of normal drain
                break;
            default:
                drainRate = 1.0f;
        }

        // Glass typical usage: ~2-3 hours normal operation
        float baseLifeHours = 2.5f;
        mEstimatedBatteryLife = Math.round((mBatteryLevel / 100.0f) * baseLifeHours * 60 / drainRate);

        if (mListener != null) {
            mListener.onBatteryLifeEstimate(mEstimatedBatteryLife);
        }
    }

    /**
     * Check if frame should be processed based on decimation
     */
    public boolean shouldProcessFrame() {
        return (mTotalFrames % mCurrentDecimation) == 0;
    }

    /**
     * Get optimal thermal processing resolution
     */
    public int getOptimalThermalWidth() {
        return mOptimalThermalWidth;
    }

    public int getOptimalThermalHeight() {
        return mOptimalThermalHeight;
    }

    public PerformanceMode getCurrentMode() {
        return mCurrentMode;
    }

    public long getEstimatedBatteryLife() {
        return mEstimatedBatteryLife;
    }

    public boolean isThrottling() {
        return mIsThrottling;
    }

    /**
     * Start background performance monitoring
     */
    private void startPerformanceMonitoring() {
        mPerformanceHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Monitor system performance periodically
                checkSystemThrottling();
                mPerformanceHandler.postDelayed(this, 10000); // Check every 10 seconds
            }
        }, 10000);
    }

    /**
     * Check for thermal throttling or system stress
     */
    private void checkSystemThrottling() {
        // Simple heuristic for detecting throttling
        boolean wasThrottling = mIsThrottling;
        mIsThrottling = mAverageFrameTime > MAX_FRAME_TIME_MS * 2;

        if (mIsThrottling && !wasThrottling) {
            setPerformanceMode(PerformanceMode.THERMAL_THROTTLE, "System thermal throttling detected");
        } else if (!mIsThrottling && wasThrottling && mCurrentMode == PerformanceMode.THERMAL_THROTTLE) {
            setPerformanceMode(PerformanceMode.BALANCED, "Thermal throttling recovered");
        }
    }

    private void notifyPerformanceChange(String reason) {
        if (mListener != null) {
            mListener.onPerformanceModeChanged(mCurrentMode, reason);
        }
        updateProcessingParameters();
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        mPerformanceHandler.removeCallbacksAndMessages(null);
    }
}