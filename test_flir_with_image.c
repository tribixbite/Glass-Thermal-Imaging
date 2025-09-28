#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <time.h>
#include <sys/time.h>

#define VENDOR_ID 0x09CB
#define PRODUCT_ID 0x1996
#define EP_VIDEO_READ_SIZE 512 // Standard USB packet size for maximum compatibility

void print_hex(unsigned char *buf, int len) {
    for (int i = 0; i < len; i++) {
        printf("%02X ", buf[i]);
        if ((i + 1) % 16 == 0) printf("\n");
    }
    printf("\n");
}

int find_device(int *bus, int *dev) {
    FILE *fp;
    char line[256];

    fp = popen("ls /sys/bus/usb/devices/*/idVendor 2>/dev/null", "r");
    if (!fp) return 0;

    while (fgets(line, sizeof(line), fp)) {
        line[strlen(line)-1] = 0; // Remove newline
        FILE *vf = fopen(line, "r");
        if (vf) {
            int vid;
            fscanf(vf, "%x", &vid);
            fclose(vf);

            if (vid == VENDOR_ID) {
                // Check product ID
                char pid_path[256];
                strcpy(pid_path, line);
                char *p = strrchr(pid_path, '/');
                strcpy(p, "/idProduct");

                FILE *pf = fopen(pid_path, "r");
                if (pf) {
                    int pid;
                    fscanf(pf, "%x", &pid);
                    fclose(pf);

                    if (pid == PRODUCT_ID) {
                        // Get bus and device number
                        char *dev_path = strrchr(line, '/');
                        *dev_path = 0;
                        dev_path = strrchr(line, '/');
                        dev_path++;

                        // Parse bus and device from path like "1-1"
                        *bus = 1; // Assume bus 1 on Android

                        // Get actual device number from kernel
                        char devnum_path[256];
                        sprintf(devnum_path, "/sys/bus/usb/devices/%s/devnum", dev_path);
                        FILE *df = fopen(devnum_path, "r");
                        if (df) {
                            fscanf(df, "%d", dev);
                            fclose(df);
                            pclose(fp);
                            return 1;
                        }
                    }
                }
            }
        }
    }
    pclose(fp);
    return 0;
}

