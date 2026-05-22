package com.piperostool

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var universalAdapter: UniversalAppAdapter
    private var allApps = listOf<AppInfoModel>()

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

        rvApps.layoutManager = LinearLayoutManager(requireContext())

        // BỘ LẮNG NGHE HÀNH VI CUỘN ĐỂ ẨN/HIỆN THANH MENU TAB (MỚI)
        rvApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy > 15) {
                    // Người dùng cuộn xuống -> Ép thanh Menu ẩn xuống dưới cho rộng màn hình
                    (activity as? HomeActivity)?.hideBottomNav()
                } else if (dy < -15) {
                    // Người dùng cuộn lên -> Hiện thanh Menu lại ngay lập tức
                    (activity as? HomeActivity)?.showBottomNav()
                }
            }
        })

        // Khởi tạo Adapter với 2 hành động: Click App và Click Activity
        universalAdapter = UniversalAppAdapter(
            items = emptyList(),
            onAppClick = { app -> showAppDetailsDialog(app) },
            onActivityClick = { app, actInfo -> showActivityActionDialog(app, actInfo) }
        )
        rvApps.adapter = universalAdapter

        etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterAppsAndActivities(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps()
    }

    // TÌM KIẾM CẢ APP VÀ ACTIVITY NGẦM CÙNG LÚC
    private fun filterAppsAndActivities(query: String) {
        val q = query.lowercase(Locale.getDefault())

        // Nếu không gõ gì -> Chỉ hiện danh sách các App
        if (q.isEmpty()) {
            universalAdapter.updateData(allApps.map { AppListItem.App(it) })
            return
        }

        val searchResults = mutableListOf<AppListItem>()

        for (app in allApps) {
            // 1. Kiểm tra xem App có khớp không
            if (app.name.lowercase().contains(q) || app.packageName.lowercase().contains(q)) {
                searchResults.add(AppListItem.App(app))
            }

            // 2. Lục lọi xem Activity ngầm nào của App này khớp không
            for (act in app.activities) {
                val shortActName = act.name.substringAfterLast('.')
                if (act.name.lowercase().contains(q) || shortActName.lowercase().contains(q)) {
                    searchResults.add(AppListItem.Activity(app, act))
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
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
            val appList = mutableListOf<AppInfoModel>()

            for (pack in packages) {
                val appInfo = pack.applicationInfo
                if (appInfo != null) {
                    val name = appInfo.loadLabel(pm).toString()
                    val icon = appInfo.loadIcon(pm)
                    val activities = pack.activities?.toList() ?: emptyList()
                    val installTime = pack.firstInstallTime
                    val updateTime = pack.lastUpdateTime
                    val version = pack.versionName ?: "Unknown"
                    val targetSdk = appInfo.targetSdkVersion

                    appList.add(
                        AppInfoModel(name, pack.packageName, icon, activities, version, targetSdk, installTime, updateTime, appInfo.sourceDir)
                    )
                }
            }
            appList.sortBy { it.name.lowercase(Locale.getDefault()) }
            allApps = appList

            withContext(Dispatchers.Main) {
                // Ban đầu chỉ hiển thị App
                universalAdapter.updateData(allApps.map { AppListItem.App(it) })
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
            }
        }
    }

    // DIALOG HIỂN THỊ CHI TIẾT APP VÀ LIST ACTIVITY KÈM THEO
    private fun showAppDetailsDialog(app: AppInfoModel) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_app_details, null)

        val dialog = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Ánh xạ View trong Dialog
        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDialogName)
        val tvPackage = dialogView.findViewById<TextView>(R.id.tvDialogPackage)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvDialogVersion)

        val btnLaunch = dialogView.findViewById<Button>(R.id.btnDialogLaunch)
        val btnBackup = dialogView.findViewById<Button>(R.id.btnDialogBackup)
        val btnClose = dialogView.findViewById<Button>(R.id.btnDialogClose)
        val rvActivities = dialogView.findViewById<RecyclerView>(R.id.rvDialogActivities)

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        // Gắn dữ liệu
        ivIcon.setImageDrawable(app.icon)
        tvName.text = app.name
        tvPackage.text = app.packageName
        tvVersion.text = "Bản: ${app.versionName} | Cài đặt: ${sdf.format(Date(app.installTime))}"

        // Cài đặt danh sách RecyclerView bên trong Dialog
        rvActivities.layoutManager = LinearLayoutManager(requireContext())
        val activityItems = app.activities.map { AppListItem.Activity(app, it) }
        val dialogAdapter = UniversalAppAdapter(
            items = activityItems,
            onAppClick = {},
            onActivityClick = { a, actInfo ->
                dialog.dismiss()
                showActivityActionDialog(a, actInfo)
            }
        )
        rvActivities.adapter = dialogAdapter

        // Xử lý nút bấm
        btnLaunch.setOnClickListener {
            dialog.dismiss()
            launchApp(app.packageName)
        }

        btnBackup.setOnClickListener {
            dialog.dismiss()
            backupApk(app)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        // BẮT BUỘC: Hiển thị Dialog lên trước...
        dialog.show()

        // ...SAU ĐÓ ÉP KÍCH THƯỚC WINDOW RỘNG RA THEO TỶ LỆ MÀN HÌNH (THẦN CHÚ FIX LỖI)
        dialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics

            // Chiều rộng = 92% màn hình
            val width = (displayMetrics.widthPixels * 0.92).toInt()
            // Chiều cao = 85% màn hình
            val height = (displayMetrics.heightPixels * 0.85).toInt()

            window.setLayout(width, height)
        }
    }

    private fun showActivityActionDialog(app: AppInfoModel, activityInfo: ActivityInfo) {
        val shortName = activityInfo.name.substringAfterLast('.')
        val options = arrayOf("Chạy ép buộc Activity này (Launch)", "Tạo Shortcut ra Màn hình chính")

        AlertDialog.Builder(requireContext())
            .setTitle(shortName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchActivity(app.packageName, activityInfo.name)
                    1 -> createShortcut(app, shortName, activityInfo.name)
                }
            }
            .show()
    }

    // --- CÁC HÀM XỬ LÝ LÕI ---
    private fun launchApp(packageName: String) {
        val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(requireContext(), "App hệ thống ẩn, không có giao diện chính!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchActivity(packageName: String, activityName: String) {
        try {
            val intent = Intent()
            intent.setClassName(packageName, activityName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Bị chặn bảo mật (Exported=false) hoặc cần quyền Root!", Toast.LENGTH_LONG).show()
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

                val pinShortcutInfoSafe = ShortcutInfo.Builder(requireContext(), "shortcut_${System.currentTimeMillis()}")
                    .setShortLabel(shortName)
                    .setIntent(intent)
                    .setIcon(Icon.createWithResource(requireContext(), R.mipmap.ic_launcher)) // Dùng icon PiperOS an toàn
                    .build()

                val pinnedShortcutCallbackIntent = shortcutManager.createShortcutResultIntent(pinShortcutInfoSafe)
                val successCallback = PendingIntent.getBroadcast(
                    requireContext(), 0, pinnedShortcutCallbackIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                shortcutManager.requestPinShortcut(pinShortcutInfoSafe, successCallback.intentSender)
                Toast.makeText(requireContext(), "Đã gửi yêu cầu tạo Shortcut ra màn hình", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Launcher của bạn không hỗ trợ Shortcut", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun backupApk(app: AppInfoModel) {
        Toast.makeText(requireContext(), "Đang sao lưu...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val srcFile = File(app.apkPath)
                val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PiperOS_Backups")
                if (!backupDir.exists()) backupDir.mkdirs()

                val destFileName = "${app.name.replace(" ", "_")}_${app.versionName}.apk"
                val destFile = File(backupDir, destFileName)

                srcFile.copyTo(destFile, overwrite = true)

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Đã lưu APK tại: Download/PiperOS_Backups", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Lỗi sao lưu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// --- DATA MODELS ---
data class AppInfoModel(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val activities: List<ActivityInfo>,
    val versionName: String,
    val targetSdk: Int,
    val installTime: Long,
    val updateTime: Long,
    val apkPath: String
)

sealed class AppListItem {
    data class App(val info: AppInfoModel) : AppListItem()
    data class Activity(val app: AppInfoModel, val activityInfo: ActivityInfo) : AppListItem()
}

// --- ADAPTER ĐA NĂNG ---
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
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AppListItem.App -> {
                holder.ivIcon.setImageDrawable(item.info.icon)
                holder.tvName.text = item.info.name
                holder.tvName.setTextColor(Color.parseColor("#FFFFFF")) // Trắng cho App
                holder.tvPackage.text = item.info.packageName

                holder.itemView.setOnClickListener { onAppClick(item.info) }
            }
            is AppListItem.Activity -> {
                holder.ivIcon.setImageDrawable(item.app.icon) // Lấy icon của app gốc
                holder.tvName.text = "⚡ [Ngầm] ${item.activityInfo.name.substringAfterLast('.')}"
                holder.tvName.setTextColor(Color.parseColor("#00E5FF")) // Màu Xanh Neon cho dễ nhìn
                holder.tvPackage.text = item.activityInfo.name

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