package com.nutomic.syncthingandroid.service;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.google.common.base.Charsets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs as a <a href="https://shizuku.rikka.app/">Shizuku</a> user service, i.e. inside the Shizuku
 * server process as {@code shell} (uid 2000) or {@code root} (uid 0) depending on how Shizuku was
 * started.
 * <p>
 * This class is instantiated by Shizuku via reflection; it must not reference app resources.
 * It performs the privileged filesystem work needed to sync folders the app itself cannot reach.
 */
public class RootCompatService extends IRootCompatService.Stub {

    private static final String TAG = "RootCompatService";

    private static final Object DAEMON_LOCK = new Object();

    private volatile Process mDaemonProcess = null;

    public RootCompatService() {
        Log.i(TAG, "RootCompatService created, uid=" + android.os.Process.myUid());
    }

    @Override
    public int getUid() {
        return android.os.Process.myUid();
    }

    @Override
    public int exec(String[] command, String[] environment) {
        try {
            Process p = startProcess(command, environment, null);
            int exit = p.waitFor();
            p.destroy();
            return exit;
        } catch (Exception e) {
            Log.w(TAG, "exec failed: " + e);
            return -1;
        }
    }

    @Override
    public int startDaemon(String[] command, String[] environment, String logFile) {
        synchronized (DAEMON_LOCK) {
            // Stop a previous instance first.
            stopDaemonLocked();
            try {
                Process p = startProcess(command, environment, logFile);
                mDaemonProcess = p;
                Log.i(TAG, "Daemon started, pid=" + pidOf(p));
                int exit = p.waitFor();
                Log.i(TAG, "Daemon exited with " + exit);
                return exit;
            } catch (Exception e) {
                Log.w(TAG, "startDaemon failed: " + e);
                return -1;
            } finally {
                mDaemonProcess = null;
            }
        }
    }

    @Override
    public void stopDaemon() {
        synchronized (DAEMON_LOCK) {
            stopDaemonLocked();
        }
    }

    private void stopDaemonLocked() {
        Process p = mDaemonProcess;
        if (p == null) {
            return;
        }
        Log.i(TAG, "Stopping daemon, pid=" + pidOf(p));
        try {
            // Syncthing shuts down gracefully on SIGINT.
            p.destroy();
        } catch (Exception e) {
            Log.w(TAG, "stopDaemon: " + e);
        }
        mDaemonProcess = null;
    }

    @Override
    public boolean isDaemonRunning() {
        Process p = mDaemonProcess;
        return p != null && isAlive(p);
    }

    private static boolean isAlive(Process process) {
        try {
            // Process#exitValue() throws IllegalThreadStateException while the process runs and is
            // available since API 1, unlike Process#isAlive() which requires API 26.
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    @Override
    public String[] list(String absolutePath) {
        File dir = new File(absolutePath);
        String[] names = dir.list();
        return names == null ? new String[0] : names;
    }

    @Override
    public boolean isDirectory(String absolutePath) {
        File f = new File(absolutePath);
        return f.isDirectory() || f.exists() && f.isDirectory();
    }

    @Override
    public boolean canWrite(String absolutePath) {
        File probe = new File(absolutePath, ".stwritetest-root");
        try {
            if (!probe.createNewFile() && !probe.exists()) {
                return false;
            }
            return probe.canWrite();
        } catch (IOException e) {
            Log.v(TAG, "canWrite: " + absolutePath + " -> " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }
    }

    @Override
    public boolean writeFile(String absolutePath, String content) {
        File target = new File(absolutePath);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(content.getBytes(Charsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "writeFile: " + absolutePath + " -> " + e);
            return false;
        }
    }

    @Override
    public String readFile(String absolutePath) {
        File source = new File(absolutePath);
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(source), Charsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            Log.w(TAG, "readFile: " + absolutePath + " -> " + e);
            return "";
        }
    }

    @Override
    public ParcelFileDescriptor openFile(String absolutePath, int mode) {
        try {
            return ParcelFileDescriptor.open(new File(absolutePath), mode);
        } catch (IOException e) {
            Log.w(TAG, "openFile: " + absolutePath + " -> " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------

    private static Process startProcess(String[] command, String[] environment, String logFile)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (environment != null && environment.length > 0) {
            for (String entry : environment) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    pb.environment().put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
        }
        if (logFile != null) {
            File log = new File(logFile);
            File parent = log.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            pb.redirectErrorStream(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            } else {
                // API < 26 has no ProcessBuilder.Redirect; let the shell do the redirection.
                return startShellRedirected(command, pb.environment(), log);
            }
        }
        return pb.start();
    }

    /**
     * Fallback for API &lt; 26 where {@code ProcessBuilder.Redirect} is unavailable.
     */
    @SuppressWarnings("deprecation")
    private static Process startShellRedirected(String[] command, Map<String, String> env, File log)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String part : command) {
            sb.append('\'').append(part.replace("'", "'\\''")).append('\'').append(' ');
        }
        sb.append(">> ").append('\'').append(log.getAbsolutePath().replace("'", "'\\''"))
                .append('\'').append(" 2>&1");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", sb.toString());
        pb.environment().putAll(env);
        return pb.start();
    }

    private static int pidOf(Process p) {
        try {
            // Android's Process implementation exposes pid via reflection on the internal field.
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return (int) f.get(p);
        } catch (Throwable t) {
            return -1;
        }
    }
}
