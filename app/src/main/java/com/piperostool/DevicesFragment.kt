package com.piperostool

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileFilter
import java.util.regex.Pattern

class DevicesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_devices, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ánh xạ View
        val tvDeviceModel = view.findViewById<TextView>(R.id.tvDeviceModel)
        val tvAndroidVersion = view.findViewById<TextView>(R.id.tvAndroidVersion)
        val tvApiLevel = view.findViewById<TextView>(R.id.tvApiLevel)

        val tvArchitecture = view.findViewById<TextView>(R.id.tvArchitecture)
        val tvHardware = view.findViewById<TextView>(R.id.tvHardware)
        val tvCores = view.findViewById<TextView>(R.id.tvCores)

        val tvRam = view.findViewById<TextView>(R.id.tvRam)
        val tvKernel = view.findViewById<TextView>(R.id.tvKernel)

        // 1. LẤY THÔNG TIN HỆ THỐNG
        tvDeviceModel.text = "Model: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
        tvAndroidVersion.text = "Android Version: ${Build.VERSION.RELEASE}"
        tvApiLevel.text = "API Level: ${Build.VERSION.SDK_INT}"

        // 2. LẤY THÔNG TIN CPU & CẤU TRÚC (ABI)
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "Unknown"
        tvArchitecture.text = "Architecture: $abi"
        tvHardware.text = "Hardware: ${Build.HARDWARE}"
        tvCores.text = "CPU Cores: ${getNumberOfCores()}"

        // 3. LẤY THÔNG TIN RAM & KERNEL
        tvRam.text = "Total RAM: ${getTotalRAM(requireContext())}"
        tvKernel.text = "Kernel Version: ${System.getProperty("os.version") ?: "Unknown"}"
    }

    // Hàm đếm số nhân CPU vật lý thực tế
    private fun getNumberOfCores(): Int {
        return try {
            val dir = File("/sys/devices/system/cpu/")
            val files = dir.listFiles(FileFilter { pathname ->
                Pattern.matches("cpu[0-9]+", pathname.name)
            })
            files?.size ?: Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    // Hàm lấy tổng dung lượng RAM (Đổi ra GB)
    private fun getTotalRAM(context: Context): String {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamGB = memInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
        return String.format("%.2f GB", totalRamGB)
    }
}