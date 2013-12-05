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
 * limitations under the License.
 */

package com.android.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaFile;
import android.net.Uri;
import android.net.WebAddress;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.File;

/**
 * Handle download requests
 */
public class MyDownloadHandler {

    private static final boolean LOGD_ENABLED =
            com.android.browser.Browser.LOGD_ENABLED;

    private static final String LOGTAG = "MyDLHandler";

    private static String mInternalStorage;
    private static String mExternalStorage;
    private final static String INVALID_PATH = "/storage";

    public static void startingDownload(Activity activity,
            String url, String userAgent, String contentDisposition,
            String mimetype, String referer, boolean privateBrowsing,
            long contentLength, String filename, String downloadPath) {
        // java.net.URI is a lot stricter than KURL so we have to encode some
        // extra characters. Fix for b 2538060 and b 1634719
        WebAddress webAddress;
        try {
            webAddress = new WebAddress(url);
            webAddress.setPath(encodePath(webAddress.getPath()));
        } catch (Exception e) {
            // This only happens for very bad urls, we want to chatch the
            // exception here
            Log.e(LOGTAG, "Exception trying to parse url:" + url);
            return;
        }

        String addressString = webAddress.toString();
        Uri uri = Uri.parse(addressString);
        final DownloadManager.Request request;
        try {
            request = new DownloadManager.Request(uri);
        } catch (IllegalArgumentException e) {
            Toast.makeText(activity, R.string.cannot_download, Toast.LENGTH_SHORT).show();
            return;
        }
        request.setMimeType(mimetype);
        // set downloaded file destination to /sdcard/Download.
        // or, should it be set to one of several Environment.DIRECTORY* dirs
        // depending on mimetype?
        try {
            setDestinationDir(downloadPath, filename, request);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.cannot_download, Toast.LENGTH_SHORT).show();
            return;
        }
        // let this downloaded file be scanned by MediaScanner - so that it can
        // show up in Gallery app, for example.
        request.allowScanningByMediaScanner();
        request.setDescription(webAddress.getHost());
        // XXX: Have to use the old url since the cookies were stored using the
        // old percent-encoded url.
        String cookies = CookieManager.getInstance().getCookie(url, privateBrowsing);
        request.addRequestHeader("cookie", cookies);
        request.addRequestHeader("User-Agent", userAgent);
        request.addRequestHeader("Referer", referer);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        final DownloadManager manager = (DownloadManager) activity
                .getSystemService(Context.DOWNLOAD_SERVICE);
        new Thread("Browser download") {
            public void run() {
                manager.enqueue(request);
            }
        }.start();

