package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.webkit.WebViewFeature
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separates local user data by Firebase UID and encrypts preference values at rest. */
object AccountDataScope {
    private const val GLOBAL_STATE = "PiperAccountDataScope"
    private const val KEY_ACTIVE_UID = "active_uid"
    private const val KEY_LEGACY_WEB_OWNER = "legacy_web_owner"
    private const val MIGRATION_PREFS = "PiperAccountDataMigration"
    private const val ACCOUNT_PREFIX = "PiperAccount_"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "piperos.account."
    private const val SIGNED_OUT_UID = "signed-out"

    private val migratedPreferenceNames = listOf(
        "PiperPrefs",
        LockScreenActivity.PREFS_NAME,
        "PiperBrowserSession",
        "PiperBrowserExtensions",
        "PiperMockLocation",
        "PiperMockLocationRuntime",
        "piperos_media",
        "PiperTerminalPrefs",
        "piperos_terminal_keyboard"
    )

    fun currentUid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: SIGNED_OUT_UID

    fun accountId(uid: String = currentUid()): String = sha256(uid).take(24)

    /** The account active during migration keeps the legacy default WebView profile. */
    fun webViewProfileName(context: Context, uid: String = currentUid()): String? {
        val state = context.getSharedPreferences(GLOBAL_STATE, Context.MODE_PRIVATE)
        val owner = state.getString(KEY_LEGACY_WEB_OWNER, null)
        val currentAccount = accountId(uid)
        val ownsLegacyProfile = owner == currentAccount || owner == uid
        if (owner == uid) state.edit().putString(KEY_LEGACY_WEB_OWNER, currentAccount).apply()
        return if (ownsLegacyProfile) null else "piperos-user-$currentAccount"
    }

    fun preferences(context: Context, baseName: String): SharedPreferences {
        val uid = currentUid()
        val accountId = accountId(uid)
        val raw = context.getSharedPreferences(
            "$ACCOUNT_PREFIX${accountId}_$baseName",
            Context.MODE_PRIVATE
        )
        val encrypted = AccountEncryptedPreferences(raw, uid, baseName)
        migrateLegacyPreferences(context, baseName, encrypted, uid)
        return encrypted
    }

    fun directory(context: Context, category: String): File =
        File(context.filesDir, "accounts/${accountId()}/$category").apply { mkdirs() }

    fun file(context: Context, category: String, name: String): File =
        File(directory(context, category), name)

    fun externalDirectory(context: Context, category: String): File =
        File(context.getExternalFilesDir(null), "accounts/${accountId()}/$category").apply { mkdirs() }

    /** Called whenever Firebase changes identity. It terminates processes owned by the old UID. */
    fun activate(context: Context, uid: String?) {
        val app = context.applicationContext
        val normalized = uid ?: SIGNED_OUT_UID
        val activeAccount = accountId(normalized)
        val state = app.getSharedPreferences(GLOBAL_STATE, Context.MODE_PRIVATE)
        val previous = state.getString(KEY_ACTIVE_UID, null)
        val previousAccount = previous?.let {
            if (it.length == 24 && it.all { char -> char.isLowerCaseHexDigit() }) it else accountId(it)
        }
        if (previous != null && previous != previousAccount) {
            state.edit().putString(KEY_ACTIVE_UID, previousAccount).commit()
        }
        if (uid != null && !state.contains(KEY_LEGACY_WEB_OWNER)) {
            state.edit().putString(KEY_LEGACY_WEB_OWNER, activeAccount).commit()
        } else if (uid != null && state.getString(KEY_LEGACY_WEB_OWNER, null) == uid) {
            state.edit().putString(KEY_LEGACY_WEB_OWNER, activeAccount).commit()
        }
        if (previousAccount != activeAccount) {
            TerminalSessionManager.closeAll()
            BrowserVaultSession.clear()
            app.stopService(Intent(app, PiperTerminalService::class.java))
            app.stopService(Intent(app, PiperPlaybackService::class.java))
            app.stopService(Intent(app, MockLocationService::class.java))
            app.stopService(Intent(app, BrowserDownloadService::class.java))
            MockLocationRuntimeStore.clearRawAccount(app, previousAccount)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
            state.edit().putString(KEY_ACTIVE_UID, activeAccount).commit()
        }
        if (uid != null) {
            migratedPreferenceNames.forEach { preferences(app, it) }
            migrateLegacyFile(app, "custom_bg.jpg", file(app, "appearance", "custom_bg.jpg"))
            migrateLegacyDirectory(
                app,
                "terminal_home",
                File(app.filesDir, "home"),
                directory(app, "terminal/home")
            )
        }
    }

    private fun migrateLegacyDirectory(
        context: Context,
        markerName: String,
        legacy: File,
        target: File
    ) {
        val migration = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        val marker = "migrated_directory_$markerName"
        if (migration.getBoolean(marker, false)) return
        synchronized(this) {
            if (migration.getBoolean(marker, false)) return
            if (legacy.isDirectory && legacy.canonicalPath != target.canonicalPath) {
                target.mkdirs()
                legacy.listFiles()?.forEach { source ->
                    moveLegacyTree(source, File(target, source.name))
                }
                legacy.delete()
            }
            migration.edit().putBoolean(marker, true).commit()
        }
    }

