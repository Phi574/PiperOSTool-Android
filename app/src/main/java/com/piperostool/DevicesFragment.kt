package com.piperostool

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileFilter
import java.util.regex.Pattern

class DevicesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_devices, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val manufacturer = Build.MANUFACTURER
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = "$manufacturer ${Build.MODEL}".trim()
        val androidVersion = Build.VERSION.RELEASE
        val apiLevel = Build.VERSION.SDK_INT.toString()
        val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "Không xác định"
        val hardware = Build.HARDWARE.ifBlank { "Không xác định" }
        val cores = getNumberOfCores().toString()
        val ram = getTotalRam(requireContext())
        val kernel = System.getProperty("os.version") ?: "Không xác định"

        view.findViewById<TextView>(R.id.tvDeviceHeadline).text = model
        view.findViewById<TextView>(R.id.tvAndroidOverview).text = androidVersion
        view.findViewById<TextView>(R.id.tvRamOverview).text = ram

        bindRow(view, R.id.rowDeviceModel, "Thiết bị", model)
        bindRow(view, R.id.rowAndroidVersion, "Phiên bản Android", androidVersion)
        bindRow(view, R.id.rowApiLevel, "API Level", apiLevel)
        bindRow(view, R.id.rowArchitecture, "Kiến trúc", architecture)
        bindRow(view, R.id.rowHardware, "Phần cứng", hardware)
        bindRow(view, R.id.rowCores, "Số nhân CPU", cores)
        bindRow(view, R.id.rowRam, "Tổng RAM", ram)
        bindRow(view, R.id.rowKernel, "Phiên bản Kernel", kernel)

        val deviceInfo = """
            PiperOS Device Info
            Thiết bị: $model
            Android: $androidVersion
            API Level: $apiLevel
            Kiến trúc: $architecture
            Phần cứng: $hardware
            Số nhân CPU: $cores
            Tổng RAM: $ram
            Kernel: $kernel
        """.trimIndent()

        view.findViewById<View>(R.id.btnCopyDeviceInfo).setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PiperOS Device Info", deviceInfo))
            Toast.makeText(requireContext(), "Đã sao chép thông tin thiết bị", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun bindRow(root: View, rowId: Int, label: String, value: String) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvDeviceLabel).text = label
        row.findViewById<TextView>(R.id.tvDeviceValue).text = value
    }

    private fun getNumberOfCores(): Int {
        return try {
            val files = File("/sys/devices/system/cpu/").listFiles(
                FileFilter { pathname -> Pattern.matches("cpu[0-9]+", pathname.name) }
            )
            files?.size ?: Runtime.getRuntime().availableProcessors()
        } catch (_: Exception) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    private fun getTotalRam(context: Context): String {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
        return String.format("%.2f GB", totalRamGb)
    }
}
