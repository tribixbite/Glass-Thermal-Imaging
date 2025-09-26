package com.serenegiant.usb;

import android.hardware.usb.UsbDevice;

public interface OnDeviceConnectListener {
    void onAttach(UsbDevice device);
    void onDetach(UsbDevice device);
    void onConnect(UsbDevice device, UsbControlBlock ctrlBlock, boolean createNew);
    void onDisconnect(UsbDevice device, UsbControlBlock ctrlBlock);
    void onCancel(UsbDevice device);
}