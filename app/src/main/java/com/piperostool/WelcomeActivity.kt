package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.welcome)

        // Ánh xạ nút bấm
        val btnCreateAccount = findViewById<Button>(R.id.btnCreateAccount)
        val btnLogin = findViewById<Button>(R.id.btnLogin)


        btnCreateAccount.setOnClickListener {
            // Chuyển sang màn hình Đăng ký (Làm sau)
            val intent = Intent(this, SignupActivity::class.java)
            startActivity(intent)
        }

        btnLogin.setOnClickListener {
            // Chuyển sang màn hình Đăng nhập (Làm sau)
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }
}