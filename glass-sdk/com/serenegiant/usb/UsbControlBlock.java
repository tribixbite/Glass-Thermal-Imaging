package com.serenegiant.usb;

import android.hardware.usb.UsbDevice;

public class UsbControlBlock {
    private UsbDevice mDevice;

    public UsbControlBlock(UsbDevice device) {
        this.mDevice = device;
    }

    public UsbDevice getDevice() {
        return mDevice;
    }

    public void close() {
        // Close USB connection
    }
}