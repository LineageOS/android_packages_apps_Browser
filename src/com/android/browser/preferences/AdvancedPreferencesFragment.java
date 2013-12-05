/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.browser.preferences;

import com.android.browser.BrowserActivity;
import com.android.browser.BrowserSettings;
import com.android.browser.MyDownloadHandler;
import com.android.browser.PreferenceKeys;
import com.android.browser.R;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebStorage;
import android.widget.Toast;

import java.util.Map;
import java.util.Set;

public class AdvancedPreferencesFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String ACTION_FILE_EXPLORER = "com.android.fileexplorer.action.DIR_SEL";
    private static final int PATH_REQUEST_CODE = 0;
    private static final String RESULT_EXTRA_DIR = "result_dir_sel";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load the XML preferences file
        addPreferencesFromResource(R.xml.advanced_preferences);

        PreferenceScreen websiteSettings = (PreferenceScreen) findPreference(
                PreferenceKeys.PREF_WEBSITE_SETTINGS);
        websiteSettings.setFragment(WebsiteSettingsFragment.class.getName());

        Preference e = findPreference(PreferenceKeys.PREF_DEFAULT_ZOOM);
        e.setOnPreferenceChangeListener(this);
        e.setSummary(getVisualDefaultZoomName(
                getPreferenceScreen().getSharedPreferences()
                .getString(PreferenceKeys.PREF_DEFAULT_ZOOM, null)) );

        e = findPreference(PreferenceKeys.PREF_DEFAULT_TEXT_ENCODING);
        e.setOnPreferenceChangeListener(this);
        updateListPreferenceSummary((ListPreference) e);

        e = findPreference(PreferenceKeys.PREF_RESET_DEFAULT_PREFERENCES);
        e.setOnPreferenceChangeListener(this);

        e = findPreference(PreferenceKeys.PREF_SEARCH_ENGINE);
        e.setOnPreferenceChangeListener(this);
        updateListPreferenceSummary((ListPreference) e);

        e = findPreference(PreferenceKeys.PREF_PLUGIN_STATE);
        e.setOnPreferenceChangeListener(this);
        updateListPreferenceSummary((ListPreference) e);

        initDownloadSettingsPreference();
    }

    private void initDownloadSettingsPreference() {
        addPreferencesFromResource(R.xml.download_settings_preferences);
        PreferenceScreen downloadPathPref =
                (PreferenceScreen) findPreference(PreferenceKeys.PREF_DOWNLOAD_PATH);
        downloadPathPref.setOnPreferenceClickListener(onClickDownloadPathSettings());

        String downloadPath = downloadPathPref.getSharedPreferences().
                getString(PreferenceKeys.PREF_DOWNLOAD_PATH,
                        BrowserSettings.getInstance().getDownloadPath());
        String downloadPathForUser = MyDownloadHandler.getDownloadPathForUser(this.getActivity(),
                downloadPath);
        downloadPathPref.setSummary(downloadPathForUser);
    }

    private Preference.OnPreferenceClickListener onClickDownloadPathSettings() {
        return new Preference.OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                try {
                    Intent i = new Intent(ACTION_FILE_EXPLORER);
                    AdvancedPreferencesFragment.this.startActivityForResult(i,
                            PATH_REQUEST_CODE);
                } catch (ActivityNotFoundException ex) {
                    String err_msg = getResources().getString(R.string.activity_not_found,
                            ACTION_FILE_EXPLORER);
                    Toast.makeText(getActivity(), err_msg, Toast.LENGTH_LONG).show();
                }
                return true;
            }
        };
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PATH_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                String downloadPath = data.getStringExtra(RESULT_EXTRA_DIR);
                if (downloadPath != null) {
                    PreferenceScreen downloadPathPref =
                            (PreferenceScreen) findPreference(PreferenceKeys.PREF_DOWNLOAD_PATH);
                    Editor editor = downloadPathPref.getEditor();
                    editor.putString(PreferenceKeys.PREF_DOWNLOAD_PATH, downloadPath);
                    editor.apply();
                    String downloadPathForUser = MyDownloadHandler.getDownloadPathForUser(
                            this.getActivity(), downloadPath);
                    downloadPathPref.setSummary(downloadPathForUser);
                }
            }
        }
    }

    void updateListPreferenceSummary(ListPreference e) {
        e.setSummary(e.getEntry());
    }

    /*
     * We need to set the PreferenceScreen state in onResume(), as the number of
     * origins with active features (WebStorage, Geolocation etc) could have
     * changed after calling the WebsiteSettingsActivity.
     */
    @Override
    public void onResume() {
        super.onResume();
        final PreferenceScreen websiteSettings = (PreferenceScreen) findPreference(
                PreferenceKeys.PREF_WEBSITE_SETTINGS);
        websiteSettings.setEnabled(false);
        WebStorage.getInstance().getOrigins(new ValueCallback<Map>() {
            @Override
            public void onReceiveValue(Map webStorageOrigins) {
                if ((webStorageOrigins != null) && !webStorageOrigins.isEmpty()) {
                    websiteSettings.setEnabled(true);
                }
            }
        });
        GeolocationPermissions.getInstance().getOrigins(new ValueCallback<Set<String> >() {
            @Override
            public void onReceiveValue(Set<String> geolocationOrigins) {
                if ((geolocationOrigins != null) && !geolocationOrigins.isEmpty()) {
                    websiteSettings.setEnabled(true);
                }
            }
        });
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object objValue) {
        if (getActivity() == null) {
            // We aren't attached, so don't accept preferences changes from the
            // invisible UI.
            Log.w("PageContentPreferencesFragment", "onPreferenceChange called from detached fragment!");
            return false;
        }

        if (pref.getKey().equals(PreferenceKeys.PREF_DEFAULT_ZOOM)) {
            pref.setSummary(getVisualDefaultZoomName((String) objValue));
            return true;
        } else if (pref.getKey().equals(PreferenceKeys.PREF_DEFAULT_TEXT_ENCODING)) {
            pref.setSummary((String) objValue);
            return true;
        } else if (pref.getKey().equals(PreferenceKeys.PREF_RESET_DEFAULT_PREFERENCES)) {
            Boolean value = (Boolean) objValue;
            if (value.booleanValue() == true) {
                startActivity(new Intent(BrowserActivity.ACTION_RESTART, null,
                        getActivity(), BrowserActivity.class));
                return true;
            }
        } else if (pref.getKey().equals(PreferenceKeys.PREF_PLUGIN_STATE)
                || pref.getKey().equals(PreferenceKeys.PREF_SEARCH_ENGINE)) {
            ListPreference lp = (ListPreference) pref;
            lp.setValue((String) objValue);
            updateListPreferenceSummary(lp);
            return false;
        }
        return false;
    }

    private CharSequence getVisualDefaultZoomName(String enumName) {
        Resources res = getActivity().getResources();
        CharSequence[] visualNames = res.getTextArray(R.array.pref_default_zoom_choices);
        CharSequence[] enumNames = res.getTextArray(R.array.pref_default_zoom_values);

        // Sanity check
        if (visualNames.length != enumNames.length) {
            return "";
        }

        int length = enumNames.length;
        for (int i = 0; i < length; i++) {
            if (enumNames[i].equals(enumName)) {
                return visualNames[i];
            }
        }

        return "";
    }
}
