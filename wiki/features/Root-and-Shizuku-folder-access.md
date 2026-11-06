# Root / Shizuku folder access

Android scoped storage prevents an app from reading most locations outside its own sandbox, most
notably:

- `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb` (SAF blocks these)
- folders owned by *other* apps
- `/data/media/0` and anything below it

This build adds an **optional** elevated mode which runs the Syncthing daemon as a privileged user
so those paths can be used as normal sync folders.

## Enabling it

*Settings → Root / Shizuku folder access*

| Setting | Meaning |
| --- | --- |
| Enable elevated access | Master switch. Without it everything behaves like the stock app. |
| Backend | `Automatic`, `Shizuku`, `Root` |
| Request Shizuku permission | Shown when Shizuku is installed but not yet authorized. |

After changing anything, restart the Syncthing service (there is a shortcut on the same screen).

### Backends

- **Shizuku** – no root required. Requires the [Shizuku](https://github.com/RikkaApps/Shizuku) app
  and Android 11+ (wireless debugging) or a rooted device. The daemon runs as the `shell` user
  (uid 2000). This can reach `Android/data`, `Android/obb` and other apps' directories.
- **Root** – the daemon runs as uid 0, which can reach everything, including `/data/media/0`.
- **Automatic** – tries Shizuku first, falls back to root.

## How it works

1. `RootCompat` resolves a backend (`SHIZUKU` via a Shizuku *user service*, or `ROOT` by probing the
   `su` binary) and exposes a small AIDL interface, `IRootCompatService`, implemented by
   `RootCompatService`.
2. The daemon is not started by the app process anymore. `SyncthingRunnable` asks the backend to
   spawn it, so the process inherits the elevated UID.
3. The elevated user cannot read `/data/data/<pkg>/files`, so the Syncthing home is staged into
   `/data/local/tmp/syncthing-rootcompat/<versionCode>/home` (`RootCompatHome`):
   - `config.xml` and the TLS/key material are streamed through the backend on every start;
   - `config.xml` is read back when the daemon stops, so Web GUI changes reach the app;
   - the index database stays in the shared home permanently.
4. Daemon output is redirected into a log file inside that shared home
   (`RootCompatHome.getElevatedLogFile`) and tailed by `SyncthingRunnable.logElevatedOutput`.

## Choosing folders

The internal folder picker (`FolderPickerActivity`) adds the elevated roots
(`/data/media/0`, `/storage/emulated/0/Android`, …) and can list/stat them through the backend. The
overflow menu has **Go to path…** for typing an absolute path directly.

`FolderActivity` validates write access with `RootCompat.canWrite` when the sandbox check fails, and
pre-creates the `.stfolder` marker as the elevated user so Syncthing finds a writable folder.

## Limitations

- Shizuku's `shell` user cannot write into `/data/media/0`; use root for that.
- Files created by the elevated user may not be writable by the app itself afterwards.
- If the backend disappears (Shizuku stopped, root revoked) the daemon stops and the app falls back
  to the normal sandbox on the next start, which also means sync folders outside the sandbox become
  unreachable until elevated access is restored.

## Shizuku integration notes

The Shizuku binder handshake trips up a lot of integrations, so the two mandatory pieces are
documented here.

1. **Provider declaration.** `rikka.shizuku.ShizukuProvider` has to be declared in the manifest. It
   is a `ContentProvider`, not a `Service` or a `BroadcastReceiver`; the Shizuku server pushes its
   binder into it through `ContentResolver.call()`. The `android:permission` attribute must be
   `android.permission.INTERACT_ACROSS_USERS_FULL`, matching the upstream demo project. Using
   `moe.shizuku.manager.permission.API_V23` looks intuitive but breaks adb/shell mode: the Shizuku
   server does not hold that signature permission, so the call fails with a `SecurityException` and
   `Shizuku.pingBinder()` silently stays `false`.

   ```xml
   <provider
       android:name="rikka.shizuku.ShizukuProvider"
       android:authorities="${applicationId}.shizuku"
       android:enabled="true"
       android:exported="true"
       android:multiprocess="false"
       android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
   ```

2. **Binder request at process start.** `ShizukuProvider.attachInfo()` only validates its own
   declaration, so `SyncthingApp.onCreate()` calls
   `ShizukuProvider.requestBinderForNonProviderProcess(this)` to subscribe to
   `moe.shizuku.api.action.BINDER_RECEIVED`. Without it the binder never reaches the app process.

Verifying the handshake with `adb logcat | grep ShizukuProvider` should print `binder received`
shortly after the app starts. If it prints `Permission Denial: opening provider
rikka.shizuku.ShizukuProvider … requires moe.shizuku.manager.permission.API_V23`, the provider is
still using the wrong permission attribute.

## Runtime pitfalls

Getting the daemon to actually run elevated took more than the handshake above. The failures below
all looked like "the elevated backend is not available" but had distinct causes.

### An unattached client is not a missing backend

`Shizuku.pingBinder()` can already return `true` while the app process is still not attached as a
client. Every API call in that window - including `Shizuku.checkSelfPermission()` - throws
`IllegalStateException: Not an attached client`. Treating that as "Shizuku is unusable" made the
startup probe fail even though the connection landed a moment later. `RootCompat.isShizukuClientAttached()`
probes the attach state first, reports "not ready yet", and `awaitShizuku()` polls until it settles.

### Never block the main thread on the user service binder

The user service binder is delivered through a main thread broadcast. Waiting for it on the main
thread deadlocks: the broadcast cannot be dispatched while the main thread sits in
`wait()`/`Future.get()`. All binding goes through `bindUserServiceOffMainThread()`, `prepare()` runs
on a worker thread, and `SyncthingApp` warms the connection up in the background.

### `exec()` must not go through `startProcess()` on Shizuku

A `ShizukuRemoteProcess` only has usable streams inside the service process, so `startProcess()`
returns `null` and every command looked like it failed with `-1`, even though it had run. `exec()`
calls the service directly and returns the real exit code.

### The binary must be reachable by the elevated user

`/data/user/0/<pkg>` is `0700`, so uid 0 / 2000 cannot traverse into it and `startDaemon` failed
with `error=13, Permission denied`. `ensureBinaryExecutable()` first checks whether the elevated user
can execute the library in place (`/data/app/.../lib/<abi>` usually is world readable); if not, it
streams a copy into the staging directory and `chmod 755` it.

Stream that copy in chunks. Encoding the ~90 MB library into a single base64 string needs a 93 MB
allocation and blows the app heap with `OutOfMemoryError`, which surfaced only as a confusing
`ExecutableNotFoundException` from the fallback path.

### Staged files have to stay writable for the app

Files written by the elevated shell are owned by uid 2000. `chmod 600` therefore locked the app out
of `config.xml` and the keys. `RootCompatHome.copyFile()` uses `rm -f` before writing and
`chmod 666` afterwards.

## Verifying on a device

1. Enable the feature in *Settings → Root/Shizuku folder access* and grant Shizuku access. The
   status line must read `Active via Shizuku (uid 2000)`.
2. Restart Syncthing (main menu → *Restart*).
3. `adb shell ps -A | grep libsyncthingnative` must show `shell` (or `root`) as the user, not
   `u0_aNNN`.
4. `adb shell ls -la /data/local/tmp/syncthing-rootcompat/<versionCode>/home` shows `config.xml`,
   `cert.pem`, `key.pem`, `https-cert.pem`, `https-key.pem` and a growing `daemon.log`.
5. `adb shell cat /data/local/tmp/syncthing-rootcompat/<versionCode>/home/daemon.log` shows the
   Syncthing banner and `GUI and API listening`.

The Shizuku `shell` user holds `ext_data_rw` / `ext_obb_rw`, which is what makes
`/storage/emulated/0/Android/data` and `/Android/obb` accessible. Those same paths still need root
for `/data/media` and other apps' private data.
