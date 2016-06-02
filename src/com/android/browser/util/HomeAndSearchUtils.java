/*
 * Copyright (C) 2015 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.browser.util;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class HomeAndSearchUtils {
    private static final String TAG = HomeAndSearchUtils.class.getSimpleName();
    public static final String SYSPROP_USER_AGENT_OVERRIDE = "ro.browser.user_agent_override";

    public static String getSystemProperty(String key, String defaultValue) {
        String returnValue = defaultValue;
        try {
            Process process = Runtime.getRuntime().exec("getprop " + key);
            BufferedReader bufferedReader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));
            StringBuilder outputBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                outputBuilder.append(line + "\n");
            }
            String value = outputBuilder.toString().trim();
            if (!TextUtils.isEmpty(value)) {
                returnValue = value;
            }
        } catch (IOException e) {
            Log.w(TAG, "Error reading system property: " + key);
        }
        return returnValue;
    }

    public static String getSyspropUserAgentOverride() {
        return getSystemProperty(SYSPROP_USER_AGENT_OVERRIDE, null);
    }

}
