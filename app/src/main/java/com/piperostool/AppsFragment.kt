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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppsFragment : Fragment() {

    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearchApp: EditText
    private lateinit var btnRefreshApps: ImageView
    private lateinit var btnOpenApkEditor: View
    private lateinit var btnSortApps: View
    private lateinit var tvAppsOverview: TextView
    private lateinit var tvAppsVisibleCount: TextView

    private lateinit var tabUser: TextView
    private lateinit var tabSystem: TextView
    private lateinit var tabDisabled: TextView

    private lateinit var universalAdapter: UniversalAppAdapter
    private var allApps = listOf<AppInfoModel>()

    private var currentTabFilter = 0
    private var currentSearchQuery = ""
    private var sortMode = AppSortMode.NAME

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        startActivity(
            Intent(requireContext(), ApkEditorActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    // BỘ NHỚ ĐỆM TĨNH (CACHE)
    companion object {
        private var cachedAllApps: List<AppInfoModel>? = null
    }

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
        btnRefreshApps = view.findViewById(R.id.btnRefreshApps)
        btnOpenApkEditor = view.findViewById(R.id.btnOpenApkEditor)
        btnSortApps = view.findViewById(R.id.btnSortApps)
        tvAppsOverview = view.findViewById(R.id.tvAppsOverview)
        tvAppsVisibleCount = view.findViewById(R.id.tvAppsVisibleCount)
        tabUser = view.findViewById(R.id.tabUser)
        tabSystem = view.findViewById(R.id.tabSystem)
        tabDisabled = view.findViewById(R.id.tabDisabled)

        rvApps.layoutManager = LinearLayoutManager(requireContext())

        universalAdapter = UniversalAppAdapter(
            items = emptyList(),
            onAppClick = { app -> showAppDetailsDialog(app) },
            onActivityClick = { app, actInfo -> showActivityActionDialog(app, actInfo) }
        )
        rvApps.adapter = universalAdapter

        etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s.toString()
                applyFilters()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        tabUser.setOnClickListener { switchTab(0) }
        tabSystem.setOnClickListener { switchTab(1) }
        tabDisabled.setOnClickListener { switchTab(2) }

        btnRefreshApps.setOnClickListener {
            cachedAllApps = null
            loadApps()
            Toast.makeText(requireContext(), "Đang làm mới danh sách App...", Toast.LENGTH_SHORT).show()
        }
        btnOpenApkEditor.setOnClickListener {
            apkPicker.launch(arrayOf(
                "application/vnd.android.package-archive",
                "application/zip"
            ))
        }
        btnSortApps.setOnClickListener { showSortOptions() }

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

        // Nạp Cache
        if (cachedAllApps != null) {
            allApps = cachedAllApps!!
            updateTabCounts()
            applyFilters()
            progressBar.visibility = View.GONE
            rvApps.visibility = View.VISIBLE
        } else {
            loadApps()
        }
    }

    private fun switchTab(tabIndex: Int) {
        currentTabFilter = tabIndex
        tabUser.setTextColor(if (tabIndex == 0) Color.parseColor("#7DFFB0") else Color.WHITE)
        tabSystem.setTextColor(if (tabIndex == 1) Color.parseColor("#7DFFB0") else Color.WHITE)
        tabDisabled.setTextColor(if (tabIndex == 2) Color.parseColor("#7DFFB0") else Color.WHITE)
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
        val sorted = when (sortMode) {
            AppSortMode.NAME -> searchResults.sortedBy { it.sortName.lowercase(Locale.getDefault()) }
            AppSortMode.SIZE -> searchResults.sortedByDescending { it.sortSize }
            AppSortMode.UPDATED -> searchResults.sortedByDescending { it.sortUpdated }
        }
        universalAdapter.updateData(sorted)
        tvAppsVisibleCount.text = "${sorted.count { it is AppListItem.App }} ứng dụng" +
            if (currentSearchQuery.isBlank()) "" else " • ${sorted.count { it is AppListItem.Activity }} activity"
    }

    private fun updateTabCounts() {
        tabUser.text = "Người dùng (${allApps.count { !it.isSystem && it.isEnabled }})"
        tabSystem.text = "Hệ thống (${allApps.count { it.isSystem && it.isEnabled }})"
        tabDisabled.text = "Đã tắt (${allApps.count { !it.isEnabled }})"
        val totalSize = allApps.sumOf { it.apkSizeBytes }
        tvAppsOverview.text = "${allApps.size} ứng dụng • " +
            Formatter.formatShortFileSize(requireContext(), totalSize)
    }

    private fun showSortOptions() {
        val options = arrayOf("Tên A-Z", "Dung lượng lớn nhất", "Cập nhật gần nhất")
        AlertDialog.Builder(requireContext())
            .setTitle("Sắp xếp ứng dụng")
            .setSingleChoiceItems(options, sortMode.ordinal) { dialog, which ->
                sortMode = AppSortMode.entries[which]
                applyFilters()
                dialog.dismiss()
            }
            .show()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        val safeContext = context ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pm = safeContext.packageManager
                val am = safeContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

                val runningProcesses = am.runningAppProcesses?.map { it.processName } ?: emptyList()
                val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
                val packages = pm.getInstalledPackages(flags)

                val appList = mutableListOf<AppInfoModel>()

                for (pack in packages) {
                    if (!isAdded) return@launch // Chống văng app khi chuyển tab nhanh

                    val appInfo = pack.applicationInfo
                    if (appInfo != null) {
                        val name = appInfo.loadLabel(pm).toString()
                        val icon = appInfo.loadIcon(pm)
                        val activities = pack.activities?.toList() ?: emptyList()

                        val permsCount = pack.requestedPermissions?.size ?: 0
                        val version = pack.versionName ?: "Unknown"
                        val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        val apkFile = File(appInfo.sourceDir)
                        val sizeBytes = if (apkFile.exists()) apkFile.length() else 0L
                        val formattedSize = Formatter.formatShortFileSize(safeContext, sizeBytes)

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
                                apkSizeBytes = sizeBytes,
                                isRunning = isRunning,
                                permissionsCount = permsCount
                            )
                        )
                    }
                }
                appList.sortBy { it.name.lowercase(Locale.getDefault()) }

                cachedAllApps = appList
                allApps = appList

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext

                    updateTabCounts()
                    applyFilters()
                    progressBar.visibility = View.GONE
                    rvApps.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // =========================================================
    // HIỂN THỊ CỬA SỔ BOTTOM SHEET BẢNG ĐIỀU KHIỂN CHI TIẾT
    // =========================================================
    private fun showAppDetailsDialog(app: AppInfoModel) {
        val bottomSheetDialog = BottomSheetDialog(requireContext(), R.style.TransparentBottomSheetDialogTheme)
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_app_details, null)
        PiperAutoFont.watch(dialogView)
        bottomSheetDialog.setContentView(dialogView)

        // Ép trong suốt nền mặc định của BottomSheet
        val parentView = dialogView.parent as? View
        parentView?.setBackgroundColor(Color.TRANSPARENT)
        parentView?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)

        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDialogName)
        val tvVersionTop = dialogView.findViewById<TextView>(R.id.tvDialogVersionTop)

        val ivStatusIcon = dialogView.findViewById<ImageView>(R.id.ivStatusIcon)
        val tvQuickStatus = dialogView.findViewById<TextView>(R.id.tvQuickStatus)
        val ivTypeIcon = dialogView.findViewById<ImageView>(R.id.ivTypeIcon)
        val tvQuickType = dialogView.findViewById<TextView>(R.id.tvQuickType)
        val tvQuickSize = dialogView.findViewById<TextView>(R.id.tvQuickSize)
        val btnToggleInfo = dialogView.findViewById<LinearLayout>(R.id.btnToggleInfo)

        val layoutAppInfo = dialogView.findViewById<LinearLayout>(R.id.layoutAppInfo)
        val tvInfoPackage = dialogView.findViewById<TextView>(R.id.tvInfoPackage)
        val tvInfoVersion = dialogView.findViewById<TextView>(R.id.tvInfoVersion)
        val tvInfoStatus = dialogView.findViewById<TextView>(R.id.tvInfoStatus)
        val tvInfoInstall = dialogView.findViewById<TextView>(R.id.tvInfoInstall)
        val tvInfoUpdate = dialogView.findViewById<TextView>(R.id.tvInfoUpdate)
        val tvInfoSdk = dialogView.findViewById<TextView>(R.id.tvInfoSdk)
        val tvInfoPerms = dialogView.findViewById<TextView>(R.id.tvInfoPerms)
        val btnDialogBackup = dialogView.findViewById<LinearLayout>(R.id.btnDialogBackup)
        val btnDialogEditApk = dialogView.findViewById<LinearLayout>(R.id.btnDialogEditApk)

        val btnLaunch = dialogView.findViewById<LinearLayout>(R.id.btnLaunch)
        val btnDetails = dialogView.findViewById<LinearLayout>(R.id.btnDetails)

        ivIcon.setImageDrawable(app.icon)
        tvName.text = app.name
        tvVersionTop.text = "Phiên bản ${app.versionName}"

        if (app.isRunning) {
            ivStatusIcon.setImageResource(R.drawable.status)
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

        if (app.isSystem) {
            ivTypeIcon.setImageResource(R.drawable.system)
            ivTypeIcon.setColorFilter(Color.parseColor("#E53935"))
            tvQuickType.text = "Hệ thống"
        } else {
            ivTypeIcon.setImageResource(R.drawable.apk)
            ivTypeIcon.setColorFilter(Color.parseColor("#4CAF50"))
            tvQuickType.text = "Ứng dụng"
        }

        tvQuickSize.text = app.apkSize

        tvInfoPackage.text = app.packageName
        tvInfoVersion.text = app.versionName
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        tvInfoInstall.text = sdf.format(Date(app.installTime))
        tvInfoUpdate.text = sdf.format(Date(app.updateTime))
        tvInfoSdk.text = app.targetSdk.toString()
        tvInfoPerms.text = "${app.permissionsCount} quyền"

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

        btnDialogEditApk.setOnClickListener {
            bottomSheetDialog.dismiss()
            startActivity(
                Intent(requireContext(), ApkEditorActivity::class.java)
                    .putExtra(ApkEditorActivity.EXTRA_APK_PATH, app.apkPath)
                    .putExtra(ApkEditorActivity.EXTRA_PACKAGE_NAME, app.packageName)
            )
        }

        bottomSheetDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        bottomSheetDialog.window?.setDimAmount(0.6f)

        bottomSheetDialog.show()
    }

    // =========================================================
    // HIỂN THỊ BẢNG DANH SÁCH ACTIVITIES NGẦM SIÊU ĐẸP
    // =========================================================
    private fun showActivitiesList(app: AppInfoModel) {
        if (app.activities.isEmpty()) {
            Toast.makeText(requireContext(), "App này không có Activity ngầm hợp lệ!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_activities_list, null)
        PiperAutoFont.watch(dialogView)
        val dialog = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(dialogView).create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.7f) // Làm mờ nền sau 70%

        val tvActCount = dialogView.findViewById<TextView>(R.id.tvActCount)
        val rvActivities = dialogView.findViewById<RecyclerView>(R.id.rvActivities)
        val btnActClose = dialogView.findViewById<LinearLayout>(R.id.btnActClose)

        tvActCount.text = app.activities.size.toString()

        rvActivities.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvActivities.adapter = ActivityAdapter(app.activities) { selectedAct ->
            dialog.dismiss()
            showActivityActionDialog(app, selectedAct)
        }

        btnActClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        // =======================================================
        // FIX LỖI TRÀN MÀN HÌNH BẰNG THUẬT TOÁN BÓP CHIỀU CAO
        // =======================================================

        // 1. Ép cửa sổ dàn Full chiều ngang (để ăn lề margin 20dp), chiều cao co giãn
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 2. Chặn chiều cao của Danh sách: Không được vượt quá 55% chiều cao màn hình
        rvActivities.post {
            val displayMetrics = resources.displayMetrics
            val maxHeight = (displayMetrics.heightPixels * 0.55).toInt()

            // Nếu danh sách có quá nhiều app (ví dụ 65 app), chiều cao bị lố -> Cắt nó lại!
            if (rvActivities.height > maxHeight) {
                val params = rvActivities.layoutParams
                params.height = maxHeight
                rvActivities.layoutParams = params
            }
        }
    }

    // =========================================================
    // HIỂN THỊ HỘP THOẠI HÀNH ĐỘNG CHO TỪNG ACTIVITY
    // =========================================================
    private fun showActivityActionDialog(app: AppInfoModel, activityInfo: ActivityInfo) {
        val shortName = activityInfo.name.substringAfterLast('.')

        // Khởi tạo Custom Dialog
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_action_activity, null)
        PiperAutoFont.watch(dialogView)
        val dialog = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(dialogView).create()

        // Làm trong suốt viền dư và mờ màn hình phía sau
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.6f)

        // Ánh xạ
        val tvActionTitle = dialogView.findViewById<TextView>(R.id.tvActionTitle)
        val btnActionLaunch = dialogView.findViewById<LinearLayout>(R.id.btnActionLaunch)
        val btnActionShortcut = dialogView.findViewById<LinearLayout>(R.id.btnActionShortcut)

        // Set Tên Rút Gọn lên thanh tiêu đề
        tvActionTitle.text = shortName

        // Sự kiện: Bấm Nút Trái (Ép Khởi Chạy)
        btnActionLaunch.setOnClickListener {
            dialog.dismiss()
            launchActivity(app.packageName, activityInfo.name)
        }

        // Sự kiện: Bấm Nút Phải (Ghim Lối Tắt)
        btnActionShortcut.setOnClickListener {
            dialog.dismiss()
            createShortcut(app, shortName, activityInfo.name)
        }

        // Hiển thị ra màn hình
        dialog.show()

        // Ép hộp thoại không bị tràn, bóp vừa phải ở giữa màn hình (Margin 40dp 2 bên)
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - 80, // Chiều rộng bằng màn hình trừ đi 80 pixel
            ViewGroup.LayoutParams.WRAP_CONTENT        // Chiều cao tự ôm sát nội dung
        )
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
        val safeContext = context ?: return
        Toast.makeText(safeContext, "Đang đóng gói APK...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val srcFile = File(app.apkPath)
                val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PiperOS_Backups")
                if (!backupDir.exists()) backupDir.mkdirs()
                val destFile = File(backupDir, "${app.name.replace(" ", "_")}_${app.versionName}.apk")
                srcFile.copyTo(destFile, overwrite = true)

                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(safeContext, "Thành công! Đã lưu tại Download/PiperOS_Backups", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(safeContext, "Lỗi đóng gói: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// =========================================================
// DATA MODELS
// =========================================================
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
    val apkSizeBytes: Long,
    val isRunning: Boolean,
    val permissionsCount: Int
)

sealed class AppListItem {
    data class App(val info: AppInfoModel) : AppListItem()
    data class Activity(val app: AppInfoModel, val activityInfo: ActivityInfo) : AppListItem()

    val sortName: String
        get() = when (this) {
            is App -> info.name
            is Activity -> activityInfo.name
        }
    val sortSize: Long
        get() = when (this) {
            is App -> info.apkSizeBytes
            is Activity -> app.apkSizeBytes
        }
    val sortUpdated: Long
        get() = when (this) {
            is App -> info.updateTime
            is Activity -> app.updateTime
        }
}

private enum class AppSortMode { NAME, SIZE, UPDATED }

// =========================================================
// ADAPTER CHO LƯỚI GRID MÀN HÌNH CHÍNH
// =========================================================
class UniversalAppAdapter(
    private var items: List<AppListItem>,
    private val onAppClick: (AppInfoModel) -> Unit,
    private val onActivityClick: (AppInfoModel, ActivityInfo) -> Unit
) : RecyclerView.Adapter<UniversalAppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val tvMeta: TextView = view.findViewById(R.id.tvAppMeta)
        val tvBadge: TextView = view.findViewById(R.id.tvAppBadge)
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

                holder.tvPackage.text = "${item.info.packageName}  •  ${item.info.apkSize}"
                holder.tvMeta.text = "v${item.info.versionName} • SDK ${item.info.targetSdk} • ${item.info.permissionsCount} quyền"
                holder.tvBadge.text = when {
                    !item.info.isEnabled -> "ĐÃ TẮT"
                    item.info.isRunning -> "ĐANG CHẠY"
                    item.info.isSystem -> "HỆ THỐNG"
                    else -> "NGƯỜI DÙNG"
                }
                holder.tvBadge.setTextColor(
                    Color.parseColor(if (item.info.isRunning) "#7DFFB0" else "#B8FFFFFF")
                )

                holder.itemView.setOnClickListener { onAppClick(item.info) }
            }
            is AppListItem.Activity -> {
                holder.ivIcon.setImageDrawable(item.app.icon)
                holder.tvName.text = "⚡ ${item.activityInfo.name.substringAfterLast('.')}"
                holder.tvName.setTextColor(Color.parseColor("#00E5FF"))
                holder.tvPackage.text = "Act Ngầm"
                holder.tvMeta.text = item.app.packageName
                holder.tvBadge.text = "ACTIVITY"
                holder.tvBadge.setTextColor(Color.parseColor("#00E5FF"))

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

// =========================================================
// ADAPTER CHO BẢNG DANH SÁCH ACTIVITIES NGẦM (NẰM BÊN NGOÀI CÙNG)
// =========================================================
class ActivityAdapter(
    private val activities: List<ActivityInfo>,
    private val onActClick: (ActivityInfo) -> Unit
) : RecyclerView.Adapter<ActivityAdapter.ActViewHolder>() {

    class ActViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvShortName: TextView = view.findViewById(R.id.tvActShortName)
        val tvFullName: TextView = view.findViewById(R.id.tvActFullName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity_row, parent, false)
        return ActViewHolder(view)
    }

    override fun onBindViewHolder(holder: ActViewHolder, position: Int) {
        val actInfo = activities[position]

        holder.tvShortName.text = actInfo.name.substringAfterLast('.')
        holder.tvFullName.text = actInfo.name

        holder.itemView.setOnClickListener { onActClick(actInfo) }
    }

    override fun getItemCount() = activities.size
}
