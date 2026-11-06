package com.nutomic.syncthingandroid.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.common.base.Charsets;
import com.nutomic.syncthingandroid.service.Constants;
import com.nutomic.syncthingandroid.service.IRootCompatService;
import com.nutomic.syncthingandroid.service.RootCompatService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

import rikka.shizuku.Shizuku;

/**
 * Central entry point for running filesystem operations with elevated privileges.
 * <p>
 * Android's scoped storage makes large parts of the filesystem (e.g. {@code /storage/emulated/0/Android},
 * {@code /data/media}, {@code /data/system}, other apps' data) unreadable for the Syncthing native
 * process. This helper allows to run the Syncthing daemon (and small helper commands) as
 * {@code shell} (uid 2000) via <a href="https://shizuku.rikka.app/">Shizuku</a> or as {@code root}
 * (uid 0) via a superuser daemon, which gives access to those paths.
 * <p>
 * The elevated process is started through {@link IRootCompatService}, implemented by the Shizuku
 * user service {@code RootCompatService}, that
 * runs inside the Shizuku server process. If Shizuku is not installed/authorized and the device is
 * rooted, a {@code su} shell is used as a fallback transport.
 */
public final class RootCompat {

    private static final String TAG = "RootCompat";

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    /**
     * Timeout for binding the Shizuku user service / waiting for the elevated daemon.
     */
    private static final long BIND_TIMEOUT_MS = 10_000;

    private RootCompat() {
    }

    // ---------------------------------------------------------------------------------------
    // Preference helpers
    // ---------------------------------------------------------------------------------------

