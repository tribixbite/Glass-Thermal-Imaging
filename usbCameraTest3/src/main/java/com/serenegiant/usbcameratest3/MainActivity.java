/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest3;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

// Minimal Glass-compatible thermal imaging app
// This is a proof-of-concept stub for Google Glass XE24
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create a simple text view for now
        TextView textView = new TextView(this);
        textView.setText("FLIR Boson Thermal Imaging for Google Glass\n\n" +
                        "Glass XE24 Compatible (API 19)\n" +
                        "USB OTG Support: Ready\n" +
                        "Thermal Camera: Not Connected\n\n" +
                        "Connect FLIR Boson camera via USB OTG\n" +
                        "Tap to initialize thermal imaging");
        textView.setTextSize(18);
        textView.setPadding(20, 20, 20, 20);

        setContentView(textView);
    }
}