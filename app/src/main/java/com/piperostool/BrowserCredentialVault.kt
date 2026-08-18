package com.piperostool

import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BrowserCredential(
    val id: String,
    val site: String,
    val origin: String,
    val username: String,
    val password: String,
    val createdAt: Long,
    val updatedAt: Long
)

object BrowserVaultSession {
    private var uid: String? = null
    private var key: ByteArray? = null

    @Synchronized
    fun unlock(userId: String, vaultKey: ByteArray) {
        clear()
        uid = userId
        key = vaultKey.copyOf()
    }

    @Synchronized
    fun keyFor(userId: String): ByteArray? =
        if (uid == userId) key?.copyOf() else null

    @Synchronized
    fun clear() {
        key?.fill(0)
        key = null
        uid = null
    }
}

class BrowserCredentialVault(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun isConfigured(callback: (Result<Boolean>) -> Unit) {
        val uid = currentUid(callback) ?: return
        config(uid).get()
            .addOnSuccessListener { callback(Result.success(it.exists())) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun create(pin: String, clearExisting: Boolean, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid(callback) ?: return
        runCatching { validatePin(pin) }.onFailure {
            callback(Result.failure(it))
            return
        }
        val proceed: () -> Unit = {
            val vaultKey = ByteArray(KEY_BYTES).also(random::nextBytes)
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val wrappingKey = deriveKey(pin, salt, KDF_ITERATIONS)
            val wrapped = encrypt(wrappingKey, vaultKey, configAad(uid))
            wrappingKey.fill(0)
            val data = mapOf(
                "version" to FORMAT_VERSION,
                "kdf" to "PBKDF2-HMAC-SHA256",
                "iterations" to KDF_ITERATIONS,
                "salt" to encode(salt),
                "wrappedKey" to encode(wrapped),
                "pinLength" to pin.length,
                "updatedAt" to System.currentTimeMillis()
            )
            config(uid).set(data)
                .addOnSuccessListener {
                    BrowserVaultSession.unlock(uid, vaultKey)
                    vaultKey.fill(0)
                    callback(Result.success(Unit))
                }
                .addOnFailureListener {
                    vaultKey.fill(0)
                    callback(Result.failure(it))
                }
        }
        if (clearExisting) clearEntries(uid, proceed, callback) else proceed()
    }

    fun unlock(pin: String, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid(callback) ?: return
        config(uid).get()
            .addOnSuccessListener { snapshot ->
                runCatching {
                    check(snapshot.exists()) { "Vault has not been configured" }
                    val salt = decode(requireNotNull(snapshot.getString("salt")))
                    val wrapped = decode(requireNotNull(snapshot.getString("wrappedKey")))
                    val iterations = snapshot.getLong("iterations")?.toInt() ?: KDF_ITERATIONS
                    val wrappingKey = deriveKey(pin, salt, iterations)
                    val vaultKey = decrypt(wrappingKey, wrapped, configAad(uid))
                    wrappingKey.fill(0)
                    check(vaultKey.size == KEY_BYTES) { "Invalid vault key" }
                    BrowserVaultSession.unlock(uid, vaultKey)
                    vaultKey.fill(0)
                }.fold(
                    onSuccess = { callback(Result.success(Unit)) },
                    onFailure = { callback(Result.failure(SecurityException("PIN is incorrect", it))) }
                )
            }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun changePin(oldPin: String, newPin: String, callback: (Result<Unit>) -> Unit) {
        validatePinResult(newPin, callback) ?: return
        unlock(oldPin) { unlocked ->
            unlocked.onFailure { callback(Result.failure(it)); return@unlock }
            val uid = auth.currentUser?.uid
                ?: return@unlock callback(Result.failure(SecurityException("Sign in required")))
            val vaultKey = BrowserVaultSession.keyFor(uid)
                ?: return@unlock callback(Result.failure(SecurityException("Vault is locked")))
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val wrappingKey = deriveKey(newPin, salt, KDF_ITERATIONS)
            val wrapped = encrypt(wrappingKey, vaultKey, configAad(uid))
            wrappingKey.fill(0)
            vaultKey.fill(0)
            config(uid).update(
                mapOf(
                    "iterations" to KDF_ITERATIONS,
                    "salt" to encode(salt),
                    "wrappedKey" to encode(wrapped),
                    "pinLength" to newPin.length,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).addOnSuccessListener { callback(Result.success(Unit)) }
                .addOnFailureListener { callback(Result.failure(it)) }
        }
    }

    fun load(callback: (Result<List<BrowserCredential>>) -> Unit) {
        val uid = currentUid(callback) ?: return
        val key = BrowserVaultSession.keyFor(uid)
            ?: return callback(Result.failure(SecurityException("Vault is locked")))
        entries(uid).get()
            .addOnSuccessListener { query ->
                runCatching {
                    query.documents.map { document ->
                        val encrypted = decode(requireNotNull(document.getString("payload")))
                        decodeCredential(
                            document.id,
                            decrypt(key, encrypted, entryAad(uid, document.id))
                        )
                    }.sortedByDescending(BrowserCredential::updatedAt)
                }.also { key.fill(0) }.let(callback)
            }
            .addOnFailureListener { key.fill(0); callback(Result.failure(it)) }
    }

    fun save(
        site: String,
        origin: String,
        username: String,
        password: String,
        existingId: String? = null,
        createdAt: Long? = null,
        callback: (Result<Unit>) -> Unit
    ) {
        val uid = currentUid(callback) ?: return
        val key = BrowserVaultSession.keyFor(uid)
            ?: return callback(Result.failure(SecurityException("Vault is locked")))
        val now = System.currentTimeMillis()
        val id = existingId ?: UUID.randomUUID().toString()
        val credential = BrowserCredential(
            id = id,
            site = site.trim().take(160),
            origin = origin.trim().take(500),
            username = username.trim().take(320),
            password = password.take(MAX_SECRET_LENGTH),
            createdAt = createdAt ?: now,
            updatedAt = now
        )
        val encrypted = encrypt(key, encodeCredential(credential), entryAad(uid, id))
        key.fill(0)
        entries(uid).document(id).set(
            mapOf(
                "version" to FORMAT_VERSION,
                "payload" to encode(encrypted),
                "createdAt" to credential.createdAt,
                "updatedAt" to credential.updatedAt
            )
        ).addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun delete(id: String, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid(callback) ?: return
        entries(uid).document(id).delete()
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun reset(pin: String, callback: (Result<Unit>) -> Unit) =
        create(pin, clearExisting = true, callback)

    private fun clearEntries(
        uid: String,
        onSuccess: () -> Unit,
        callback: (Result<Unit>) -> Unit
    ) {
        entries(uid).get().addOnSuccessListener { query ->
            val batch = firestore.batch()
            query.documents.forEach { batch.delete(it.reference) }
            batch.delete(config(uid))
            batch.commit().addOnSuccessListener { onSuccess() }
                .addOnFailureListener { callback(Result.failure(it)) }
        }.addOnFailureListener { callback(Result.failure(it)) }
    }

    private fun encodeCredential(value: BrowserCredential): ByteArray = JSONObject()
        .put("site", value.site)
        .put("origin", value.origin)
        .put("username", value.username)
        .put("password", value.password)
        .put("createdAt", value.createdAt)
        .put("updatedAt", value.updatedAt)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decodeCredential(id: String, bytes: ByteArray): BrowserCredential {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        bytes.fill(0)
        return BrowserCredential(
            id = id,
            site = json.optString("site"),
            origin = json.optString("origin"),
            username = json.optString("username"),
            password = json.optString("password"),
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt")
        )
    }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(key: ByteArray, clear: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(clear)
        clear.fill(0)
        return ByteBuffer.allocate(nonce.size + ciphertext.size).put(nonce).put(ciphertext).array()
    }

    private fun decrypt(key: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(encrypted.size > NONCE_BYTES + 16) { "Invalid encrypted payload" }
        val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
        val ciphertext = encrypted.copyOfRange(NONCE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun validatePin(pin: String) {
        require(pin.length in 4..64) { "PIN must contain 4 to 64 characters" }
    }

    private fun <T> validatePinResult(pin: String, callback: (Result<T>) -> Unit): Unit? =
        runCatching { validatePin(pin) }.fold(
            onSuccess = { Unit },
            onFailure = { callback(Result.failure(it)); null }
        )

    private fun config(uid: String) =
        firestore.collection("users").document(uid).collection("browserVault").document("config")

    private fun entries(uid: String) =
        firestore.collection("users").document(uid).collection("browserVaultEntries")

    private fun configAad(uid: String) = "piperos-vault-config:$uid".toByteArray()
    private fun entryAad(uid: String, id: String) = "piperos-vault-entry:$uid:$id".toByteArray()
    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    private fun <T> currentUid(callback: (Result<T>) -> Unit): String? =
        auth.currentUser?.uid ?: run {
            callback(Result.failure(SecurityException("Sign in to PiperOS Tool first")))
            null
        }

    companion object {
        private const val FORMAT_VERSION = 1L
        private const val KDF_ITERATIONS = 600_000
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val MAX_SECRET_LENGTH = 4096
        private val random = SecureRandom()
    }
}