    public static boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(Constants.PREF_ROOT_COMPAT_ENABLED, false);
    }

    public static String getMode(Context context) {
        return getPrefs(context).getString(Constants.PREF_ROOT_COMPAT_MODE, Constants.RootCompatMode.AUTO);
    }

    /**
     * Custom superuser command, e.g. {@code su -c} or {@code /system/xbin/su}. Empty = auto.
     */
    public static String getSuperuserCommand(Context context) {
        return getPrefs(context).getString(Constants.PREF_ROOT_COMPAT_SUPERUSER_CMD, "");
    }

    private static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    // ---------------------------------------------------------------------------------------
    // Availability detection
    // ---------------------------------------------------------------------------------------

    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether the Shizuku binder is reachable, i.e. the Shizuku server is running.
     */
    public static boolean isShizukuAvailable(Context context) {
        if (!isShizukuInstalled(context)) {
            return false;
        }
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            // Shizuku >= 11 requires an explicit runtime grant.
            if (Shizuku.isPreV11()) {
                return true;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.w(TAG, "isShizukuAvailable: Shizuku not usable", t);
            return false;
        }
    }

    /**
     * Whether Shizuku is running but our app has not been granted the API permission yet.
     */
    public static boolean isShizukuPermissionPending(Context context) {
        if (!isShizukuInstalled(context)) {
            return false;
        }
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Asks the user to grant the Shizuku API permission. The result is delivered through
     * {@link Shizuku#OnRequestPermissionResultListener} (must be registered by the caller).
     */
    public static void requestShizukuPermission() {
        try {
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            Log.w(TAG, "requestShizukuPermission: Failed to request permission", t);
        }
    }

    /**
     * Probes whether a working {@code su} binary exists on this device.
     */
    public static boolean isRootAvailable() {
        String customSu = "";
        String[] candidates = TextUtils.isEmpty(customSu)
                ? new String[]{"su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su"}
                : new String[]{customSu};
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "-c", "id")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), Charsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                reader.close();
                if (p.waitFor() == 0 && sb.toString().contains("uid=0")) {
                    Log.i(TAG, "isRootAvailable: " + candidate + " grants uid=0");
                    return true;
                }
            } catch (IOException | InterruptedException e) {
                Log.v(TAG, "isRootAvailable: " + candidate + " not usable: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * The backend that would be used with the current configuration.
     *
     * @return one of {@link Constants.RootCompatMode#OFF}, {@code "shizuku"}, {@code "root"} or
     * {@code null} if elevating is configured but no backend is currently usable.
     */
    @Nullable
    public static String resolveBackend(Context context) {
        if (!isEnabled(context)) {
            return Constants.RootCompatMode.OFF;
        }
        String mode = getMode(context);
        boolean shizukuOk = isShizukuAvailable(context);
        boolean rootOk = isRootAvailable();

        switch (mode) {
            case Constants.RootCompatMode.OFF:
                return Constants.RootCompatMode.OFF;
            case Constants.RootCompatMode.SHIZUKU:
                return shizukuOk ? Constants.RootCompatMode.SHIZUKU : null;
            case Constants.RootCompatMode.ROOT:
                return rootOk ? Constants.RootCompatMode.ROOT : null;
            case Constants.RootCompatMode.AUTO:
            default:
                if (shizukuOk) {
                    return Constants.RootCompatMode.SHIZUKU;
                }
                if (rootOk) {
                    return Constants.RootCompatMode.ROOT;
                }
                return null;
        }
    }

    /**
     * True if the elevated backend is usable right now.
     */
    public static boolean isActive(Context context) {
        String backend = resolveBackend(context);
        return Constants.RootCompatMode.SHIZUKU.equals(backend)
                || Constants.RootCompatMode.ROOT.equals(backend);
    }

    /**
     * Convenience for UI code: short description of the active backend, or the reason why
     * nothing is active.
     */
    public static String describeStatus(Context context) {
        if (!isEnabled(context)) {
            return "disabled";
        }
        String mode = getMode(context);
        String backend = resolveBackend(context);
        if (Constants.RootCompatMode.SHIZUKU.equals(backend)) {
            return "shizuku (uid " + getElevatedUid(context) + ")";
        }
        if (Constants.RootCompatMode.ROOT.equals(backend)) {
            return "root (uid 0)";
        }
        if (Constants.RootCompatMode.SHIZUKU.equals(mode) || !isShizukuInstalled(context)) {
            if (isShizukuPermissionPending(context)) {
                return "shizuku permission not granted";
            }
            if (isShizukuInstalled(context)) {
                return "shizuku not running";
            }
        }
        return "no root or shizuku backend available";
    }

    /**
     * UID of the elevated process or -1.
     */
    public static int getElevatedUid(Context context) {
        IRootCompatService service = bindUserService(context);
        if (service == null) {
            return -1;
        }
        try {
            return service.getUid();
        } catch (Throwable t) {
            Log.w(TAG, "getElevatedUid: " + t);
            return -1;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shizuku user service
    // ---------------------------------------------------------------------------------------

    @Nullable
    private static volatile IRootCompatService sUserService;

    /**
     * Returns the Shizuku user service arguments. The component name must carry the concrete
     * package name of this installation (release vs. debug build), so it cannot be a constant.
     */
    private static Shizuku.UserServiceArgs getUserServiceArgs(Context context) {
        return new Shizuku.UserServiceArgs(new ComponentName(
                context.getPackageName(), RootCompatService.class.getName()))
                .daemon(false)
                .processNameSuffix("rootcompat")
                .debuggable(false)
                .version(1);
    }

    private static final ServiceConnection USER_SERVICE_CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Shizuku user service connected");
            synchronized (USER_SERVICE_LOCK) {
                sUserService = IRootCompatService.Stub.asInterface(binder);
                sUserServiceRequested = false;
                USER_SERVICE_LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Shizuku user service disconnected");
            synchronized (USER_SERVICE_LOCK) {
                sUserService = null;
                sUserServiceRequested = false;
                USER_SERVICE_LOCK.notifyAll();
            }
        }
    };

    private static final Object USER_SERVICE_LOCK = new Object();

    /**
     * True while a {@link Shizuku#bindUserService} call is in flight, so that concurrent callers
     * do not stack up bind requests. Guarded by {@link #USER_SERVICE_LOCK}.
     */
    private static boolean sUserServiceRequested;

    /**
     * Binds (once) to the Shizuku user service and returns it, or {@code null} if Shizuku is not
     * usable. Blocking; must not be called on the main thread.
     */
    @Nullable
    public static IRootCompatService bindUserService(Context context) {
        if (!isShizukuAvailable(context)) {
            return null;
        }
        IRootCompatService service = sUserService;
        if (service != null && service.asBinder().pingBinder()) {
            return service;
        }
        synchronized (USER_SERVICE_LOCK) {
            // Another thread may have completed the bind while we waited for the lock.
            service = sUserService;
            if (service != null && service.asBinder().pingBinder()) {
                return service;
            }
            if (!sUserServiceRequested) {
                sUserServiceRequested = true;
                try {
                    Shizuku.bindUserService(getUserServiceArgs(context), USER_SERVICE_CONNECTION);
                } catch (Throwable t) {
                    sUserServiceRequested = false;
                    Log.w(TAG, "bindUserService: Failed to bind Shizuku user service", t);
                    return null;
                }
            }
            /*
             * Loop instead of a single wait(): the connection callback may already have fired
             * before we got the lock (the notify would have been lost) and a stale binder may
             * need another round trip. Polling keeps this robust without depending on timing.
             */
            long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
            while (true) {
                service = sUserService;
                if (service != null && service.asBinder().pingBinder()) {
                    return service;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Log.w(TAG, "bindUserService: Timed out waiting for the Shizuku user service");
                    return null;
                }
                try {
                    USER_SERVICE_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Remote process helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Runs a command in the elevated context and returns its exit code.
     */
    public static int exec(Context context, String[] command) {
        Process p = startProcess(context, command, null);
        if (p == null) {
            return -1;
        }
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            p.destroy();
        }
    }

    /**
     * Runs a shell command line in the elevated context.
     */
    public static int execShell(Context context, String shellCommand) {
        return exec(context, new String[]{"sh", "-c", shellCommand});
    }

    /**
     * Starts a short lived process in the elevated context. Only use this for commands whose
     * output you do not need (use {@link #exec} / {@link #execShell} instead) and never for the
     * Syncthing daemon - {@code ShizukuRemoteProcess} streams are only readable inside the
     * service process, so the daemon is started through {@link #startDaemon}.
     */
    @Nullable
    public static Process startProcess(Context context, String[] command,
                                       @Nullable java.util.Map<String, String> environment) {
        String backend = resolveBackend(context);
        if (backend == null || Constants.RootCompatMode.OFF.equals(backend)) {
            Log.w(TAG, "startProcess: No elevated backend available");
            return null;
        }
        if (Constants.RootCompatMode.SHIZUKU.equals(backend)) {
            IRootCompatService service = bindUserService(context);
            if (service == null) {
                Log.w(TAG, "startProcess: Shizuku user service not available");
                return null;
            }
            try {
                // Fire and forget: the exit code is transported through the return value of
                // exec(), the streams are not usable from here.
                service.exec(command, envToArray(environment));
                return null;
            } catch (Throwable t) {
                Log.w(TAG, "startProcess: Failed to start process via Shizuku", t);
                return null;
            }
        }
        return startRootProcess(context, command, environment);
    }

    /**
     * Starts the Syncthing daemon in the elevated context.
     * <p>
     * The native binary is first copied to a location where it can be made executable for the
     * elevated user, then started with its stdout/stderr redirected to {@code logFile} inside the
     * elevated context. Because that call blocks until the daemon exits, it is executed on a
     * background thread; the returned future completes with the daemon's exit code.
     *
     * @param command     full argv of the Syncthing daemon
     * @param environment environment variables for the daemon
     * @param logFile     file the elevated process writes its output to (must be writable there)
     * @return a future that completes with the exit code, or {@code null} if no backend is usable
     */
    @Nullable
    public static FutureTask<Integer> startDaemon(Context context, String[] command,
                                                  @NonNull Map<String, String> environment,
                                                  @NonNull File logFile) {
        String backend = resolveBackend(context);
        if (backend == null || Constants.RootCompatMode.OFF.equals(backend)) {
            Log.w(TAG, "startDaemon: No elevated backend available");
            return null;
        }
        final String[] execCommand = ensureBinaryExecutable(context, command);
        Log.i(TAG, "startDaemon via " + backend + ": " + Arrays.toString(execCommand));

        FutureTask<Integer> task = new FutureTask<>(() -> {
            if (Constants.RootCompatMode.SHIZUKU.equals(backend)) {
                IRootCompatService service = bindUserService(context);
                if (service == null) {
                    Log.w(TAG, "startDaemon: Shizuku user service not available");
                    return -1;
                }
                return service.startDaemon(execCommand, envToArray(environment),
                        logFile.getAbsolutePath());
            }
            Process p = startRootProcess(context, command, environment, logFile);
            if (p == null) {
                return -1;
            }
            try {
                return p.waitFor();
            } finally {
                p.destroy();
            }
        });
        Thread thread = new Thread(task, "syncthing-rootcompat");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /**
     * Asks the elevated backend to terminate a running daemon.
     */
    public static void stopDaemon(Context context) {
        String backend = resolveBackend(context);
        if (!Constants.RootCompatMode.SHIZUKU.equals(backend)) {
            // For the "su" transport the daemon is a child of a shell we cannot reach, so we
            // fall back to killing it by its process name (see Util#killProcess).
            return;
        }
        IRootCompatService service = bindUserService(context);
        if (service == null) {
            return;
        }
        try {
            service.stopDaemon();
        } catch (Throwable t) {
            Log.w(TAG, "stopDaemon: " + t);
        }
    }

    /**
     * Starts a shell command in the elevated context whose stdin the app may write to. Used to
     * stream files the elevated user cannot read by itself into an elevated location.
     *
     * @return a process whose {@code getOutputStream()} is connected to the command's stdin, or
     * {@code null}. Note: with the Shizuku backend the streams belong to the service process and
     * are therefore not usable - use {@link #writeFile} or {@link #execShell} instead.
     */
    @Nullable
    public static Process startWritableShell(Context context, String shellCommand) {
        String backend = resolveBackend(context);
        if (!Constants.RootCompatMode.ROOT.equals(backend)) {
            return null;
        }
        return startRootProcess(context, new String[]{"sh", "-c", shellCommand}, null, null);
    }

    /**
     * Reads a file with elevated privileges.
     *
     * @return the file content or an empty string if it could not be read
     */
    public static String readFile(Context context, File file) {
        if (file == null) {
            return "";
        }
        String resolved = resolveBackend(context);
        if (Constants.RootCompatMode.SHIZUKU.equals(resolved)) {
            IRootCompatService service = bindUserService(context);
            if (service == null) {
                return "";
            }
            try {
                return service.readFile(file.getAbsolutePath());
            } catch (Throwable t) {
                Log.w(TAG, "readFile: " + t);
                return "";
            }
        }
        if (!Constants.RootCompatMode.ROOT.equals(resolved)) {
            return "";
        }
        Process p = startRootProcess(context, new String[]{"cat", file.getAbsolutePath()}, null, null);
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "readFile: " + e);
        } finally {
            p.destroy();
        }
        return sb.toString();
    }

    /**
     * The native Syncthing binary lives in the APK's native library directory and is not marked
     * executable (Android only maps it executable for the app UID). When running it as a different
     * user we have to make a copy with the exec bit set.
     *
     * @return command array with {@code command[0]} replaced by an executable copy.
     */
    private static String[] ensureBinaryExecutable(Context context, String[] command) {
        if (command.length == 0) {
            return command;
        }
        File binary = new File(command[0]);
        String appLibDir = context.getApplicationInfo().nativeLibraryDir;
        if (!binary.getAbsolutePath().startsWith(appLibDir.concat("/"))) {
            return command;
        }
        File execCopy = new File(getWorkDir(context), binary.getName());
        if (!execCopy.exists() || execCopy.length() != binary.length()) {
            try (FileInputStream in = new FileInputStream(binary);
                 FileOutputStream out = new FileOutputStream(execCopy)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy native binary to " + execCopy, e);
                return command;
            }
        }
        execShell(context, "chmod 755 '" + execCopy.getAbsolutePath() + "'");
        String[] result = command.clone();
        result[0] = execCopy.getAbsolutePath();
        return result;
    }

    @Nullable
    private static Process startRootProcess(Context context, String[] command,
                                            @Nullable java.util.Map<String, String> environment) {
        return startRootProcess(context, command, environment, null);
    }

    @Nullable
    private static Process startRootProcess(Context context, String[] command,
                                            @Nullable java.util.Map<String, String> environment,
                                            @Nullable File logFile) {
        String customSu = getSuperuserCommand(context).trim();
        List<String> argv = new ArrayList<>();
        if (TextUtils.isEmpty(customSu)) {
            argv.add("su");
        } else {
            argv.addAll(Arrays.asList(customSu.split("\\s+")));
        }
        StringBuilder sb = new StringBuilder();
        if (environment != null) {
            for (java.util.Map.Entry<String, String> e : environment.entrySet()) {
                sb.append("export ").append(e.getKey()).append('=')
                        .append(shellQuote(e.getValue())).append("; ");
            }
        }
        sb.append("cd /; ");
        for (String part : command) {
            sb.append(shellQuote(part)).append(' ');
        }
        if (logFile != null) {
            // Compress the log inside the elevated process, i.e. out of the app's reach.
            sb.append(" >> ").append(shellQuote(logFile.getAbsolutePath())).append(" 2>&1");
        }
        argv.add("-c");
        argv.add(sb.toString());
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            return pb.start();
        } catch (IOException e) {
            Log.w(TAG, "startRootProcess: Failed to run " + argv, e);
            return null;
        }
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String[] envToArray(@Nullable java.util.Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return new String[0];
        }
        List<String> list = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : environment.entrySet()) {
            list.add(e.getKey() + "=" + e.getValue());
        }
        return list.toArray(new String[0]);
    }

    private static File getWorkDir(Context context) {
        File dir = new File(context.getFilesDir(), Constants.ROOT_COMPAT_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    // ---------------------------------------------------------------------------------------
    // Elevated file access (used by the folder picker and the write checks)
    // ---------------------------------------------------------------------------------------

    /**
     * Lists a directory with elevated privileges.
     *
     * @return absolute paths of the entries, sorted; empty if the directory is not readable.
     */
    public static List<File> listDirectory(Context context, File directory) {
        if (directory == null) {
            return Collections.emptyList();
        }
        if (directory.canRead() && directory.list() != null) {
            File[] children = directory.listFiles();
            if (children != null) {
                List<File> list = new ArrayList<>(Arrays.asList(children));
                Collections.sort(list);
                return list;
            }
        }
        if (!isActive(context)) {
            return Collections.emptyList();
        }
        IRootCompatService service = Constants.RootCompatMode.SHIZUKU.equals(resolveBackend(context))
                ? bindUserService(context)
                : null;
        String[] names;
        try {
            if (service != null) {
                names = service.list(directory.getAbsolutePath());
            } else if (Constants.RootCompatMode.ROOT.equals(resolveBackend(context))) {
                names = listViaShell(context, directory.getAbsolutePath());
            } else {
                names = new String[0];
            }
        } catch (Throwable t) {
            Log.w(TAG, "listDirectory: Failed for " + directory, t);
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                if (!TextUtils.isEmpty(name)) {
                    list.add(new File(directory, name));
                }
            }
        }
        Collections.sort(list);
        return list;
    }

    private static String[] listViaShell(Context context, String path) {
        Process p = startProcess(context, new String[]{
                "sh", "-c", "ls -1a " + shellQuote(path)
        }, null);
        if (p == null) {
            return new String[0];
        }
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!".".equals(line) && !"..".equals(line)) {
                    result.add(line);
                }
            }
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "listViaShell: " + e);
        } finally {
            p.destroy();
        }
        return result.toArray(new String[0]);
    }

    /**
     * Checks whether the given path is a directory, using elevated privileges if necessary.
     */
    public static boolean isDirectory(Context context, File path) {
        if (path == null) {
            return false;
        }
        if (path.isDirectory() && path.canRead()) {
            return true;
        }
        if (!isActive(context) || !path.isAbsolute()) {
            return false;
        }
        String resolved = resolveBackend(context);
        try {
            if (Constants.RootCompatMode.SHIZUKU.equals(resolved)) {
                IRootCompatService service = bindUserService(context);
                return service != null && service.isDirectory(path.getAbsolutePath());
            }
            return execShell(context, "test -d " + shellQuote(path.getAbsolutePath())) == 0;
        } catch (Throwable t) {
            Log.w(TAG, "isDirectory: " + t);
            return false;
        }
    }

    /**
     * Tests write access with elevated privileges.
     */
    public static boolean canWrite(Context context, String absoluteFolderPath) {
        if (TextUtils.isEmpty(absoluteFolderPath)) {
            return false;
        }
        String probe = absoluteFolderPath + "/.stwritetest-root";
        String resolved = resolveBackend(context);
        if (Constants.RootCompatMode.SHIZUKU.equals(resolved)) {
            try {
                IRootCompatService service = bindUserService(context);
                if (service != null && service.canWrite(absoluteFolderPath)) {
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "canWrite: Shizuku check failed", t);
            }
        }
        if (isActive(context)) {
            return execShell(context, "echo \"\" > " + shellQuote(probe)
                    + " && rm -f " + shellQuote(probe)) == 0;
        }
        return false;
    }

    /**
     * Creates the {@code .stfolder} / {@code .stignore} markers and makes the folder usable by the
     * app UID where possible.
     */
    public static boolean prepareFolder(Context context, String absoluteFolderPath) {
        if (TextUtils.isEmpty(absoluteFolderPath) || !isActive(context)) {
            return false;
        }
        // Create the folder (if missing) and the ".stfolder" marker as the elevated user, because
        // the app UID may not be allowed to write there. Everything is best effort: on a "shell"
        // backend chown fails and the original ownership is kept.
        String folder = shellQuote(absoluteFolderPath);
        String marker = shellQuote(absoluteFolderPath + "/" + Constants.FILENAME_STFOLDER);
        String cmd = "mkdir -p " + folder + " ; "
                + "mkdir -p " + marker + " 2>/dev/null ; "
                + "chmod 771 " + folder + " 2>/dev/null ; "
                + "chmod -R 771 " + marker + " 2>/dev/null ; "
                + "test -d " + folder;
        return execShell(context, cmd) == 0;
    }

    /**
     * Copies the config/database directory so that the elevated process can use it.
     * <p>
     * The elevated process cannot read {@code /data/data/<pkg>/files} of the app, therefore the
     * Syncthing home directory is mirrored into an elevated-owned location and the modified
     * {@code config.xml} is written back to the app after the daemon stopped.
     */
    public static boolean mirrorTree(Context context, File source, File destination, boolean reverse) {
        if (!isActive(context)) {
            return false;
        }
        File from = reverse ? destination : source;
        File to = reverse ? source : destination;
        String cmd = "rm -rf " + shellQuote(to.getAbsolutePath()) + " ; "
                + "mkdir -p " + shellQuote(to.getAbsolutePath()) + " ; "
                + "cp -a " + shellQuote(from.getAbsolutePath()) + "/. "
                + shellQuote(to.getAbsolutePath()) + "/ 2>/dev/null ; "
                + "chmod -R 771 " + shellQuote(to.getAbsolutePath()) + " ; "
                + "true";
        return execShell(context, cmd) == 0;
    }

    /**
     * Writes text to a file using elevated privileges.
     */
    public static boolean writeFile(Context context, File target, String content) {
        String resolved = resolveBackend(context);
        if (Constants.RootCompatMode.SHIZUKU.equals(resolved)) {
            try {
                IRootCompatService service = bindUserService(context);
                if (service != null) {
                    return service.writeFile(target.getAbsolutePath(), content);
                }
            } catch (Throwable t) {
                Log.w(TAG, "writeFile: Shizuku write failed", t);
            }
        }
        if (!isActive(context)) {
            return false;
        }
        Process p = startProcess(context, new String[]{"sh", "-c",
                "cat > " + shellQuote(target.getAbsolutePath())}, null);
        if (p == null) {
            return false;
        }
        try (OutputStream out = p.getOutputStream()) {
            out.write(content.getBytes(Charsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "writeFile: Failed to write " + target, e);
            return false;
        }
        try {
            return p.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            p.destroy();
        }
    }

    /**
     * Ensures the native Syncthing binary exists at a path the elevated user can execute and
     * returns it. Returns the original path if no elevated backend is active.
     */
    public static String getExecutableBinaryPath(Context context, File original) {
        if (!isActive(context)) {
            return original.getAbsolutePath();
        }
        String[] cmd = ensureBinaryExecutable(context, new String[]{original.getAbsolutePath()});
        return cmd[0];
    }

    /**
     * Lists PIDs of a process with elevated privileges (the app's own {@code ps} output does not
     * contain processes of other users).
     */
    public static List<String> getProcessPIDs(Context context, String processName) {
        List<String> pids = new ArrayList<>();
        if (!isActive(context) || TextUtils.isEmpty(processName)) {
            return pids;
        }
        // "pidof" is available on all modern Android versions; fall back to "ps -A -o PID,NAME".
        String output = runCommandCapturingStdout(context,
                new String[]{"sh", "-c", "pidof " + shellQuote(processName)
                        + " 2>/dev/null || ps -A -o PID,NAME 2>/dev/null"});
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.matches("\\d+(\\s+\\d+)*")) {
                // Output of pidof: space separated list of PIDs.
                Collections.addAll(pids, line.split("\\s+"));
            } else if (line.contains(processName)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 1 && parts[0].matches("\\d+")) {
                    pids.add(parts[0]);
                }
            }
        }
        return pids;
    }

    /**
     * Sends SIGINT (graceful Syncthing shutdown) to all instances of a process and waits for them
     * to disappear.
     */
    public static void killProcess(Context context, String processName) {
        if (!isActive(context)) {
            return;
        }
        List<String> pids = getProcessPIDs(context, processName);
        if (pids.isEmpty()) {
            Log.v(TAG, "killProcess: No elevated instance of [" + processName + "] running");
            return;
        }
        StringBuilder cmd = new StringBuilder();
        for (String pid : pids) {
            cmd.append("kill -SIGINT ").append(pid).append(" 2>/dev/null ; ");
        }
        cmd.append("true");
        execShell(context, cmd.toString());

        // Wait for the processes to exit, at most 10 seconds.
        for (int i = 0; i < 200; i++) {
            if (getProcessPIDs(context, processName).isEmpty()) {
                Log.d(TAG, "killProcess: Elevated instance of [" + processName + "] ended");
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w(TAG, "killProcess: [" + processName + "] did not exit in time");
    }

    /**
     * Runs a command in the elevated context and returns everything it wrote to stdout.
     */
    public static String runCommandCapturingStdout(Context context, String[] command) {
        String backend = resolveBackend(context);
        if (Constants.RootCompatMode.ROOT.equals(backend)) {
            Process p = startRootProcess(context, command, null, null);
            return p == null ? "" : readAll(p.getInputStream());
        }
        // Shizuku AIDL methods do not return stream content, so redirect into a temp file.
        File tmp = new File(getWorkDir(context), "out-" + System.nanoTime() + ".txt");
        StringBuilder sb = new StringBuilder();
        for (String part : command) {
            sb.append(shellQuote(part)).append(' ');
        }
        String full = sb + "> " + shellQuote(tmp.getAbsolutePath()) + " 2>/dev/null";
        if (execShell(context, full) != 0) {
            return "";
        }
        String content = Constants.RootCompatMode.SHIZUKU.equals(backend)
                ? readFileInternal(context, tmp)
                : readFile(context, tmp);
        execShell(context, "rm -f " + shellQuote(tmp.getAbsolutePath()));
        return content;
    }

    private static String readFileInternal(Context context, File file) {
        IRootCompatService service = bindUserService(context);
        if (service == null) {
            return "";
        }
        try {
            return service.readFile(file.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "readFileInternal: " + t);
            return "";
        }
    }

    private static String readAll(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            Log.w(TAG, "readAll: " + e);
        }
        return sb.toString();
    }

    /**
     * Blocks until the given process finished, returning its exit code.
     */
    public static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
