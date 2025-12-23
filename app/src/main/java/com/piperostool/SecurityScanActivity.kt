package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    // Danh sách kết quả quét (chứa các app có vấn đề)
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

        // Sự kiện nút Tin tưởng tất cả
        btnTrustAll.setOnClickListener {
            trustAllApps()
        }

        btnBackHome.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        resultAdapter = ScanResultAdapter(scanResults,
            onTrustClick = { result ->
                addToWhitelist(result.packageName)
                removeResultFromList(result)
            },
            onUninstallClick = { result ->
                uninstallApp(result.packageName)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = resultAdapter
    }

    // --- LOGIC QUÉT ---

    private fun startFullScan() {
        updateUIState(ScanState.SCANNING, "Đang quét toàn bộ hệ thống...")
        scanResults.clear()

        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            for (appInfo in packages) {
                // Bỏ qua app hiện tại (Piper OS Tool) để tránh tự báo mình
                if (appInfo.packageName == packageName) {
                    continue
                }

                // Chạy phân tích
                val result = analyzeAppReal(appInfo.packageName)
                if (result.level != ThreatLevel.SAFE) {
                    scanResults.add(result)
                }
            }

            withContext(Dispatchers.Main) {
                showScanResults()
            }
        }
    }

    private fun startTargetScan(pkgName: String) {
        updateUIState(ScanState.SCANNING, "Đang phân tích ứng dụng: $pkgName...")
        scanResults.clear()

        CoroutineScope(Dispatchers.IO).launch {
            val result = analyzeAppReal(pkgName)
            // Giả lập delay một chút cho người dùng kịp đọc
            Thread.sleep(1500)

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
            btnTrustAll.visibility = View.GONE // Ẩn nút tin tưởng tất cả vì không có gì để tin
        } else {
            // CÓ VẤN ĐỀ
            val count = scanResults.size
            val dangerCount = scanResults.count { it.level == ThreatLevel.DANGEROUS }

            val state = if (dangerCount > 0) ScanState.DANGEROUS else ScanState.SUSPICIOUS
            val msg = "Phát hiện $count ứng dụng cần chú ý ($dangerCount nguy hiểm)."

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

    // --- PHÂN TÍCH APP ---
    data class ScanResult(
        val packageName: String,
        val appName: String,
        val level: ThreatLevel,
        val reasons: List<String>
    )

    private fun analyzeAppReal(pkgName: String): ScanResult {
        // 0. Kiểm tra Whitelist (Danh sách tin tưởng)
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", emptySet())
        if (whitelist?.contains(pkgName) == true) {
            return ScanResult(pkgName, getAppName(pkgName), ThreatLevel.SAFE, emptyList())
        }

        val reasons = mutableListOf<String>()
        val pm = packageManager
        val appName = getAppName(pkgName)

        try {
            // --- SỬA LOGIC KIỂM TRA APP HỆ THỐNG TẠI ĐÂY ---
            val appInfo = pm.getApplicationInfo(pkgName, 0)

            // 1. KIỂM TRA NẾU LÀ APP HỆ THỐNG -> AN TOÀN TUYỆT ĐỐI
            // Các cờ FLAG_SYSTEM và FLAG_UPDATED_SYSTEM_APP cho biết đây là app cài sẵn của ROM
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                // App hệ thống luôn an toàn, không cần check nguồn hay quyền
                return ScanResult(pkgName, appName, ThreatLevel.SAFE, emptyList())
            }

            // 2. NGUỒN CÀI ĐẶT
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkgName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkgName)
            }

            // Các nguồn tin cậy: Google Play hoặc Package Installer gốc
            val isFromStore = installer == "com.android.vending" || installer == "com.google.android.packageinstaller"

            if (!isFromStore) {
                reasons.add("⚠ Cài từ nguồn ngoài (APK)")
            }

            // 3. QUYỀN HẠN (Chỉ kiểm tra nếu không phải system app - đã check ở trên)
            val packageInfoWithPerms = pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
            val requestedPermissions = packageInfoWithPerms.requestedPermissions
            var dangerousScore = 0

            if (requestedPermissions != null) {
                val permMap = mapOf(
                    "android.permission.RECEIVE_SMS" to "Đọc tin nhắn",
                    "android.permission.SEND_SMS" to "Gửi tin nhắn",
                    "android.permission.READ_CONTACTS" to "Đọc danh bạ",
                    "android.permission.ACCESS_FINE_LOCATION" to "Theo dõi vị trí",
                    "android.permission.CAMERA" to "Dùng Camera",
                    "android.permission.RECORD_AUDIO" to "Ghi âm",
                    "android.permission.SYSTEM_ALERT_WINDOW" to "Vẽ đè ứng dụng",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE" to "Quyền Trợ năng (Cao nhất)"
                )

                for (perm in requestedPermissions) {
                    if (permMap.containsKey(perm)) {
                        dangerousScore++
                        reasons.add("⚠ ${permMap[perm]}")
                    }
                }
            }

            // 4. KẾT LUẬN
            // App nguồn ngoài + 2 quyền nguy hiểm -> NGUY HIỂM
            if (!isFromStore && dangerousScore >= 2) return ScanResult(pkgName, appName, ThreatLevel.DANGEROUS, reasons)
            // App nguồn ngoài -> NGHI NGỜ
            if (!isFromStore) return ScanResult(pkgName, appName, ThreatLevel.SUSPICIOUS, reasons)
            // App CH Play nhưng spam quyền -> NGHI NGỜ
            if (isFromStore && dangerousScore >= 4) return ScanResult(pkgName, appName, ThreatLevel.SUSPICIOUS, reasons)

            return ScanResult(pkgName, appName, ThreatLevel.SAFE, emptyList())

        } catch (e: Exception) {
            // Nếu lỗi khi lấy thông tin (ví dụ app vừa bị gỡ), coi như an toàn để không crash
            return ScanResult(pkgName, appName, ThreatLevel.SAFE, emptyList())
        }
    }

    private fun getAppName(pkgName: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkgName, 0)).toString()
        } catch (e: Exception) { pkgName }
    }

    // --- CÁC HÀM HÀNH ĐỘNG ---

    private fun uninstallApp(pkgName: String?) {
        pkgName?.let {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$it")
            startActivity(intent)
            // Lưu ý: Không finish() ở đây để người dùng quay lại danh sách
        }
    }

    private fun addToWhitelist(pkgName: String) {
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", HashSet())?.toMutableSet()
        whitelist?.add(pkgName)
        prefs.edit().putStringSet("whitelist", whitelist).apply()
        Toast.makeText(this, "Đã thêm '$pkgName' vào tin tưởng", Toast.LENGTH_SHORT).show()
    }

    private fun trustAllApps() {
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", HashSet())?.toMutableSet()

        // Thêm tất cả package trong danh sách hiện tại vào whitelist
        for (result in scanResults) {
            whitelist?.add(result.packageName)
        }

        prefs.edit().putStringSet("whitelist", whitelist).apply()
        Toast.makeText(this, "Đã tin tưởng toàn bộ ${scanResults.size} ứng dụng.", Toast.LENGTH_SHORT).show()

        // Làm mới giao diện (coi như đã xử lý xong)
        scanResults.clear()
        showScanResults()
    }

    private fun removeResultFromList(result: ScanResult) {
        val position = scanResults.indexOf(result)
        if (position >= 0) {
            scanResults.removeAt(position)
            resultAdapter.notifyItemRemoved(position)

            // Nếu danh sách trống thì cập nhật lại giao diện thành An toàn
            if (scanResults.isEmpty()) {
                showScanResults()
            }
        }
    }

    // --- UI HELPERS ---

    private fun updateUIState(state: ScanState, message: String) {
        statusDesc.text = message

        when (state) {
            ScanState.SCANNING -> {
                progressBar.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.security_shield)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
                statusTitle.text = "ĐANG QUÉT..."
                statusTitle.setTextColor(ContextCompat.getColor(this, R.color.white))
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
        private val onUninstallClick: (ScanResult) -> Unit
    ) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.item_app_icon)
            val name: TextView = view.findViewById(R.id.item_app_name)
            val pkg: TextView = view.findViewById(R.id.item_pkg_name)
            val level: TextView = view.findViewById(R.id.item_threat_level)
            val reasons: TextView = view.findViewById(R.id.item_reasons)
            val btnTrust: Button = view.findViewById(R.id.btn_trust_item)
            val btnUninstall: Button = view.findViewById(R.id.btn_uninstall_item)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scan_check, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]

            holder.name.text = item.appName
            holder.pkg.text = item.packageName
            holder.reasons.text = item.reasons.joinToString("\n")

            // Lấy icon app
            try {
                val icon = holder.itemView.context.packageManager.getApplicationIcon(item.packageName)
                holder.icon.setImageDrawable(icon)
            } catch (e: Exception) {
                holder.icon.setImageResource(R.mipmap.ic_launcher)
            }

            if (item.level == ThreatLevel.DANGEROUS) {
                holder.level.text = "NGUY HIỂM"
                holder.level.backgroundTintList = ContextCompat.getColorStateList(holder.itemView.context, android.R.color.holo_red_dark)
            } else {
                holder.level.text = "NGHI NGỜ"
                holder.level.backgroundTintList = ContextCompat.getColorStateList(holder.itemView.context, android.R.color.holo_orange_dark)
            }

            holder.btnTrust.setOnClickListener { onTrustClick(item) }
            holder.btnUninstall.setOnClickListener { onUninstallClick(item) }
        }

        override fun getItemCount() = list.size
    }
}