        startDownloadUiActivity(activity);
    }

    /**
     * Notify the host application a download should be done, or that
     * the data should be streamed if a streaming viewer is available.
     * @param activity Activity requesting the download.
     * @param url The full url to the content that should be downloaded
     * @param userAgent User agent of the downloading application.
     * @param contentDisposition Content-disposition http header, if present.
     * @param mimetype The mimetype of the content reported by the server
     * @param referer The referer associated with the downloaded url
     * @param privateBrowsing If the request is coming from a private browsing tab.
     */
    public static void onDownloadStart(Activity activity, String url,
            String userAgent, String contentDisposition, String mimetype,
            String referer, boolean privateBrowsing, long contentLength) {
        // if we're dealing wih A/V content that's not explicitly marked
        //     for download, check if it's streamable.
        if (contentDisposition == null
                || !contentDisposition.regionMatches(
                        true, 0, "attachment", 0, 10)) {
            // query the package manager to see if there's a registered handler
            //     that matches.
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), mimetype);
            ResolveInfo info = activity.getPackageManager().resolveActivity(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                ComponentName myName = activity.getComponentName();
                // If we resolved to ourselves, we don't want to attempt to
                // load the url only to try and download it again.
                if (!myName.getPackageName().equals(
                        info.activityInfo.packageName)
                        || !myName.getClassName().equals(
                                info.activityInfo.name)) {
                    // someone (other than us) knows how to handle this mime
                    // type with this scheme, don't download.
                    try {
                        activity.startActivity(intent);
                        return;
                    } catch (ActivityNotFoundException ex) {
                        if (LOGD_ENABLED) {
                            Log.d(LOGTAG, "activity not found for " + mimetype
                                    + " over " + Uri.parse(url).getScheme(),
                                    ex);
                        }
                        // Best behavior is to fall back to a download in this
                        // case
                    }
                }
            }
        }
        onDownloadStartNoStream(activity, url, userAgent, contentDisposition,
                mimetype, referer, privateBrowsing, contentLength);
    }

    // This is to work around the fact that java.net.URI throws Exceptions
    // instead of just encoding URL's properly
    // Helper method for onDownloadStartNoStream
    private static String encodePath(String path) {
        char[] chars = path.toCharArray();

        boolean needed = false;
        for (char c : chars) {
            if (c == '[' || c == ']' || c == '|') {
                needed = true;
                break;
            }
        }
        if (needed == false) {
            return path;
        }

        StringBuilder sb = new StringBuilder("");
        for (char c : chars) {
            if (c == '[' || c == ']' || c == '|') {
                sb.append('%');
                sb.append(Integer.toHexString(c));
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /**
     * Notify the host application a download should be done, even if there
     * is a streaming viewer available for this type.
     * @param activity Activity requesting the download.
     * @param url The full url to the content that should be downloaded
     * @param userAgent User agent of the downloading application.
     * @param contentDisposition Content-disposition http header, if present.
     * @param mimetype The mimetype of the content reported by the server
     * @param referer The referer associated with the downloaded url
     * @param privateBrowsing If the request is coming from a private browsing tab.
     */
    /* package */static void onDownloadStartNoStream(Activity activity,
            String url, String userAgent, String contentDisposition, String mimetype,
            String referer, boolean privateBrowsing, long contentLength) {

        initStorageDefaultPath(activity);
        String filename = URLUtil.guessFileName(url,
                contentDisposition, mimetype);

        if (mimetype == null) {
            // We must have long pressed on a link or image to download it. We
            // are not sure of the mimetype in this case, so do a head request
            new MyFetchUrlMimeType(activity, url, userAgent, referer,
                    privateBrowsing, filename).start();
        } else {
            startDownloadSettings(activity, url, userAgent, contentDisposition, mimetype,
                    referer, privateBrowsing, contentLength, filename);
        }

    }

    private static void initStorageDefaultPath(Context context) {
        mInternalStorage = Environment.getExternalStorageDirectory().getPath();
        if (isSecondStorageSupported()) {
            mExternalStorage = getExternalStorageDirectory(context);
        } else {
            mExternalStorage = null;
        }
    }

    public static void startDownloadSettings(Activity activity,
            String url, String userAgent, String contentDisposition,
            String mimetype, String referer, boolean privateBrowsing,
            long contentLength, String filename) {

        Bundle fileInfo = new Bundle();
        fileInfo.putString("url", url);
        fileInfo.putString("userAgent", userAgent);
        fileInfo.putString("contentDisposition", contentDisposition);
        fileInfo.putString("mimetype", mimetype);
        fileInfo.putString("referer", referer);
        fileInfo.putBoolean("privateBrowsing", privateBrowsing);
        fileInfo.putLong("contentLength", contentLength);
        fileInfo.putString("filename", filename);

        Intent intent = new Intent("android.intent.action.BROWSER_DOWNLOAD");
        intent.putExtras(fileInfo);
        activity.startActivity(intent);
    }

    public static void showInvalidPathDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.path_wrong)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(R.string.invalid_path)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    public static void checkDownloadPath(String downloadPath) {
        if (downloadPath.equals(INVALID_PATH)) {
            throw new IllegalStateException(downloadPath +
                    " is an invalid directory");

        }

        File file = new File(downloadPath);
        if (file.exists()) {
            if (!file.isDirectory()) {
                throw new IllegalStateException(file.getAbsolutePath() +
                        " already exists and is not a directory");
            }
        } else {
            if (!file.mkdir()) {
                throw new IllegalStateException("Unable to create directory: " +
                        file.getAbsolutePath());
            }
        }
    }

    private static void setDestinationDir(String downloadPath, String filename, Request request) {
        checkDownloadPath(downloadPath);
        if (filename == null) {
            throw new NullPointerException("filename cannot be null");
        }
        File file = new File(downloadPath);
        request.setDestinationUri(Uri.withAppendedPath(Uri.fromFile(file), filename));
    }

    public static void showFileExistDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.download_file_exist)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setMessage(R.string.download_file_exist_msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static long getAvailableMemory(String root) {
        StatFs stat = new StatFs(root);
        final long LEFT_BYTES = 256 * 1024;  // 256KB
        long availableBytes = stat.getAvailableBytes() - LEFT_BYTES;
        return availableBytes;
    }

    public static void showNoEnoughMemoryDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.download_no_enough_memory)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setMessage(R.string.download_no_enough_memory)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    public static boolean isNoEnoughMemory(long contentLength, String root) {
        long mAvailableBytes = getAvailableMemory(root);
        if (mAvailableBytes <= 0 || contentLength > mAvailableBytes) {
            return true;
        }
        return false;
    }

    private static void startDownloadUiActivity(Activity activity) {
        Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        activity.startActivity(intent);
        Toast.makeText(activity, R.string.download_pending, Toast.LENGTH_SHORT)
                .show();
    }

    /**
     * Whether the storage status is OK for download file.
     *
     * @param activity
     * @param filename the download file's name
     * @param downloadPath the download file's path
     * @return boolean true is ok, and false is not
     */
    public static boolean isStorageStatusOK(Activity activity, String filename,
            String downloadPath) {
        if (isSecondStorageSupported() && downloadPath.startsWith(mExternalStorage)) {
            String status = getExternalStorageState(activity);
            if (!status.equals(Environment.MEDIA_MOUNTED)) {
                int title;
                String msg;

                // Check to see if the SDCard is busy, same as the music app
                if (status.equals(Environment.MEDIA_SHARED)) {
                    msg = activity.getString(R.string.download_sdcard_busy_dlg_msg);
                    title = R.string.download_sdcard_busy_dlg_title;
                } else {
                    msg = activity.getString(R.string.download_no_sdcard_dlg_msg, filename);
                    title = R.string.download_no_sdcard_dlg_title;
                }

                new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(msg)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return false;
            }
        } else if (downloadPath.startsWith(mInternalStorage)) {
            String status = Environment.getExternalStorageState();
            if (!status.equals(Environment.MEDIA_MOUNTED)) {
                int title = R.string.download_path_unavailable_dlg_title;
                String msg = activity.getString(R.string.download_path_unavailable_dlg_msg);
                new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(msg)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return false;
            }
        }
        return true;
    }

    /**
     * Whether support the second storage
     *
     * @return boolean
     */
    private static boolean isSecondStorageSupported() {
        return true;
    }

    /**
     * Show dialog to warn filename is empty
     *
     * @param activity
     */
    public static void showFilenameEmptyDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.filename_empty_title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(R.string.filename_empty_msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /**
     * Get the filename except the suffix and dot
     *
     * @return String the filename except suffix and dot
     */
    public static String getFilenameBase(String filename) {
        int dotindex = filename.lastIndexOf('.');
        if (dotindex != -1) {
            return filename.substring(0, dotindex);
        } else {
            return filename;
        }
    }

    /**
     * Get the filename's extension from filename
     *
     * @param filename the download filename, may be the user entered
     * @return String the filename's extension
     */
    public static String getFilenameExtension(String filename) {
        int dotindex = filename.lastIndexOf('.');
        if (dotindex != -1) {
            return filename.substring(dotindex + 1);
        } else {
            return "";
        }
    }

    public static String getDefaultDownloadPath(Context context) {
        String defaultStorage = Environment.getExternalStorageDirectory().getPath();
        String defaultDownloadPath = defaultStorage + context.getString(
                R.string.download_default_path);
        return defaultDownloadPath;
    }

    /**
     * Translate the directory name so that it's easy to read for user
     *
     * @param activity
     * @param downloadPath
     * @return String
     */
    public static String getDownloadPathForUser(Activity activity, String downloadPath) {
        if (downloadPath == null) {
            return "";
        }

        final String internalStorageDir = Environment.getExternalStorageDirectory().getPath();
        String externalStorageDir = null;
        if (isSecondStorageSupported()) {
            externalStorageDir = getExternalStorageDirectory(activity);
        }

        if (downloadPath.startsWith(internalStorageDir)) {
            String internalLabel = activity.getResources().getString(
                    R.string.download_path_phone_storage_label);
            downloadPath = downloadPath.replace(internalStorageDir, internalLabel);
        } else if ((externalStorageDir != null) && downloadPath.startsWith(externalStorageDir)) {
            String externalLabel = activity.getResources().getString(
                    R.string.download_path_sd_card_label);
            downloadPath = downloadPath.replace(externalStorageDir, externalLabel);
        }

        return downloadPath;
    }

    private static String getExternalStorageDirectory(Context context) {
        String sdCardDir = null;
        StorageManager mStorageManager = (StorageManager) context
                .getSystemService(Context.STORAGE_SERVICE);
        StorageVolume[] volumes = mStorageManager.getVolumeList();
        for (int i = 0; i < volumes.length; i++) {
            if (volumes[i].isRemovable() && volumes[i].allowMassStorage()) {
                sdCardDir = volumes[i].getPath();
                break;
            }
        }
        return sdCardDir;
    }

    private static String getExternalStorageState(Context context) {
        StorageManager mStorageManager = (StorageManager) context
                .getSystemService(Context.STORAGE_SERVICE);
        return mStorageManager.getVolumeState(getExternalStorageDirectory(context));
    }
}
