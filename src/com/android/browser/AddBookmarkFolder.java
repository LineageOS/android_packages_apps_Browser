/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.AsyncTaskLoader;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BrowserContract;
import android.provider.BrowserContract.Accounts;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.browser.addbookmark.FolderSpinner;
import com.android.browser.addbookmark.FolderSpinnerAdapter;

public class AddBookmarkFolder extends Activity implements View.OnClickListener,
        TextView.OnEditorActionListener, AdapterView.OnItemClickListener,
        LoaderManager.LoaderCallbacks<Cursor>, BreadCrumbView.Controller,
        FolderSpinner.OnSetSelectionListener, OnItemSelectedListener {

    public static final long DEFAULT_FOLDER_ID = -1;

    private static final int MAX_CRUMBS_SHOWN = 2;

    private boolean mIsFolderChanged = false;
    private boolean mIsOtherFolderSelected = false;
    private boolean mIsRecentFolder = false;

    // IDs for the CursorLoaders that are used.
    private static final int LOADER_ID_ACCOUNTS = 0;
    private static final int LOADER_ID_FOLDER_CONTENTS = 1;
    private static final int LOADER_ID_EDIT_INFO = 2;

    private EditText mTitle;
    private TextView mButton;
    private View mCancelButton;
    private Bundle mMap;
    private FolderSpinner mFolder;
    private View mDefaultView;
    private View mFolderSelector;
    private EditText mFolderNamer;
    private View mFolderCancel;
    private boolean mIsFolderNamerShowing;
    private View mFolderNamerHolder;
    private View mAddNewFolder;
    private View mAddSeparator;
    private long mCurrentFolder;
    private FolderAdapter mAdapter;
    private BreadCrumbView mCrumbs;
    private TextView mFakeTitle;
    private View mCrumbHolder;
    private AddBookmarkPage.CustomListView mListView;
    private long mRootFolder;
    private TextView mTopLevelLabel;
    private Drawable mHeaderIcon;
    private View mRemoveLink;
    private View mFakeTitleHolder;
    private FolderSpinnerAdapter mFolderAdapter;
    private Spinner mAccountSpinner;
    private ArrayAdapter<BookmarkAccount> mAccountAdapter;

    private static class Folder {
        String mName;
        long mId;

        Folder(String name, long id) {
            mName = name;
            mId = id;
        }
    }

    private InputMethodManager getInputMethodManager() {
        return (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    }

    private Uri getUriForFolder(long folder) {
        BookmarkAccount account = (BookmarkAccount) mAccountSpinner.getSelectedItem();
        if (folder == mRootFolder && account != null) {
            return BookmarksLoader.addAccount(BrowserContract.Bookmarks.CONTENT_URI_DEFAULT_FOLDER,
                    account.mAccountType, account.mAccountName);
        }
        return BrowserContract.Bookmarks.buildFolderUri(folder);
    }

    @Override
    public void onTop(BreadCrumbView view, int level, Object data) {
        if (null == data) {
            return;
        }
        Folder folderData = (Folder) data;
        long folder = folderData.mId;
        LoaderManager manager = getLoaderManager();
        CursorLoader loader = (CursorLoader) ((Loader<?>) manager
                .getLoader(LOADER_ID_FOLDER_CONTENTS));
        loader.setUri(getUriForFolder(folder));
        loader.forceLoad();
        if (mIsFolderNamerShowing) {
            completeOrCancelFolderNaming(true);
        }
        setShowBookmarkIcon(level == 1);
    }

    /**
     * Show or hide the icon for bookmarks next to "Bookmarks" in the crumb
     * view.
     *
     * @param show True if the icon should visible, false otherwise.
     */
    private void setShowBookmarkIcon(boolean show) {
        Drawable drawable = show ? mHeaderIcon : null;
        mTopLevelLabel.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (v == mFolderNamer) {
            if (v.getText().length() > 0) {
                if (actionId == EditorInfo.IME_NULL) {
                    // Only want to do this once.
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        completeOrCancelFolderNaming(false);
                    }
                }
            }
            // Steal the key press; otherwise a newline will be added
            return true;
        }
        return false;
    }

    private void switchToDefaultView(boolean changedFolder) {
        mFolderSelector.setVisibility(View.GONE);
        mDefaultView.setVisibility(View.VISIBLE);
        mCrumbHolder.setVisibility(View.GONE);
        mFakeTitleHolder.setVisibility(View.VISIBLE);
        if (changedFolder) {
            Object data = mCrumbs.getTopData();
            if (data != null) {
                Folder folder = (Folder) data;
                mCurrentFolder = folder.mId;
                if (mCurrentFolder == mRootFolder) {
                    // The Spinner changed to show "Other folder ..." Change
                    // it back to "Bookmarks", which is position 0 if we are
                    // editing a folder, 1 otherwise.
                    mFolder.setSelectionIgnoringSelectionChange(0);
                } else {
                    mFolderAdapter.setOtherFolderDisplayText(folder.mName);
                }
            }
        } else {
            if (mCurrentFolder == mRootFolder) {
                mFolder.setSelectionIgnoringSelectionChange(0);
            } else {
                Object data = mCrumbs.getTopData();
                if (data != null && ((Folder) data).mId == mCurrentFolder) {
                    // We are showing the correct folder hierarchy. The
                    // folder selector will say "Other folder..." Change it
                    // to say the name of the folder once again.
                    mFolderAdapter.setOtherFolderDisplayText(((Folder) data).mName);
                } else {
                    // We are not showing the correct folder hierarchy.
                    // Clear the Crumbs and find the proper folder
                    setupTopCrumb();
                    LoaderManager manager = getLoaderManager();
                    manager.restartLoader(LOADER_ID_FOLDER_CONTENTS, null, this);

                }
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mButton) {
            if (mFolderSelector.getVisibility() == View.VISIBLE) {
                // We are showing the folder selector.
                if (mIsFolderNamerShowing) {
                    completeOrCancelFolderNaming(false);
                } else {
                    switchToDefaultView(true);
                }
            } else {
                checkDupeAndSave();
            }
        } else if (v == mCancelButton) {
            if (mIsFolderNamerShowing) {
                completeOrCancelFolderNaming(true);
            } else if (mFolderSelector.getVisibility() == View.VISIBLE) {
                switchToDefaultView(false);
            } else {
                finish();
            }
        } else if (v == mFolderCancel) {
            completeOrCancelFolderNaming(true);
        }
    }

    @Override
    public void onSetSelection(long id) {
        int intId = (int) id;
        mIsFolderChanged = true;
        mIsOtherFolderSelected = false;
        mIsRecentFolder = false;
        switch (intId) {
            case FolderSpinnerAdapter.ROOT_FOLDER:
                mCurrentFolder = mRootFolder;
                break;
            case FolderSpinnerAdapter.OTHER_FOLDER:
                mIsOtherFolderSelected = true;
                switchToFolderSelector();
                break;
            case FolderSpinnerAdapter.RECENT_FOLDER:
                mCurrentFolder = mFolderAdapter.recentFolderId();
                mIsRecentFolder = true;
                // In case the user decides to select OTHER_FOLDER
                // and choose a different one, so that we will start from
                // the correct place.
                LoaderManager manager = getLoaderManager();
                manager.restartLoader(LOADER_ID_FOLDER_CONTENTS, null, this);
                break;
            default:
                break;
        }
    }

    /**
     * Finish naming a folder, and close the IME
     *
     * @param cancel If true, the new folder is not created. If false, the new
     *            folder is created and the user is taken inside it.
     */
    private void completeOrCancelFolderNaming(boolean cancel) {
        if (!cancel && !TextUtils.isEmpty(mFolderNamer.getText())) {
            String name = mFolderNamer.getText().toString();
            long id = addFolderToCurrent(mFolderNamer.getText().toString());
            descendInto(name, id);
        }
        setShowFolderNamer(false);
        getInputMethodManager().hideSoftInputFromWindow(mListView.getWindowToken(), 0);
    }

    private long addFolderToCurrent(String name) {
        // Add the folder to the database
        ContentValues values = new ContentValues();
        values.put(BrowserContract.Bookmarks.TITLE,
                name);
        values.put(BrowserContract.Bookmarks.IS_FOLDER, 1);
        long currentFolder;
        Object data = mCrumbs.getTopData();
        if (data != null) {
            currentFolder = ((Folder) data).mId;
        } else {
            currentFolder = mRootFolder;
        }
        values.put(BrowserContract.Bookmarks.PARENT, currentFolder);
        Uri uri = getContentResolver().insert(
                BrowserContract.Bookmarks.CONTENT_URI, values);
        if (uri != null) {
            return ContentUris.parseId(uri);
        } else {
            return -1;
        }
    }


    private void switchToFolderSelector() {
        // Set the list to the top in case it is scrolled.
        mListView.setSelection(0);
        mFakeTitleHolder.setVisibility(View.GONE);
        mDefaultView.setVisibility(View.GONE);
        mFolderSelector.setVisibility(View.VISIBLE);
        mCrumbHolder.setVisibility(View.VISIBLE);
        getInputMethodManager().hideSoftInputFromWindow(mListView.getWindowToken(), 0);
    }

    private void descendInto(String foldername, long id) {
        if (id != DEFAULT_FOLDER_ID) {
            mCrumbs.pushView(foldername, new Folder(foldername, id));
            mCrumbs.notifyController();
        }
    }

    private LoaderCallbacks<EditBookmarkInfo> mEditInfoLoaderCallbacks = new LoaderCallbacks<EditBookmarkInfo>() {

        @Override
        public void onLoaderReset(Loader<EditBookmarkInfo> loader) {
            // Don't care
        }

        @Override
        public void onLoadFinished(Loader<EditBookmarkInfo> loader, EditBookmarkInfo info) {
            boolean setAccount = false;

            // Display the parent title if can get parent id from intent bundle.
            if (info.mParentId != -1) {
                mFolderAdapter.setOtherFolderDisplayText(info.mParentTitle);
                setAccount = true;
                setAccount(info.mAccountName, info.mAccountType);
                mCurrentFolder = info.mParentId;
                onCurrentFolderFound();
            }

            // TODO: Detect if lastUsedId is a subfolder of info.id in the
            // editing folder case. For now, just don't show the last used
            // folder at all to prevent any chance of the user adding a parent
            // folder to a child folder
            if (info.mLastUsedId != -1 && info.mLastUsedId != info.mId) {
                if (setAccount && info.mLastUsedId != mRootFolder
                        && TextUtils.equals(info.mLastUsedAccountName, info.mAccountName)
                        && TextUtils.equals(info.mLastUsedAccountType, info.mAccountType)) {
                    mFolderAdapter.addRecentFolder(info.mLastUsedId, info.mLastUsedTitle);
                } else if (!setAccount) {
                    setAccount = true;
                    setAccount(info.mLastUsedAccountName, info.mLastUsedAccountType);
                    if (info.mLastUsedId != mRootFolder) {
                        mFolderAdapter.addRecentFolder(info.mLastUsedId, info.mLastUsedTitle);
                    }
                }
            }
            if (!setAccount) {
                mAccountSpinner.setSelection(0);
            }
        }

        @Override
        public Loader<EditBookmarkInfo> onCreateLoader(int id, Bundle args) {
            return new EditBookmarkInfoLoader(AddBookmarkFolder.this, mMap);
        }
    };

    void setAccount(String accountName, String accountType) {
        int count = mAccountAdapter.getCount();
        for (int i = 0; i < count; i++) {
            BookmarkAccount account = mAccountAdapter.getItem(i);
            if (TextUtils.equals(account.mAccountName, accountName)
                    && TextUtils.equals(account.mAccountType, accountType)) {
                mAccountSpinner.setSelection(i);
                onRootFolderFound(account.rootFolderId);
                return;
            }
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] projection;
        switch (id) {
            case LOADER_ID_ACCOUNTS:
                return new AccountsLoader(this);
            case LOADER_ID_FOLDER_CONTENTS:
                projection = new String[] {
                        BrowserContract.Bookmarks._ID, BrowserContract.Bookmarks.TITLE,
                        BrowserContract.Bookmarks.IS_FOLDER
                };
                String where = BrowserContract.Bookmarks.IS_FOLDER + " != 0" + " AND "
                        + BrowserContract.Bookmarks._ID + " != ?";
                String whereArgs[] = new String[] {
                        Long.toString(mMap.getLong(BrowserContract.Bookmarks._ID))
                };
                long currentFolder;
                Object data = mCrumbs.getTopData();
                if (data != null) {
                    currentFolder = ((Folder) data).mId;
                } else {
                    currentFolder = mRootFolder;
                }
                return new CursorLoader(this, getUriForFolder(currentFolder), projection, where,
                        whereArgs, BrowserContract.Bookmarks._ID + " ASC");
            default:
                throw new AssertionError("Asking for nonexistant loader!");
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        switch (loader.getId()) {
            case LOADER_ID_ACCOUNTS:
                mAccountAdapter.clear();
                while (cursor.moveToNext()) {
                    mAccountAdapter.add(new BookmarkAccount(this, cursor));
                }
                getLoaderManager().destroyLoader(LOADER_ID_ACCOUNTS);
                getLoaderManager().restartLoader(LOADER_ID_EDIT_INFO, null,
                        mEditInfoLoaderCallbacks);
                break;
            case LOADER_ID_FOLDER_CONTENTS:
                mAdapter.changeCursor(cursor);
                break;
            default:
                break;
        }
    }

    public void onLoaderReset(Loader<Cursor> loader) {
        switch (loader.getId()) {
            case LOADER_ID_FOLDER_CONTENTS:
                mAdapter.changeCursor(null);
                break;
            default:
                break;
        }
    }

    /**
     * Move cursor to the position that has folderToFind as its "_id".
     *
     * @param cursor Cursor containing folders in the bookmarks database
     * @param folderToFind "_id" of the folder to move to.
     * @param idIndex Index in cursor of "_id"
     * @throws AssertionError if cursor is empty or there is no row with
     *             folderToFind as its "_id".
     */
    void moveCursorToFolder(Cursor cursor, long folderToFind, int idIndex) throws AssertionError {
        if (!cursor.moveToFirst()) {
            throw new AssertionError("No folders in the database!");
        }
        long folder;
        do {
            folder = cursor.getLong(idIndex);
        } while (folder != folderToFind && cursor.moveToNext());
        if (cursor.isAfterLast()) {
            throw new AssertionError("Folder(id=" + folderToFind
                    + ") holding this bookmark does not exist!");
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TextView tv = (TextView) view.findViewById(android.R.id.text1);
        // Switch to the folder that was clicked on.
        descendInto(tv.getText().toString(), id);
    }

    private void setShowFolderNamer(boolean show) {
        if (show != mIsFolderNamerShowing) {
            mIsFolderNamerShowing = show;
            if (show) {
                // Set the selection to the folder namer so it will be in
                // view.
                mListView.addFooterView(mFolderNamerHolder);
            } else {
                mListView.removeFooterView(mFolderNamerHolder);
            }
            // Refresh the list.
            mListView.setAdapter(mAdapter);
            if (show) {
                mListView.setSelection(mListView.getCount() - 1);
            }
        }
    }

    /**
     * Shows a list of names of folders.
     */
    private class FolderAdapter extends CursorAdapter {
        public FolderAdapter(Context context) {
            super(context, null);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            ((TextView) view.findViewById(android.R.id.text1)).setText(cursor.getString(cursor
                    .getColumnIndexOrThrow(BrowserContract.Bookmarks.TITLE)));
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View view = LayoutInflater.from(context).inflate(R.layout.folder_list_item, null);
            view.setBackgroundDrawable(context.getResources().getDrawable(
                    android.R.drawable.list_selector_background));
            return view;
        }

        @Override
        public boolean isEmpty() {
            // Do not show the empty view if the user is creating a new folder.
            return super.isEmpty() && !mIsFolderNamerShowing;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mMap = getIntent().getExtras();

        setContentView(R.layout.browser_add_bookmark);

        Window window = getWindow();

        String title = this.getString(R.string.new_folder);
        mFakeTitle = (TextView) findViewById(R.id.fake_title);
        mFakeTitleHolder = findViewById(R.id.title_holder);
        mFakeTitle.setText(title);

        mTitle = (EditText) findViewById(R.id.title);
        mTitle.setText(title);

        findViewById(R.id.row_address).setVisibility(View.GONE);

        mButton = (TextView) findViewById(R.id.OK);
        mButton.setOnClickListener(this);

        mCancelButton = findViewById(R.id.cancel);
        mCancelButton.setOnClickListener(this);

        mFolder = (FolderSpinner) findViewById(R.id.folder);
        mFolderAdapter = new FolderSpinnerAdapter(this, false);
        mFolder.setAdapter(mFolderAdapter);
        mFolder.setOnSetSelectionListener(this);

        mDefaultView = findViewById(R.id.default_view);
        mFolderSelector = findViewById(R.id.folder_selector);

        mFolderNamerHolder = getLayoutInflater().inflate(R.layout.new_folder_layout, null);
        mFolderNamer = (EditText) mFolderNamerHolder.findViewById(R.id.folder_namer);
        mFolderNamer.setOnEditorActionListener(this);
        setShowFolderNamer(false);
        mFolderCancel = mFolderNamerHolder.findViewById(R.id.close);
        mFolderCancel.setOnClickListener(this);

        // Hide the "add_new_folder" view for it's doing the same thing as this class does.
        mAddNewFolder = findViewById(R.id.add_new_folder);
        mAddNewFolder.setVisibility(View.GONE);
        mAddSeparator = findViewById(R.id.add_divider);
        mAddSeparator.setVisibility(View.GONE);

        mCrumbs = (BreadCrumbView) findViewById(R.id.crumbs);
        mCrumbs.setUseBackButton(true);
        mCrumbs.setController(this);
        mHeaderIcon = getResources().getDrawable(R.drawable.ic_folder_holo_dark);
        mCrumbHolder = findViewById(R.id.crumb_holder);
        mCrumbs.setMaxVisible(MAX_CRUMBS_SHOWN);

        mAdapter = new FolderAdapter(this);
        mListView = (AddBookmarkPage.CustomListView) findViewById(R.id.list);
        View empty = findViewById(R.id.empty);
        mListView.setEmptyView(empty);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(this);
        //mListView.addEditText(mFolderNamer);

        mAccountAdapter = new ArrayAdapter<BookmarkAccount>(this,
                android.R.layout.simple_spinner_item);
        mAccountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAccountSpinner = (Spinner) findViewById(R.id.accounts);
        mAccountSpinner.setAdapter(mAccountAdapter);
        mAccountSpinner.setOnItemSelectedListener(this);

        if (!window.getDecorView().isInTouchMode()) {
            mButton.requestFocus();
        }

        getLoaderManager().restartLoader(LOADER_ID_ACCOUNTS, null, this);
    }

    private void showRemoveButton() {
        findViewById(R.id.remove_divider).setVisibility(View.VISIBLE);
        mRemoveLink = findViewById(R.id.remove);
        mRemoveLink.setVisibility(View.VISIBLE);
        mRemoveLink.setOnClickListener(this);
    }

    // Called once we have determined which folder is the root folder
    private void onRootFolderFound(long root) {
        mRootFolder = root;
        mCurrentFolder = mRootFolder;
        setupTopCrumb();
        onCurrentFolderFound();
    }

    private void setupTopCrumb() {
        mCrumbs.clear();
        String name = getString(R.string.bookmarks);
        mTopLevelLabel = (TextView) mCrumbs.pushView(name, false, new Folder(name, mRootFolder));
        // To better match the other folders.
        mTopLevelLabel.setCompoundDrawablePadding(6);
    }

    private void onCurrentFolderFound() {
        LoaderManager manager = getLoaderManager();
        if (mCurrentFolder != mRootFolder) {
            // Since we're not in the root folder, change the selection to other
            // folder now. The text will get changed once we select the correct
            // folder.
            mFolder.setSelectionIgnoringSelectionChange(1);
        } else {
            setShowBookmarkIcon(true);
        }
        // Find the contents of the current folder
        manager.restartLoader(LOADER_ID_FOLDER_CONTENTS, null, this);
    }

    /**
     * Parse the data entered in the dialog and update the bookmarks database.
     */
    private void save() {
        String title = mTitle.getText().toString().trim();

        boolean emptyTitle = title.length() == 0;
        Resources r = getResources();
        if (emptyTitle) {
            mTitle.setError(r.getText(R.string.bookmark_needs_title));
            return;
        }

        // Add the folder to the database
        ContentValues values = new ContentValues();
        values.put(BrowserContract.Bookmarks.TITLE,
                title);
        values.put(BrowserContract.Bookmarks.IS_FOLDER, 1);
        values.put(BrowserContract.Bookmarks.PARENT, mCurrentFolder);
        getContentResolver().insert(
                BrowserContract.Bookmarks.CONTENT_URI, values);

        setResult(RESULT_OK);
        finish();
    }

    private void checkDupeAndSave() {
        String title = mTitle.getText().toString().trim();

        final ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(BrowserContract.Bookmarks.CONTENT_URI,
                BookmarksLoader.PROJECTION,
                "title = ? AND parent = ?",
                new String[] {
                        title, Long.toString(mCurrentFolder)
                },
                null);

        if (cursor == null) {
            save();
            return;
        }

        int duplicateCount = cursor.getCount();
        long duplicateId = -1;
        if (duplicateCount <= 0) {
            cursor.close();
            save();
            return;
        } else {
            try {
                while (cursor.moveToNext()) {
                    duplicateId = cursor.getLong(BookmarksLoader.COLUMN_INDEX_ID);
                    break;
                }
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } finally {
                if (cursor != null)
                    cursor.close();
            }
        }

        if (duplicateId != -1) {
            Toast.makeText(getApplicationContext(), R.string.duplicated_folder_warning,
                    Toast.LENGTH_LONG).show();
        } else {
            save();
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position,
            long id) {
        if (mAccountSpinner == parent) {
            long root = mAccountAdapter.getItem(position).rootFolderId;
            if (root != mRootFolder) {
                onRootFolderFound(root);
                mFolderAdapter.clearRecentFolder();
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // Don't care
    }

    static class AccountsLoader extends CursorLoader {
        static final String[] PROJECTION = new String[] {
                Accounts.ACCOUNT_NAME, Accounts.ACCOUNT_TYPE, Accounts.ROOT_ID,
        };

        static final int COLUMN_INDEX_ACCOUNT_NAME = 0;
        static final int COLUMN_INDEX_ACCOUNT_TYPE = 1;
        static final int COLUMN_INDEX_ROOT_ID = 2;

        public AccountsLoader(Context context) {
            super(context, Accounts.CONTENT_URI, PROJECTION, null, null, null);
        }
    }

    public static class BookmarkAccount {

        private String mLabel;
        String mAccountName;
        String mAccountType;
        public long rootFolderId;

        public BookmarkAccount(Context context, Cursor cursor) {
            mAccountName = cursor.getString(AccountsLoader.COLUMN_INDEX_ACCOUNT_NAME);
            mAccountType = cursor.getString(AccountsLoader.COLUMN_INDEX_ACCOUNT_TYPE);
            rootFolderId = cursor.getLong(AccountsLoader.COLUMN_INDEX_ROOT_ID);
            mLabel = mAccountName;
            if (TextUtils.isEmpty(mLabel)) {
                mLabel = context.getString(R.string.local_bookmarks);
            }
        }

        @Override
        public String toString() {
            return mLabel;
        }
    }

    static class EditBookmarkInfo {
        long mId = -1;
        long mParentId = -1;
        String mParentTitle;
        String mTitle;
        String mAccountName;
        String mAccountType;

        long mLastUsedId = -1;
        String mLastUsedTitle;
        String mLastUsedAccountName;
        String mLastUsedAccountType;
    }

    static class EditBookmarkInfoLoader extends AsyncTaskLoader<EditBookmarkInfo> {

        private Context mContext;
        private Bundle mMap;

        public EditBookmarkInfoLoader(Context context, Bundle bundle) {
            super(context);
            mContext = context.getApplicationContext();
            mMap = bundle;
        }

        @Override
        public EditBookmarkInfo loadInBackground() {
            final ContentResolver cr = mContext.getContentResolver();
            EditBookmarkInfo info = new EditBookmarkInfo();
            Cursor c = null;
            try {
                // Find the parent name/account
                long parentId = mMap.getLong(BrowserContract.Bookmarks.PARENT, DEFAULT_FOLDER_ID);
                if (parentId != -1) {
                    String where = BrowserContract.Bookmarks.IS_FOLDER + " != 0";
                    c = cr.query(ContentUris.withAppendedId(
                            BrowserContract.Bookmarks.CONTENT_URI, parentId),
                            new String[] {
                                BrowserContract.Bookmarks.ACCOUNT_NAME,
                                BrowserContract.Bookmarks.ACCOUNT_TYPE,
                                BrowserContract.Bookmarks.TITLE},
                            where, null, null);
                    if (c.moveToFirst()) {
                        info.mParentId = parentId;
                        info.mAccountName = c.getString(0);
                        info.mAccountType = c.getString(1);
                        info.mParentTitle = c.getString(2);
                    }
                    c.close();
                }

                // Figure out the last used folder/account
                c = cr.query(BrowserContract.Bookmarks.CONTENT_URI, new String[] {
                        BrowserContract.Bookmarks.PARENT,
                }, null, null, BrowserContract.Bookmarks.DATE_MODIFIED + " DESC LIMIT 1");
                if (c.moveToFirst()) {
                    long parent = c.getLong(0);
                    c.close();
                    c = cr.query(BrowserContract.Bookmarks.CONTENT_URI, new String[] {
                            BrowserContract.Bookmarks.TITLE,
                            BrowserContract.Bookmarks.ACCOUNT_NAME,
                            BrowserContract.Bookmarks.ACCOUNT_TYPE
                    }, BrowserContract.Bookmarks._ID + "=?", new String[] {
                            Long.toString(parent)
                    }, null);
                    if (c.moveToFirst()) {
                        info.mLastUsedId = parent;
                        info.mLastUsedTitle = c.getString(0);
                        info.mLastUsedAccountName = c.getString(1);
                        info.mLastUsedAccountType = c.getString(2);
                    }
                    c.close();
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            return info;
        }

        @Override
        protected void onStartLoading() {
            forceLoad();
        }
    }
}
