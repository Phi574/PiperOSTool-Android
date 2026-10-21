package com.piperostool

/** Whitelist for the small, explicit settings-database editor surface. */
object PiperSetEditPolicy {
    enum class Table(val commandName: String, val displayName: String, val editable: Boolean, val highRisk: Boolean) {
        SYSTEM("system", "System Table", true, false),
        SECURE("secure", "Secure Table", true, true),
        GLOBAL("global", "Global Table", true, true),
        ANDROID_PROPERTIES("", "Android Properties", false, true),
        JAVA_PROPERTIES("", "Java Properties", false, true),
        LINUX_ENVIRONMENT("", "Linux Environment", false, true)
    }

    private val keyPattern = Regex("[A-Za-z0-9_.-]{1,160}")
    private val sensitivePattern = Regex("(?i)(security|lock|password|credential|adb|debug|accessibility|permission|location|install|device|account|privacy|biometric|keystore)")

    fun isValidKey(key: String): Boolean = keyPattern.matches(key)

    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun risk(table: Table, key: String): Risk = if (
        table != Table.SYSTEM || sensitivePattern.containsMatchIn(key)
    ) Risk.UNSAFE else Risk.RELATIVE_SAFE

    enum class Risk { RELATIVE_SAFE, UNSAFE }

    fun parseSettingsList(output: String): List<Pair<String, String>> = output
        .lineSequence()
        .mapNotNull { line ->
            val splitAt = line.indexOf('=')
            if (splitAt <= 0) return@mapNotNull null
            val key = line.substring(0, splitAt).trim()
            if (!isValidKey(key)) return@mapNotNull null
            key to line.substring(splitAt + 1)
        }
        .distinctBy { it.first }
        .sortedBy { it.first.lowercase() }
        .toList()
}
