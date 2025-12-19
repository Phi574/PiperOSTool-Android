package com.piperostool

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.system.exitProcess

class MaintenanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maintenance)

        val btnRetry = findViewById<Button>(R.id.btnRetry)
        val tvExit = findViewById<TextView>(R.id.tvExit)

        // Nút thử lại (Giả lập check server)
        btnRetry.setOnClickListener {
            Toast.makeText(this, "Checking server status...", Toast.LENGTH_SHORT).show()
        }


        tvExit.setOnClickListener {
            finishAffinity()
            exitProcess(0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Không làm gì cả (Vô hiệu hóa nút Back)
        Toast.makeText(this, "App is under maintenance", Toast.LENGTH_SHORT).show()
        // super.onBackPressed() // <-- Comment dòng này lại
    }
}