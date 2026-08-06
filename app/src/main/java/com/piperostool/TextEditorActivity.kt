package com.piperostool

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class TextEditorActivity : AppCompatActivity() {
    private lateinit var file: File
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_text_editor)
        val root = findViewById<View>(R.id.textEditorRoot)
        val toolbar = findViewById<View>(R.id.textEditorToolbar)
        val initialTop = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, initialTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            root.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        file = File(intent.getStringExtra(EXTRA_FILE_PATH).orEmpty())
        if (!file.isFile) {
            Toast.makeText(this, "Tệp không còn tồn tại", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        editor = findViewById(R.id.textEditorContent)
        editor.setTag(R.id.piper_auto_font_ignore, true)
        editor.setText(file.readText())
        findViewById<TextView>(R.id.textEditorTitle).text = file.name
        findViewById<TextView>(R.id.textEditorPath).text = file.parentFile?.name.orEmpty()
        findViewById<View>(R.id.btnTextEditorBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnTextEditorSave).setOnClickListener {
            file.writeText(editor.text.toString())
            Toast.makeText(this, "Đã lưu ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
    }
}
