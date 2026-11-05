package com.nutomic.syncthingandroid.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.RootCompat
import com.nutomic.syncthingandroid.util.RootCompatHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState
import rikka.shizuku.Shizuku

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

fun EntryProviderScope<SettingsRoute>.settingsRootCompatEntry() {
    entry<SettingsRoute.RootCompat> {
        SettingsRootCompatScreen()
    }
}

/**
 * Settings for running Syncthing with elevated privileges (root or Shizuku) so that folders which
 * are not accessible to the app sandbox can be used as sync folders.
 */
@Composable
fun SettingsRootCompatScreen() {
    val context = LocalContext.current
    val activity = context as? SettingsActivity

    val enabled = rememberPreferenceState(Constants.PREF_ROOT_COMPAT_ENABLED, false)
    val mode = rememberPreferenceState(
        Constants.PREF_ROOT_COMPAT_MODE,
        Constants.RootCompatMode.AUTO,
    )

    // Backend state needs to be recomputed when it changes (permission granted, Shizuku started...)
    var statusRefresh by remember { mutableStateOf(0) }
    var refreshTick by remember { mutableStateOf(0) }
    var elevatedUid by remember { mutableStateOf(-1) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermissionGranted by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }

    suspend fun refreshStatus() {
        withContext(Dispatchers.IO) {
            shizukuInstalled = RootCompat.isShizukuInstalled(context)
            shizukuRunning = RootCompat.isShizukuAvailable(context) ||
                    RootCompat.isShizukuPermissionPending(context)
            shizukuPermissionGranted = RootCompat.isShizukuAvailable(context)
            rootAvailable = RootCompat.isRootAvailable()
            elevatedUid = if (RootCompat.isActive(context)) RootCompat.getElevatedUid(context) else -1
        }
        statusRefresh++
    }

    LaunchedEffect(enabled.value, mode.value, refreshTick) {
        refreshStatus()
    }

    // Registering the permission listener lets us react to the Shizuku grant dialog.
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                refreshTick++
            } else {
                Toast.makeText(
                    context,
                    R.string.root_compat_status_shizuku_permission,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        val binderReceived = Shizuku.OnBinderReceivedListener {
            refreshTick++
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
        }
        onDispose {
            runCatching {
                Shizuku.removeRequestPermissionResultListener(listener)
                Shizuku.removeBinderReceivedListener(binderReceived)
            }
        }
    }

    val statusText = when {
        !enabled.value -> stringResource(R.string.root_compat_status_disabled)
        !shizukuInstalled && !rootAvailable -> stringResource(R.string.root_compat_status_no_backend)
        RootCompat.isActive(context) -> stringResource(
            if (mode.value == Constants.RootCompatMode.ROOT) R.string.root_compat_status_root
            else R.string.root_compat_status_shizuku,
            elevatedUid,
        )
        shizukuInstalled && !shizukuRunning -> stringResource(R.string.root_compat_status_shizuku_not_running)
        shizukuInstalled && !shizukuPermissionGranted -> stringResource(R.string.root_compat_status_shizuku_permission)
        mode.value == Constants.RootCompatMode.SHIZUKU && !shizukuInstalled ->
            stringResource(R.string.root_compat_status_shizuku_missing)
        else -> stringResource(R.string.root_compat_status_no_backend)
    }

    SettingsScaffold(
        title = stringResource(R.string.root_compat_title),
        description = stringResource(R.string.root_compat_summary),
    ) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.root_compat_enable_title)) },
                summary = { Text(stringResource(R.string.root_compat_enable_summary)) },
                state = enabled,
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.root_compat_status_title)) },
                summary = { Text(statusText) },
                onClick = {
                    refreshTick++
                },
            )
        }
        if (enabled.value) {
            item {
                ListPreference(
                    title = { Text(stringResource(R.string.root_compat_mode_title)) },
                    summary = {
                        Text(
                            when (mode.value) {
                                Constants.RootCompatMode.SHIZUKU -> stringResource(R.string.root_compat_mode_shizuku)
                                Constants.RootCompatMode.ROOT -> stringResource(R.string.root_compat_mode_root)
                                else -> stringResource(R.string.root_compat_mode_auto)
                            }
                        )
                    },
                    state = mode,
                    values = listOf(
                        Constants.RootCompatMode.AUTO,
                        Constants.RootCompatMode.SHIZUKU,
                        Constants.RootCompatMode.ROOT,
                    ),
                    valueToText = { value ->
                        AnnotatedString(
                            when (value) {
                                Constants.RootCompatMode.SHIZUKU -> stringResource(R.string.root_compat_mode_shizuku)
                                Constants.RootCompatMode.ROOT -> stringResource(R.string.root_compat_mode_root)
                                else -> stringResource(R.string.root_compat_mode_auto)
                            }
                        )
                    },
                )
            }
            if (shizukuInstalled && (!shizukuRunning || !shizukuPermissionGranted)) {
                item {
                    Preference(
                        title = { Text(stringResource(R.string.root_compat_request_permission)) },
                        onClick = {
                            if (shizukuRunning) {
                                RootCompat.requestShizukuPermission()
                            } else {
                                runCatching {
                                    context.startActivity(
                                        context.packageManager
                                            .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                                            ?: Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("market://details?id=$SHIZUKU_PACKAGE"),
                                            ),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (!shizukuInstalled && !rootAvailable) {
                item {
                    Preference(
                        title = { Text(stringResource(R.string.root_compat_install_shizuku)) },
                        summary = { Text(SHIZUKU_PACKAGE) },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=$SHIZUKU_PACKAGE"),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            item {
                Preference(
                    title = { Text(stringResource(R.string.root_compat_shared_home_title)) },
                    summary = {
                        Text(
                            stringResource(
                                R.string.root_compat_shared_home_summary,
                                RootCompatHome.getSharedHome(context).absolutePath,
                            )
                        )
                    },
                )
            }
            item {
                Preference(
                    title = { Text(stringResource(R.string.root_compat_restart_required)) },
                    onClick = {
                        activity?.startService(
                            Intent(context, SyncthingService::class.java)
                                .setAction(SyncthingService.ACTION_RESTART),
                        )
                        Toast.makeText(
                            context,
                            R.string.root_compat_restart_required,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }
}
