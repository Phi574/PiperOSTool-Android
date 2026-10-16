package com.piperostool

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.android.apksig.ApkVerifier
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

class AppInspectionActivity : AppCompatActivity() {
    private lateinit var packageInfo: PackageInfo
    private lateinit var appLabel: String
    private lateinit var rootView: LinearLayout
    private lateinit var rootContent: LinearLayout
    private lateinit var scanSummary: TextView
    private lateinit var scanRows: LinearLayout
    private var scan: AppPackageScanResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf(String::isNotBlank)
        if (packageName == null) {
            finishWithMessage("Không nhận được ứng dụng cần kiểm tra.")
            return
        }
        packageInfo = try {
            loadPackageInfo(packageName)
        } catch (_: Exception) {
            finishWithMessage("Ứng dụng không còn được cài đặt hoặc không thể đọc thông tin.")
            return
        }
        appLabel = packageInfo.applicationInfo?.loadLabel(packageManager)?.toString() ?: packageName

        setContentView(createLayout())
        PiperModernUi.watch(this)
        PiperAutoFont.watch(rootView)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(18), bars.top + dp(8), bars.right + dp(18), bars.bottom + dp(12))
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
        renderAppIdentity()
        loadScan()
    }

    private fun createLayout(): View {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        toolbar.addView(MaterialButton(this).apply {
            text = "Quay lại"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        toolbar.addView(TextView(this).apply {
            text = "Thông tin ứng dụng"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@AppInspectionActivity))
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        rootView.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))

        val scroll = ScrollView(this).apply { clipToPadding = false }
        rootContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(20))
        }
        scroll.addView(rootContent)
        rootView.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return rootView
    }

    private fun renderAppIdentity() {
        val app = packageInfo.applicationInfo ?: return
        val cardBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val icon = ImageView(this).apply {
            setImageDrawable(app.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        cardBody.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
        cardBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(label(appLabel, 18f, true).apply { setTextColor(PiperModernUi.textColor(this@AppInspectionActivity)) })
            addView(label(packageInfo.packageName.orEmpty(), 12f, false), topMargin(4))
            addView(label("Phiên bản ${packageInfo.versionName ?: "-"}", 12f, false), topMargin(4))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        rootContent.addView(card(cardBody), bottomMargin(10))
        rootContent.addView(label(
            "Thông tin ứng dụng và các dấu hiệu trong APK được hiển thị để tham khảo; các kết quả quét không thay đổi ứng dụng.",
            13f,
            false
        ).apply {
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })

        val scanCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        scanCard.addView(label("KẾT QUẢ KIỂM TRA", 12f, true).apply {
            setTextColor(PiperModernUi.accentColor(this@AppInspectionActivity))
        })
        scanSummary = label("Đang kiểm tra APK…", 14f, true).apply {
            setTextColor(PiperModernUi.textColor(this@AppInspectionActivity))
        }
        scanCard.addView(scanSummary, topMargin(8))
        scanRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanCard.addView(scanRows, topMargin(8))
        rootContent.addView(card(scanCard), bottomMargin(16))

        rootContent.addView(label("CHI TIẾT ỨNG DỤNG", 12f, true).apply {
            setTextColor(PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
        }, bottomMargin(8))
        addOption("Thông tin, quyền và chứng chỉ", "Xem thông tin kỹ thuật đầy đủ của ứng dụng", R.drawable.details, enabled = true) {
            showAppInfo()
        }
    }

    private fun loadScan() {
        scanSummary.text = "Đang quét dấu hiệu trong APK…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val path = packageInfo.applicationInfo?.sourceDir.orEmpty()
                if (path.isBlank()) AppPackageScanResult(false, false, false)
                else AppPackageAnalyzer.analyze(File(path))
            }
            scan = result
            scanSummary.text = if (result.hasDetectedComponents) {
                "Đã phát hiện một số thành phần trong APK. Xem từng kết quả bên dưới."
            } else {
                "Chưa phát hiện các dấu hiệu được hỗ trợ trong APK này."
            }
            scanRows.removeAllViews()
            addScanRow("Thấy Google ADS", result.googleAds)
            addScanRow("Thấy xác minh giấy phép", result.licenseVerification)
            addScanRow("Thấy có mục mua InApp", result.inAppPurchases)
            addScanRow("Bản vá tùy chỉnh có sẵn", result.customPatchAvailable)
        }
    }

    private fun addScanRow(title: String, found: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (found) Color.rgb(44, 176, 111) else PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)))
        row.addView(label(title, 13f, false).apply {
            setTextColor(PiperModernUi.textColor(this@AppInspectionActivity))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        row.addView(label(if (found) "Có" else "Không phát hiện", 12f, true).apply {
            setTextColor(if (found) Color.rgb(44, 176, 111) else PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
            gravity = Gravity.END
        })
        scanRows.addView(row)
    }

    private fun addOption(title: String, subtitle: String, iconRes: Int, enabled: Boolean, onClick: (() -> Unit)? = null) {
        val row = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(PiperModernUi.surfaceColor(this@AppInspectionActivity))
            strokeColor = PiperModernUi.borderColor(this@AppInspectionActivity)
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.46f
            if (enabled && onClick != null) setOnClickListener { onClick() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        body.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (enabled) PiperModernUi.accentColor(this@AppInspectionActivity)
                else PiperModernUi.secondaryTextColor(this@AppInspectionActivity)
            )
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(label(title, 14f, true).apply { setTextColor(PiperModernUi.textColor(this@AppInspectionActivity)) })
            addView(label(subtitle, 11f, false).apply { setTextColor(PiperModernUi.secondaryTextColor(this@AppInspectionActivity)) }, topMargin(3))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!enabled) {
            val badge = TextView(this).apply {
                text = "BETA"
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
                background = GradientDrawable().apply {
                    cornerRadius = dp(30).toFloat()
                    setColor(PiperModernUi.surfaceColor(this@AppInspectionActivity))
                    setStroke(dp(1), PiperModernUi.borderColor(this@AppInspectionActivity))
                }
                setPadding(dp(9), dp(5), dp(9), dp(5))
            }
            body.addView(badge)
        }
        row.addView(body)
        rootContent.addView(row, bottomMargin(8))
    }

    private fun showAppInfo() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedSurface()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val page = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        val pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(8))
        }
        page.addView(pageContent)
        fun setTab(selected: Int) {
            val infoSelected = selected == 0
            styleTab(tabs.getChildAt(0) as TextView, infoSelected)
            styleTab(tabs.getChildAt(1) as TextView, !infoSelected)
            pageContent.removeAllViews()
            if (infoSelected) renderAppFacts(pageContent) else renderPermissions(pageContent)
        }
        listOf("Thông tin ứng dụng", "Quyền").forEachIndexed { index, title ->
            tabs.addView(TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setOnClickListener { setTab(index) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(tabs)
        body.addView(page, LinearLayout.LayoutParams(-1, dp(390)).apply { topMargin = dp(8) })
        PiperDialog.showCustom(
            context = this,
            title = appLabel,
            content = body,
            positiveLabel = "OK",
            negativeLabel = null,
            neutralLabel = "Thông tin chứng chỉ",
            onPositive = { true },
            onNeutral = { showCertificateInfo() }
        )
        setTab(0)
        PiperAutoFont.watch(body)
    }

    private fun renderAppFacts(container: LinearLayout) {
        val app = packageInfo.applicationInfo ?: return
        val launchActivity = packageManager.getLaunchIntentForPackage(packageInfo.packageName)
            ?.component?.className ?: "Không có activity khởi chạy"
        val signingInfo = packageInfo.signingInfo
        val certificate = signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        val sha1 = certificate?.let { digest("SHA-1", it) } ?: "Không đọc được"
        val installerName = installerLabel(packageInfo.packageName)
        val facts = listOf(
            "Tên gói" to packageInfo.packageName,
            "Hoạt động chạy" to launchActivity,
            "Chữ ký SHA-1" to sha1,
            "Đường dẫn ứng dụng" to app.sourceDir,
            "Đường dẫn dữ liệu của ứng dụng" to (app.dataDir ?: "Không có"),
            "Phiên bản" to (packageInfo.versionName ?: "Không rõ"),
            "Bản dựng" to packageInfo.longVersionCode.toString(),
            "minSDK" to app.minSdkVersion.toString(),
            "TargetSDK" to app.targetSdkVersion.toString(),
            "ID người dùng" to app.uid.toString(),
            "Đã cài đặt từ" to installerName,
            "Ngày cài đặt" to dateTime(packageInfo.firstInstallTime),
            "Cỡ APK" to app.sourceDir?.let { fileSize(File(it).length()) }.orEmpty().ifBlank { "Không rõ" },
            "Kích thước Dalvik-cache" to "Android không cho ứng dụng thường đọc trực tiếp"
        )
        facts.forEach { (name, value) -> addFact(container, name, value) }
    }

    private fun renderPermissions(container: LinearLayout) {
        val permissions = packageInfo.requestedPermissions.orEmpty()
        if (permissions.isEmpty()) {
            container.addView(label("Ứng dụng không khai báo quyền nào.", 14f, false))
            return
        }
        permissions.forEach { permission ->
            val granted = packageManager.checkPermission(permission, packageInfo.packageName) == PackageManager.PERMISSION_GRANTED
            val description = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPermissionInfo(permission, 0).loadDescription(packageManager)?.toString()
            }.getOrNull()?.takeIf(String::isNotBlank) ?: "Không có mô tả từ Android."
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundedSurface()
            }
            item.addView(label(permission, 12f, true).apply {
                setTextColor(PiperModernUi.accentColor(this@AppInspectionActivity))
            })
            item.addView(label(description, 12f, false).apply {
                setTextColor(PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
            }, topMargin(6))
            item.addView(label(if (granted) "Đã cấp" else "Chưa cấp", 11f, true).apply {
                setTextColor(if (granted) Color.rgb(44, 176, 111) else PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
            }, topMargin(6))
            container.addView(item, bottomMargin(8))
        }
    }

    private fun showCertificateInfo() {
        val path = packageInfo.applicationInfo?.sourceDir.orEmpty()
        val details = runCatching {
            val verification = ApkVerifier.Builder(File(path)).build().verify()
            val cert = verification.signerCertificates.firstOrNull()
                ?: throw IllegalStateException("Không có chứng chỉ ký APK.")
            certificateDetails(verification, cert)
        }.getOrElse { "Không đọc được chứng chỉ APK: ${it.localizedMessage ?: "lỗi không xác định"}" }
        PiperDialog.showMessage(
            context = this,
            title = "Thông tin chứng chỉ",
            message = details,
            icon = R.drawable.security,
            positiveLabel = "OK"
        )
    }

    private fun certificateDetails(result: ApkVerifier.Result, cert: X509Certificate): String {
        val schemes = buildList {
            if (result.isVerifiedUsingV1Scheme) add("V1")
            if (result.isVerifiedUsingV2Scheme) add("V2")
            if (result.isVerifiedUsingV3Scheme) add("V3")
            if (result.isVerifiedUsingV31Scheme) add("V3.1")
            if (result.isVerifiedUsingV4Scheme) add("V4")
        }.ifEmpty { listOf("Không xác định") }.joinToString(", ")
        val encoded = cert.encoded
        val hashes = listOf("MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512")
            .joinToString("\n") { algorithm ->
                val value = runCatching { digest(algorithm, encoded) }.getOrElse { "Không hỗ trợ" }
                "$algorithm: $value"
            }
        val crc = CRC32().apply { update(encoded) }.value
        val base64Certificate = java.util.Base64.getEncoder().encodeToString(encoded)
        val publicKey = java.util.Base64.getEncoder().encodeToString(cert.publicKey.encoded)
        return listOf(
            "Phiên bản lược đồ: $schemes",
            "Kiểu: X.509",
            "Phiên bản chứng chỉ: ${cert.version}",
            "Số seri: ${cert.serialNumber.toString(16)}",
            "Thuật toán chữ ký: ${cert.sigAlgName} (${cert.sigAlgOID})",
            "Chủ thể: ${cert.subjectX500Principal.name}",
            "Đơn vị phát hành: ${cert.issuerX500Principal.name}",
            "Có hiệu lực từ: ${dateTime(cert.notBefore.time)}",
            "Có hiệu lực đến: ${dateTime(cert.notAfter.time)}",
            hashes,
            "CRC32: ${java.lang.String.format(Locale.ROOT, "%08x", crc)}",
            "Số byte chứng chỉ: ${encoded.size}",
            "Chứng chỉ (Base64):\n$base64Certificate",
            "Khóa công cộng (Base64):\n$publicKey"
        ).joinToString("\n\n")
    }

    private fun addFact(container: LinearLayout, title: String, value: String) {
        container.addView(label("$title:", 12f, true).apply {
            setTextColor(PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
        })
        container.addView(label(value, 13f, false).apply {
            setTextColor(PiperModernUi.textColor(this@AppInspectionActivity))
            setTextIsSelectable(true)
        }, bottomMargin(10))
    }

    private fun installerLabel(packageName: String): String {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) packageManager.getInstallSourceInfo(packageName).installingPackageName
            else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull() ?: return "Không rõ"
        return runCatching {
            packageManager.getApplicationInfo(installer, 0).loadLabel(packageManager).toString()
        }.getOrDefault(installer)
    }

    private fun loadPackageInfo(packageName: String): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(if (selected) PiperModernUi.onAccentColor(this) else PiperModernUi.secondaryTextColor(this))
        tab.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (selected) PiperModernUi.accentColor(this@AppInspectionActivity) else Color.TRANSPARENT)
        }
    }

    private fun card(content: View) = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        setCardBackgroundColor(PiperModernUi.surfaceColor(this@AppInspectionActivity))
        strokeColor = PiperModernUi.borderColor(this@AppInspectionActivity)
        addView(content)
    }

    private fun roundedSurface() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(PiperModernUi.surfaceColor(this@AppInspectionActivity))
        setStroke(dp(1), PiperModernUi.borderColor(this@AppInspectionActivity))
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(PiperModernUi.secondaryTextColor(this@AppInspectionActivity))
        setLineSpacing(dp(2).toFloat(), 1.05f)
    }

    private fun dateTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))

    private fun fileSize(value: Long): String = Formatter.formatFileSize(this, value)

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun bottomMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PACKAGE_NAME = "piperos.mod.PACKAGE_NAME"
    }
}
