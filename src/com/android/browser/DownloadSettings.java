/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *         copyright notice, this list of conditions and the following
 *         disclaimer in the documentation and/or other materials provided
 *         with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *         contributors may be used to endorse or promote products derived
 *         from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.browser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.Thread;

public class DownloadSettings extends Activity {

    private EditText downloadFilenameText;
    private EditText downloadPathText;
    private TextView downloadEstimateSize;
    private Button downloadStart;
    private Button downloadCancel;

    private String url;
    private String userAgent;
    private String contentDisposition;
    private String mimetype;
    private String referer;
    private long contentLength;
    private boolean privateBrowsing;
    private String filename;

    private String filenameBase;
    private String filenameExtension;

    private String downloadPath;
    private String downloadPathForUser;

    private final static String LOGTAG = "DownloadSettings";

    private static final String ACTION_FILE_EXPLORER = "com.android.fileexplorer.action.DIR_SEL";
    private static final int PATH_REQUEST_CODE = 0;
    private static final String RESULT_EXTRA_DIR = "result_dir_sel";

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // init the activity view
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.download_settings);
        downloadFilenameText = (EditText) findViewById(R.id.download_filename_edit);
        downloadPathText = (EditText) findViewById(R.id.download_filepath_selected);
        downloadEstimateSize = (TextView) findViewById(R.id.download_estimate_size_content);
        downloadStart = (Button) findViewById(R.id.download_start);
        downloadCancel = (Button) findViewById(R.id.download_cancle);
        downloadPathText.setOnClickListener(downloadPathListener);
        downloadStart.setOnClickListener(downloadStartListener);
        downloadCancel.setOnClickListener(downloadCancelListener);

        // get the bundle from Intent
        Intent intent = getIntent();
        Bundle fileInfo = intent.getExtras();
        url = fileInfo.getString("url");
        userAgent = fileInfo.getString("userAgent");
        contentDisposition = fileInfo.getString("contentDisposition");
        mimetype = fileInfo.getString("mimetype");
        referer = fileInfo.getString("referer");
        contentLength = fileInfo.getLong("contentLength");
        privateBrowsing = fileInfo.getBoolean("privateBrowsing");
        filename = fileInfo.getString("filename");

        // init the text
        filenameBase = MyDownloadHandler.getFilenameBase(filename);
        downloadFilenameText.setText(filenameBase);
        downloadPath = chooseFolderFromMimeType(BrowserSettings.getInstance().getDownloadPath(),
                mimetype);
        downloadPathForUser = MyDownloadHandler.getDownloadPathForUser(DownloadSettings.this,
                downloadPath);
        setDownloadPathText(downloadPathForUser);
        setDownloadFileSizeText();

    }

    private OnClickListener downloadPathListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            // start file explorer for selecting download path
            try {
                Intent intent = new Intent(ACTION_FILE_EXPLORER);
                DownloadSettings.this.startActivityForResult(intent, PATH_REQUEST_CODE);
            } catch (ActivityNotFoundException ex) {
                String err_msg = getString(R.string.activity_not_found,
                        ACTION_FILE_EXPLORER);
                Toast.makeText(DownloadSettings.this, err_msg, Toast.LENGTH_LONG).show();
            }
        }
    };

    private OnClickListener downloadStartListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            // check the filename empty or not
            filenameBase = getFilenameBaseFromUserInput();
            if (filenameBase.length() <= 0) {
                MyDownloadHandler.showFilenameEmptyDialog(DownloadSettings.this);
                return;
            }
            filenameExtension = MyDownloadHandler.getFilenameExtension(filename);
            filename = filenameBase + "." + filenameExtension;

            // check the storage status
            if (!MyDownloadHandler.isStorageStatusOK(DownloadSettings.this, filename,
                    downloadPath)) {
                return;
            }

            // check the download path valid or invalid
            try {
                MyDownloadHandler.checkDownloadPath(downloadPath);
            } catch (IllegalStateException ex) {
                MyDownloadHandler.showInvalidPathDialog(DownloadSettings.this);
                return;
            }

            // check the storage memory enough or not
            boolean isNoEnoughMemory = MyDownloadHandler.isNoEnoughMemory(contentLength,
                    downloadPath);
            if (isNoEnoughMemory) {
                MyDownloadHandler.showNoEnoughMemoryDialog(DownloadSettings.this);
                return;
            }

            // check the download file existing or not
            String fullFilename = downloadPath + "/" + filename;
            if (new File(fullFilename).exists()) {
                MyDownloadHandler.showFileExistDialog(DownloadSettings.this);
                return;
            }

            // start downloading
            MyDownloadHandler.startingDownload(DownloadSettings.this,
                    url, userAgent, contentDisposition,
                    mimetype, referer, privateBrowsing, contentLength,
                    Uri.encode(filename), downloadPath);

            finish();
        }
    };

    private OnClickListener downloadCancelListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            finish();
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (PATH_REQUEST_CODE == requestCode) {
            if (resultCode == Activity.RESULT_OK && intent != null) {
                downloadPath = intent.getStringExtra(RESULT_EXTRA_DIR);
                if (downloadPath != null) {
                    downloadPathForUser = MyDownloadHandler.getDownloadPathForUser(
                            DownloadSettings.this, downloadPath);
                    setDownloadPathText(downloadPathForUser);
                }
            }
        }
    }

    // Add for carrier feature - download into related folders by mimetype.
    private String chooseFolderFromMimeType(String path, String mimeType) {
        String destinationFolder = null;

        if (!path.contains(Environment.DIRECTORY_DOWNLOADS) || null == mimeType)
            return path;

        if (mimeType.startsWith("audio")) {
            destinationFolder = Environment.DIRECTORY_MUSIC;
        } else if (mimeType.startsWith("video")) {
            destinationFolder = Environment.DIRECTORY_MOVIES;
        } else if (mimeType.startsWith("image"))
            destinationFolder = Environment.DIRECTORY_PICTURES;
        if (destinationFolder != null)
            path = path.replace(Environment.DIRECTORY_DOWNLOADS, destinationFolder);

        return path;
    }

    /**
     * show download path for user
     *
     * @param downloadPath the download path user can see
     */
    private void setDownloadPathText(String downloadPathForUser) {
        downloadPathText.setText(downloadPathForUser);
    }

    /**
     * get the filename from user input
     *
     * @return String the filename string from user input
     */
    private String getFilenameBaseFromUserInput() {
        return downloadFilenameText.getText().toString();
    }

    /**
     * show the download file's size text to user
     */
    private void setDownloadFileSizeText() {
        String sizeText;
        if (contentLength <= 0) {
            sizeText = getString(R.string.unknow_length);
        } else {
            sizeText = getDownloadFileSize();
        }
        downloadEstimateSize.setText(sizeText);
    }

    /**
     * get the download file's size and format the value
     *
     * @return String the formatted value
     */
    private String getDownloadFileSize() {
        String currentSizeText = "";
        if (contentLength > 0) {
            currentSizeText = Formatter.formatFileSize(DownloadSettings.this, contentLength);
        }
        return currentSizeText;
    }

}
