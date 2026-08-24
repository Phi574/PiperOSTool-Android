package com.piperostool

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

enum class PiperRemoteMethod { LAN, QR, CODE, USB }

data class PiperRemoteEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val method: PiperRemoteMethod,
    val credential: String
)

data class PiperRemoteSession(
    val method: PiperRemoteMethod,
    val port: Int,
    val token: String,
    val code: String,
    val sessionId: String,
    val host: String
) {
    fun qrUri(): String = Uri.Builder()
        .scheme("piperos")
        .authority("remote")
        .appendQueryParameter("host", host)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("token", token)
        .build()
        .toString()
}

object PiperRemoteProtocol {
    const val MAGIC = "PIPER_REMOTE_2"
    const val DISCOVERY_PORT = 39776
    const val DISCOVER = "PIPER_REMOTE_DISCOVER"
    const val CODE_LOOKUP = "PIPER_REMOTE_CODE|"
    const val HOST_REPLY = "PIPER_REMOTE_HOST|"
    const val PACKET_FRAME: Byte = 1
    const val PACKET_TOUCH: Byte = 2
    const val PACKET_BACK: Byte = 3
    const val PACKET_HOME: Byte = 4
    const val PACKET_AUDIO_CONFIG: Byte = 5
    const val PACKET_AUDIO: Byte = 6
    const val USB_PORT = 39211
    const val USB_CREDENTIAL = "piperos-usb-adb-v1"

    fun randomCode(): String = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    fun randomToken(bytes: Int = 24): String {
        val value = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun deviceName(context: Context): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { context.getString(R.string.piperos_remote) }

    fun localIpv4(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"
}
