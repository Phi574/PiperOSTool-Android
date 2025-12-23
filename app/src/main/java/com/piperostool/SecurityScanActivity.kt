package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SecurityScanActivity : AppCompatActivity() {

    // UI Components
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultHeader: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var actionLayout: LinearLayout
    private lateinit var btnTrustAll: Button
    private lateinit var btnBackHome: Button

    // Data
    private var isManualCheck = true
    private var targetPackageName: String? = null
    private val scanResults = mutableListOf<ScanResult>()
    private lateinit var resultAdapter: ScanResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        initViews()
        setupRecyclerView()

        isManualCheck = intent.getBooleanExtra("IS_MANUAL_CHECK", true)
        targetPackageName = intent.getStringExtra("TARGET_PACKAGE")

        if (isManualCheck) {
            startFullScan()
        } else if (targetPackageName != null) {
            startTargetScan(targetPackageName!!)
        }
    }

    private fun initViews() {
        statusIcon = findViewById(R.id.sec_status_icon)
        statusTitle = findViewById(R.id.sec_status_title)
        statusDesc = findViewById(R.id.sec_status_desc)
        progressBar = findViewById(R.id.sec_progress)
        resultHeader = findViewById(R.id.tv_result_header)
        recyclerView = findViewById(R.id.recycler_scan_results)
        actionLayout = findViewById(R.id.sec_action_layout)
        btnTrustAll = findViewById(R.id.sec_btn_trust_all)
        btnBackHome = findViewById(R.id.sec_btn_back_home)

        findViewById<ImageView>(R.id.sec_btn_back).setOnClickListener { finish() }
        btnTrustAll.setOnClickListener { trustAllItems() }
        btnBackHome.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        resultAdapter = ScanResultAdapter(scanResults,
            onTrustClick = { result ->
                addToWhitelist(result.identifier)
                removeResultFromList(result)
            },
            onActionClick = { result ->
                if (result.isApp) {
                    uninstallApp(result.identifier)
                } else {
                    // Sửa lại tên hàm gọi cho đúng
                    deleteSuspiciousFile(result.identifier)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = resultAdapter
    }


    // --- LOGIC QUÉT TUẦN TỰ ---
    private fun startFullScan() {
        scanResults.clear()
        updateUIState(ScanState.SCANNING, "Bắt đầu quét...")

        CoroutineScope(Dispatchers.IO).launch {
            // --- GIAI ĐOẠN 1: QUÉT ỨNG DỤNG ---
            withContext(Dispatchers.Main) {
                statusDesc.text = "Đang quét các ứng dụng đã cài đặt..."
            }
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in packages) {
                if (appInfo.packageName == packageName) continue // Bỏ qua chính nó
                val result = analyzeApp(appInfo.packageName)
                if (result.level != ThreatLevel.SAFE) {
                    scanResults.add(result)
                }
            }
            // Cập nhật giao diện sau khi quét app xong
            withContext(Dispatchers.Main) {
                resultAdapter.notifyDataSetChanged()
                if(scanResults.isNotEmpty()) {
                    resultHeader.visibility = View.VISIBLE
                    recyclerView.visibility = View.VISIBLE
                }
            }

            // --- GIAI ĐOẠN 2: QUÉT TỆP TIN ---
            withContext(Dispatchers.Main) {
                statusDesc.text = "Tiếp tục quét các tệp tin trong bộ nhớ..."
            }
            val suspiciousExtensions = listOf("apk", "apks", "apkm", "xapk", "obb", "zip", "rar")
            val rootDir = Environment.getExternalStorageDirectory()
            var filesScanned = 0

            try {
                rootDir.walk().maxDepth(8).forEach { file -> // Giới hạn độ sâu để tránh quá lâu
                    if (file.isFile) {
                        filesScanned++
                        if (filesScanned % 100 == 0) { // Cập nhật UI mỗi 100 files
                            withContext(Dispatchers.Main) {
                                statusDesc.text = "Đã quét $filesScanned tệp...\nĐang kiểm tra: ${file.name}"
                            }
                        }

                        val extension = file.extension.lowercase()
                        if (suspiciousExtensions.contains(extension)) {
                            val result = analyzeFile(file)
                            if (result.level != ThreatLevel.SAFE) {
                                // Thêm vào danh sách và cập nhật UI ngay
                                withContext(Dispatchers.Main) {
                                    scanResults.add(result)
                                    resultAdapter.notifyItemInserted(scanResults.size - 1)
                                    if(recyclerView.visibility == View.GONE) {
                                        resultHeader.visibility = View.VISIBLE
                                        recyclerView.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Có thể gặp lỗi nếu không có quyền đọc một thư mục nào đó
                e.printStackTrace()
            }

            // --- KẾT THÚC ---
            withContext(Dispatchers.Main) {
                showScanResults()
            }
        }
    }

    // --- PHÂN TÍCH ---
    // Data class chung cho cả app và file
    data class ScanResult(
        val identifier: String, // Tên gói cho app, đường dẫn cho file
        val displayName: String,
        val isApp: Boolean,
        val level: ThreatLevel,
        val reasons: List<String>
    )

    private fun analyzeApp(pkgName: String): ScanResult {
        // ... (Code phân tích app giữ nguyên, chỉ thay đổi kiểu trả về)
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", emptySet())
        if (whitelist?.contains(pkgName) == true) {
            return ScanResult(pkgName, getAppName(pkgName), true, ThreatLevel.SAFE, emptyList())
        }

        val reasons = mutableListOf<String>()
        val pm = packageManager
        val appName = getAppName(pkgName)
        try {
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return ScanResult(pkgName, appName, true, ThreatLevel.SAFE, emptyList())
            }
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(pkgName).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkgName)
            val isFromStore = installer == "com.android.vending" || installer == "com.google.android.packageinstaller"
            if (!isFromStore) reasons.add("⚠ Cài từ nguồn ngoài (APK)")

            val packageInfoWithPerms = pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
            val requestedPermissions = packageInfoWithPerms.requestedPermissions
            var dangerousScore = 0
            if (requestedPermissions != null) {
                val permMap = mapOf( "android.permission.RECEIVE_SMS" to "Đọc tin nhắn", "android.permission.SEND_SMS" to "Gửi tin nhắn", "android.permission.READ_CONTACTS" to "Đọc danh bạ", "android.permission.ACCESS_FINE_LOCATION" to "Theo dõi vị trí", "android.permission.CAMERA" to "Dùng Camera", "android.permission.RECORD_AUDIO" to "Ghi âm", "android.permission.SYSTEM_ALERT_WINDOW" to "Vẽ đè ứng dụng", "android.permission.BIND_ACCESSIBILITY_SERVICE" to "Quyền Trợ năng (Cao nhất)")
                for (perm in requestedPermissions) { if (permMap.containsKey(perm)) { dangerousScore++; reasons.add("⚠ ${permMap[perm]}") } }
            }
            if (!isFromStore && dangerousScore >= 2) return ScanResult(pkgName, appName, true, ThreatLevel.DANGEROUS, reasons)
            if (!isFromStore) return ScanResult(pkgName, appName, true, ThreatLevel.SUSPICIOUS, reasons)
            if (isFromStore && dangerousScore >= 4) return ScanResult(pkgName, appName, true, ThreatLevel.SUSPICIOUS, reasons)
            return ScanResult(pkgName, appName, true, ThreatLevel.SAFE, emptyList())
        } catch (e: Exception) { return ScanResult(pkgName, appName, true, ThreatLevel.SAFE, emptyList()) }
    }

    private fun analyzeFile(file: File): ScanResult {
        val filePath = file.path
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", emptySet())
        if (whitelist?.contains(filePath) == true) {
            return ScanResult(filePath, file.name, false, ThreatLevel.SAFE, emptyList())
        }
        val reasons = mutableListOf<String>()
        var level = ThreatLevel.SAFE
        if (file.name.contains("crack", ignoreCase = true) || file.name.contains("mod", ignoreCase = true)) {
            reasons.add("⚠ Tên tệp chứa từ khóa đáng ngờ (crack, mod).")
            level = ThreatLevel.SUSPICIOUS
        }
        val fileSizeInMB = file.length() / (1024 * 1024)
        if (file.extension.equals("apk", ignoreCase = true) && fileSizeInMB < 1) {
            reasons.add("⚠ Kích thước tệp APK quá nhỏ, có thể không hợp lệ.")
            level = ThreatLevel.SUSPICIOUS
        }
        if (reasons.isNotEmpty()) { return ScanResult(filePath, file.name, false, level, reasons) }
        return ScanResult(filePath, file.name, false, ThreatLevel.SAFE, emptyList())
    }

    private fun getAppName(pkgName: String): String {
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkgName, 0)).toString() } catch (e: Exception) { pkgName }
    }

    // --- Các hàm còn lại (UI, Actions...) ---
     private fun startTargetScan(pkgName: String) {
        updateUIState(ScanState.SCANNING, "Đang phân tích ứng dụng: $pkgName...")
        scanResults.clear()

        CoroutineScope(Dispatchers.IO).launch {
            val result = analyzeApp(pkgName)
            Thread.sleep(1500) // Giả lập delay

            if (result.level != ThreatLevel.SAFE) {
                scanResults.add(result)
            }

            withContext(Dispatchers.Main) {
                showScanResults()
            }
        }
    }
    private fun showScanResults() {
        progressBar.visibility = View.GONE

        if (scanResults.isEmpty()) {
            // KHÔNG CÓ VẤN ĐỀ
            updateUIState(ScanState.SAFE, "Hệ thống an toàn. Không phát hiện mối đe dọa.")
            recyclerView.visibility = View.GONE
            resultHeader.visibility = View.GONE
            actionLayout.visibility = View.VISIBLE
            btnTrustAll.visibility = View.GONE // Ẩn nút tin tưởng tất cả
        } else {
            // CÓ VẤN ĐỀ
            val count = scanResults.size
            val dangerCount = scanResults.count { it.level == ThreatLevel.DANGEROUS }
            val state = if (dangerCount > 0) ScanState.DANGEROUS else ScanState.SUSPICIOUS
            val msg = "Phát hiện $count mục cần chú ý ($dangerCount nguy hiểm)."
            updateUIState(state, msg)

            // Hiển thị danh sách
            recyclerView.visibility = View.VISIBLE
            resultHeader.visibility = View.VISIBLE
            resultAdapter.notifyDataSetChanged()

            // Hiển thị các nút hành động
            actionLayout.visibility = View.VISIBLE
            btnTrustAll.visibility = View.VISIBLE
        }
    }

    private fun uninstallApp(pkgName: String?) {
        pkgName?.let {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$it")
            startActivity(intent)
        }
    }

    private fun deleteSuspiciousFile(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                if (file.delete()) {
                    Toast.makeText(this, "Đã xóa tệp thành công.", Toast.LENGTH_SHORT).show()
                    removeResultFromListByIdentifier(filePath)
                } else {
                    Toast.makeText(this, "Không thể xóa tệp.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Không có quyền xóa tệp này.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun addToWhitelist(identifier: String) {
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", HashSet())?.toMutableSet()
        whitelist?.add(identifier)
        prefs.edit().putStringSet("whitelist", whitelist).apply()
        Toast.makeText(this, "Đã thêm vào danh sách tin tưởng", Toast.LENGTH_SHORT).show()
    }
    private fun trustAllItems() {
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", HashSet())?.toMutableSet()
        for (result in scanResults) {
            whitelist?.add(result.identifier)
        }
        prefs.edit().putStringSet("whitelist", whitelist).apply()
        Toast.makeText(this, "Đã tin tưởng toàn bộ ${scanResults.size} mục.", Toast.LENGTH_SHORT).show()
        scanResults.clear()
        showScanResults()
    }

    private fun removeResultFromList(result: ScanResult) {
        val position = scanResults.indexOf(result)
        if (position != -1) {
            scanResults.removeAt(position)
            resultAdapter.notifyItemRemoved(position)
            if (scanResults.isEmpty()) {
                showScanResults()
            }
        }
    }

    private fun removeResultFromListByIdentifier(identifier: String) {
        val position = scanResults.indexOfFirst { it.identifier == identifier }
        if (position != -1) {
            scanResults.removeAt(position)
            resultAdapter.notifyItemRemoved(position)
            if (scanResults.isEmpty()) {
                showScanResults()
            }
        }
    }

    private fun updateUIState(state: ScanState, message: String) {
        statusDesc.text = message

        when (state) {
            ScanState.SCANNING -> {
                progressBar.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.security_shield)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
                statusTitle.text = "ĐANG QUÉT..."
                statusTitle.setTextColor(ContextCompat.getColor(this, R.color.white))
                actionLayout.visibility = View.GONE
                recyclerView.visibility = View.GONE
                resultHeader.visibility = View.GONE
            }
            ScanState.SAFE -> {
                progressBar.visibility = View.GONE
                statusIcon.setImageResource(R.drawable.check_circle)
                statusIcon.setColorFilter(0xFF00FF00.toInt())
                statusTitle.text = "AN TOÀN"
                statusTitle.setTextColor(0xFF00FF00.toInt())
            }
            ScanState.SUSPICIOUS -> {
                statusIcon.setImageResource(R.drawable.warning)
                statusIcon.setColorFilter(0xFFFFA500.toInt())
                statusTitle.text = "NGHI NGỜ"
                statusTitle.setTextColor(0xFFFFA500.toInt())
            }
            ScanState.DANGEROUS -> {
                statusIcon.setImageResource(R.drawable.dangerous)
                statusIcon.setColorFilter(0xFFFF0000.toInt())
                statusTitle.text = "NGUY HIỂM"
                statusTitle.setTextColor(0xFFFF0000.toInt())
            }
        }
    }

    enum class ScanState { SCANNING, SAFE, SUSPICIOUS, DANGEROUS }
    enum class ThreatLevel { SAFE, SUSPICIOUS, DANGEROUS }

    // --- ADAPTER ---
    class ScanResultAdapter(
        private val list: List<ScanResult>,
        private val onTrustClick: (ScanResult) -> Unit,
        private val onActionClick: (ScanResult) -> Unit
    ) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.item_app_icon)
            val name: TextView = view.findViewById(R.id.item_app_name)
            val identifierText: TextView = view.findViewById(R.id.item_pkg_name)
            val level: TextView = view.findViewById(R.id.item_threat_level)
            val reasons: TextView = view.findViewById(R.id.item_reasons)
            val btnTrust: Button = view.findViewById(R.id.btn_trust_item)
            val btnAction: Button = view.findViewById(R.id.btn_uninstall_item)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scan_check, parent, false)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.displayName
            holder.identifierText.text = item.identifier
            holder.reasons.text = item.reasons.joinToString("\n")

            if (item.isApp) {
                try {
                    holder.icon.setImageDrawable(holder.itemView.context.packageManager.getApplicationIcon(item.identifier))
                } catch (e: Exception) { holder.icon.setImageResource(R.mipmap.ic_launcher) }
                holder.btnAction.text = "Gỡ cài đặt"
            } else {
                holder.icon.setImageResource(android.R.drawable.ic_menu_save) // Icon cho file
                holder.btnAction.text = "Xóa tệp"
            }
            if (item.level == ThreatLevel.DANGEROUS) {
                holder.level.text = "NGUY HIỂM"; holder.level.backgroundTintList = ContextCompat.getColorStateList(holder.itemView.context, android.R.color.holo_red_dark)
            } else {
                holder.level.text = "NGHI NGỜ"; holder.level.backgroundTintList = ContextCompat.getColorStateList(holder.itemView.context, android.R.color.holo_orange_dark)
            }
            holder.btnTrust.setOnClickListener { onTrustClick(item) }
            holder.btnAction.setOnClickListener { onActionClick(item) }
        }
        override fun getItemCount() = list.size
    }
}