    private fun moveLegacyTree(source: File, target: File) {
        if (!source.exists()) return
        if (!target.exists() && source.renameTo(target)) return
        // java.nio.file.Files requires API 26. Canonical/absolute comparison
        // detects links while keeping account migration compatible with API 24.
        val symbolicLink = runCatching {
            source.canonicalFile != source.absoluteFile
        }.getOrDefault(true)
        if (source.isDirectory && !symbolicLink) {
            target.mkdirs()
            source.listFiles()?.forEach { child ->
                moveLegacyTree(child, File(target, child.name))
            }
            source.delete()
        } else if (!target.exists() && !symbolicLink) {
            source.copyTo(target, overwrite = false)
            source.delete()
        }
    }

    private fun migrateLegacyFile(context: Context, legacyName: String, target: File) {
        val migration = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        val marker = "migrated_file_$legacyName"
        if (migration.getBoolean(marker, false)) return
        synchronized(this) {
            if (migration.getBoolean(marker, false)) return
            val legacy = File(context.filesDir, legacyName)
            if (legacy.isFile && !target.exists()) {
                target.parentFile?.mkdirs()
                if (!legacy.renameTo(target)) {
                    legacy.copyTo(target, overwrite = false)
                    legacy.delete()
                }
            }
            migration.edit().putBoolean(marker, true).commit()
        }
    }

    private fun migrateLegacyPreferences(
        context: Context,
        baseName: String,
        target: SharedPreferences,
        uid: String
    ) {
        if (uid == SIGNED_OUT_UID) return
        val migration = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        val marker = "migrated_$baseName"
        if (migration.getBoolean(marker, false)) return
        synchronized(this) {
            if (migration.getBoolean(marker, false)) return
            val legacy = context.getSharedPreferences(baseName, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                val editor = target.edit()
                legacy.all.forEach { (key, value) -> editor.putAny(key, value) }
                check(editor.commit()) { "Could not migrate $baseName" }
                legacy.edit().clear().commit()
            }
            migration.edit().putBoolean(marker, true).commit()
        }
    }

    private fun SharedPreferences.Editor.putAny(key: String, value: Any?): SharedPreferences.Editor =
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> this
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun Char.isLowerCaseHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

    private class AccountEncryptedPreferences(
        private val raw: SharedPreferences,
        private val uid: String,
        private val namespace: String
    ) : SharedPreferences {
        private val listeners = mutableMapOf<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = raw.all.mapNotNull { (key, value) ->
            decode(key, value as? String)?.let { key to it }
        }.toMap().toMutableMap()

        override fun getString(key: String, defValue: String?): String? = value(key) as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (value(key) as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = value(key) as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = value(key) as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = value(key) as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = value(key) as? Boolean ?: defValue
        override fun contains(key: String): Boolean = raw.contains(key)
        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> listener.onSharedPreferenceChanged(this, key) }
            synchronized(listeners) { listeners[listener] = wrapper }
            raw.registerOnSharedPreferenceChangeListener(wrapper)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            val wrapper = synchronized(listeners) { listeners.remove(listener) } ?: return
            raw.unregisterOnSharedPreferenceChangeListener(wrapper)
        }

        private fun value(key: String): Any? = decode(key, raw.getString(key, null))

        private fun decode(key: String, encoded: String?): Any? = runCatching {
            if (encoded == null) return null
            val clear = crypt(Cipher.DECRYPT_MODE, key, Base64.decode(encoded, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            when (clear.firstOrNull()) {
                's' -> clear.drop(2)
                'i' -> clear.drop(2).toInt()
                'l' -> clear.drop(2).toLong()
                'f' -> clear.drop(2).toFloat()
                'b' -> clear.drop(2) == "1"
                't' -> JSONArray(clear.drop(2)).let { array ->
                    buildSet { repeat(array.length()) { add(array.getString(it)) } }
                }
                else -> null
            }
        }.getOrNull()

        private fun encode(key: String, value: Any): String {
            val clear = when (value) {
                is String -> "s:$value"
                is Int -> "i:$value"
                is Long -> "l:$value"
                is Float -> "f:$value"
                is Boolean -> "b:${if (value) 1 else 0}"
                is Set<*> -> "t:${JSONArray(value.filterIsInstance<String>())}"
                else -> error("Unsupported preference type")
            }.toByteArray()
            return Base64.encodeToString(crypt(Cipher.ENCRYPT_MODE, key, clear), Base64.NO_WRAP)
        }

        private fun crypt(mode: Int, key: String, input: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secret = secretKey(uid)
            val aad = "$uid|$namespace|$key".toByteArray()
            return if (mode == Cipher.ENCRYPT_MODE) {
                cipher.init(mode, secret)
                cipher.updateAAD(aad)
                val encrypted = cipher.doFinal(input)
                ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
                    .put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
            } else {
                val buffer = ByteBuffer.wrap(input)
                val nonceSize = buffer.get().toInt() and 0xff
                require(nonceSize in 12..16 && buffer.remaining() > nonceSize)
                val nonce = ByteArray(nonceSize).also(buffer::get)
                val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
                cipher.init(mode, secret, GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                cipher.doFinal(encrypted)
            }
        }

        private inner class Editor : SharedPreferences.Editor {
            private val values = linkedMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { this.values[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun remove(key: String) = apply { values[key] = null }
            override fun clear() = apply { clear = true; values.clear() }
            override fun commit(): Boolean = write().commit()
            override fun apply() = write().apply()
            private fun write(): SharedPreferences.Editor = raw.edit().also { editor ->
                if (clear) editor.clear()
                values.forEach { (key, value) ->
                    if (value == null) editor.remove(key) else editor.putString(key, encode(key, value))
                }
            }
        }
    }

    private fun secretKey(uid: String): SecretKey {
        val alias = KEY_ALIAS_PREFIX + accountId(uid)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}
