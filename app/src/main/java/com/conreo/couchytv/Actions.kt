package com.conreo.couchytv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object Actions {

    fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun launchApp(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { toast(context, context.getString(R.string.toast_cannot_open)) }
        } else toast(context, context.getString(R.string.toast_no_launchable))
    }

    fun openAppInfo(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg")
                )
            )
        }
    }

    fun uninstall(context: Context, pkg: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
    }

    /** Best-effort: clears the app from cached memory. True force-stop is in App info. */
    fun close(context: Context, pkg: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        runCatching { am.killBackgroundProcesses(pkg) }
    }

    /**
     * Continue-watching card: open the exact content through the publisher's
     * `intent_uri`, falling back to simply opening the app when the URI is
     * missing or no longer resolvable (stale rows happen).
     *
     * Two URI forms are published:
     *  • Watch Next writes `intent:` URIs (parsed with URI_INTENT_SCHEME);
     *  • preview channels write custom schemes (`seena://…`,
     *    `tvhomechannels://…`) that resolve through a plain ACTION_VIEW. LMD's
     *    URIs embed comma-separated, base64-encoded fields with real newlines
     *    in them — truncating at the first line corrupts the payload and makes
     *    the publisher crash, so the whole value is kept. The package is
     *    pinned so another app can't hijack the scheme.
     */
    fun launchProgram(context: Context, pkg: String, intentUri: String?) {
        val raw = intentUri?.trim()?.takeIf { it.isNotEmpty() }
        val intent = when {
            raw == null -> null
            raw.startsWith("intent:", ignoreCase = true) ->
                runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            else -> runCatching {
                Intent(Intent.ACTION_VIEW, Uri.parse(raw)).setPackage(pkg)
            }.getOrNull()
        }
        // Own task: a bare startActivity would put the publisher's activity in
        // the launcher's task, and players that expect to be the task root
        // (Seena does) immediately finish themselves.
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { launchApp(context, pkg) }
        } else launchApp(context, pkg)
    }

    fun openSystemSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun openNetworkSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { openSystemSettings(context) }
    }

    fun openVpnSettings(context: Context) {
        runCatching { context.startActivity(Intent("android.settings.VPN_SETTINGS")) }
            .onFailure { openSystemSettings(context) }
    }

    /** AerialViews screensaver (github.com/theothernt/AerialViews) */
    const val AERIAL_PKG = "com.neilturner.aerialviews"

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun openAppStore(context: Context, pkg: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        }.onFailure {
            toast(context, context.getString(R.string.toast_no_store))
        }
    }

}
