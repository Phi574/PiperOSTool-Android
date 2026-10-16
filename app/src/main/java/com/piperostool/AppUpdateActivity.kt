package com.piperostool

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppUpdateActivity : AppCompatActivity() {
    private lateinit var headline: TextView
    private lateinit var currentVersion: TextView
    private lateinit var published: TextView
    private lateinit var description: TextView
    private lateinit var status: TextView
    private lateinit var progressArtwork: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var action: MaterialCardView
    private lateinit var otherReleases: MaterialCardView
    private lateinit var deleteDownloaded: MaterialCardView
    private lateinit var refresh: MaterialCardView
    private lateinit var actionText: TextView
    private lateinit var cancel: MaterialCardView
    private lateinit var logToggle: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var log: TextView
    private var release: AppRelease? = null
    private var downloadedApk: File? = null
    private var downloadWorkInfo: WorkInfo? = null
    private var checking = false
    private var installerPending = false
    private var currentArtwork = 0
    private var refreshAnimator: ObjectAnimator? = null
    private val updatePreferences by lazy { getSharedPreferences("update_program", MODE_PRIVATE) }
    private var foundReleases: List<AppRelease> = emptyList()

    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (installerPending && packageManager.canRequestPackageInstalls()) {
            installerPending = false
            launchInstaller()
        } else if (installerPending) {
            installerPending = false
            showError("Chưa cấp quyền cài APK từ PiperOS Tool. Bạn có thể bật quyền và thử lại.")
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkRelease()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update)
        PiperModernUi.watch(this)
        headline = findViewById(R.id.updateHeadline)
        currentVersion = findViewById(R.id.updateCurrentVersion)
        published = findViewById(R.id.updatePublished)
        description = findViewById(R.id.updateDescription)
        status = findViewById(R.id.updateStatus)
        progressArtwork = findViewById(R.id.updateProgressArtwork)
        progress = findViewById(R.id.updateProgress)
        action = findViewById(R.id.updateAction)
        otherReleases = findViewById(R.id.updateOtherReleases)
        deleteDownloaded = findViewById(R.id.updateDeleteDownload)
        refresh = findViewById(R.id.updateRefresh)
        actionText = findViewById(R.id.updateActionText)
        cancel = findViewById(R.id.updateCancel)
        logToggle = findViewById(R.id.updateLogToggle)
        logScroll = findViewById(R.id.updateLogScroll)
        log = findViewById(R.id.updateLog)
        currentVersion.text = "Đang có trên máy: ${AppVersion.name(this)}"
        findViewById<View>(R.id.updateBack).setOnClickListener { leave() }
        findViewById<View>(R.id.updateSettings).setOnClickListener { showUpdateSettingsMenu() }
        refresh.setOnClickListener { checkRelease() }
        deleteDownloaded.setOnClickListener { confirmDeleteDownloaded() }
        cancel.setOnClickListener { cancelDownload() }
        otherReleases.setOnClickListener { showOtherReleases() }
        logToggle.setOnClickListener {
            val expanded = logScroll.visibility != View.VISIBLE
            logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
            logToggle.text = if (expanded) "Ẩn chi tiết tải xuống  ▴" else "Chi tiết tải xuống  ▾"
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        observeDownloadWork()
        requestUpdateNotificationsOrCheck()
    }

    private fun leave() {
        finish()
    }

    private fun requestUpdateNotificationsOrCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            checkRelease()
            return
        }
        val prompted = updatePreferences.getBoolean("update_notifications_prompted", false)
        if (prompted) {
            checkRelease()
            return
        }
        updatePreferences.edit().putBoolean("update_notifications_prompted", true).apply()
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.update_notifications_prompt_title),
            message = getString(R.string.update_notifications_prompt_message),
            positiveLabel = getString(R.string.update_notifications_prompt_action),
            negativeLabel = getString(R.string.update_notifications_prompt_later),
            onPositive = {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                true
            },
            onNegative = { checkRelease() }
        )
    }

    private fun observeDownloadWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(AppUpdateDownload.WORK_NAME)
            .observe(this) { infos ->
                val info = infos?.firstOrNull { !it.state.isFinished } ?: infos?.lastOrNull()
                downloadWorkInfo = info
                if (info == null) {
                    cancel.visibility = View.GONE
                    return@observe
                }
                val tag = info.progress.getString(AppUpdateDownload.KEY_TAG)
                    ?: info.outputData.getString(AppUpdateDownload.KEY_TAG)
                    ?: info.tags.firstOrNull { it.startsWith(AppUpdateDownload.TAG_PREFIX) }
                        ?.removePrefix(AppUpdateDownload.TAG_PREFIX)
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        if (tag != null) {
                            status.text = "Đang chờ tải PiperOS Tool $tag"
                            progress.visibility = View.GONE
                            progressArtwork.visibility = View.GONE
                            action.visibility = View.GONE
                            cancel.visibility = View.VISIBLE
                        }
                    }
                    WorkInfo.State.RUNNING -> {
                        if (tag != null) {
                            val percent = info.progress.getInt(AppUpdateDownload.KEY_PERCENT, 0)
                            val bytes = info.progress.getLong(AppUpdateDownload.KEY_BYTES, 0L)
                            val total = info.progress.getLong(AppUpdateDownload.KEY_TOTAL, 0L)
                            progress.visibility = View.VISIBLE
                            progress.progress = percent
                            progressArtwork.visibility = View.VISIBLE
                            showDownloadArtwork(percent)
                            status.text = "Đang tải $percent% · ${bytes / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                            action.visibility = View.GONE
                            cancel.visibility = View.VISIBLE
                            deleteDownloaded.visibility = View.GONE
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        cancel.visibility = View.GONE
                        val path = info.outputData.getString(AppUpdateDownload.KEY_APK_PATH)
                        val file = path?.let(::File)?.takeIf { it.isFile }
                        val installed = AppUpdateRepository.version(AppVersion.name(this))
                        val completed = tag?.let(AppUpdateRepository::version)
                        if (file != null && installed != null && completed != null && completed > installed) {
                            showDownloadedApk(file, tag)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        cancel.visibility = View.GONE
                        val message = info.outputData.getString(AppUpdateDownload.KEY_ERROR)
                        status.text = message ?: "Không thể tải hoặc xác minh APK. Hãy thử lại."
                        release?.let { target -> setAction("Thử tải lại") { showInstallConfirmation(target) } }
                    }
                    WorkInfo.State.CANCELLED -> {
                        cancel.visibility = View.GONE
                        progress.visibility = View.GONE
                        progressArtwork.visibility = View.GONE
                        release?.let { target -> setAction("Tải xuống và cài đặt") { showInstallConfirmation(target) } }
                    }
                }
            }
    }

    private fun showDownloadedApk(file: File, tag: String) {
        downloadedApk = file
        progress.visibility = View.GONE
        cancel.visibility = View.GONE
        showVerificationArtwork(success = true)
        status.text = "APK $tag đã tải và xác minh · sẵn sàng cài đặt"
        setAction("Mở trình cài đặt") { launchInstaller() }
        deleteDownloaded.visibility = View.VISIBLE
    }

    private fun confirmDeleteDownloaded() {
        val file = downloadedApk ?: release?.let { AppUpdateRepository.downloadedApk(this, it) }
        if (file == null || !file.exists()) {
            deleteDownloaded.visibility = View.GONE
            return
        }
        PiperDialog.showConfirm(
            context = this,
            title = "Xóa APK đã tải xuống?",
            message = "Tệp ${file.name} sẽ bị xóa khỏi bộ nhớ ứng dụng. Bạn có thể tải lại sau.",
            positiveLabel = "Xóa",
            negativeLabel = "Giữ lại",
            onConfirm = {
                if (file.delete()) {
                    downloadedApk = null
                    deleteDownloaded.visibility = View.GONE
                    progressArtwork.visibility = View.GONE
                    release?.let { target -> setAction("Tải xuống và cài đặt") { showInstallConfirmation(target) } }
                    status.text = "Đã xóa APK đã tải xuống"
                } else {
                    showError("Không thể xóa APK đã tải xuống")
                }
            }
        )
    }

    private fun checkRelease() {
        if (checking) return
        checking = true
        deleteDownloaded.visibility = View.GONE
        refreshAnimator?.cancel()
        refreshAnimator = ObjectAnimator.ofFloat(findViewById(R.id.updateRefreshIcon), View.ROTATION, 0f, 360f).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        action.visibility = View.GONE
        otherReleases.visibility = View.GONE
        status.text = "Đang kiểm tra"
        val loadingContent = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(22))
                addView(ProgressBar(this@AppUpdateActivity), LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(TextView(this@AppUpdateActivity).apply {
                    text = "Đang kiểm tra"
                    textSize = 16f
                    setTextColor(PiperModernUi.textColor(this@AppUpdateActivity))
                    setPadding(dp(18), 0, 0, 0)
                })
        }
        val loading = PiperDialog.createContent(this, loadingContent).apply { setCancelable(false) }
        loading.show()
        lifecycleScope.launch {
            try {
                foundReleases = AppUpdateRepository.releases()
                val channel = AppUpdateRepository.selectedChannel(updatePreferences)
                val localName = AppVersion.name(this@AppUpdateActivity)
                val mustInstallBaseline = AppUpdateRepository.needsRequiredMigration(localName)
                val found = if (mustInstallBaseline) {
                    AppUpdateRepository.requiredMigrationRelease(foundReleases, localName)
                } else {
                    AppUpdateRepository.nextRelease(foundReleases, localName, channel)
                }
                val latestEligible = foundReleases.lastOrNull { AppUpdateRepository.isEligible(it.tag, channel) }
                if (found == null) {
                    headline.text = "PiperOS Tool $localName"
                    published.text = latestEligible?.let { "${channelLabel(channel)} · bản mới nhất ${it.tag}" }.orEmpty()
                    if (mustInstallBaseline) {
                        headline.text = "Cần cài bản ${AppUpdateRepository.REQUIRED_BASELINE} trước"
                        description.text = "Bản ${AppUpdateRepository.REQUIRED_BASELINE} thiết lập nền tảng ký để cập nhật các phiên bản tiếp theo. Hãy cài đúng bản này trước; PiperOS sẽ mở khóa các bản mới hơn sau khi cập nhật xong."
                        status.text = "Không thể bỏ qua bước cập nhật bắt buộc này"
                    } else {
                        description.text = latestEligible?.let { readableNotes(it.description) } ?: "Chưa có bản phát hành cho kênh này."
                        status.text = "Bản hiện tại là bản mới nhất trong kênh ${channelLabel(channel)}"
                    }
                    release = null
                    action.visibility = View.GONE
                    return@launch
                }
                release = found
                headline.text = found.title
                published.text = "GitHub Releases · ${found.tag} · ${found.published}"
                val local = AppUpdateRepository.version(localName)
                val remote = AppUpdateRepository.version(found.tag)
                val newer = local != null && remote != null && remote > local
                if (newer) {
                    description.text = readableNotes(found.description)
                    status.text = if (found.assetUrl == null) "Bước kế tiếp ${found.tag} chưa có APK để cài" else "Bước cập nhật kế tiếp: ${found.tag} · ${channelLabel(channel)}"
                    if (found.assetUrl != null) showSavedDownload(found)
                    otherReleases.visibility = if (!mustInstallBaseline && AppUpdateRepository.laterReleases(foundReleases, localName, channel).size > 1) View.VISIBLE else View.GONE
                } else {
                    if (remote != local) published.text = "Bản công khai gần nhất: ${found.tag}"
                    description.text = if (remote == local) readableNotes(found.description) else
                        "Bản ${AppVersion.name(this@AppUpdateActivity)} gồm các bản vá bảo mật thường xuyên."
                    headline.text = "PiperOS Tool ${AppVersion.name(this@AppUpdateActivity)}"
                    status.text = "Bản hiện tại là bản mới nhất"
                    action.visibility = View.GONE
                }
            } catch (error: Exception) {
                headline.text = "Không kiểm tra được bản phát hành"
                description.text = "Không thể lấy thông tin từ GitHub. Kiểm tra kết nối mạng rồi thử lại."
                showError(error.message ?: "Lỗi kết nối không xác định")
                setAction("Kiểm tra lại") { checkRelease() }
            } finally {
                checking = false
                refreshAnimator?.cancel()
                findViewById<View>(R.id.updateRefreshIcon).rotation = 0f
                loading.dismiss()
            }
        }
    }

    private fun showSavedDownload(target: AppRelease) {
        val info = downloadWorkInfo
        val activeTag = info?.let { work ->
            work.progress.getString(AppUpdateDownload.KEY_TAG)
                ?: work.outputData.getString(AppUpdateDownload.KEY_TAG)
                ?: work.tags.firstOrNull { it.startsWith(AppUpdateDownload.TAG_PREFIX) }
                    ?.removePrefix(AppUpdateDownload.TAG_PREFIX)
        }
        if (info != null && !info.state.isFinished && activeTag == target.tag) {
            action.visibility = View.GONE
            cancel.visibility = View.VISIBLE
            return
        }
        cancel.visibility = View.GONE
        progress.visibility = View.GONE
        progressArtwork.visibility = View.GONE
        val file = AppUpdateRepository.downloadedApk(this, target)
        if (!file.isFile) {
            deleteDownloaded.visibility = View.GONE
            setAction("Tải xuống và cài đặt") { showInstallConfirmation(target) }
            return
        }
        lifecycleScope.launch {
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppUpdateRepository.verifyDownloadedApk(this@AppUpdateActivity, target, file)
                }
                if (release?.tag == target.tag) showDownloadedApk(file, target.tag)
            } catch (_: Exception) {
                AppUpdateRepository.deleteDownloadedApk(this@AppUpdateActivity, target)
                if (release?.tag == target.tag) {
                    setAction("Tải xuống và cài đặt") { showInstallConfirmation(target) }
                    deleteDownloaded.visibility = View.GONE
                }
            }
        }
    }

    private fun channelLabel(channel: AppUpdateRepository.Channel) = when (channel) {
        AppUpdateRepository.Channel.STABLE -> "Ổn định"
        AppUpdateRepository.Channel.BETA -> "BETA"
        AppUpdateRepository.Channel.PREVIEW -> "PREVIEW"
    }

    private fun showUpdateSettingsMenu() {
        val option = TextView(this).apply {
            text = "Chương trình Beta / Preview     ›"
            textSize = 15f
            setTextColor(PiperModernUi.textColor(this@AppUpdateActivity))
            setPadding(dp(14), dp(15), dp(14), dp(15))
            isClickable = true
            isFocusable = true
        }
        lateinit var menu: Dialog
        option.setOnClickListener {
            menu.dismiss()
            showProgramSettings()
        }
        menu = PiperDialog.showCustom(
            context = this,
            title = "Cài đặt cập nhật",
            content = option,
            positiveLabel = "Đóng",
            negativeLabel = null,
            onPositive = { true }
        )
    }

    private fun showProgramSettings() {
        val enabled = updatePreferences.getBoolean("update_program_enabled", false)
        if (!enabled && !updatePreferences.getBoolean("update_terms_accepted", false)) {
            PiperDialog.showCustom(
                context = this,
                title = "Điều khoản Beta / Preview",
                message = "Bản thử nghiệm có thể chứa lỗi, gây mất ổn định hoặc không tương thích với thiết bị. Hãy sao lưu dữ liệu trước khi cài. Bạn có thể tắt chương trình bất cứ lúc nào; các bản đang cài sẽ không tự hạ cấp.",
                positiveLabel = "Đồng ý",
                negativeLabel = "Hủy",
                onPositive = {
                    updatePreferences.edit().putBoolean("update_terms_accepted", true)
                        .putBoolean("update_program_enabled", true)
                        .putString("update_channel", AppUpdateRepository.Channel.BETA.name).apply()
                    checkRelease()
                    true
                }
            )
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val toggle = Switch(this).apply {
            text = "Cập nhật các bản thử nghiệm (Beta/Preview)"
            isChecked = enabled
            setTextColor(PiperModernUi.textColor(this@AppUpdateActivity))
        }
        root.addView(toggle)
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val beta = RadioButton(this).apply { text = "Bản thử nghiệm (BETA)"; id = View.generateViewId() }
        val preview = RadioButton(this).apply { text = "Bản DEV (PREVIEW) · có thể không ổn định"; id = View.generateViewId() }
        choices.addView(beta); choices.addView(preview)
        root.addView(choices)
        val currentChannel = AppUpdateRepository.selectedChannel(updatePreferences)
        choices.check(if (currentChannel == AppUpdateRepository.Channel.PREVIEW) preview.id else beta.id)
        fun applyEnabledState() {
            beta.isEnabled = toggle.isChecked
            preview.isEnabled = toggle.isChecked
            beta.alpha = if (toggle.isChecked) 1f else 0.45f
            preview.alpha = if (toggle.isChecked) 1f else 0.45f
        }
        applyEnabledState()
        lateinit var dialog: Dialog
        dialog = PiperDialog.showCustom(
            context = this,
            title = "Chương trình Beta / Preview",
            content = root,
            positiveLabel = "Đóng",
            negativeLabel = null,
            onPositive = { true }
        )
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!updatePreferences.getBoolean("update_terms_accepted", false)) {
                    toggle.isChecked = false
                    PiperDialog.showCustom(
                        context = this,
                        title = "Điều khoản Beta / Preview",
                        message = "Bản thử nghiệm có thể chứa lỗi, gây mất ổn định hoặc không tương thích với thiết bị. Hãy sao lưu dữ liệu trước khi cài.",
                        positiveLabel = "Đồng ý",
                        negativeLabel = "Hủy",
                        onPositive = {
                            updatePreferences.edit().putBoolean("update_terms_accepted", true)
                                .putBoolean("update_program_enabled", true)
                                .putString("update_channel", AppUpdateRepository.Channel.BETA.name).apply()
                            dialog.dismiss(); checkRelease()
                            true
                        }
                    )
                } else {
                    val editor = updatePreferences.edit().putBoolean("update_program_enabled", true)
                    if (AppUpdateRepository.selectedChannel(updatePreferences) == AppUpdateRepository.Channel.STABLE) {
                        editor.putString("update_channel", AppUpdateRepository.Channel.BETA.name)
                    }
                    editor.apply()
                    applyEnabledState()
                }
            } else {
                val isNonStableInstalled = AppUpdateRepository.channel(AppVersion.name(this)) != AppUpdateRepository.Channel.STABLE
                val confirm = {
                    updatePreferences.edit().putBoolean("update_program_enabled", false)
                        .putString("update_channel", AppUpdateRepository.Channel.STABLE.name).apply()
                    applyEnabledState(); checkRelease()
                }
                if (isNonStableInstalled) PiperDialog.showCustom(
                    context = this,
                    title = "Thoát chương trình Beta / Preview?",
                    message = "Bạn đang dùng bản thử nghiệm. Nếu tắt, các lần kiểm tra sau chỉ đề xuất bản ổn định chính thức; ứng dụng hiện tại không bị gỡ hoặc hạ cấp.",
                    positiveLabel = "Tắt chương trình",
                    negativeLabel = "Tiếp tục tham gia",
                    onPositive = { confirm(); true },
                    onNegative = { toggle.isChecked = true }
                )
                else confirm()
            }
        }
        choices.setOnCheckedChangeListener { _, checkedId ->
            if (toggle.isChecked) {
                val selected = if (checkedId == preview.id) AppUpdateRepository.Channel.PREVIEW else AppUpdateRepository.Channel.BETA
                updatePreferences.edit().putString("update_channel", selected.name).apply()
                checkRelease()
            }
        }
        dialog.show()
    }

    private fun showOtherReleases() {
        val channel = AppUpdateRepository.selectedChannel(updatePreferences)
        val candidates = AppUpdateRepository.laterReleases(foundReleases, AppVersion.name(this), channel)
        if (candidates.isEmpty()) return
        val next = AppUpdateRepository.nextRelease(foundReleases, AppVersion.name(this), channel)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var releasesDialog: Dialog? = null
        candidates.forEach { candidate ->
            val row = TextView(this).apply {
                text = "${candidate.tag} · ${channelLabel(AppUpdateRepository.channel(candidate.tag))}"
                textSize = 15f
                setTextColor(PiperModernUi.textColor(this@AppUpdateActivity))
                setPadding(dp(14), dp(13), dp(14), dp(13))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    releasesDialog?.dismiss()
                    if (candidate.tag.equals(next?.tag, ignoreCase = true)) {
                        selectRelease(candidate)
                    } else {
                        PiperDialog.showConfirm(
                            context = this@AppUpdateActivity,
                            title = "Cập nhật không tuần tự",
                            message = "Cài ${candidate.tag} trực tiếp có thể gây lỗi ở một số phiên bản hoặc điện thoại. Bạn có thể bỏ qua các bản trung gian. Tiếp tục?",
                            positiveLabel = "Tiếp tục",
                            onConfirm = { selectRelease(candidate) }
                        )
                    }
                }
            }
            list.addView(row)
            if (candidate != candidates.last()) list.addView(View(this).apply {
                setBackgroundColor(PiperModernUi.borderColor(this@AppUpdateActivity))
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        releasesDialog = PiperDialog.showCustom(
            context = this,
            title = "Các bản cập nhật khác",
            content = scroll,
            positiveLabel = "Đóng",
            negativeLabel = null,
            onPositive = { true }
        )
    }

    private fun selectRelease(selected: AppRelease) {
        if (AppUpdateRepository.needsRequiredMigration(AppVersion.name(this)) &&
            AppUpdateRepository.version(selected.tag) != AppUpdateRepository.version(AppUpdateRepository.REQUIRED_BASELINE)
        ) {
            showError("Bạn cần cài ${AppUpdateRepository.REQUIRED_BASELINE} trước khi chọn bản mới hơn")
            return
        }
        release = selected
        otherReleases.visibility = View.GONE
        headline.text = selected.title
        published.text = "GitHub Releases · ${selected.tag} · ${selected.published}"
        description.text = readableNotes(selected.description)
        status.text = "Đã chọn cập nhật lên ${selected.tag}"
        showSavedDownload(selected)
    }

    private fun showInstallConfirmation(target: AppRelease) {
        PiperDialog.showConfirm(
            context = this,
            title = "Tải bản ${target.tag}?",
            message = "Tệp APK sẽ được tải nền và lưu lại trong PiperOS. Bạn có thể rời màn hình này hoặc quay lại sau; chỉ vuốt tắt PiperOS khỏi màn đa nhiệm mới dừng tiến trình nền. Hãy sao lưu dữ liệu quan trọng trước khi cài.",
            positiveLabel = "Tải xuống",
            negativeLabel = "Hủy",
            onConfirm = { beginDownload(target) }
        )
    }

    private fun beginDownload(target: AppRelease) {
        downloadedApk = null
        log.text = ""
        currentArtwork = 0
        logToggle.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        showDownloadArtwork(0)
        cancel.visibility = View.VISIBLE
        deleteDownloaded.visibility = View.GONE
        action.visibility = View.GONE
        otherReleases.visibility = View.GONE
        appendLog("Đã xếp tải nền ${target.tag} từ GitHub Releases")
        appendLog("Tệp sẽ được giữ lại trong ứng dụng sau khi tải xong")
        AppUpdateDownload.enqueue(this, target)
    }

    private fun cancelDownload() {
        AppUpdateDownload.cancel(this)
        cancel.visibility = View.GONE
        progress.visibility = View.GONE
        progressArtwork.visibility = View.GONE
        release?.let { target -> setAction("Tải xuống và cài đặt") { showInstallConfirmation(target) } }
    }

    private fun showDownloadArtwork(percent: Int) {
        val resource = when {
            percent >= 100 -> R.drawable.loading_100
            percent >= 90 -> R.drawable.loading_90
            percent >= 80 -> R.drawable.loading_80
            percent >= 75 -> R.drawable.loading_75
            percent >= 70 -> R.drawable.loading_70
            percent >= 60 -> R.drawable.loading_60
            percent >= 50 -> R.drawable.loading_50
            percent >= 40 -> R.drawable.loading_40
            percent >= 30 -> R.drawable.loading_30
            percent >= 25 -> R.drawable.loading_25
            percent >= 20 -> R.drawable.loading_20
            else -> R.drawable.loading_10
        }
        if (currentArtwork != resource) {
            progressArtwork.setImageResource(resource)
            currentArtwork = resource
        }
        progressArtwork.imageTintList = android.content.res.ColorStateList.valueOf(
            PiperModernUi.accentColor(this)
        )
        progressArtwork.visibility = View.VISIBLE
    }

    private fun showVerificationArtwork(success: Boolean) {
        val resource = if (success) R.drawable.very else R.drawable.no_very
        progressArtwork.setImageResource(resource)
        progressArtwork.imageTintList = android.content.res.ColorStateList.valueOf(
            if (success) Color.rgb(69, 211, 135) else Color.rgb(248, 86, 86)
        )
        progressArtwork.visibility = View.VISIBLE
        currentArtwork = resource
    }

    private fun launchInstaller() {
        val file = downloadedApk ?: return
        if (!file.isFile) {
            showError("APK đã tải không còn trong bộ nhớ ứng dụng. Hãy tải lại.")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            installerPending = true
            status.text = "Cần cho phép PiperOS Tool cài ứng dụng từ nguồn này"
            try {
                unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } catch (error: Exception) {
                installerPending = false
                showError("Không mở được quyền cài ứng dụng trên ROM này. Hãy cấp quyền 'Cài ứng dụng không rõ nguồn gốc' cho PiperOS Tool trong Cài đặt: ${error.message}")
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        appendLog("Mở trình cài cho ${release?.tag ?: file.name}: ${file.name}")
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            clipData = ClipData.newRawUri("PiperOS update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(install)
            appendLog("Đã chuyển APK cho trình cài đặt hệ thống")
        } catch (error: Exception) {
            appendLog("Trình cài đặt mặc định không mở được: ${error.message}")
            try {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("PiperOS update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(view, "Chọn trình cài đặt APK"))
                appendLog("Đã mở lựa chọn trình cài đặt APK")
            } catch (fallbackError: Exception) {
                showError("Không tìm thấy trình cài APK: ${fallbackError.message}")
            }
        }
    }

    private fun setAction(label: String, click: () -> Unit) {
        actionText.text = label
        action.visibility = View.VISIBLE
        action.setOnClickListener { click() }
    }

    private fun appendLog(message: String) {
        log.append("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}  $message\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showError(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun readableNotes(markdown: String): String = markdown
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*-\\s+"), "• ")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace("**", "")
        .replace("`", "")
        .trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
