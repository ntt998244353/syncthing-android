package com.nutomic.syncthingandroid.service;

import android.os.ParcelFileDescriptor;

/**
 * Dedicated AIDL surface used when Syncthing does not support a custom fd transport
 * (kept for compatibility with the root/Shizuku = root-compat folder access feature).
 */
interface IRootCompatService {

    /**
     * @return uid the elevated service runs as
     */
    int getUid();

    /**
     * Runs a command and returns the process (stdout/stderr streams of a
     * {@code rikka.shizuku.ShizukuRemoteProcess} are only usable in-process, so this is *not*
     * used for the long running daemon - see {@link #startDaemon}).
     */
    int exec(in String[] command, in String[] environment);

    /**
     * Starts the Syncthing daemon in the elevated context.
     * <p>
     * The process' stdout/stderr are redirected inside the elevated context into the given log
     * file, so the app can follow it without holding a pipe. The call blocks until the daemon
     * exits and returns its exit code.
     *
     * @param command     full argv of the daemon
     * @param environment extra environment variables ("KEY=value")
     * @param logFile     absolute path the elevated process may write to
     * @return exit code of the daemon
     */
    int startDaemon(in String[] command, in String[] environment, String logFile);

    /** Aborts a running {@link #startDaemon}. */
    void stopDaemon();

    boolean isDaemonRunning();

    String[] list(String absolutePath);

    boolean isDirectory(String absolutePath);

    boolean canWrite(String absolutePath);

    boolean writeFile(String absolutePath, String content);

    String readFile(String absolutePath);

    ParcelFileDescriptor openFile(String absolutePath, int mode);
}
