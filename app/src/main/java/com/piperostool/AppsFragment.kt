package com.piperostool

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.WindowManager
class AppsFragment : Fragment() {

    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearchApp: EditText

    // 3 Tab Phân loại
    private lateinit var tabUser: TextView
    private lateinit var tabSystem: TextView
    private lateinit var tabDisabled: TextView

    private lateinit var universalAdapter: UniversalAppAdapter
    private var allApps = listOf<AppInfoModel>()

    // 0 = User, 1 = System, 2 = Disabled
    private var currentTabFilter = 0
    private var currentSearchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_apps, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvApps = view.findViewById(R.id.rvApps)
        progressBar = view.findViewById(R.id.progressBar)
        etSearchApp = view.findViewById(R.id.etSearchApp)
        tabUser = view.findViewById(R.id.tabUser)
        tabSystem = view.findViewById(R.id.tabSystem)
        tabDisabled = view.findViewById(R.id.tabDisabled)

        // Lưới Grid 2 cột
        rvApps.layoutManager = GridLayoutManager(requireContext(), 2)

        universalAdapter = UniversalAppAdapter(
            items = emptyList(),
            onAppClick = { app -> showAppDetailsDialog(app) },
            onActivityClick = { app, actInfo -> showActivityActionDialog(app, actInfo) }
        )
        rvApps.adapter = universalAdapter

        // Bắt sự kiện tìm kiếm
        etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s.toString()
                applyFilters()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Bắt sự kiện bấm Tab
        tabUser.setOnClickListener { switchTab(0) }
        tabSystem.setOnClickListener { switchTab(1) }
        tabDisabled.setOnClickListener { switchTab(2) }

