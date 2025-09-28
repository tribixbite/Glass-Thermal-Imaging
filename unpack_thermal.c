#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

int main(int argc, char *argv[]) {
    if (argc < 3) {
        printf("Usage: %s <input_raw_file> <output_unpacked_file>\n", argv[0]);
        return 1;
    }

    FILE *in = fopen(argv[1], "rb");
    if (!in) {
        perror("fopen input");
        return 1;
    }

    fseek(in, 0, SEEK_END);
    long file_size = ftell(in);
    fseek(in, 0, SEEK_SET);

    uint8_t *buf85 = malloc(file_size);
    if (!buf85) {
        printf("malloc failed\n");
        fclose(in);
        return 1;
    }

    if (fread(buf85, 1, file_size, in) != file_size) {
        printf("fread failed\n");
        free(buf85);
        fclose(in);
        return 1;
    }
    fclose(in);

    uint16_t pix[160 * 120];
    int v;

    for (uint8_t y = 0; y < 120; ++y) {
        for (uint8_t x = 0; x < 160; ++x) {
            if (x < 80) {
                v = buf85[2 * (y * 164 + x) + 4] + 256 * buf85[2 * (y * 164 + x) + 5];
            } else {
                v = buf85[2 * (y * 164 + x) + 8] + 256 * buf85[2 * (y * 164 + x) + 9];
            }
            pix[y * 160 + x] = v;
        }
    }

    FILE *out = fopen(argv[2], "wb");
    if (!out) {
        perror("fopen output");
        free(buf85);
        return 1;
    }

    fwrite(pix, 1, sizeof(pix), out);
    fclose(out);

    free(buf85);
    printf("Successfully unpacked thermal data to %s\n", argv[2]);

    return 0;
}
