package com.piperostool

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar

object NetworkAccess {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun observe(
        owner: LifecycleOwner,
        context: Context,
        onChanged: (Boolean) -> Unit
    ) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val mainHandler = Handler(Looper.getMainLooper())
        var lastValue: Boolean? = null

        fun publish() {
            mainHandler.post {
                val value = isOnline(context)
                if (lastValue != value) {
                    lastValue = value
                    onChanged(value)
                }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = publish()
        }

        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lastValue = null
                runCatching { manager.registerDefaultNetworkCallback(callback) }
                publish()
            }

            override fun onStop(owner: LifecycleOwner) {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        })
    }

    fun showOffline(anchor: View) {
        Snackbar.make(
            anchor,
            anchor.context.getString(R.string.offline_message),
            7_000
        ).setAction(R.string.dismiss) { }.show()
    }

    fun requireOnline(anchor: View, action: () -> Unit) {
        if (isOnline(anchor.context)) action() else showOffline(anchor)
    }
}
