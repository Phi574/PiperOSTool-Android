package com.piperostool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TerminalRuntimeInstallCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        TerminalRuntimeInstallService.cancelUserInitiatedInstall(context)
    }
}
