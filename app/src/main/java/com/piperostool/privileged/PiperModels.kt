package com.piperostool.privileged

import android.content.Context
import android.os.Bundle

enum class PiperPrivilege { STANDARD, SHELL, ROOT }

enum class PiperServiceState { STOPPED, STARTING, RUNNING, ERROR }

enum class PiperError {
    NONE,
    SERVICE_NOT_RUNNING,
    ADB_NOT_AUTHORIZED,
    ROOT_DENIED,
    ROOT_NOT_AVAILABLE,
    BINDER_DIED,
    PERMISSION_DENIED,
    SELINUX_DENIED,
    READ_ONLY_FILESYSTEM,
    UNSUPPORTED_OPERATION,
    CLIENT_NOT_AUTHORIZED,
    TIMEOUT,
    CANCELLED,
    IO_ERROR;

    companion object {
        fun fromMessage(message: String?): PiperError = when {
            message.isNullOrBlank() -> NONE
            "permission denied" in message.lowercase() -> PERMISSION_DENIED
            "read-only file system" in message.lowercase() -> READ_ONLY_FILESYSTEM
            "avc: denied" in message.lowercase() -> SELINUX_DENIED
            "timed out" in message.lowercase() -> TIMEOUT
            else -> IO_ERROR
        }
    }
}

data class PiperCapabilities(
    val privilege: PiperPrivilege = PiperPrivilege.STANDARD,
    val canAccessAndroidData: Boolean = false,
    val canAccessAndroidObb: Boolean = false,
    val canReadSystemFiles: Boolean = false,
    val canWriteSystemFiles: Boolean = false,
    val canUsePackageManager: Boolean = false,
    val canUseAppOps: Boolean = false,
    val canChmod: Boolean = false,
    val canChown: Boolean = false,
    val canExecutePrivilegedCommands: Boolean = false,
    val canManageProcesses: Boolean = false
) {
    fun toBundle() = Bundle().apply {
        putString(KEY_PRIVILEGE, privilege.name)
        putBoolean(KEY_ANDROID_DATA, canAccessAndroidData)
        putBoolean(KEY_ANDROID_OBB, canAccessAndroidObb)
        putBoolean(KEY_SYSTEM_READ, canReadSystemFiles)
        putBoolean(KEY_SYSTEM_WRITE, canWriteSystemFiles)
        putBoolean(KEY_PACKAGE_MANAGER, canUsePackageManager)
        putBoolean(KEY_APP_OPS, canUseAppOps)
        putBoolean(KEY_CHMOD, canChmod)
        putBoolean(KEY_CHOWN, canChown)
        putBoolean(KEY_COMMANDS, canExecutePrivilegedCommands)
        putBoolean(KEY_PROCESSES, canManageProcesses)
    }

    companion object {
        private const val KEY_PRIVILEGE = "privilege"
        private const val KEY_ANDROID_DATA = "android_data"
        private const val KEY_ANDROID_OBB = "android_obb"
        private const val KEY_SYSTEM_READ = "system_read"
        private const val KEY_SYSTEM_WRITE = "system_write"
        private const val KEY_PACKAGE_MANAGER = "package_manager"
        private const val KEY_APP_OPS = "app_ops"
        private const val KEY_CHMOD = "chmod"
        private const val KEY_CHOWN = "chown"
        private const val KEY_COMMANDS = "commands"
        private const val KEY_PROCESSES = "processes"

        fun fromBundle(bundle: Bundle?) = PiperCapabilities(
            privilege = runCatching {
                PiperPrivilege.valueOf(bundle?.getString(KEY_PRIVILEGE).orEmpty())
            }.getOrDefault(PiperPrivilege.STANDARD),
            canAccessAndroidData = bundle?.getBoolean(KEY_ANDROID_DATA) == true,
            canAccessAndroidObb = bundle?.getBoolean(KEY_ANDROID_OBB) == true,
            canReadSystemFiles = bundle?.getBoolean(KEY_SYSTEM_READ) == true,
            canWriteSystemFiles = bundle?.getBoolean(KEY_SYSTEM_WRITE) == true,
            canUsePackageManager = bundle?.getBoolean(KEY_PACKAGE_MANAGER) == true,
            canUseAppOps = bundle?.getBoolean(KEY_APP_OPS) == true,
            canChmod = bundle?.getBoolean(KEY_CHMOD) == true,
            canChown = bundle?.getBoolean(KEY_CHOWN) == true,
            canExecutePrivilegedCommands = bundle?.getBoolean(KEY_COMMANDS) == true,
            canManageProcesses = bundle?.getBoolean(KEY_PROCESSES) == true
        )
    }
}

