package com.ridesmart.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ridesmart.app.data.LocationData
import com.ridesmart.app.data.ProviderName

object DeepLinks {
    private const val TAG = "DeepLinks"

    fun openWithRoute(context: Context, provider: ProviderName, pickup: LocationData, drop: LocationData) {
        // Only Uber and Ola accept the pickup/drop through a link. Rapido and Namma Yatri
        // don't — firing a made-up scheme at them just shows a "not installed" popup, so
        // open them normally and let the caller type the destination in.
        if (!routeLinkWorks(provider)) {
            openApp(context, provider)
            return
        }
        val uri = when (provider) {
            ProviderName.UBER -> "uber://?action=setPickup" +
                "&pickup[latitude]=${pickup.latitude}&pickup[longitude]=${pickup.longitude}" +
                "&dropoff[latitude]=${drop.latitude}&dropoff[longitude]=${drop.longitude}"
            ProviderName.OLA -> "olacabs://app/launch?lat=${pickup.latitude}&lng=${pickup.longitude}" +
                "&drop_lat=${drop.latitude}&drop_lng=${drop.longitude}"
            else -> ""
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(provider.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "${provider.displayName} deep link failed: ${it.message}")
                openApp(context, provider)
            }
    }

    /** Which providers accept the pickup/drop route through their deep link. */
    fun routeLinkWorks(provider: ProviderName): Boolean =
        provider == ProviderName.UBER || provider == ProviderName.OLA

    /** Brings the app to the front on whatever screen it was on (e.g. an ongoing ride). */
    fun openApp(context: Context, provider: ProviderName) {
        val intent = context.packageManager.getLaunchIntentForPackage(provider.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun returnToRideSmart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
    }

    fun isInstalled(context: Context, provider: ProviderName): Boolean {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo(provider.packageName, 0)
            true
        } catch (e: Exception) {
            // Package-visibility quirks can make getPackageInfo throw even when installed;
            // a resolvable launch intent is a reliable second opinion.
            pm.getLaunchIntentForPackage(provider.packageName) != null
        }
    }
}
