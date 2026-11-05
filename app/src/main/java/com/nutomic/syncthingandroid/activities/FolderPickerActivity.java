package com.nutomic.syncthingandroid.activities;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.common.collect.Sets;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.SyncthingApp;
import com.nutomic.syncthingandroid.util.FileUtils;
import com.nutomic.syncthingandroid.util.RootCompat;
import com.nutomic.syncthingandroid.util.Util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Activity that allows selecting a directory in the local file system.
 */
public class FolderPickerActivity extends SyncthingActivity
        implements AdapterView.OnItemClickListener {

    private static final String TAG = "FolderPickerActivity";

    private static final String EXTRA_INITIAL_DIRECTORY =
            ".activities.FolderPickerActivity.INITIAL_DIRECTORY";

    /**
     * If requested by {@link #createIntent}, we'll only use one root dir and enforce
     * the user stays within that. {@link #populateRoots} will respect this extra.
     */
    private static final String EXTRA_ROOT_DIRECTORY =
            ".activities.FolderPickerActivity.ROOT_DIRECTORY";

    public static final String EXTRA_RESULT_DIRECTORY =
            ".activities.FolderPickerActivity.RESULT_DIRECTORY";

    public static final int DIRECTORY_REQUEST_CODE = 234;

    private TextView mCurrentPath;
    private ListView mListView;
    private FileAdapter mFilesAdapter;
    private RootsAdapter mRootsAdapter;

    /**
     * Location of null means that the list of roots is displayed.
     */
    private File mLocation;

    private OnBackPressedCallback mBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    };

    public static Intent createIntent(Context context, String initialDirectory, @Nullable String rootDirectory) {
        Intent intent = new Intent(context, FolderPickerActivity.class);

        if (!TextUtils.isEmpty(initialDirectory)) {
            intent.putExtra(EXTRA_INITIAL_DIRECTORY, initialDirectory);
        }

        if (!TextUtils.isEmpty(rootDirectory)) {
            intent.putExtra(EXTRA_ROOT_DIRECTORY, rootDirectory);
        }

        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_folder_picker);
        mCurrentPath = findViewById(R.id.currentPath);
        mListView = findViewById(android.R.id.list);
        mListView.setOnItemClickListener(this);
        mListView.setEmptyView(findViewById(android.R.id.empty));
        mFilesAdapter = new FileAdapter(this);
        mRootsAdapter = new RootsAdapter(this);
        mListView.setAdapter(mFilesAdapter);

        populateRoots();
        if (getIntent().hasExtra(EXTRA_INITIAL_DIRECTORY)) {
            String initialDirectory = getIntent().getStringExtra(EXTRA_INITIAL_DIRECTORY);
            displayFolder(new File(initialDirectory));
            return;
        }
        displayRoot();

        // Register OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, mBackPressedCallback);
    }

    /**
     * If a root directory is specified it is added to {@link #mRootsAdapter} otherwise
     * all available storage devices/folders from various APIs are inserted into
     * {@link #mRootsAdapter}.
     */
    @SuppressLint("NewApi")
    private void populateRoots() {
        ArrayList<File> roots = new ArrayList<>();
        String rootDir = getIntent().getStringExtra(EXTRA_ROOT_DIRECTORY);
        if (getIntent().hasExtra(EXTRA_ROOT_DIRECTORY) && !TextUtils.isEmpty(rootDir)) {
            roots.add(new File(rootDir));
        } else {
            roots.addAll(Arrays.asList(getExternalFilesDirs(null)));
            roots.remove(getExternalFilesDir(null));
            roots.remove(null);      // getExternalFilesDirs may return null for an ejected SDcard.
            roots.add(Environment.getExternalStorageDirectory());
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));

            // Add paths where we might have read-only access.
            File[] mountedStoragePaths = FileUtils.getMountedStoragePathsAsFileArray();
            if (mountedStoragePaths != null) {
                Collections.addAll(roots, mountedStoragePaths);
            }
            roots.add(new File("/"));

            // Additional roots which are usually only reachable with root or Shizuku.
            if (RootCompat.isEnabled(this)) {
                roots.add(new File("/data/media/0"));
                roots.add(new File("/storage/emulated/0/Android"));
                roots.add(new File("/storage/emulated/0/Android/data"));
                roots.add(new File("/storage/emulated/0/Android/obb"));
                roots.add(new File("/data/local/tmp"));
            }
        }
        // Remove any invalid directories.
        Iterator<File> it = roots.iterator();
        while (it.hasNext()) {
            File f = it.next();
            if (f == null) {
                it.remove();
                continue;
            }
            // Root/Shizuku mode may browse folders we cannot stat ourselves.
            if (f.exists() && f.isDirectory()) {
                continue;
            }
            if (!f.exists() && RootCompat.isEnabled(this) && RootCompat.isActive(this)
                    && RootCompat.isDirectory(this, f)) {
                continue;
            }
            it.remove();
        }

        mRootsAdapter.addAll(Sets.newTreeSet(roots));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mListView.getAdapter() == mRootsAdapter)
            return true;

        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.folder_picker, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.create_folder) {
            final EditText et = new EditText(this);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.create_folder)
                    .setView(et)
                    .setPositiveButton(android.R.string.ok,
                            (dialogInterface, i) -> createFolder(et.getText().toString())
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnShowListener(dialogInterface -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT));
            dialog.show();
            return true;
        } else if (itemId == R.id.go_to_path) {
            showGoToPathDialog();
            return true;
        } else if (itemId == R.id.folder_go_up) {
            if (canGoUpToSubDir() || canGoUpToRootDir()) {
                goUpToParentDir();
            }
            return true;
        } else if (itemId == R.id.select) {
            Intent intent = new Intent()
                    .putExtra(EXTRA_RESULT_DIRECTORY, Util.formatPath(mLocation.getAbsolutePath()));
            setResult(Activity.RESULT_OK, intent);
            finish();
            return true;
        } else if (itemId == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public Boolean canGoUpToSubDir() {
        return mLocation != null && !mRootsAdapter.contains(mLocation);
    }

    public Boolean canGoUpToRootDir() {
        return mRootsAdapter.contains(mLocation) && mRootsAdapter.getCount() > 1;
    }

    public void goUpToParentDir() {
        if (canGoUpToSubDir()) {
            displayFolder(mLocation.getParentFile());
            return;
        }
        if (canGoUpToRootDir()) {
            displayRoot();
            return;
        }
        Log.e(TAG, "goUpToParentDir: Cannot go up.");
    }

    /**
     * Creates a new folder with the given name and enters it.
     */
    private void createFolder(String name) {
        File newFolder = new File(mLocation, name);
        boolean created = newFolder.mkdir();
        if (!created && RootCompat.isEnabled(this) && RootCompat.isActive(this)) {
            created = RootCompat.execShell(this,
                    "mkdir -p " + RootCompat.shellQuote(newFolder.getAbsolutePath())) == 0;
        }
        if (created) {
            displayFolder(newFolder);
        } else {
            Toast.makeText(this, R.string.create_folder_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Lets the user type an absolute path, e.g. a folder inside {@code /data/media/0} that is not
     * reachable by browsing. Useful together with root/Shizuku mode.
     */
    private void showGoToPathDialog() {
        final EditText et = new EditText(this);
        if (mLocation != null) {
            et.setText(mLocation.getAbsolutePath());
            et.setSelection(et.getText().length());
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.folder_picker_go_to_path)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                    String path = Util.formatPath(et.getText().toString());
                    if (TextUtils.isEmpty(path)) {
                        return;
                    }
                    File target = new File(path);
                    if (RootCompat.isEnabled(this) && RootCompat.isActive(this)
                            && RootCompat.isDirectory(this, target)) {
                        displayFolder(target);
                        invalidateOptions();
                    } else if (target.isDirectory()) {
                        displayFolder(target);
                        invalidateOptions();
                    } else {
                        Toast.makeText(this, R.string.folder_picker_invalid_path,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT));
        dialog.show();
    }

    /**
     * Refreshes the ListView to show the contents of the folder in {@code }mLocation.peek()}.
     */
    private void displayFolder(File folder) {
        mLocation = folder;
        mCurrentPath.setText(getString(R.string.current_path, mLocation.getAbsolutePath()));
        mFilesAdapter.clear();
        File[] contents = mLocation.listFiles();
        if (contents == null && RootCompat.isEnabled(this) && RootCompat.isActive(this)) {
            // We cannot read this folder ourselves - ask the elevated backend (root/Shizuku).
            // This does blocking IPC, so it must not run on the UI thread.
            mCurrentPath.setText(getString(R.string.current_path, mLocation.getAbsolutePath())
                    + " (" + getString(R.string.folder_picker_loading) + ")");
            final File requested = folder;
            final java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                final List<File> elevated = RootCompat.listDirectory(this, requested);
                runOnUiThread(() -> {
                    // Ignore stale results if the user navigated away meanwhile.
                    if (mLocation != null && mLocation.equals(requested)) {
                        showFolderContents(requested, elevated.toArray(new File[0]), true);
                    }
                });
            });
            executor.shutdown();
            return;
        }
        // In case we don't have read access to the folder, just display nothing.
        if (contents == null) {
            contents = new File[]{};
        }
        showFolderContents(folder, contents, false);
    }

    /**
     * Fills the file list for {@code folder}.
     */
    private void showFolderContents(File folder, File[] contents, boolean elevatedListing) {
        mCurrentPath.setText(getString(R.string.current_path, folder.getAbsolutePath()));
        mFilesAdapter.clear();
        if (elevatedListing && contents.length == 0) {
            Toast.makeText(this, R.string.folder_picker_no_elevated_access, Toast.LENGTH_SHORT).show();
        }

        // In elevated mode we usually cannot stat the entries ourselves, so only the plain-fs
        // listing can be sorted by "directories first"; the elevated listing is already sorted by
        // RootCompat.listDirectory().
        if (!elevatedListing) {
            Arrays.sort(contents, (f1, f2) -> {
                if (f1.isDirectory() && f2.isFile())
                    return -1;
                if (f1.isFile() && f2.isDirectory())
                    return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });
        }

        for (File f : contents) {
            mFilesAdapter.add(f);
        }
        mListView.setAdapter(mFilesAdapter);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
        ArrayAdapter<File> adapter = (ArrayAdapter<File>) mListView.getAdapter();
        final File f = adapter.getItem(i);
        if (f.isDirectory()) {
            displayFolder(f);
            invalidateOptions();
            return;
        }
        // In root/Shizuku mode we cannot stat the entry, so ask the elevated backend.
        if (RootCompat.isEnabled(this) && RootCompat.isActive(this)) {
            final java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                final boolean isDir = RootCompat.isDirectory(this, f);
                runOnUiThread(() -> {
                    if (isDir) {
                        displayFolder(f);
                        invalidateOptions();
                    }
                });
            });
            executor.shutdown();
        }
    }

    private void invalidateOptions() {
        invalidateOptionsMenu();
    }

    private class FileAdapter extends ArrayAdapter<File> {

        public FileAdapter(Context context) {
            super(context, android.R.layout.simple_list_item_1);
        }

        @Override
        @NonNull
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            convertView = super.getView(position, convertView, parent);
            TextView title = convertView.findViewById(android.R.id.text1);
            File f = getItem(position);
            title.setText(f.getName());
            title.setTypeface(title.getTypeface(), f.isFile() ? Typeface.ITALIC : Typeface.NORMAL);
            return convertView;
        }
    }

    private class RootsAdapter extends ArrayAdapter<File> {

        public RootsAdapter(Context context) {
            super(context, android.R.layout.simple_list_item_1);
        }

        @Override
        @NonNull
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            convertView = super.getView(position, convertView, parent);
            TextView title = convertView.findViewById(android.R.id.text1);
            title.setText(getItem(position).getAbsolutePath());
            return convertView;
        }

        public boolean contains(File file) {
            for (int i = 0; i < getCount(); i++) {
                if (getItem(i).equals(file))
                    return true;
            }
            return false;
        }
    }

    /**
     * Goes up a directory, up to the list of roots if there are multiple roots.
     * <p>
     * If we already are in the list of roots, or if we are directly in the only
     * root folder, we cancel.
     */

    /**
     * Displays a list of all available roots, or if there is only one root, the
     * contents of that folder.
     */
    private void displayRoot() {
        mCurrentPath.setText(R.string.advanced_storage_path_overview);
        mFilesAdapter.clear();
        if (mRootsAdapter.getCount() == 1) {
            displayFolder(mRootsAdapter.getItem(0));
        } else {
            mListView.setAdapter(mRootsAdapter);
            mLocation = null;
        }
        invalidateOptions();
    }

}
