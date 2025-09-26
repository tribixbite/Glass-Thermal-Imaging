# Google Glass Thermal Camera App TODO List

This document outlines the tasks required to successfully build the UVC-Boson project into a functional thermal imaging application for Google Glass XE.

## Phase 1: Core Functionality & Build Fixes

These are the essential tasks to get a basic version of the app building and running.

- [x] **Enable and Verify NDK Build:**
    - In `libuvccamera/build.gradle`, re-enable the `ndkBuild` task.
    - This is critical for compiling the JNI code that communicates with the USB camera.
    - **Action:** Uncomment the `dependsOn ndkBuild` and `dependsOn ndkClean` lines.
    - **Verify:** Ensure you have the Android NDK installed and the `ndk.dir` property is correctly set in your `local.properties` file.

- [x] **Implement GDK Voice Trigger:**
    - Allow the app to be launched with a voice command (e.g., "Okay Glass, start thermal camera").
    - **Action:**
        1. Modify `usbCameraTest3/src/main/AndroidManifest.xml` to add the `VOICE_TRIGGER` intent filter to the `MainActivity`.
        2. Create a `res/xml/voice_trigger.xml` file in `usbCameraTest3` to define the keyword.

- [x] **Integrate GDK Gesture Detector:**
    - Replace the standard `android.view.GestureDetector` with the `com.google.android.glass.touchpad.GestureDetector`.
    - This ensures reliable handling of Glass-specific gestures.
    - **Action:**
        1. Update `MainActivity.java` to use the GDK `GestureDetector`.
        2. Map gestures to actions:
            - `TAP`: Toggle settings menu (once created).
            - `TWO_TAP`: Take a picture.
            - `SWIPE_RIGHT` / `SWIPE_LEFT`: Navigate settings menu.
            - `SWIPE_DOWN`: Exit the application (`finish()`).

- [x] **Add GPS Location Permission and Implementation:**
    - Add GPS metadata to captured images and videos.
    - **Action:**
        1. Add the `ACCESS_FINE_LOCATION` permission to `AndroidManifest.xml`.
        2. In `MainActivity.java`, implement `LocationManager` to get the current GPS coordinates.
        3. Update `saveRadiometricData()` to include GPS coordinates in the metadata file.

## Phase 2: Feature Implementation

With the core app running, these tasks add the required features.

- [x] **Implement Video Recording:**
    - Add the ability to record video from the thermal camera.
    - **Action:**
        1. In `MainActivity.java`, add a method `startRecording()` and `stopRecording()`.
        2. Use the `mUVCCamera.startCapture()` and `mUVCCamera.stopCapture()` methods.
        3. Add a gesture or menu item to trigger recording.
        4. Save the video file to the capture directory, including metadata.

- [x] **Create a Glass-Style Settings Menu:**
    - Create a menu for changing settings, navigable with swipe gestures.
    - **Action:**
        1. Create a `MenuActivity.java` that is launched from `MainActivity`.
        2. Use the GDK `CardScrollView` and a `CardScrollAdapter` to create a list of settings.
        3. Implement menu items for:
            - Toggling thermal mode.
            - Cycling through color palettes.
            - Toggling GPS metadata.
            - Starting/stopping video recording.

- [x] **Implement a Live Card:**
    - Show the status of the camera on the Glass timeline when the app is not in the foreground.
    - **Action:**
        1. Create a `LiveCardService.java` that extends `Service`.
        2. Use the `LiveCard` API to publish a card.
        3. Update the card to show "Camera Connected" or "Camera Disconnected" based on USB events.
        4. Set the card's action to open the `MainActivity`.

## Phase 3: UI/UX Polish and Refinements

These tasks improve the user experience and robustness of the app.

- [x] **Refine UI for Glass:**
    - Ensure all UI elements are optimized for the Glass display (640x360).
    - **Action:**
        1. Use the GDK `CardBuilder` for displaying text and status information where appropriate.
        2. Ensure text is large and clear (Roboto font, as recommended in docs).
        3. Provide clear visual feedback for all actions (e.g., "Picture Saved", "Recording Started").

- [ ] **Improve Error Handling:**
    - Make the app more robust to errors (e.g., camera disconnects, storage full).
    - **Action:**
        1. Add checks for storage space before saving files.
        2. Provide user-friendly error messages on the Glass display.
        3. Handle cases where the camera returns invalid data.

- [ ] **Review and Refactor Code:**
    - Clean up the codebase and ensure it follows best practices.
    - **Action:**
        1. Remove unused code and variables.
        2. Add comments where the logic is complex.
        3. Ensure the app correctly handles the Android Activity lifecycle.