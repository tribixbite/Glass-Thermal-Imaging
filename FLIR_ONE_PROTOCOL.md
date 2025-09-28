test_flir_usb_fixed.c succeeds.
Here is a full architectural and connection summary for the Google Glass thermal imaging project, based on the troubleshooting and findings from this session. This document can serve as the source of truth for all future agents.

***

### Project Summary

The goal is to enable a **FLIR ONE Pro** thermal camera to stream video to a **Google Glass Explorer Edition** running Android 4.4.4.

**Key Components:**
* **Host Device:** Google Glass (OMAP4430 processor, Android 4.4.4 KitKat)
* **USB Peripheral:** FLIR ONE Pro Thermal Camera (Vendor ID: `0x09CB`, Product ID: `0x1996`)
* **Operating System:** AOSP-based Android 4.4.4 with a custom kernel (musb-hdrc driver)
* **Application:** A custom Android application (`com.serenegiant.usbcameratest3`) that uses the Android USB Host API and Linux `usbdevfs` for low-level access.

***

### USB Architecture & Protocol

The FLIR ONE does **not** use a standard USB Video Class (UVC) protocol. Instead, it relies on a proprietary, vendor-specific protocol that is a complex series of sequenced commands and bulk data transfers.

#### USB Endpoints & Interfaces
The FLIR ONE exposes multiple interfaces and endpoints. The following are crucial for the video stream:
* **Interface 0:** Control and Status
    * `EP 0x02` (OUT): Control commands
    * `EP 0x81` (IN): Status responses
* **Interface 1:** Status and Data
    * `EP 0x83` (IN): General status and log data
* **Interface 2:** Video Stream
    * **Alternate Setting 0:** Inactive state
    * **Alternate Setting 1:** **High-bandwidth video stream** (`EP 0x85`)
        * `EP 0x85` (IN): Raw video data (thermal and visible frames)

***

### Root Cause Analysis (Source of Truth)

The primary issue was **not a fundamental kernel limitation** but a combination of **race conditions and timing issues** exacerbated by the Google Glass's older hardware and a sensitive USB driver (`musb-hdrc`).

* **Symptom:** `ioctl(USBDEVFS_SETINTERFACE)` would fail, causing the USB device to disconnect and immediately re-attach in a loop.
* **Misdiagnosis:** Initially, this was mistaken for a kernel-level bug, but the fact that it worked intermittently pointed to a race condition.
* **Correct Diagnosis:** The USB stack on Google Glass required specific delays between claiming interfaces and setting alternate settings. Without these delays, the host controller would become overwhelmed or encounter an unhandled state transition, leading to the device reset.

***

### The Proven Initialization Sequence

A stable connection and data stream can only be achieved by following a precise, sequential state machine. This is the **correct and verified protocol**:

1.  **Open Device:** Obtain file descriptor for the device (`/dev/bus/usb/xxx/xxx`) with root permissions.
2.  **Claim Interfaces:** Claim interfaces `0`, `1`, and `2` with `ioctl(USBDEVFS_CLAIMINTERFACE)`. **Introduce a `50ms` delay between each claim** to prevent a race condition.
3.  **Set Alternate Interfaces:** Set Interface 1 to `alt 0` and **Interface 2 to `alt 1`**. The command to set Interface 2 to `alt 1` is **critical** for enabling the high-bandwidth video stream.
4.  **Initialize Camera Protocol:** Send a series of control and bulk transfers in the following order:
    * Stop Interfaces 1 and 2 (using `ioctl(USBDEVFS_CONTROL)`) with a `wValue` of `0` and a `wIndex` of `1` and `2`, respectively.
    * Start Interface 1 (with `wValue=1`).
    * Send the `CameraFiles.zip` request headers and JSON payload to `EP 0x02` via bulk transfers.
    * Read from `EP 0x81` to clear any pending status responses from the `CameraFiles.zip` request.
    * Start the video stream on Interface 2 (with `wValue=1`).
5.  **Start Data Stream Loop:** Enter a continuous loop to read from `EP 0x85`.
    * **CRITICAL:** The bulk read size must be a safe, small value (e.g., `4096` or `512` bytes) to avoid an "Invalid argument" error. This is a crucial compatibility fix for the Google Glass kernel.
    * **Keep-Alive:** Within the loop, periodically poll the status endpoints (`EP 0x81` and `EP 0x83`) with short timeouts. This keeps the camera from timing out and resetting the connection.
    * **Frame Parsing:** Read data in chunks and reassemble it into complete frames based on the magic bytes (`0xEFBE0000`) and the frame header size.

***

### Future Work & Notes

* **Power:** The FLIR ONE Pro requires significant power (`~500mA`). The Google Glass USB port may not provide this consistently, which could lead to intermittent issues.
* **Thermal Data Unpacking:** The raw thermal data is interlaced and requires a specific, non-trivial bitwise unpacking algorithm to reassemble the thermal image correctly.
* **Custom Kernel:** While not a necessity for basic functionality, a custom kernel with an updated `musb-hdrc` driver could improve stability and efficiency.
* **Android API vs. C `ioctl`:** The Android USB Host API is a high-level wrapper around the Linux `usbdevfs` interface. The C code using `ioctl` is a closer representation of the underlying system calls, which proved more reliable for low-level timing control in this case.