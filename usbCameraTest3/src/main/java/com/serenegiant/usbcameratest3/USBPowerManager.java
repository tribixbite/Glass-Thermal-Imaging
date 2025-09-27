package com.serenegiant.usbcameratest3;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.util.Log;

/**
 * USB Power Management for Glass thermal imaging
 * Handles power delivery constraints and external power detection
 */
public class USBPowerManager {
    private static final String TAG = "USBPowerManager";
    private static final boolean DEBUG = true;

    // FLIR Boson power requirements
    private static final int BOSON_MIN_CURRENT_MA = 500;   // Minimum current for Boson operation
    private static final int BOSON_OPTIMAL_CURRENT_MA = 1000; // Optimal current for full performance
    private static final int GLASS_USB_MAX_CURRENT_MA = 500;  // Glass USB OTG max current estimate

    // Power management states
    public enum PowerState {
        UNKNOWN,
        INSUFFICIENT_POWER,    // USB power insufficient for Boson
        BATTERY_ONLY,         // Running on Glass battery only
        EXTERNAL_POWER,       // External power adapter detected
        OPTIMAL_POWER         // Sufficient power for full operation
    }

    private final Context mContext;
    private final UsbManager mUsbManager;
    private PowerState mCurrentPowerState = PowerState.UNKNOWN;
    private PowerStateListener mListener;

    // Battery monitoring
    private BroadcastReceiver mBatteryReceiver;
    private int mBatteryLevel = -1;
    private boolean mIsCharging = false;

    public interface PowerStateListener {
        void onPowerStateChanged(PowerState state, String details);
        void onBatteryLevelChanged(int level, boolean charging);
        void onPowerWarning(String warning);
    }

    public USBPowerManager(Context context) {
        mContext = context;
        mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        setupBatteryMonitoring();
    }

    public void setListener(PowerStateListener listener) {
        mListener = listener;
    }

    /**
     * Analyze USB device power requirements and Glass capabilities
     */
    public PowerState analyzeUsbDevicePower(UsbDevice device) {
        if (device == null) {
            return PowerState.UNKNOWN;
        }

        // Check if this is a FLIR Boson camera
        boolean isFlirBoson = isFlirBosonCamera(device);

        if (DEBUG) {
            Log.v(TAG, String.format("USB Device Analysis: VID=0x%04X, PID=0x%04X, FLIR Boson=%s",
                device.getVendorId(), device.getProductId(), isFlirBoson));
        }

        // Estimate power requirements
        int estimatedCurrentMa = estimateDevicePowerRequirement(device, isFlirBoson);

        // Check Glass power delivery capability
        PowerState powerState = evaluateGlassPowerCapability(estimatedCurrentMa);

        // Check for external power indicators
        if (mIsCharging && mBatteryLevel > 80) {
            powerState = PowerState.EXTERNAL_POWER;
        }

        mCurrentPowerState = powerState;

        String details = String.format("Device requires ~%dmA, Glass capability: %s",
            estimatedCurrentMa, powerState.toString());

        if (mListener != null) {
            mListener.onPowerStateChanged(powerState, details);
        }

        return powerState;
    }

    /**
     * Check if USB device is a FLIR Boson camera
     */
    private boolean isFlirBosonCamera(UsbDevice device) {
        int vendorId = device.getVendorId();
        int productId = device.getProductId();

        // FLIR vendor IDs
        if (vendorId == 0x09CB || vendorId == 0x04D8) {
            // Check for known Boson product IDs
            if (productId == 0x0100 || productId == 0x0101 || productId == 0x0102) {
                return true;
            }
            // Check for FLIR ONE product ID
            if (productId == 0x1996) {  // FLIR ONE Camera
                return true;
            }
            // Check device name for Boson
            String deviceName = device.getDeviceName();
            if (deviceName != null && deviceName.toLowerCase().contains("boson")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Estimate device power requirement based on type and specs
     */
    private int estimateDevicePowerRequirement(UsbDevice device, boolean isFlirBoson) {
        if (isFlirBoson) {
            // FLIR Boson cameras require significant power
            return BOSON_MIN_CURRENT_MA;
        }

        // Generic UVC camera estimation
        return 200; // Most USB cameras require 200-300mA
    }

    /**
     * Evaluate Glass power delivery capability
     */
    private PowerState evaluateGlassPowerCapability(int requiredCurrentMa) {
        // Glass USB OTG power is limited
        if (requiredCurrentMa > GLASS_USB_MAX_CURRENT_MA) {
            if (mListener != null) {
                mListener.onPowerWarning(String.format(
                    "Device requires %dmA but Glass USB provides max %dmA. External power may be needed.",
                    requiredCurrentMa, GLASS_USB_MAX_CURRENT_MA));
            }
            return PowerState.INSUFFICIENT_POWER;
        }

        // Check battery level for sustainability
        if (mBatteryLevel < 30 && !mIsCharging) {
            if (mListener != null) {
                mListener.onPowerWarning("Low battery. Thermal imaging will drain battery quickly.");
            }
            return PowerState.BATTERY_ONLY;
        }

        return PowerState.OPTIMAL_POWER;
    }

    /**
     * Setup battery level monitoring
     */
    private void setupBatteryMonitoring() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

                mBatteryLevel = Math.round(level / (float) scale * 100);
                mIsCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                              status == BatteryManager.BATTERY_STATUS_FULL);

                if (DEBUG) {
                    Log.v(TAG, String.format("Battery: %d%%, Charging: %s", mBatteryLevel, mIsCharging));
                }

                if (mListener != null) {
                    mListener.onBatteryLevelChanged(mBatteryLevel, mIsCharging);
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mBatteryReceiver, filter);
    }

    /**
     * Get current power state
     */
    public PowerState getCurrentPowerState() {
        return mCurrentPowerState;
    }

    /**
     * Get battery level
     */
    public int getBatteryLevel() {
        return mBatteryLevel;
    }

    /**
     * Check if external power is available
     */
    public boolean isExternalPowerAvailable() {
        return mIsCharging && mBatteryLevel > 80;
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        if (mBatteryReceiver != null) {
            try {
                mContext.unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered
            }
            mBatteryReceiver = null;
        }
    }
}