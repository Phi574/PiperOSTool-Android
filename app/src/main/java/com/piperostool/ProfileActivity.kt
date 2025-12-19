package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    // Khai báo Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Khai báo các view
    private lateinit var tvFullName: TextView
    private lateinit var tvUserEmail: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvDob: TextView
    private lateinit var tvUid: TextView
    private lateinit var imgAvatar: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.profile)

        // 1. Khởi tạo Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // 2. Ánh xạ View
        initViews()

        // 3. Setup sự kiện click
        setupActions()

        // 4. Load dữ liệu
        loadUserProfile()
    }

    private fun initViews() {
        tvFullName = findViewById(R.id.tvFullName)
        tvUserEmail = findViewById(R.id.tvUserEmail)
        tvPhone = findViewById(R.id.tvPhone)
        tvDob = findViewById(R.id.tvDob)
        tvUid = findViewById(R.id.tvUid)
        imgAvatar = findViewById(R.id.imgAvatar)
    }

    private fun setupActions() {
        // Nút Back
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Nút Edit Profile
        findViewById<TextView>(R.id.btnEditProfile).setOnClickListener {
            Toast.makeText(this, "Tính năng đang phát triển", Toast.LENGTH_SHORT).show()
        }

        // Nút Settings
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingGeneral::class.java))
        }

        // Nút Đăng xuất
        findViewById<TextView>(R.id.btnLogout).setOnClickListener {
            auth.signOut()
            Toast.makeText(this, "Đã đăng xuất thành công!", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, WelcomeActivity::class.java)
            // Xóa lịch sử Activity để không bấm Back quay lại được
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun loadUserProfile() {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            val uid = currentUser.uid

            // Hiển thị thông tin cơ bản từ Auth trước
            tvUserEmail.text = currentUser.email ?: "No Email"
            tvUid.text = "UID: $uid"

            loadAvatar(null)

            db.collection("users").document(uid).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {

                        val fullname = document.getString("full_name") ?: "Người dùng Piper"
                        val phone = document.getString("phone") ?: "---"
                        val dob = document.getString("dob") ?: "---"
                        val photoUrl = document.getString("photoUrl")

                        tvFullName.text = fullname
                        tvPhone.text = "Phone: $phone"
                        tvDob.text = "DoB: $dob"

                        if (!photoUrl.isNullOrEmpty()) {
                            loadAvatar(photoUrl)
                        }
                    } else {
                        tvFullName.text = "New User"
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Lỗi tải dữ liệu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "Bạn chưa đăng nhập", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadAvatar(url: String?) {
        try {
            // SỬA LỖI Ở ĐÂY: Cách gọi Glide chuẩn
            val requestManager = Glide.with(this)
            val requestBuilder = if (!url.isNullOrEmpty()) {
                requestManager.load(url) // Load ảnh từ URL Firestore
            } else {
                requestManager.load(R.drawable.home_gif) // Load ảnh mặc định
            }

            requestBuilder
                .centerCrop() // Lệnh này giờ sẽ hoạt động đúng
                .placeholder(R.drawable.ic_launcher_background)
                .error(R.drawable.ic_launcher_background)
                .into(imgAvatar)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