data class PiperServiceStatus(
    val state: PiperServiceState = PiperServiceState.STOPPED,
    val privilege: PiperPrivilege = PiperPrivilege.STANDARD,
    val uid: Int = -1,
    val pid: Int = -1,
    val startupMethod: String = "STANDARD",
    val selinux: String = "Unknown",
    val startedAt: Long = 0L,
    val serviceVersion: String = "1.0",
    val protocolVersion: Int = 1,
    val error: PiperError = PiperError.NONE,
    val detail: String = ""
) {
    fun toBundle() = Bundle().apply {
        putString("state", state.name)
        putString("privilege", privilege.name)
        putInt("uid", uid)
        putInt("pid", pid)
        putString("startup_method", startupMethod)
        putString("selinux", selinux)
        putLong("started_at", startedAt)
        putString("service_version", serviceVersion)
        putInt("protocol_version", protocolVersion)
        putString("error", error.name)
        putString("detail", detail)
    }

    companion object {
        fun fromBundle(bundle: Bundle?) = PiperServiceStatus(
            state = enumValue(bundle?.getString("state"), PiperServiceState.STOPPED),
            privilege = enumValue(bundle?.getString("privilege"), PiperPrivilege.STANDARD),
            uid = bundle?.getInt("uid", -1) ?: -1,
            pid = bundle?.getInt("pid", -1) ?: -1,
            startupMethod = bundle?.getString("startup_method").orEmpty().ifBlank { "STANDARD" },
            selinux = bundle?.getString("selinux").orEmpty().ifBlank { "Unknown" },
            startedAt = bundle?.getLong("started_at", 0L) ?: 0L,
            serviceVersion = bundle?.getString("service_version").orEmpty().ifBlank { "1.0" },
            protocolVersion = bundle?.getInt("protocol_version", 1) ?: 1,
            error = enumValue(bundle?.getString("error"), PiperError.NONE),
            detail = bundle?.getString("detail").orEmpty()
        )

        private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
            runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
    }
}

data class PiperFileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val mode: String,
    val uid: Int,
    val gid: Int,
    val symlinkTarget: String? = null,
    val hidden: Boolean = name.startsWith('.')
)

object PiperPrivilegedPreferences {
    const val METHOD_AUTO = "auto"
    const val METHOD_SU = "su"
    const val METHOD_SHIZUKU = "shizuku"

    private const val FILE = "piperos_privileged"
    private const val KEY_METHOD = "method"
    private const val KEY_ANDROID_RESTRICTED = "android_restricted"
    private const val KEY_SYSTEM_FILES = "system_files"
    private const val KEY_SYSTEM_WRITE = "system_write"
    private const val KEY_WORKSPACE = "workspace"
    private const val KEY_HIDDEN = "hidden"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun method(context: Context) = prefs(context).getString(KEY_METHOD, METHOD_AUTO) ?: METHOD_AUTO
    fun setMethod(context: Context, value: String) = prefs(context).edit().putString(KEY_METHOD, value).apply()
    fun androidRestricted(context: Context) = prefs(context).getBoolean(KEY_ANDROID_RESTRICTED, false)
    fun setAndroidRestricted(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ANDROID_RESTRICTED, value).apply()
    fun systemFiles(context: Context) = prefs(context).getBoolean(KEY_SYSTEM_FILES, false)
    fun setSystemFiles(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SYSTEM_FILES, value).apply()
    fun systemWrite(context: Context) = prefs(context).getBoolean(KEY_SYSTEM_WRITE, false)
    fun setSystemWrite(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SYSTEM_WRITE, value).apply()
    fun workspace(context: Context) = prefs(context).getBoolean(KEY_WORKSPACE, false)
    fun setWorkspace(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_WORKSPACE, value).apply()
    fun showHidden(context: Context) = prefs(context).getBoolean(KEY_HIDDEN, false)
    fun setShowHidden(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HIDDEN, value).apply()
}
