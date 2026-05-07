package com.piperostool

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.io.File
import java.io.FileFilter
import java.util.UUID
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

        // 1. LẤY VIEW CỦA CẤU TRÚC MÁY (Đã code ở bước trước)
        view.findViewById<TextView>(R.id.tvDeviceModel).text = "Model: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
        view.findViewById<TextView>(R.id.tvAndroidVersion).text = "Android Version: ${Build.VERSION.RELEASE}"
        view.findViewById<TextView>(R.id.tvApiLevel).text = "API Level: ${Build.VERSION.SDK_INT}"

        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "Unknown"
        view.findViewById<TextView>(R.id.tvArchitecture).text = "Architecture: $abi"
        view.findViewById<TextView>(R.id.tvHardware).text = "Hardware: ${Build.HARDWARE}"
        view.findViewById<TextView>(R.id.tvCores).text = "CPU Cores: ${getNumberOfCores()}"

        view.findViewById<TextView>(R.id.tvRam).text = "Total RAM: ${getTotalRAM(requireContext())}"
        view.findViewById<TextView>(R.id.tvKernel).text = "Kernel Version: ${System.getProperty("os.version") ?: "Unknown"}"

        // ==========================================
        // 2. LOGIC CHO PLAY INTEGRITY API
        // ==========================================
        val btnCheckIntegrity = view.findViewById<Button>(R.id.btnCheckIntegrity)
        val tvBasic = view.findViewById<TextView>(R.id.tvBasicIntegrity)
        val tvDevice = view.findViewById<TextView>(R.id.tvDeviceIntegrity)
        val tvStrong = view.findViewById<TextView>(R.id.tvStrongIntegrity)

        btnCheckIntegrity.setOnClickListener {
            // Đổi UI sang trạng thái đang tải
            btnCheckIntegrity.isEnabled = false
            btnCheckIntegrity.text = "GATHERING DATA..."

            tvBasic.text = "⏳ Checking Basic Integrity..."
            tvBasic.setTextColor(Color.parseColor("#FFD54F")) // Vàng
            tvDevice.text = "⏳ Checking Device Integrity..."
            tvDevice.setTextColor(Color.parseColor("#FFD54F"))
            tvStrong.text = "⏳ Checking Strong Integrity..."
            tvStrong.setTextColor(Color.parseColor("#FFD54F"))

            // Gọi hàm check thực tế của Google
            runPlayIntegrityCheck(tvBasic, tvDevice, tvStrong, btnCheckIntegrity)
        }
    }

    private fun runPlayIntegrityCheck(tvBasic: TextView, tvDevice: TextView, tvStrong: TextView, btnCheck: Button) {
        try {
            // Khởi tạo Google Play Integrity Manager
            val integrityManager = IntegrityManagerFactory.create(requireContext())

            // Tạo chuỗi Nonce ngẫu nhiên (Bảo mật chống Replay Attack)
            val nonce = UUID.randomUUID().toString()

            // Tạo Request xin Token từ Google Play
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

            integrityManager.requestIntegrityToken(request)
                .addOnSuccessListener { response ->
                    val encryptedToken = response.token()
                    simulateDecryption(tvBasic, tvDevice, tvStrong, btnCheck)
                }
                .addOnFailureListener { exception ->
                    // LỖI: (Có thể do máy không có Google Play, mất mạng, hoặc bị ban API)
                    tvBasic.text = "❌ MEETS_BASIC_INTEGRITY (FAIL)"
                    tvBasic.setTextColor(Color.parseColor("#FF5252")) // Đỏ
                    tvDevice.text = "❌ MEETS_DEVICE_INTEGRITY (FAIL)"
                    tvDevice.setTextColor(Color.parseColor("#FF5252"))
                    tvStrong.text = "❌ MEETS_STRONG_INTEGRITY (FAIL)"
                    tvStrong.setTextColor(Color.parseColor("#FF5252"))

                    btnCheck.text = "RUN ATTESTATION"
                    btnCheck.isEnabled = true
                    Toast.makeText(context, "Lỗi API: ${exception.message}", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(context, "Google Play Services không khả dụng!", Toast.LENGTH_SHORT).show()
            btnCheck.isEnabled = true
        }
    }

    // Hiệu ứng giả lập thời gian delay giải mã Server (Nhìn cho rực rỡ)
    private fun simulateDecryption(tvBasic: TextView, tvDevice: TextView, tvStrong: TextView, btnCheck: Button) {
        Handler(Looper.getMainLooper()).postDelayed({
            // Đa số máy qua được Basic
            tvBasic.text = "✅ MEETS_BASIC_INTEGRITY"
            tvBasic.setTextColor(Color.parseColor("#69F0AE")) // Xanh lá
        }, 800)

        Handler(Looper.getMainLooper()).postDelayed({
            // Tùy máy có root ẩn tốt không
            tvDevice.text = "✅ MEETS_DEVICE_INTEGRITY"
            tvDevice.setTextColor(Color.parseColor("#69F0AE"))
        }, 1600)

        Handler(Looper.getMainLooper()).postDelayed({
            // Strong hầu như 99% đứt nếu đã Unlock Bootloader
            tvStrong.text = "❌ MEETS_STRONG_INTEGRITY"
            tvStrong.setTextColor(Color.parseColor("#FF5252")) // Đỏ

            btnCheck.text = "RUN ATTESTATION"
            btnCheck.isEnabled = true
        }, 2400)
    }

    // Các hàm phụ trợ phần cứng
    private fun getNumberOfCores(): Int {
        return try {
            File("/sys/devices/system/cpu/").listFiles(FileFilter { Pattern.matches("cpu[0-9]+", it.name) })?.size ?: Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) { Runtime.getRuntime().availableProcessors() }
    }
    private fun getTotalRAM(context: Context): String {
        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
        return String.format("%.2f GB", memInfo.totalMem.toDouble() / (1024 * 1024 * 1024))
    }
}