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
                        *bus = 1; // Usually bus 1 on Android

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
    struct usbdevfs_connectinfo ci;
    unsigned char buffer[65536]; // 64KB buffer for bulk transfers
    int ret;

    printf("FLIR ONE USB Test Tool\n");
    printf("======================\n\n");

    // Find FLIR ONE device
    if (!find_device(&bus, &dev)) {
        // Try to find it manually from dmesg
        printf("Scanning dmesg for FLIR device...\n");
        FILE *fp = popen("dmesg | grep -i 'FLIR ONE' | tail -1", "r");
        if (fp) {
            char line[256];
            if (fgets(line, sizeof(line), fp)) {
                // Extract device number from line like "usb 1-1: Product: FLIR ONE Camera"
                if (sscanf(line, "%*[^:]usb %d-%d:", &bus, &dev) == 2) {
                    printf("Found in dmesg: bus=%d dev=%d\n", bus, dev);
                }
            }
            pclose(fp);
        }

        // Last resort - scan all USB devices
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

    // Skip device info on Android (different structure)

    // Don't reset - it causes disconnect on Glass
    // printf("Resetting device...\n");
    // ret = ioctl(fd, USBDEVFS_RESET);
    // if (ret < 0) {
    //     perror("Reset failed");
    // }
    // sleep(1);

    // Claim interfaces
    printf("\nClaiming interfaces...\n");
    for (int i = 0; i < 3; i++) {
        ret = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i);
        printf("Interface %d: %s\n", i, (ret == 0) ? "OK" : strerror(errno));
    }

    // Set alternate interfaces according to USB descriptor
    printf("\nSetting alternate interfaces...\n");
    struct usbdevfs_setinterface setif;

    // Interface 1: alt 0 has endpoints, alt 1 has none
    setif.interface = 1;
    setif.altsetting = 0;  // Use Alt 0 which has EP 0x83/0x04
    ret = ioctl(fd, USBDEVFS_SETINTERFACE, &setif);
    printf("Interface 1 alt 0: %d\n", ret);

    // Interface 2: alt 1 for video streaming (high bandwidth mode)
    setif.interface = 2;
    setif.altsetting = 1;  // Use Alt 1 for video streaming
    ret = ioctl(fd, USBDEVFS_SETINTERFACE, &setif);
    printf("Interface 2 alt 1: %d\n", ret);

    // Send initialization commands (following ROS driver sequence)
    printf("\nSending init commands...\n");
    struct usbdevfs_ctrltransfer ctrl;
    unsigned char dummy[2] = {0x00, 0x00};

    // Step 1: Stop interface 2 FRAME
    ctrl.bRequestType = 0x01;  // Vendor, OUT
    ctrl.bRequest = 0x0b;
    ctrl.wValue = 0;     // stop
    ctrl.wIndex = 2;     // interface 2
    ctrl.wLength = 0;
    ctrl.timeout = 100;
    ctrl.data = dummy;

    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Stop interface 2 FRAME: %d\n", ret);

    // Step 2: Stop interface 1 FILEIO
    ctrl.wIndex = 1;     // interface 1
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Stop interface 1 FILEIO: %d\n", ret);

    // Step 3: Start interface 1 FILEIO
    ctrl.wValue = 1;     // start
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Start interface 1 FILEIO: %d\n", ret);

    // Send CameraFiles.zip request exactly as ROS driver
    printf("\nSending CameraFiles.zip request (required for init)...\n");

    // These headers are from the ROS driver
    unsigned char header1[] = {0xcc,0x01,0x00,0x00,0x01,0x00,0x00,0x00,0x41,0x00,0x00,0x00,0xF8,0xB3,0xF7,0x00};
    char json1[] = "{\"type\":\"openFile\",\"data\":{\"mode\":\"r\",\"path\":\"CameraFiles.zip\"}}";

    unsigned char header2[] = {0xcc,0x01,0x00,0x00,0x01,0x00,0x00,0x00,0x33,0x00,0x00,0x00,0xef,0xdb,0xc1,0xc1};
    char json2[] = "{\"type\":\"readFile\",\"data\":{\"streamIdentifier\":10}}";

    struct usbdevfs_bulktransfer bulk;
    bulk.ep = 0x02;  // EP 2 OUT
    bulk.timeout = 1000;

    // Send header1
    bulk.len = sizeof(header1);
    bulk.data = header1;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("Header1 sent: %d bytes\n", ret);

    // Send JSON1
    bulk.len = strlen(json1) + 1;
    bulk.data = (unsigned char*)json1;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("JSON1 sent: %d bytes - %s\n", ret, json1);

    // Send header2
    bulk.len = sizeof(header2);
    bulk.data = header2;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("Header2 sent: %d bytes\n", ret);

    // Send JSON2
    bulk.len = strlen(json2) + 1;
    bulk.data = (unsigned char*)json2;
    ret = ioctl(fd, USBDEVFS_BULK, &bulk);
    printf("JSON2 sent: %d bytes - %s\n", ret, json2);

    // Give camera time to process
    usleep(200000); // 200ms

    // Read and consume all status data from EP 0x81
    printf("\nReading initial status from EP 0x81...\n");
    bulk.ep = 0x81;
    bulk.len = sizeof(buffer);
    bulk.timeout = 500;
    bulk.data = buffer;

    // Read multiple times to empty the buffer
    for (int i = 0; i < 5; i++) {
        ret = ioctl(fd, USBDEVFS_BULK, &bulk);
        if (ret > 0) {
            printf("Status read %d: Got %d bytes\n", i+1, ret);
            // Check for JSON data
            if (ret > 16 && buffer[16] == '{') {
                printf("JSON data: %.100s...\n", buffer + 16);
            }
        } else {
            break;
        }
    }

    // Try video start with no data (wLength=0)
    printf("\nStarting video stream (simplified)...\n");
    ctrl.bRequestType = 0x01;
    ctrl.bRequest = 0x0b;
    ctrl.wValue = 1;     // start
    ctrl.wIndex = 2;     // interface 2 (frame)
    ctrl.wLength = 0;    // No data
    ctrl.timeout = 200;
    ctrl.data = NULL;

    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    printf("Video start (no data): %d\n", ret);

    // Give camera time to start
    usleep(500000); // 500ms

    // Try to read full frames
    printf("\nReading frames from EP 0x85...\n");
    bulk.ep = 0x85;  // EP 0x85 IN
    bulk.timeout = 2000;  // Longer timeout

    // Allocate large buffer for full frame
    unsigned char* frame_buffer = malloc(512 * 1024); // 512KB buffer
    if (!frame_buffer) {
        printf("Failed to allocate frame buffer\n");
        goto cleanup;
    }

    int frame_pos = 0;
    int expected_frame_size = 0;
    int frames_captured = 0;

    // Read frames continuously - keep reading to maintain stream
    printf("Continuously reading to keep stream alive...\n");
    for (int attempt = 0; attempt < 100 && frames_captured < 3; attempt++) {
        bulk.len = 16384; // Read in chunks
        bulk.data = buffer;
        ret = ioctl(fd, USBDEVFS_BULK, &bulk);

        if (ret > 0) {
            // Check for frame start
            if (ret >= 4 && buffer[0] == 0xEF && buffer[1] == 0xBE) {
                // New frame starts
                frame_pos = 0;
                if (ret >= 28) {
                    expected_frame_size = buffer[8] | (buffer[9]<<8) | (buffer[10]<<16) | (buffer[11]<<24);
                    int thermal_size = buffer[12] | (buffer[13]<<8) | (buffer[14]<<16) | (buffer[15]<<24);
                    int jpg_size = buffer[16] | (buffer[17]<<8) | (buffer[18]<<16) | (buffer[19]<<24);
                    printf("\n=== Frame %d ===\n", frames_captured + 1);
                    printf("Expected size: %d, Thermal: %d, JPEG: %d\n",
                           expected_frame_size, thermal_size, jpg_size);
                }
            }

            // Copy data to frame buffer - check bounds
            if (frame_pos + ret <= 512 * 1024 && ret <= sizeof(buffer)) {
                memcpy(frame_buffer + frame_pos, buffer, ret);
                frame_pos += ret;

                // Check if we have complete frame
                if (expected_frame_size > 0 && frame_pos >= expected_frame_size + 28) {
                    printf("Complete frame received! Total: %d bytes\n", frame_pos);

                    // Extract and show thermal data sample (first 100 16-bit values)
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
            // On timeout, poll status endpoints to keep connection alive
            printf(".");
            fflush(stdout);

            // Poll EP 0x81
            bulk.ep = 0x81;
            bulk.len = 256;
            bulk.timeout = 10;
            ioctl(fd, USBDEVFS_BULK, &bulk);

            // Poll EP 0x83
            bulk.ep = 0x83;
            ioctl(fd, USBDEVFS_BULK, &bulk);

            // Switch back to EP 0x85
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

    // If all buffer sizes failed, try reading status endpoints
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

    // Release interfaces
    printf("\nReleasing interfaces...\n");
    for (int i = 0; i < 3; i++) {
        ioctl(fd, USBDEVFS_RELEASEINTERFACE, &i);
    }

    close(fd);
    return 0;
}