int main(int argc, char *argv[]) {
    int fd;
    int bus, dev;
    char device_path[64];
    unsigned char buffer[65536]; // 64KB buffer for bulk transfers
    int ret;

    printf("FLIR ONE USB Test Tool\n");
    printf("======================\n\n");

    // Find FLIR ONE device
    if (!find_device(&bus, &dev)) {
        printf("Scanning dmesg for FLIR device...\n");
        FILE *fp = popen("dmesg | grep -i 'FLIR ONE' | tail -1", "r");
        if (fp) {
            char line[256];
            if (fgets(line, sizeof(line), fp)) {
                if (sscanf(line, "%*[^:]usb %d-%d:", &bus, &dev) == 2) {
                    printf("Found in dmesg: bus=%d dev=%d\n", bus, dev);
                }
            }
            pclose(fp);
        }
        printf("\nScanning /dev/bus/usb/...\n");
        system("ls -la /dev/bus/usb/001/");
        printf("\nEnter device number (e.g., 3 for /dev/bus/usb/001/003): ");
        scanf("%d", &dev);
        bus = 1;
    }

    sprintf(device_path, "/dev/bus/usb/%03d/%03d", bus, dev);
    printf("Opening device: %s\n", device_path);

    fd = open(device_path, O_RDWR);
    if (fd < 0) {
        perror("Failed to open device");
        printf("Try: chmod 666 %s\n", device_path);
        return 1;
    }

    printf("\nClaiming interfaces...\n");
    for (int i = 0; i < 3; i++) {
        ret = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i);
        printf("Interface %d: %s\n", i, (ret == 0) ? "OK" : strerror(errno));
        usleep(50000);
    }
    printf("Waiting for device to stabilize...\n");
    usleep(200000);

    printf("\nSetting alternate interfaces...\n");
    struct usbdevfs_setinterface setif;

    setif.interface = 1;
    setif.altsetting = 0;
    ret = ioctl(fd, USBDEVFS_SETINTERFACE, &setif);
    printf("Interface 1 alt 0: %d\n", ret);
    usleep(100000);

    // Keep interface 2 at alt 0 - that's where the endpoints are!
    printf("Setting video interface to alt 0 (has endpoints)...\n");
    setif.interface = 2;
    setif.altsetting = 0;
    ret = ioctl(fd, USBDEVFS_SETINTERFACE, &setif);
    printf("Interface 2 alt 0: %d\n", ret);
    usleep(200000);

    // Send initialization commands
    printf("\nSending init commands...\n");
    struct usbdevfs_ctrltransfer ctrl;
    unsigned char dummy[2] = {0x00, 0x00};

    ctrl.bRequestType = 0x01;
    ctrl.bRequest = 0x0b;
    ctrl.wValue = 0;
    ctrl.wIndex = 2;
    ctrl.wLength = 0;
    ctrl.timeout = 100;
    ctrl.data = dummy;
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Stop interface 2 FRAME: %d\n", ret);

    ctrl.wIndex = 1;
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Stop interface 1 FILEIO: %d\n", ret);

    ctrl.wValue = 1;
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Start interface 1 FILEIO: %d\n", ret);

    // Send CameraFiles.zip request
    printf("\nSending CameraFiles.zip request (required for init)...\n");
    unsigned char header1[] = {0xcc,0x01,0x00,0x00,0x01,0x00,0x00,0x00,0x41,0x00,0x00,0x00,0xF8,0xB3,0xF7,0x00};
    char json1[] = "{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}";
    unsigned char header2[] = {0xcc,0x01,0x00,0x00,0x01,0x00,0x00,0x00,0x33,0x00,0x00,0x00,0xef,0xdb,0xc1,0xc1};
    char json2[] = "{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":10}}";

    struct usbdevfs_bulktransfer bulk;
    bulk.ep = 0x02;
    bulk.timeout = 1000;
    bulk.len = sizeof(header1);
    bulk.data = header1;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("Header1 sent: %d bytes\n", ret);
    bulk.len = strlen(json1) + 1;
    bulk.data = (unsigned char*)json1;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("JSON1 sent: %d bytes - %s\n", ret, json1);

    bulk.len = sizeof(header2);
    bulk.data = header2;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("Header2 sent: %d bytes\n", ret);
    bulk.len = strlen(json2) + 1;
    bulk.data = (unsigned char*)json2;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("JSON2 sent: %d bytes - %s\n", ret, json2);

    usleep(200000);

    // Read and consume all status data from EP 0x81
    printf("\nReading initial status from EP 0x81...\n");
    bulk.ep = 0x81;
    bulk.len = sizeof(buffer);
    bulk.timeout = 500;
    bulk.data = buffer;
    for (int i = 0; i < 5; i++) {
        ret = ioctl(fd, USBDEVFS_BULK, &bulk);
        if (ret > 0) {
            printf("Status read %d: Got %d bytes\n", i+1, ret);
            if (ret > 16 && buffer[16] == '{') {
                printf("JSON data: %.100s...\n", buffer + 16);
            }
        } else {
            break;
        }
    }

    // Start video stream
    printf("\nStarting video stream (final command)...\n");
    ctrl.bRequestType = 0x01;
    ctrl.bRequest = 0x0b;
    ctrl.wValue = 1;
    ctrl.wIndex = 2;
    ctrl.wLength = 0;
    ctrl.timeout = 200;
    ctrl.data = dummy;  // Use dummy buffer instead of NULL
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Video start command: %d\n", ret);
    if (ret < 0) {
        printf("Video start failed: %s\n", strerror(errno));
        printf("Trying without starting video stream...\n");
    }

    // Give the camera more time to start streaming
    printf("Waiting for video stream to stabilize...\n");
    usleep(1000000);  // 1 second delay

    // Clear any pending status data that might be blocking the video endpoint
    printf("Clearing status endpoints...\n");
    bulk.ep = 0x81;
    bulk.len = 512;
    bulk.timeout = 100;
    bulk.data = buffer;
    while (ioctl(fd, USBDEVFS_BULK, &bulk) > 0) {
        usleep(10000);
    }

    bulk.ep = 0x83;
    while (ioctl(fd, USBDEVFS_BULK, &bulk) > 0) {
        usleep(10000);
    }

    // Try to read full frames
    printf("\nReading frames from EP 0x85...\n");

    // First try a tiny read to see if the endpoint is even active
    printf("Testing endpoint 0x85 with small read...\n");
    bulk.ep = 0x85;
    bulk.timeout = 1000;
    bulk.len = 64;  // Minimum USB packet size
    bulk.data = buffer;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    if (ret < 0) {
        printf("EP 0x85 test read failed: %s (errno=%d)\n", strerror(errno), errno);
        printf("Trying EP 0x83 for video instead...\n");
        bulk.ep = 0x83;
        ret = ioctl(fd, USBDEVFS_BULK, &bulk);
        if (ret >= 0) {
            printf("EP 0x83 responded with %d bytes - using this endpoint\n", ret);
        } else {
            printf("EP 0x83 also failed: %s\n", strerror(errno));
            bulk.ep = 0x85;  // Go back to 0x85
        }
    } else {
        printf("EP 0x85 test read succeeded with %d bytes\n", ret);
    }

    bulk.timeout = 2000;
    unsigned char* frame_buffer = malloc(512 * 1024);
    if (!frame_buffer) {
        printf("Failed to allocate frame buffer\n");
        goto cleanup;
    }
    int frame_pos = 0;
    int expected_frame_size = 0;
    int thermal_size = 0;
    int jpg_size = 0;
    int status_size = 0;
    int frames_captured = 0;

    printf("Continuously reading to keep stream alive...\n");
    for (int attempt = 0; attempt < 1000 && frames_captured < 3; attempt++) {  // Need ~280 attempts for 142KB frames
        // CORRECTED LINE: Use the smaller, safer bulk transfer size
        bulk.len = EP_VIDEO_READ_SIZE;
        bulk.data = buffer;
        ret = ioctl(fd, USBDEVFS_BULK, &bulk);

        if (ret > 0) {
            // Only print progress every 10KB
            if ((frame_pos % 10240) < 512) {
                printf("\rProgress: %d / %d bytes", frame_pos, expected_frame_size);
                fflush(stdout);
            }
            if (ret >= 4 && buffer[0] == 0xEF && buffer[1] == 0xBE) {
                frame_pos = 0;
                if (ret >= 28) {
                    expected_frame_size = buffer[8] | (buffer[9]<<8) | (buffer[10]<<16) | (buffer[11]<<24);
                    thermal_size = buffer[12] | (buffer[13]<<8) | (buffer[14]<<16) | (buffer[15]<<24);
                    jpg_size = buffer[16] | (buffer[17]<<8) | (buffer[18]<<16) | (buffer[19]<<24);
                    status_size = buffer[20] | (buffer[21]<<8) | (buffer[22]<<16) | (buffer[23]<<24);
                    printf("\n=== Frame %d ===\n", frames_captured + 1);
                    printf("Expected size: %d, Thermal: %d, JPEG: %d, Status: %d\n", expected_frame_size, thermal_size, jpg_size, status_size);
                }
            }
            if (frame_pos + ret <= 512 * 1024 && ret <= EP_VIDEO_READ_SIZE) {
                memcpy(frame_buffer + frame_pos, buffer, ret);
                frame_pos += ret;
                // Frame complete when we have header (28 bytes) + payload (expected_frame_size)
                if (expected_frame_size > 0 && frame_pos >= expected_frame_size + 28) {
                    printf("\nComplete frame received! Total: %d bytes\n", frame_pos);

                    // Save the complete frame
                    char frame_filename[64];
                    sprintf(frame_filename, "/data/local/tmp/frame_%d.bin", frames_captured + 1);
                    FILE *frame_file = fopen(frame_filename, "wb");
                    if (frame_file) {
                        fwrite(frame_buffer, 1, frame_pos, frame_file);
                        fclose(frame_file);
                        printf("Saved frame to %s\n", frame_filename);
                    }

                    // Extract and save JPEG
                    if (thermal_size > 0 && jpg_size > 0) {
                        // JPEG starts immediately after the thermal data
                        int jpg_offset = 28 + thermal_size;
                        if (jpg_offset + jpg_size <= frame_pos) {
                            // Find the EOI marker to get the real size
                            int actual_jpg_size = 0;
                            for (int i = 0; i < jpg_size - 1; i++) {
                                if (frame_buffer[jpg_offset + i] == 0xFF && frame_buffer[jpg_offset + i + 1] == 0xD9) {
                                    actual_jpg_size = i + 2;
                                    break;
                                }
                            }

                            if (actual_jpg_size > 0) {
                                char jpg_filename[64];
                                sprintf(jpg_filename, "/data/local/tmp/flir_%d.jpg", frames_captured + 1);
                                FILE *jpg_file = fopen(jpg_filename, "wb");
                                if (jpg_file) {
                                    fwrite(frame_buffer + jpg_offset, 1, actual_jpg_size, jpg_file);
                                    fclose(jpg_file);
                                    printf("Saved JPEG to %s (%d bytes, original size %d)\n", jpg_filename, actual_jpg_size, jpg_size);
                                }
                            } else {
                                printf("Could not find EOI marker in JPEG data\n");
                            }
                        }
                    }

                    // Save thermal data
                    if (thermal_size > 0) {
                        char thermal_filename[64];
                        sprintf(thermal_filename, "/data/local/tmp/thermal_%d.raw", frames_captured + 1);
                        FILE *thermal_file = fopen(thermal_filename, "wb");
                        if (thermal_file) {
                            fwrite(frame_buffer + 28, 1, thermal_size, thermal_file);
                            fclose(thermal_file);
                            printf("Saved thermal data to %s (%d bytes)\n", thermal_filename, thermal_size);
                        }
                    }

                    if (frame_pos > 28 + 200) {
                        printf("Thermal data (first 100 bytes as 16-bit values):\n");
                        for (int i = 0; i < 50; i++) {
                            int idx = 28 + i * 2;
                            unsigned short thermal = frame_buffer[idx] | (frame_buffer[idx + 1] << 8);
                            printf("%04X ", thermal);
                            if ((i + 1) % 10 == 0) printf("\n");
                        }
                        printf("\n");
                    }
                    frames_captured++;
                    frame_pos = 0;
                    expected_frame_size = 0;
                }
            }
        } else if (errno == ETIMEDOUT) {
            printf(".");
            fflush(stdout);
            bulk.ep = 0x81;
            bulk.len = 256;
            bulk.timeout = 10;
            ioctl(fd, USBDEVFS_BULK, &bulk);
            bulk.ep = 0x83;
            ioctl(fd, USBDEVFS_BULK, &bulk);
            bulk.ep = 0x85;
            bulk.timeout = 2000;
        } else {
            printf("Read error: %s\n", strerror(errno));
            break;
        }
    }

    printf("\nTotal frames captured: %d\n", frames_captured);
    free(frame_buffer);

cleanup:
    printf("\n\nChecking other endpoints for diagnostics...\n");
    printf("\nReading from EP 0x81 (status)...\n");
    bulk.ep = 0x81;
    bulk.len = 512;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    if (ret > 0) {
        printf("Got %d bytes from 0x81\n", ret);
        print_hex(buffer, (ret > 64) ? 64 : ret);
    }
    printf("\nReading from EP 0x83...\n");
    bulk.ep = 0x83;
    bulk.len = 512;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    if (ret > 0) {
        printf("Got %d bytes from 0x83\n", ret);
        print_hex(buffer, (ret > 64) ? 64 : ret);
    }
    printf("\nReleasing interfaces...\n");
    for (int i = 0; i < 3; i++) {
        ioctl(fd, USBDEVFS_RELEASEINTERFACE, &i);
    }

    close(fd);
    return 0;
}