# FLIR ONE USB Protocol for Google Glass

## PROVEN WORKING PROTOCOL
Based on successful capture in `test_flir_usb_fixed.c` that achieved 3 complete frames.

### Critical Configuration
- **Buffer Size**: 512 bytes (EP_VIDEO_READ_SIZE)
  - Glass kernel limitation - larger buffers cause "Invalid argument" error
- **Alternate Interface**: Use Alt 0 on Interface 2
  - Alt 1 has NO endpoints - this was the key discovery
- **Frame Structure**: 28-byte header + payload (thermal + JPEG)

### Exact Connection Sequence

```c
// 1. Open USB device
int fd = open("/dev/bus/usb/001/XXX", O_RDWR);

// 2. Claim all 3 interfaces with delays
for (int i = 0; i < 3; i++) {
    ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i);
    usleep(50000);  // 50ms between claims
}
usleep(200000);  // 200ms stabilization

// 3. Set alternate interfaces - CRITICAL: Use Alt 0!
struct usbdevfs_setinterface setif;
setif.interface = 1;
setif.altsetting = 0;  // Alt 0 has endpoints
ioctl(fd, USBDEVFS_SETINTERFACE, &setif);
usleep(100000);

setif.interface = 2;
setif.altsetting = 0;  // Alt 0 - NOT Alt 1!
ioctl(fd, USBDEVFS_SETINTERFACE, &setif);
usleep(200000);

// 4. Stop interfaces
struct usbdevfs_ctrltransfer ctrl;
unsigned char dummy[2] = {0x00, 0x00};

ctrl.bRequestType = 0x01;
ctrl.bRequest = 0x0b;
ctrl.wValue = 0;
ctrl.wIndex = 2;
ctrl.wLength = 0;
ctrl.timeout = 100;
ctrl.data = dummy;
ioctl(fd, USBDEVFS_CONTROL, &ctrl);  // Stop interface 2

ctrl.wIndex = 1;
ioctl(fd, USBDEVFS_CONTROL, &ctrl);  // Stop interface 1

// 5. Start interface 1 FILEIO
ctrl.wValue = 1;
ioctl(fd, USBDEVFS_CONTROL, &ctrl);  // Start interface 1

// 6. Send CameraFiles.zip request (REQUIRED!)
unsigned char header1[] = {0xcc,0x01,0x00,0x00,0x01,0x00,0x00,0x00,
                           0x41,0x00,0x00,0x00,0xF8,0xB3,0xF7,0x00};
char json1[] = "{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}";

unsigned char header2[] = {0xcc,0x01,0x00,0x00,0x01,0x00,0x00,0x00,
                           0x33,0x00,0x00,0x00,0xef,0xdb,0xc1,0xc1};
char json2[] = "{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":10}}";

struct usbdevfs_bulktransfer bulk;
bulk.ep = 0x02;
bulk.timeout = 1000;

// Send first command
bulk.len = sizeof(header1);
bulk.data = header1;
ioctl(fd, USBDEVFS_BULK, &bulk);
bulk.len = strlen(json1) + 1;
bulk.data = (unsigned char*)json1;
ioctl(fd, USBDEVFS_BULK, &bulk);

// Send second command
bulk.len = sizeof(header2);
bulk.data = header2;
ioctl(fd, USBDEVFS_BULK, &bulk);
bulk.len = strlen(json2) + 1;
bulk.data = (unsigned char*)json2;
ioctl(fd, USBDEVFS_BULK, &bulk);

usleep(200000);

// 7. Clear status from EP 0x81
bulk.ep = 0x81;
bulk.len = 65536;
bulk.timeout = 500;
bulk.data = buffer;
for (int i = 0; i < 5; i++) {
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    if (ret <= 0) break;
}

// 8. Start video stream
ctrl.bRequestType = 0x01;
ctrl.bRequest = 0x0b;
ctrl.wValue = 1;
ctrl.wIndex = 2;
ctrl.wLength = 0;
ctrl.timeout = 200;
ctrl.data = dummy;  // Use dummy buffer, not NULL
ioctl(fd, USBDEVFS_CONTROL, &ctrl);

// 9. Wait and clear status endpoints
usleep(1000000);  // 1 second

bulk.ep = 0x81;
bulk.len = 512;
bulk.timeout = 100;
while (ioctl(fd, USBDEVFS_BULK, &bulk) > 0) {
    usleep(10000);
}

bulk.ep = 0x83;
while (ioctl(fd, USBDEVFS_BULK, &bulk) > 0) {
    usleep(10000);
}

// 10. Read frames from EP 0x85
bulk.ep = 0x85;
bulk.timeout = 2000;
bulk.len = 512;  // CRITICAL: 512 bytes max!

// Frame reading loop
for (int attempt = 0; attempt < 1000; attempt++) {
    bulk.data = buffer;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);

    if (ret > 0) {
        // Check for frame header
        if (ret >= 4 && buffer[0] == 0xEF && buffer[1] == 0xBE) {
            // New frame started
            expected_frame_size = buffer[8] | (buffer[9]<<8) |
                                 (buffer[10]<<16) | (buffer[11]<<24);
            thermal_size = buffer[12] | (buffer[13]<<8) |
                          (buffer[14]<<16) | (buffer[15]<<24);
            jpg_size = buffer[16] | (buffer[17]<<8) |
                      (buffer[18]<<16) | (buffer[19]<<24);
        }

        // Accumulate data
        memcpy(frame_buffer + frame_pos, buffer, ret);
        frame_pos += ret;

        // Check if complete (header + payload)
        if (expected_frame_size > 0 && frame_pos >= expected_frame_size + 28) {
            // Frame complete!
            // JPEG is at: offset 28 + thermal_size + 165
        }
    }
}
```

## Frame Structure
```
Offset 0:   0xEF 0xBE (magic header)
Offset 8:   Total payload size (4 bytes, little-endian)
Offset 12:  Thermal data size (4 bytes, little-endian)
Offset 16:  JPEG size (4 bytes, little-endian)
Offset 28:  Thermal data (39852 bytes typical)
Offset 28 + thermal_size + 165: JPEG image
```

## Key Findings

1. **Interface 2 Alt 1 has NO endpoints** - must use Alt 0
2. **Buffer size must be 512 bytes** on Glass kernel
3. **CameraFiles.zip initialization is mandatory**
4. **Must use dummy buffer in control transfers, not NULL**
5. **Need ~280 reads of 512 bytes for 140KB frame**
6. **Device reconnects frequently** - need to handle gracefully

## Test Results
- Successfully captured 3 frames
- Each frame ~100-140KB total
- Thermal data: ~40KB (160x120 16-bit values)
- JPEG image: ~60-100KB

## Build Command
```bash
/home/will/android-sdk/android-ndk-r16b/ndk-build \
    APP_BUILD_SCRIPT=Android_test.mk \
    NDK_APPLICATION_MK=Application_test.mk \
    NDK_PROJECT_PATH=. -B
```

## Run Test
```bash
./test_flir.sh
```

This captures frames with thermal data showing temperature values like:
- 0x0D67 = 3431 (raw sensor units)
- Successfully extracts both thermal and visual JPEG data