        // Cuộn ẩn Menu Bottom
        rvApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 15) {
                    (activity as? HomeActivity)?.hideBottomNav()
                } else if (dy < -15) {
                    (activity as? HomeActivity)?.showBottomNav()
                }
            }
        })

        loadApps()
    }

    private fun switchTab(tabIndex: Int) {
        currentTabFilter = tabIndex
        tabUser.setTextColor(if (tabIndex == 0) Color.parseColor("#00E5FF") else Color.WHITE)
        tabSystem.setTextColor(if (tabIndex == 1) Color.parseColor("#00E5FF") else Color.WHITE)
        tabDisabled.setTextColor(if (tabIndex == 2) Color.parseColor("#00E5FF") else Color.WHITE)
        applyFilters()
    }

    private fun applyFilters() {
        val q = currentSearchQuery.lowercase(Locale.getDefault())
        val searchResults = mutableListOf<AppListItem>()

        for (app in allApps) {
            val matchTab = when (currentTabFilter) {
                0 -> !app.isSystem && app.isEnabled
                1 -> app.isSystem && app.isEnabled
                2 -> !app.isEnabled
                else -> true
            }

            if (!matchTab) continue

            if (q.isEmpty()) {
                searchResults.add(AppListItem.App(app))
            } else {
                if (app.name.lowercase().contains(q) || app.packageName.lowercase().contains(q)) {
                    searchResults.add(AppListItem.App(app))
                }
                for (act in app.activities) {
                    val shortActName = act.name.substringAfterLast('.')
                    if (act.name.lowercase().contains(q) || shortActName.lowercase().contains(q)) {
                        searchResults.add(AppListItem.Activity(app, act))
                    }
                }
            }
        }
        universalAdapter.updateData(searchResults)
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val pm = requireContext().packageManager
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // Lấy danh sách tiến trình đang chạy ngầm
            val runningProcesses = am.runningAppProcesses?.map { it.processName } ?: emptyList()

            // Cờ QUYỀN (GET_PERMISSIONS) và Cờ COMPONENT (GET_ACTIVITIES, v.v...)
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            val packages = pm.getInstalledPackages(flags)

            val appList = mutableListOf<AppInfoModel>()

            for (pack in packages) {
                val appInfo = pack.applicationInfo
                if (appInfo != null) {
                    val name = appInfo.loadLabel(pm).toString()
                    val icon = appInfo.loadIcon(pm)
                    val activities = pack.activities?.toList() ?: emptyList()

                    val permsCount = pack.requestedPermissions?.size ?: 0
                    val version = pack.versionName ?: "Unknown"
                    val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    // Tính dung lượng file APK
                    val apkFile = File(appInfo.sourceDir)
                    val sizeBytes = if (apkFile.exists()) apkFile.length() else 0L
                    val formattedSize = Formatter.formatShortFileSize(requireContext(), sizeBytes)

                    // Check Trạng thái
                    val isRunning = runningProcesses.contains(pack.packageName)

                    appList.add(
                        AppInfoModel(
                            name = name,
                            packageName = pack.packageName,
                            icon = icon,
                            activities = activities,
                            versionName = version,
                            targetSdk = appInfo.targetSdkVersion,
                            installTime = pack.firstInstallTime,
                            updateTime = pack.lastUpdateTime,
                            apkPath = appInfo.sourceDir,
                            dataDir = appInfo.dataDir ?: "No Data",
                            uid = appInfo.uid,
                            isSystem = isSys,
                            isEnabled = appInfo.enabled,
                            apkSize = formattedSize,
                            isRunning = isRunning,
                            permissionsCount = permsCount
                        )
                    )
                }
            }
            appList.sortBy { it.name.lowercase(Locale.getDefault()) }
            allApps = appList

            withContext(Dispatchers.Main) {
                tabUser.text = "Người dùng (${allApps.count { !it.isSystem && it.isEnabled }})"
                tabSystem.text = "Hệ thống (${allApps.count { it.isSystem && it.isEnabled }})"
                tabDisabled.text = "Đã tắt (${allApps.count { !it.isEnabled }})"

                applyFilters()
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
            }
        }
    }

    // =======================================================
    // HIỂN THỊ CỬA SỔ BOTTOM SHEET CHI TIẾT APP (MỚI SIÊU ĐỈNH)
    // =======================================================
    private fun showAppDetailsDialog(app: AppInfoModel) {
        // Dùng BottomSheetDialog của Google Material Design
        val bottomSheetDialog = BottomSheetDialog(requireContext(), R.style.TransparentBottomSheetDialogTheme)
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_app_details, null)
        bottomSheetDialog.setContentView(dialogView)

        // Làm nền trong suốt để lộ 2 góc bo tròn
        (dialogView.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 1. Ánh xạ Dòng trên cùng
        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDialogName)
        val tvVersionTop = dialogView.findViewById<TextView>(R.id.tvDialogVersionTop)

        // 2. Ánh xạ 4 viên thuốc ngang
        val ivStatusIcon = dialogView.findViewById<ImageView>(R.id.ivStatusIcon)
        val tvQuickStatus = dialogView.findViewById<TextView>(R.id.tvQuickStatus)
        val ivTypeIcon = dialogView.findViewById<ImageView>(R.id.ivTypeIcon)
        val tvQuickType = dialogView.findViewById<TextView>(R.id.tvQuickType)
        val tvQuickSize = dialogView.findViewById<TextView>(R.id.tvQuickSize)
        val btnToggleInfo = dialogView.findViewById<LinearLayout>(R.id.btnToggleInfo)

        // 3. Ánh xạ Khối thông tin chi tiết
        val layoutAppInfo = dialogView.findViewById<LinearLayout>(R.id.layoutAppInfo)
        val tvInfoPackage = dialogView.findViewById<TextView>(R.id.tvInfoPackage)
        val tvInfoVersion = dialogView.findViewById<TextView>(R.id.tvInfoVersion)
        val tvInfoStatus = dialogView.findViewById<TextView>(R.id.tvInfoStatus)
        val tvInfoInstall = dialogView.findViewById<TextView>(R.id.tvInfoInstall)
        val tvInfoUpdate = dialogView.findViewById<TextView>(R.id.tvInfoUpdate)
        val tvInfoSdk = dialogView.findViewById<TextView>(R.id.tvInfoSdk)
        val tvInfoPerms = dialogView.findViewById<TextView>(R.id.tvInfoPerms)
        val btnDialogBackup = dialogView.findViewById<LinearLayout>(R.id.btnDialogBackup)

        // 4. Ánh xạ 2 viên thuốc bự dưới đáy
        val btnLaunch = dialogView.findViewById<LinearLayout>(R.id.btnLaunch)
        val btnDetails = dialogView.findViewById<LinearLayout>(R.id.btnDetails)

        // ================= GẮN DỮ LIỆU =================
        ivIcon.setImageDrawable(app.icon)
        tvName.text = app.name
        tvVersionTop.text = "Phiên bản ${app.versionName}"

        // Trạng thái Ngủ/Chạy
        if (app.isRunning) {
            ivStatusIcon.setImageResource(R.drawable.check_circle)
            ivStatusIcon.setColorFilter(Color.parseColor("#00E5FF"))
            tvQuickStatus.text = "Đang chạy"
            tvQuickStatus.setTextColor(Color.parseColor("#00E5FF"))
            tvInfoStatus.text = "Hoạt động ngầm"
            tvInfoStatus.setTextColor(Color.parseColor("#00E5FF"))
        } else {
            ivStatusIcon.setImageResource(R.drawable.sleep)
            ivStatusIcon.setColorFilter(Color.parseColor("#BDBDBD"))
            tvQuickStatus.text = "Ngủ đông"
            tvQuickStatus.setTextColor(Color.parseColor("#BDBDBD"))
            tvInfoStatus.text = "Đã dừng (Sleeping)"
            tvInfoStatus.setTextColor(Color.parseColor("#BDBDBD"))
        }

        // Hệ thống hay APK ngoài
        if (app.isSystem) {
            ivTypeIcon.setImageResource(R.drawable.system)
            ivTypeIcon.setColorFilter(Color.parseColor("#E53935")) // Đỏ
            tvQuickType.text = "Hệ thống"
        } else {
            ivTypeIcon.setImageResource(R.drawable.apk)
            ivTypeIcon.setColorFilter(Color.parseColor("#4CAF50")) // Xanh lá
            tvQuickType.text = "APK ngoài"
        }

        tvQuickSize.text = app.apkSize

        // Khối chi tiết
        tvInfoPackage.text = app.packageName
        tvInfoVersion.text = app.versionName
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        tvInfoInstall.text = sdf.format(Date(app.installTime))
        tvInfoUpdate.text = sdf.format(Date(app.updateTime))
        tvInfoSdk.text = app.targetSdk.toString()
        tvInfoPerms.text = "${app.permissionsCount} quyền"

        // ================= XỬ LÝ SỰ KIỆN =================
        var isInfoExpanded = false
        btnToggleInfo.setOnClickListener {
            isInfoExpanded = !isInfoExpanded
            layoutAppInfo.visibility = if (isInfoExpanded) View.VISIBLE else View.GONE
        }

        btnLaunch.setOnClickListener {
            bottomSheetDialog.dismiss()
            launchApp(app.packageName)
        }

        btnDetails.setOnClickListener {
            bottomSheetDialog.dismiss()
            showActivitiesList(app)
        }

        btnDialogBackup.setOnClickListener {
            bottomSheetDialog.dismiss()
            backupApk(app)
        }

        // 2. BẬT HIỆU ỨNG LÀM MỜ NỀN PHÍA SAU KHI CỬA SỔ HIỆN LÊN
        bottomSheetDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        bottomSheetDialog.window?.setDimAmount(0.6f) // Độ mờ 60% (càng lớn càng tối)

        bottomSheetDialog.show()
    }

    private fun showActivitiesList(app: AppInfoModel) {
        if (app.activities.isEmpty()) {
            Toast.makeText(requireContext(), "App này không có Activity ngầm hợp lệ!", Toast.LENGTH_SHORT).show()
            return
        }
        val actNames = app.activities.map { it.name.substringAfterLast('.') }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Bảng Activities (${app.activities.size})")
            .setItems(actNames) { _, which -> showActivityActionDialog(app, app.activities[which]) }
            .show()
    }

    private fun showActivityActionDialog(app: AppInfoModel, activityInfo: ActivityInfo) {
        val shortName = activityInfo.name.substringAfterLast('.')
        val options = arrayOf("Ép khởi chạy (Launch ngầm)", "Ghim Shortcut ra màn hình chính")
        AlertDialog.Builder(requireContext())
            .setTitle(shortName)
            .setItems(options) { _, which ->
                if (which == 0) launchActivity(app.packageName, activityInfo.name)
                else createShortcut(app, shortName, activityInfo.name)
            }
            .show()
    }

    private fun launchApp(packageName: String) {
        val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) startActivity(launchIntent)
        else Toast.makeText(requireContext(), "App hệ thống ẩn, không có giao diện chính!", Toast.LENGTH_SHORT).show()
    }

    private fun launchActivity(packageName: String, activityName: String) {
        try {
            val intent = Intent().apply {
                setClassName(packageName, activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Bị chặn bảo mật Android (Exported=false) hoặc cần quyền Root!", Toast.LENGTH_LONG).show()
        }
    }

    private fun createShortcut(app: AppInfoModel, shortName: String, activityFullName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = requireContext().getSystemService(ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val intent = Intent().apply {
                    setClassName(app.packageName, activityFullName)
                    action = Intent.ACTION_MAIN
                }
                val pinInfo = ShortcutInfo.Builder(requireContext(), "sc_${System.currentTimeMillis()}")
                    .setShortLabel(shortName)
                    .setIntent(intent)
                    .setIcon(Icon.createWithResource(requireContext(), R.mipmap.ic_launcher))
                    .build()
                val cb = PendingIntent.getBroadcast(requireContext(), 0, shortcutManager.createShortcutResultIntent(pinInfo), PendingIntent.FLAG_IMMUTABLE)
                shortcutManager.requestPinShortcut(pinInfo, cb.intentSender)
                Toast.makeText(requireContext(), "Đã gửi yêu cầu ghim Shortcut", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun backupApk(app: AppInfoModel) {
        Toast.makeText(requireContext(), "Đang đóng gói APK...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val srcFile = File(app.apkPath)
                val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PiperOS_Backups")
                if (!backupDir.exists()) backupDir.mkdirs()
                val destFile = File(backupDir, "${app.name.replace(" ", "_")}_${app.versionName}.apk")
                srcFile.copyTo(destFile, overwrite = true)
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "Thành công! Đã lưu tại Download/PiperOS_Backups", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "Lỗi đóng gói: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

// --- DATA MODEL ---
data class AppInfoModel(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val activities: List<ActivityInfo>,
    val versionName: String,
    val targetSdk: Int,
    val installTime: Long,
    val updateTime: Long,
    val apkPath: String,
    val dataDir: String,
    val uid: Int,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val apkSize: String,
    val isRunning: Boolean,
    val permissionsCount: Int
)

sealed class AppListItem {
    data class App(val info: AppInfoModel) : AppListItem()
    data class Activity(val app: AppInfoModel, val activityInfo: ActivityInfo) : AppListItem()
}

// --- ADAPTER CHO LƯỚI GRID MÀN HÌNH CHÍNH ---
class UniversalAppAdapter(
    private var items: List<AppListItem>,
    private val onAppClick: (AppInfoModel) -> Unit,
    private val onActivityClick: (AppInfoModel, ActivityInfo) -> Unit
) : RecyclerView.Adapter<UniversalAppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvAppPackage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AppListItem.App -> {
                holder.ivIcon.setImageDrawable(item.info.icon)
                holder.tvName.text = item.info.name
                holder.tvName.setTextColor(Color.WHITE)

                holder.tvPackage.text = item.info.apkSize

                holder.itemView.setOnClickListener { onAppClick(item.info) }
            }
            is AppListItem.Activity -> {
                holder.ivIcon.setImageDrawable(item.app.icon)
                holder.tvName.text = "⚡ ${item.activityInfo.name.substringAfterLast('.')}"
                holder.tvName.setTextColor(Color.parseColor("#00E5FF"))
                holder.tvPackage.text = "Act Ngầm"

                holder.itemView.setOnClickListener { onActivityClick(item.app, item.activityInfo) }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<AppListItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}