package com.piperostool.privileged.adb

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.app.RemoteInput
import com.piperostool.R
import com.piperostool.privileged.IPiperOSService
import com.piperostool.privileged.server.PiperPrivilegedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PiperAdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PiperAdbPairingNotifications.ACTION_SUBMIT_CODE) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PiperAdbPairingNotifications.REMOTE_INPUT_CODE)
            ?.toString()?.trim().orEmpty()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!code.matches(Regex("\\d{6}"))) {
                    PiperAdbPairingNotifications.showError(
                        appContext,
                        appContext.getString(R.string.pps_pairing_code_invalid)
                    )
                    return@launch
                }
                PiperAdbPairingNotifications.showPairing(appContext)
                val port = PiperAdbBootstrap.discoverPairingPort(appContext).getOrThrow()
                check(PiperAdbBootstrap.pair(appContext, port, code).getOrThrow()) {
                    appContext.getString(R.string.pps_pairing_failed_message)
                }
                refreshPps(appContext)
                PiperAdbPairingNotifications.showSuccess(appContext)
            } catch (error: Throwable) {
                PiperAdbPairingNotifications.showError(
                    appContext,
                    error.message ?: appContext.getString(R.string.pps_pairing_failed_message)
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun refreshPps(context: Context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runCatching {
                    IPiperOSService.Stub.asInterface(binder).refreshCapabilities()
                }
                runCatching { context.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        check(context.bindService(
            Intent(context, PiperPrivilegedService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )) { "Khong the khoi dong PiperOS Privileged Service" }
    }
}
