package com.piperostool.privileged.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object PiperAdbBootstrap {
    suspend fun autoConnect(context: Context, timeoutMs: Long = 8_000L): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Wireless debugging requires Android 11 or newer"
                }
                val manager = PiperAdbConnectionManager.getInstance(context)
                manager.isConnected || manager.autoConnect(context.applicationContext, timeoutMs)
            }
        }

    suspend fun discoverPairingPort(context: Context, timeoutMs: Long = 25_000L): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Wireless debugging requires Android 11 or newer"
                }
                val port = AtomicInteger(-1)
                val latch = CountDownLatch(1)
                val mdns = AdbMdns(
                    context.applicationContext,
                    AdbMdns.SERVICE_TYPE_TLS_PAIRING
                ) { _, discoveredPort ->
                    if (discoveredPort > 0) port.set(discoveredPort)
                    latch.countDown()
                }
                mdns.start()
                try {
                    check(latch.await(timeoutMs, TimeUnit.MILLISECONDS) && port.get() > 0) {
                        "Pairing port was not found. Keep the pairing-code screen open."
                    }
                } finally {
                    mdns.stop()
                }
                port.get()
            }
        }

    suspend fun pair(context: Context, port: Int, code: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(code.matches(Regex("\\d{6}"))) { "Pairing code must contain 6 digits" }
                val manager = PiperAdbConnectionManager.getInstance(context)
                val paired = manager.pair(AndroidUtils.getHostIpAddress(context), port, code)
                check(paired) { "Android rejected the pairing code" }
                manager.disconnect()
                paired
            }
        }
}
