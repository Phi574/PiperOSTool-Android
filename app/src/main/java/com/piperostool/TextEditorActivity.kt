package com.piperostool

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

class TextEditorActivity : AppCompatActivity() {
    private lateinit var file: File
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var encodingButton: TextView
    private lateinit var wrapButton: TextView
    private lateinit var document: EditorDocument
    private var charsetName = "UTF-8"
    private var binaryMode = false
    private var dirty = false
    private var loading = false
    private var wrapLines = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_text_editor)
        applyInsets()
        file = File(intent.getStringExtra(EXTRA_FILE_PATH).orEmpty())
        if (!file.isFile) {
            Toast.makeText(this, "Tệp không còn tồn tại", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        editor = findViewById(R.id.textEditorContent)
        editor.setTag(R.id.piper_auto_font_ignore, true)
        status = findViewById(R.id.textEditorStatus)
        encodingButton = findViewById(R.id.textEditorEncoding)
        wrapButton = findViewById(R.id.textEditorWrap)
        findViewById<TextView>(R.id.textEditorTitle).text = file.name
        findViewById<TextView>(R.id.textEditorPath).text = file.absolutePath
        configureActions()
        loadFile()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.textEditorRoot)
        val toolbar = findViewById<View>(R.id.textEditorToolbar)
        val initialTop = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, initialTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            root.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun configureActions() {
        findViewById<View>(R.id.btnTextEditorBack).setOnClickListener { handleBack() }
        findViewById<View>(R.id.btnTextEditorSave).setOnClickListener { saveFile() }
        findViewById<View>(R.id.btnTextEditorMore).setOnClickListener { showTools() }
        encodingButton.setOnClickListener { showEncodingPicker() }
        wrapButton.setOnClickListener {
            wrapLines = !wrapLines
            applyWrapMode()
        }
        findViewById<View>(R.id.textEditorUndo).setOnClickListener { restoreOriginal() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loading) {
                    dirty = true
                    updateStatus()
                }
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    private fun loadFile(forceCharset: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val detected = EditorFileCodec.read(file)
                if (forceCharset == null || detected.binary) detected else detected.copy(
                    text = String(file.readBytes(), Charset.forName(forceCharset)),
                    charsetName = forceCharset
                )
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { loaded ->
                    document = loaded
                    charsetName = loaded.charsetName
                    binaryMode = loaded.binary
                    loading = true
                    editor.setText(loaded.text)
                    editor.setSelection(0)
                    loading = false
                    dirty = false
                    editor.isEnabled = !loaded.truncated
                    encodingButton.isEnabled = !loaded.binary
                    applyWrapMode()
                    updateStatus()
                    if (loaded.truncated) {
                        PiperDialog.showMessage(
                            this@TextEditorActivity,
                            "Tệp quá lớn",
                            "Đang hiển thị ${EditorFileCodec.MAX_EDIT_BYTES / 1024 / 1024} MB đầu ở chế độ chỉ đọc để tránh hết bộ nhớ."
                        )
                    }
                }.onFailure { error ->
                    Toast.makeText(this@TextEditorActivity, "Không thể đọc: ${error.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun saveFile() {
        if (document.truncated) return
        val value = editor.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                EditorHistoryStore.capture(this@TextEditorActivity, file)
                EditorFileCodec.write(file, value, charsetName, binaryMode)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    dirty = false
                    updateStatus()
                    Toast.makeText(this@TextEditorActivity, "Đã lưu ${file.name}", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@TextEditorActivity, "Không thể lưu: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restoreOriginal() {
        if (!EditorHistoryStore.hasBackup(this, file)) {
            Toast.makeText(this, "Chưa có phiên bản để khôi phục", Toast.LENGTH_SHORT).show()
            return
        }
        PiperDialog.showConfirm(
            this,
            "Khôi phục tệp gốc?",
            "Các thay đổi đã lưu trong phiên chỉnh sửa sẽ bị thay thế.",
            "Khôi phục",
            destructive = true
        ) {
            if (EditorHistoryStore.restore(this, file)) loadFile()
        }
    }

    private fun showEncodingPicker() {
        PiperActionSheet.showSingleSelect(
            context = this,
            title = "Mã hóa ký tự",
            choices = EditorFileCodec.selectableCharsets.map {
                PiperSheetChoice(it, it, it.equals(charsetName, true))
            },
            onSelect = { selected ->
                if (dirty) {
                    PiperDialog.showConfirm(
                        this,
                        "Đọc lại bằng $selected?",
                        "Nội dung chưa lưu trong trình soạn thảo sẽ bị bỏ.",
                        "Đọc lại"
                    ) { loadFile(selected) }
                } else loadFile(selected)
            },
            onRemove = {},
            onAdd = {}
        )
    }

    private fun showTools() {
        val actions = mutableListOf<PiperSheetAction>()
        if (!binaryMode) {
            actions += PiperSheetAction("Mã hóa Base64", "Chuyển nội dung hiện tại thành Base64", R.drawable.ic_file_document) {
                runCatching { EditorFileCodec.encodeBase64(editor.text.toString(), charsetName) }
                    .onSuccess { replaceEditor(it) }
                    .onFailure { showToolError(it) }
            }
            actions += PiperSheetAction("Giải mã Base64", "Giải mã Base64 theo $charsetName", R.drawable.ic_file_document) {
                runCatching { EditorFileCodec.decodeBase64(editor.text.toString(), charsetName) }
                    .onSuccess { replaceEditor(it) }
                    .onFailure { showToolError(it) }
            }
        }
        actions += PiperSheetAction("Khôi phục bản trước", "Hoàn tác các lần lưu trong phiên chỉnh sửa", R.drawable.ic_autorenew) {
            restoreOriginal()
        }
        actions += PiperSheetAction("Xóa tệp", file.absolutePath, R.drawable.ic_file_delete) {
            PiperDialog.showConfirm(this, "Xóa ${file.name}?", "Thao tác này không thể hoàn tác.", "Xóa", destructive = true) {
                if (file.delete()) finish() else Toast.makeText(this, "Không thể xóa tệp", Toast.LENGTH_SHORT).show()
            }
        }
        PiperActionSheet.show(this, "Công cụ tệp", actions)
    }

    private fun replaceEditor(value: String) {
        editor.setText(value)
        dirty = true
        updateStatus()
    }

    private fun showToolError(error: Throwable) {
        Toast.makeText(this, "Dữ liệu không hợp lệ: ${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun applyWrapMode() {
        editor.setHorizontallyScrolling(!wrapLines)
        wrapButton.text = if (wrapLines) "NGẮT DÒNG: BẬT" else "NGẮT DÒNG: TẮT"
    }

    private fun updateStatus() {
        val type = if (binaryMode) "HEX/BINARY" else syntaxName(file.extension)
        val changed = when {
            dirty -> "CHƯA LƯU"
            EditorHistoryStore.isApkModified(file) -> "ĐÃ SỬA"
            else -> "GỐC"
        }
        status.text = "$type • $changed • ${document.originalSize} byte"
        encodingButton.text = if (binaryMode) "HEX" else charsetName
    }

    private fun syntaxName(extension: String): String = when (extension.lowercase()) {
        "kt", "kts" -> "KOTLIN"
        "java" -> "JAVA"
        "xml" -> "XML"
        "json" -> "JSON"
        "js", "mjs", "ts" -> "JAVASCRIPT"
        "py" -> "PYTHON"
        "c", "h", "cpp", "hpp" -> "C/C++"
        "sh", "bash", "zsh" -> "SHELL"
        "html", "htm" -> "HTML"
        "css", "scss" -> "CSS"
        "md" -> "MARKDOWN"
        "smali" -> "SMALI"
        "gradle" -> "GRADLE"
        else -> "TEXT"
    }

    private fun handleBack() {
        if (!dirty) {
            finish()
            return
        }
        PiperDialog.showConfirm(
            this,
            "Bỏ thay đổi chưa lưu?",
            "Bạn có thể quay lại trình soạn thảo hoặc bỏ các thay đổi hiện tại.",
            "Bỏ thay đổi",
            destructive = true
        ) { finish() }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
    }
}
