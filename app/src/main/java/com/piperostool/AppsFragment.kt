package com.piperostool

import android.app.ActivityManager
import android.app.Dialog
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
import android.os.SystemClock
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.piperostool.privileged.PiperAppActionPolicy
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.client.PiperAppActionResult
import com.piperostool.privileged.client.PiperPrivilegedClient
import com.piperostool.privileged.ui.PiperAdbActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private lateinit var btnRefreshApps: View
    private lateinit var btnOpenApkEditor: View
    private lateinit var btnSortApps: View
    private lateinit var tvAppsOverview: TextView
    private lateinit var tvAppsVisibleCount: TextView
    private lateinit var forceAccessSwitch: SwitchMaterial
    private lateinit var tvForceAccessSummary: TextView

    private lateinit var tabUser: TextView
    private lateinit var tabSystem: TextView
    private lateinit var tabDisabled: TextView

    private lateinit var universalAdapter: UniversalAppAdapter
    private var allApps = listOf<AppInfoModel>()

    private var currentTabFilter = 0
    private var currentSearchQuery = ""
    private var sortMode = AppSortMode.NAME
    private var forceAccessEnabled = false
    private var updatingForceAccessSwitch = false
    private var forceAccessSyncToken = 0

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
        private const val APPS_PREFS = "piperos_apps_preferences"
        private const val KEY_FORCE_ACCESS = "force_private_activity_access"
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
        forceAccessSwitch = view.findViewById(R.id.switchForceAccess)
        tvForceAccessSummary = view.findViewById(R.id.tvForceAccessSummary)
        tabUser = view.findViewById(R.id.tabUser)
        tabSystem = view.findViewById(R.id.tabSystem)
        tabDisabled = view.findViewById(R.id.tabDisabled)

        // Keep the search glyph compact; the source asset has a large
        // intrinsic size and otherwise renders as a white streak in EditText.
        etSearchApp.compoundDrawablesRelative.firstOrNull()?.let { drawable ->
            val size = (18 * resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, size, size)
            etSearchApp.setCompoundDrawablesRelative(
                drawable,
                etSearchApp.compoundDrawablesRelative[1],
                etSearchApp.compoundDrawablesRelative[2],
                etSearchApp.compoundDrawablesRelative[3]
            )
        }

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
        setupForceAccessToggle(view)

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

        // Apply the selected surface style to the fragment immediately;
        // RecyclerView items are styled again when they are rebound below.
        PiperModernUi.apply(view)
    }

    override fun onResume() {
        super.onResume()
        if (::forceAccessSwitch.isInitialized) syncForceAccessSwitch()
    }

    private fun setupForceAccessToggle(root: View) {
        forceAccessSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingForceAccessSwitch) setForceAccessEnabled(checked)
        }
        root.findViewById<View>(R.id.rowForceAccess).setOnClickListener {
            if (forceAccessSwitch.isEnabled) {
                forceAccessSwitch.isChecked = !forceAccessSwitch.isChecked
            }
        }
        syncForceAccessSwitch()
    }

    private fun syncForceAccessSwitch() {
        val safeContext = context ?: return
        val saved = safeContext.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_ACCESS, false)
        val token = ++forceAccessSyncToken
        updatingForceAccessSwitch = true
        forceAccessSwitch.isEnabled = false
        forceAccessSwitch.isChecked = false
        updatingForceAccessSwitch = false
        tvForceAccessSummary.text = "Đang kiểm tra PiperOS ADB…"

        CoroutineScope(Dispatchers.IO).launch {
            val client = PiperPrivilegedClient(safeContext.applicationContext)
            val adbReady = try {
                client.connect() && client.adbEnabled() && isPiperAdbStatus(client.status())
            } catch (_: Exception) {
                false
            } finally {
                client.close()
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || token != forceAccessSyncToken) return@withContext
                forceAccessEnabled = adbReady && saved
                updatingForceAccessSwitch = true
                forceAccessSwitch.isChecked = forceAccessEnabled
                forceAccessSwitch.isEnabled = true
                updatingForceAccessSwitch = false
                tvForceAccessSummary.text = when {
                    !adbReady -> "Bật và kết nối ADB trong PiperOS ADB để sử dụng"
                    forceAccessEnabled -> "Activity riêng tư sẽ mở qua PiperOS ADB"
                    else -> "Dùng phiên PiperOS ADB đang hoạt động"
                }
            }
        }
    }

    private fun setForceAccessEnabled(enabled: Boolean) {
        val safeContext = context ?: return
        if (!enabled) {
            forceAccessEnabled = false
            safeContext.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FORCE_ACCESS, false).apply()
            syncForceAccessSwitch()
            return
        }
        forceAccessSwitch.isEnabled = false
        tvForceAccessSummary.text = "Đang kiểm tra kết nối PiperOS ADB…"
        CoroutineScope(Dispatchers.IO).launch {
            val client = PiperPrivilegedClient(safeContext.applicationContext)
            val connected = try {
                awaitPiperAdbReady(client)
            } catch (_: Exception) {
                false
            } finally {
                client.close()
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                forceAccessSwitch.isEnabled = true
                if (connected) {
                    forceAccessEnabled = true
                    safeContext.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_FORCE_ACCESS, true).apply()
                } else {
                    updatingForceAccessSwitch = true
                    forceAccessSwitch.isChecked = false
                    updatingForceAccessSwitch = false
                    Toast.makeText(safeContext, "PiperOS ADB chưa kết nối. Hãy hoàn tất thiết lập rồi bật lại công tắc.", Toast.LENGTH_LONG).show()
                    openPiperAdb()
                }
                syncForceAccessSwitch()
            }
        }
    }

    private suspend fun awaitPiperAdbReady(client: PiperPrivilegedClient): Boolean {
        if (!client.connect() || !client.adbEnabled()) return false
        // Only PiperOS ADB may start or refresh this connection. Consumer screens
        // can reuse a live shell session but cannot reconnect it themselves.
        repeat(52) {
            val status = client.status()
            if (isPiperAdbStatus(status)) return true
            if (status != null && status.state != PiperServiceState.STARTING) return false
            delay(250)
        }
        return false
    }

    private fun isPiperAdbStatus(status: PiperServiceStatus?): Boolean =
        status?.let {
            it.state == PiperServiceState.RUNNING &&
                it.privilege == PiperPrivilege.SHELL &&
                it.startupMethod == "PIPEROS_ADB" &&
                it.error == PiperError.NONE
        } == true

    private fun openPiperAdb() {
        context?.let { startActivity(Intent(it, PiperAdbActivity::class.java)) }
    }

    private fun switchTab(tabIndex: Int) {
        currentTabFilter = tabIndex
        val active = PiperModernUi.accentColor(requireContext())
        val inactive = PiperModernUi.textColor(requireContext())
        tabUser.setTextColor(if (tabIndex == 0) active else inactive)
        tabSystem.setTextColor(if (tabIndex == 1) active else inactive)
        tabDisabled.setTextColor(if (tabIndex == 2) active else inactive)
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
        PiperActionSheet.showSingleSelect(
            context = requireContext(),
            title = "Sắp xếp ứng dụng",
            choices = options.mapIndexed { index, label ->
                PiperSheetChoice(index.toString(), label, sortMode.ordinal == index)
            },
            onSelect = { key ->
                sortMode = AppSortMode.entries[key.toInt()]
                applyFilters()
            },
            onRemove = {},
            onAdd = {}
        )
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
        val btnDialogForceStop = dialogView.findViewById<LinearLayout>(R.id.btnDialogForceStop)
        val btnDialogUninstall = dialogView.findViewById<LinearLayout>(R.id.btnDialogUninstall)

        val btnLaunch = dialogView.findViewById<LinearLayout>(R.id.btnLaunch)
        val btnDetails = dialogView.findViewById<LinearLayout>(R.id.btnDetails)

        ivIcon.setImageDrawable(app.icon)
        tvName.text = app.name
        tvVersionTop.text = "Phiên bản ${app.versionName}"

        if (!app.isEnabled) {
            ivStatusIcon.setImageResource(R.drawable.sleep)
            ivStatusIcon.setColorFilter(Color.parseColor("#BDBDBD"))
            tvQuickStatus.text = "Đã tắt"
            tvQuickStatus.setTextColor(Color.parseColor("#BDBDBD"))
            tvInfoStatus.text = "Đã vô hiệu hóa"
            tvInfoStatus.setTextColor(Color.parseColor("#BDBDBD"))
        } else if (app.isRunning) {
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

        btnDialogForceStop.visibility = if (app.packageName == requireContext().packageName) View.GONE else View.VISIBLE
        btnDialogUninstall.visibility = if (!app.isSystem && app.packageName != requireContext().packageName) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val toggleAppAction = if (app.isEnabled) {
            PiperAppActionPolicy.DISABLE_USER_APP
        } else {
            PiperAppActionPolicy.ENABLE_USER_APP
        }
        dialogView.findViewById<TextView>(R.id.tvDialogToggleAppState).apply {
            text = if (app.isEnabled) "Tắt ứng dụng" else "Bật ứng dụng"
            setTextColor(
                Color.parseColor(if (app.isEnabled) "#E97927" else "#31966A")
            )
        }
        btnDialogForceStop.findViewById<ImageView>(R.id.ivDialogToggleAppState)
            .setImageResource(if (app.isEnabled) R.drawable.ic_stop else R.drawable.play)
        btnDialogForceStop.setOnClickListener {
            PiperDialog.showConfirm(
                requireContext(),
                if (app.isEnabled) "Tắt ứng dụng?" else "Bật ứng dụng?",
                if (app.isEnabled) {
                    "PiperOS ADB sẽ vô hiệu hóa ${app.name} cho người dùng hiện tại. Ứng dụng sẽ chuyển sang mục Đã tắt và không thể mở cho đến khi bạn bật lại."
                } else {
                    "PiperOS ADB sẽ bật lại ${app.name} cho người dùng hiện tại."
                },
                positiveLabel = if (app.isEnabled) "Tắt ứng dụng" else "Bật ứng dụng",
                destructive = app.isEnabled
            ) {
                runPiperAdbAction(toggleAppAction, app.packageName, displayName = app.name) { result ->
                    if (result.success) {
                        bottomSheetDialog.dismiss()
                        updateAppEnabledInMemory(app.packageName, !app.isEnabled)
                    } else {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        btnDialogUninstall.setOnClickListener {
            PiperDialog.showConfirm(
                requireContext(),
                "Gỡ ${app.name}?",
                "Gỡ ứng dụng khỏi người dùng hiện tại bằng PiperOS ADB. Dữ liệu riêng của app có thể bị xóa.",
                positiveLabel = "Gỡ ứng dụng",
                destructive = true
            ) {
                bottomSheetDialog.dismiss()
                runPiperAdbAction(
                    PiperAppActionPolicy.UNINSTALL_USER_APP,
                    app.packageName,
                    displayName = app.name
                ) { result ->
                    if (result.success) {
                        removeAppFromMemory(app.packageName)
                    } else {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        PiperModernUi.apply(dialogView)

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
        val dialog = PiperDialog.createContent(requireContext(), dialogView)

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
        val dialog = PiperDialog.createContent(requireContext(), dialogView)

        // Ánh xạ
        val tvActionTitle = dialogView.findViewById<TextView>(R.id.tvActionTitle)
        val tvActionSubtitle = dialogView.findViewById<TextView>(R.id.tvActionSubtitle)
        val btnActionLaunch = dialogView.findViewById<LinearLayout>(R.id.btnActionLaunch)
        val btnActionShortcut = dialogView.findViewById<LinearLayout>(R.id.btnActionShortcut)

        // Set Tên Rút Gọn lên thanh tiêu đề
        tvActionTitle.text = shortName
        tvActionSubtitle.text = when {
            !activityInfo.exported && forceAccessEnabled -> "Dùng PiperOS ADB để thử mở Activity riêng tư. Một số ROM vẫn chặn thao tác này."
            !activityInfo.exported -> "Android chặn Activity riêng tư. Bật PiperOS ADB và công tắc ép mở ở trang Ứng dụng để thử."
            forceAccessEnabled -> "Đang bật chế độ mở bằng PiperOS ADB."
            else -> "Mở Activity bằng Android hoặc tạo lối tắt."
        }
        PiperModernUi.apply(dialogView)

        // Sự kiện: Bấm Nút Trái (Ép Khởi Chạy)
        btnActionLaunch.setOnClickListener {
            dialog.dismiss()
            if (!activityInfo.exported && !forceAccessEnabled) {
                PiperDialog.showMessage(
                    requireContext(),
                    "Activity bị Android chặn",
                    "Bật PiperOS ADB, sau đó bật ‘Tùy chọn sâu ứng dụng’ ở đầu trang Ứng dụng."
                )
            } else if (forceAccessEnabled) {
                runPiperAdbAction(
                    PiperAppActionPolicy.LAUNCH_ACTIVITY,
                    app.packageName,
                    activityInfo.name
                ) { result ->
                    if (!result.success) Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            } else {
                launchActivity(app.packageName, activityInfo.name)
            }
        }

        // Sự kiện: Bấm Nút Phải (Ghim Lối Tắt)
        btnActionShortcut.setOnClickListener {
            dialog.dismiss()
            if (!activityInfo.exported) {
                Toast.makeText(requireContext(), "Launcher không thể mở Activity riêng tư bằng lối tắt", Toast.LENGTH_LONG).show()
            } else {
                createShortcut(app, shortName, activityInfo.name)
            }
        }

        btnActionShortcut.isEnabled = activityInfo.exported
        btnActionShortcut.alpha = if (activityInfo.exported) 1f else 0.45f

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

    private fun runPiperAdbAction(
        action: String,
        packageName: String,
        activityName: String = "",
        displayName: String = packageName,
        onResult: (PiperAppActionResult) -> Unit
    ) {
        val safeContext = context ?: return
        val startedAt = SystemClock.elapsedRealtime()
        val actionLabel = when (action) {
            PiperAppActionPolicy.DISABLE_USER_APP -> "Tắt ứng dụng"
            PiperAppActionPolicy.ENABLE_USER_APP -> "Bật ứng dụng"
            PiperAppActionPolicy.UNINSTALL_USER_APP -> "Gỡ ứng dụng"
            PiperAppActionPolicy.LAUNCH_ACTIVITY -> "Mở Activity"
            else -> "Thao tác ứng dụng"
        }
        val progressUi = createAppActionProgressUi(actionLabel, displayName, packageName)
        progressUi.record("Bắt đầu: $actionLabel • $displayName ($packageName)")
        progressUi.setStatus("Đang xác nhận PiperOS ADB…")
        val timer = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(1_000)
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1_000
                progressUi.setStatus("Đang chờ Android xử lý • ${elapsed}s")
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            val client = PiperPrivilegedClient(safeContext.applicationContext)
            val result = try {
                check(client.connect()) { "Không kết nối được dịch vụ PiperOS ADB" }
                withContext(Dispatchers.Main) {
                    progressUi.record("Đã kết nối dịch vụ PiperOS ADB; đang xác minh quyền shell.")
                }
                check(awaitPiperAdbReady(client)) {
                    "PiperOS ADB chưa kết nối. Hãy mở trang PiperOS ADB để kiểm tra."
                }
                withContext(Dispatchers.Main) {
                    progressUi.record("Đã xác nhận phiên ADB shell UID 2000.")
                    progressUi.record("Đã gửi yêu cầu tới Android Package Manager.")
                    progressUi.setStatus("Android đang áp dụng thay đổi package…")
                }
                client.runAppAction(action, packageName, activityName)
                    ?: error("PiperOS ADB không trả kết quả")
            } catch (error: Exception) {
                PiperAppActionResult(false, error.message ?: "Không thực hiện được thao tác")
            } finally {
                client.close()
            }
            withContext(Dispatchers.Main) {
                timer.cancel()
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1_000.0
                progressUi.record(
                    if (result.success) "Android xác nhận: ${result.message.ifBlank { "Thành công" }}"
                    else "Android trả lỗi: ${result.message}"
                )
                progressUi.record(String.format(Locale.getDefault(), "Thời gian thực: %.1f giây", elapsed))
                progressUi.dialog.dismiss()
                if (!isAdded) return@withContext
                if (!result.success && result.message.contains("PiperOS ADB", ignoreCase = true)) {
                    Toast.makeText(safeContext, result.message, Toast.LENGTH_LONG).show()
                    openPiperAdb()
                } else {
                    onResult(result)
                }
                if (action in setOf(
                        PiperAppActionPolicy.DISABLE_USER_APP,
                        PiperAppActionPolicy.ENABLE_USER_APP,
                        PiperAppActionPolicy.UNINSTALL_USER_APP
                    )
                ) {
                    PiperDialog.showMessage(
                        safeContext,
                        if (result.success) "$actionLabel hoàn tất" else "$actionLabel thất bại",
                        progressUi.logText()
                    )
                }
            }
        }
    }

    private fun createAppActionProgressUi(
        actionLabel: String,
        displayName: String,
        packageName: String
    ): AppActionProgressUi {
        val safeContext = requireContext()
        val status = TextView(safeContext).apply {
            textSize = 14f
            setTextColor(PiperModernUi.accentColor(safeContext))
            setPadding(0, 0, 0, dp(safeContext, 8))
        }
        val log = TextView(safeContext).apply {
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(safeContext))
            setPadding(dp(safeContext, 10), dp(safeContext, 8), dp(safeContext, 10), dp(safeContext, 8))
            setBackgroundColor(PiperModernUi.surfaceColor(safeContext))
        }
        val scroll = ScrollView(safeContext).apply {
            isFillViewport = true
            addView(log)
        }
        val content = LinearLayout(safeContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(ProgressBar(safeContext).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    PiperModernUi.accentColor(safeContext)
                )
            }, LinearLayout.LayoutParams(dp(safeContext, 28), dp(safeContext, 28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(safeContext, 12)
            })
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(safeContext, 132)
            ))
        }
        val dialog = PiperDialog.showCustom(
            context = safeContext,
            title = "$actionLabel: $displayName",
            message = "Package: $packageName",
            content = content,
            positiveLabel = "Đang chạy",
            neutralLabel = "Ẩn",
            negativeLabel = null,
            onPositive = { false }
        )
        return AppActionProgressUi(dialog, status, log, scroll)
    }

    private fun updateAppEnabledInMemory(packageName: String, enabled: Boolean) {
        val updated = allApps.map { app ->
            if (app.packageName == packageName) app.copy(isEnabled = enabled, isRunning = false) else app
        }
        allApps = updated
        cachedAllApps = updated
        updateTabCounts()
        applyFilters()
    }

    private fun removeAppFromMemory(packageName: String) {
        val updated = allApps.filterNot { it.packageName == packageName }
        allApps = updated
        cachedAllApps = updated
        updateTabCounts()
        applyFilters()
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

private class AppActionProgressUi(
    val dialog: Dialog,
    private val statusView: TextView,
    private val logView: TextView,
    private val scrollView: ScrollView
) {
    private val events = mutableListOf<String>()

    fun setStatus(value: String) {
        statusView.text = value
    }

    fun record(value: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        events += "$timestamp  $value"
        logView.text = events.joinToString("\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    fun logText(): String = events.joinToString("\n")
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

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
                holder.tvName.setTextColor(PiperModernUi.textColor(holder.itemView.context))

                holder.tvPackage.text = "${item.info.packageName}  •  ${item.info.apkSize}"
                holder.tvMeta.text = "v${item.info.versionName} • SDK ${item.info.targetSdk} • ${item.info.permissionsCount} quyền"
                holder.tvBadge.text = when {
                    !item.info.isEnabled -> "ĐÃ TẮT"
                    item.info.isRunning -> "ĐANG CHẠY"
                    item.info.isSystem -> "HỆ THỐNG"
                    else -> "NGƯỜI DÙNG"
                }
                holder.tvBadge.setTextColor(
                    if (item.info.isRunning) PiperModernUi.accentColor(holder.itemView.context)
                    else PiperModernUi.secondaryTextColor(holder.itemView.context)
                )

                holder.itemView.setOnClickListener { onAppClick(item.info) }
            }
            is AppListItem.Activity -> {
                holder.ivIcon.setImageDrawable(item.app.icon)
                holder.tvName.text = "⚡ ${item.activityInfo.name.substringAfterLast('.')}"
                holder.tvName.setTextColor(PiperModernUi.accentColor(holder.itemView.context))
                holder.tvPackage.text = "Act Ngầm"
                holder.tvMeta.text = item.app.packageName
                holder.tvBadge.text = "ACTIVITY"
                holder.tvBadge.setTextColor(PiperModernUi.accentColor(holder.itemView.context))

                holder.itemView.setOnClickListener { onActivityClick(item.app, item.activityInfo) }
            }
        }
        holder.tvPackage.setTextColor(PiperModernUi.secondaryTextColor(holder.itemView.context))
        holder.tvMeta.setTextColor(PiperModernUi.secondaryTextColor(holder.itemView.context))
        PiperModernUi.apply(holder.itemView)
        PiperAutoFont.apply(holder.itemView)
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
        PiperModernUi.apply(holder.itemView)
        PiperAutoFont.apply(holder.itemView)
    }

    override fun getItemCount() = activities.size
}
