package com.nutomic.syncthingandroid.util;

import android.content.Context;
import android.util.Log;

import com.nutomic.syncthingandroid.service.Constants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Manages the Syncthing home directory of the elevated (root / Shizuku) daemon.
 * <p>
 * The elevated process runs as a different UID and therefore cannot read the app's private
 * {@code files/} directory. The home (config, keys, index database) is staged into a directory
 * that is shared with the elevated user and synchronized back when the daemon exits.
 * <p>
 * Layout:
 * <pre>
 *   app   : /data/data/&lt;pkg&gt;/files/            (mContext.getFilesDir())
 *   shared: /data/local/tmp/syncthing-rootcompat/&lt;versionCode&gt;/home/
 * </pre>
 * The shared dir is created/chowned by the elevated user. Only the config and the keys are copied
 * on start (cheap); the index database stays in the shared directory and is not touched by the app.
 */
public final class RootCompatHome {

    private static final String TAG = "RootCompatHome";

    private RootCompatHome() {
    }

    /**
     * @return the directory used as {@code STHOMEDIR} for the elevated daemon
     */
    public static File getSharedHome(Context context) {
        int versionCode;
        try {
            versionCode = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            versionCode = 0;
        }
        return new File("/data/local/tmp/syncthing-rootcompat/" + versionCode + "/home");
    }

    /**
     * @return the log file of the elevated daemon. It lives in the shared home because the app
     * private directory is not writable for the elevated user, and it is made world readable so
     * the app can follow it.
     */
    public static File getElevatedLogFile(Context context) {
        return new File(getSharedHome(context), Constants.ROOT_COMPAT_LOG_FILE);
    }

    /**
     * Convenience wrapper.
     */
    public static boolean isEnabled(Context context) {
        return RootCompat.isActive(context);
    }

    /**
     * Makes the shared home usable. Must be called before {@link #stageAppFiles}.
     *
     * @return true if the shared home exists and is writable by the elevated user
     */
    public static boolean prepare(Context context) {
        if (!RootCompat.isActive(context)) {
            return false;
        }
        String home = getSharedHome(context).getAbsolutePath();
        File log = getElevatedLogFile(context);
        String cmd = "mkdir -p " + RootCompat.shellQuote(home) + " ; "
                + "chmod 777 " + RootCompat.shellQuote(home) + " ; "
                + "touch " + RootCompat.shellQuote(log.getAbsolutePath()) + " ; "
                + "chmod 666 " + RootCompat.shellQuote(log.getAbsolutePath()) + " 2>/dev/null ; "
                + "test -d " + RootCompat.shellQuote(home);
        return RootCompat.execShell(context, cmd) == 0;
    }

    /**
     * Copies the app owned files which Syncthing needs for a first start (config + keys) into the
     * shared home. The app's private files are streamed through the elevated backend, because the
     * elevated user must not read {@code /data/data/<pkg>/files} directly.
     */
    public static void stageAppFiles(Context context) {
        // First pull anything the previous elevated run changed (e.g. Web GUI edits), so a restart
        // does not overwrite it with a stale app-side copy.
        syncBackConfig(context);

        File filesDir = context.getFilesDir();
        File sharedHome = getSharedHome(context);
        File config = new File(filesDir, Constants.CONFIG_FILE);

        // Always push the current config: it may have been edited in the UI since the last run.
        copyFile(context, config, new File(sharedHome, Constants.CONFIG_FILE));

        // Keys and the HTTPS certificate only need to be copied once.
        for (String name : new String[]{
                Constants.PRIVATE_KEY_FILE,
                Constants.PUBLIC_KEY_FILE,
                Constants.HTTPS_CERT_FILE,
                Constants.HTTPS_KEY_FILE}) {
            File src = new File(filesDir, name);
            File dst = new File(sharedHome, name);
            if (src.exists() && !fileExistsElevated(context, dst)) {
                copyFile(context, src, dst);
            }
        }
    }

    /**
     * Reads {@code config.xml} back from the shared home after the daemon stopped, so changes made
     * through the Web GUI are visible to the app again.
     */
    public static void syncBackConfig(Context context) {
        if (!RootCompat.isActive(context)) {
            return;
        }
        File sharedConfig = new File(getSharedHome(context), Constants.CONFIG_FILE);
        String content = RootCompat.readFile(context, sharedConfig);
        if (content == null || content.isEmpty()) {
            Log.w(TAG, "syncBackConfig: Could not read " + sharedConfig);
            return;
        }
        File appConfig = Constants.getConfigFile(context);
        File tmp = Constants.getConfigTempFile(context);
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (!tmp.renameTo(appConfig)) {
                try (OutputStream out = new FileOutputStream(appConfig)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            }
            Log.i(TAG, "syncBackConfig: Restored config from the elevated home");
        } catch (IOException e) {
            Log.w(TAG, "syncBackConfig: Failed to write back config", e);
        }
    }

    /**
     * Best effort: makes the shared home world readable so the app itself can inspect it (e.g. for
     * the folder picker). Not required for Syncthing to work.
     */
    public static void relaxPermissions(Context context) {
        if (!RootCompat.isActive(context)) {
            return;
        }
        String home = getSharedHome(context).getAbsolutePath();
        RootCompat.execShell(context,
                "chmod -R a+rX " + RootCompat.shellQuote(home) + " 2>/dev/null ; true");
    }

    // ---------------------------------------------------------------------------------------

    private static boolean fileExistsElevated(Context context, File file) {
        return RootCompat.execShell(context,
                "test -e " + RootCompat.shellQuote(file.getAbsolutePath())) == 0;
    }

    /**
     * Copies an app-private file into the elevated home. The content is streamed through the
     * backend, the elevated user never needs to read the app's sandbox.
     */
    private static void copyFile(Context context, File source, File destination) {
        if (!source.exists()) {
            return;
        }
        try {
            byte[] content = readFully(source);
            // Config and keys are small text files; base64 keeps the transport binary safe.
            String encoded = android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP);
            String cmd = "mkdir -p " + RootCompat.shellQuote(destination.getParent())
                    + " && printf %s " + RootCompat.shellQuote(encoded)
                    + " | base64 -d > " + RootCompat.shellQuote(destination.getAbsolutePath())
                    + " && chmod 600 " + RootCompat.shellQuote(destination.getAbsolutePath());
            if (RootCompat.execShell(context, cmd) != 0) {
                Log.w(TAG, "copyFile: Failed to copy " + source + " -> " + destination);
            }
        } catch (IOException e) {
            Log.w(TAG, "copyFile: Failed to read " + source, e);
        }
    }

    private static byte[] readFully(File file) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
