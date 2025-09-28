#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

// Structure for a PPM pixel
struct ppm_pixel {
    uint8_t r, g, b;
};

// Function to apply the Iron palette
struct ppm_pixel apply_iron_palette(int value) {
    struct ppm_pixel p;
    int r, g, b;
    if (value < 85) {
        r = 0;
        g = 0;
        b = value * 3;
    } else if (value < 170) {
        r = (value - 85) * 3;
        g = 0;
        b = 255 - ((value - 85) * 3);
    } else {
        r = 255;
        g = (value - 170) * 3;
        b = 0;
    }
    p.r = (uint8_t)(r > 255 ? 255 : r);
    p.g = (uint8_t)(g > 255 ? 255 : g);
    p.b = (uint8_t)(b > 255 ? 255 : b);
    return p;
}

int main(int argc, char *argv[]) {
    if (argc < 3) {
        printf("Usage: %s <input_raw_file> <output_ppm_file>\n", argv[0]);
        return 1;
    }

    char *input_filename = argv[1];
    char *output_filename = argv[2];

    FILE *input_file = fopen(input_filename, "rb");
    if (!input_file) {
        perror("Failed to open input file");
        return 1;
    }

    fseek(input_file, 0, SEEK_END);
    long file_size = ftell(input_file);
    fseek(input_file, 0, SEEK_SET);

    int width = 160;
    int height = 120;
    int num_pixels = width * height;
    long data_size = num_pixels * 2;

    if (file_size < data_size) {
        printf("Input file is too small for 160x120 resolution\n");
        fclose(input_file);
        return 1;
    }

    uint16_t *thermal_data = malloc(data_size);
    if (!thermal_data) {
        printf("Failed to allocate memory\n");
        fclose(input_file);
        return 1;
    }

    if (fread(thermal_data, 1, data_size, input_file) != data_size) {
        printf("Failed to read thermal data\n");
        free(thermal_data);
        fclose(input_file);
        return 1;
    }

    fclose(input_file);

    uint16_t min_val = 65535;
    uint16_t max_val = 0;
    for (int i = 0; i < num_pixels; i++) {
        if (thermal_data[i] < min_val) min_val = thermal_data[i];
        if (thermal_data[i] > max_val) max_val = thermal_data[i];
    }

    uint16_t range = max_val - min_val;
    if (range == 0) range = 1;

    struct ppm_pixel *ppm_data = malloc(num_pixels * sizeof(struct ppm_pixel));
    if (!ppm_data) {
        printf("Failed to allocate memory for PGM data\n");
        free(thermal_data);
        return 1;
    }

    for (int i = 0; i < num_pixels; i++) {
        int scaled_value = ((thermal_data[i] - min_val) * 255) / range;
        ppm_data[i] = apply_iron_palette(scaled_value);
    }

    FILE *output_file = fopen(output_filename, "wb");
    if (!output_file) {
        perror("Failed to open output file");
        free(thermal_data);
        free(ppm_data);
        return 1;
    }

    fprintf(output_file, "P6\n%d %d\n255\n", width, height);
    fwrite(ppm_data, sizeof(struct ppm_pixel), num_pixels, output_file);

    fclose(output_file);
    free(thermal_data);
    free(ppm_data);

    printf("Successfully converted %s to %s\n", input_filename, output_filename);

    return 0;
}