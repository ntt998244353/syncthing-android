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
