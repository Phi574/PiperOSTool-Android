package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var tvUserId: TextView
    private lateinit var imgAvatar: ImageView

    // Menu Items
    private lateinit var menuIdentity: View
    private lateinit var menuSignature: View
    private lateinit var menuIdPaper: View
    private lateinit var menuEmail: View
    private lateinit var menuChangePass: View
    private lateinit var btnLogout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.profile)

        // 1. Khởi tạo Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // 2. Ánh xạ View
        initViews()

        // 3. Setup giao diện cho các item menu (Icon + Text)
        setupMenuUI()

        // 4. Setup sự kiện click
        setupActions()

        // 5. Load dữ liệu
        loadUserProfile()
    }

    private fun initViews() {
        tvFullName = findViewById(R.id.tvFullName)
        tvUserId = findViewById(R.id.tvUserId)
        imgAvatar = findViewById(R.id.imgAvatar)

        // Ánh xạ các dòng menu (được include)
        menuIdentity = findViewById(R.id.menuIdentity)
        menuSignature = findViewById(R.id.menuSignature)
        menuIdPaper = findViewById(R.id.menuIdPaper)
        menuEmail = findViewById(R.id.menuEmail)
        menuChangePass = findViewById(R.id.menuChangePass)

        btnLogout = findViewById(R.id.btnLogout)
    }

    private fun setupMenuUI() {
        // Hàm hỗ trợ set icon và text cho các mục include
        fun setItem(view: View, title: String, iconResId: Int) {
            val tvTitle = view.findViewById<TextView>(R.id.itemTitle)
            val imgIcon = view.findViewById<ImageView>(R.id.itemIcon)
            tvTitle.text = title
            // Bạn hãy thay icon tương ứng nếu có
            imgIcon.setImageResource(iconResId)
        }

        // Lưu ý: Thay R.drawable.xxx bằng icon bạn có
        setItem(menuIdentity, "Mức định danh", R.drawable.apps) // Giả lập icon
        setItem(menuSignature, "Chữ ký số", R.drawable.modul)   // Giả lập icon
        setItem(menuIdPaper, "Giấy tờ tùy thân", R.drawable.security) // Giả lập icon
        setItem(menuEmail, "Email", R.drawable.modul) // Giả lập icon email
        setItem(menuChangePass, "Đổi mật khẩu", R.drawable.settings) // Giả lập icon lock
    }

    private fun setupActions() {
        // Nút Back
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Nút Home (Về trang chủ)
        findViewById<ImageView>(R.id.btnHome).setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // Sự kiện click cho các menu
        menuEmail.setOnClickListener {
            Toast.makeText(this, "Chức năng cập nhật Email", Toast.LENGTH_SHORT).show()
        }

        menuChangePass.setOnClickListener {
            Toast.makeText(this, "Chức năng đổi mật khẩu", Toast.LENGTH_SHORT).show()
        }

        // Nút Đăng xuất
        btnLogout.setOnClickListener {
            auth.signOut()
            Toast.makeText(this, "Đã đăng xuất thành công!", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, WelcomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun loadUserProfile() {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            val uid = currentUser.uid

            // Mặc định hiển thị UID nếu chưa load xong
            tvUserId.text = uid.take(10) // Lấy 10 ký tự đầu cho gọn giống ID ngân hàng

            loadAvatar(null)

            db.collection("users").document(uid).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {

                        val fullname = document.getString("full_name") ?: "NGƯỜI DÙNG PIPER"
                        val phone = document.getString("phone")
                        val email = document.getString("email")
                        val photoUrl = document.getString("photoUrl")

                        tvFullName.text = fullname.uppercase() // Tên in hoa như trong ảnh

                        // Ưu tiên hiển thị Số điện thoại làm User ID, nếu không có thì dùng Email, cuối cùng là UID
                        tvUserId.text = phone ?: email ?: uid.take(10)

                        if (!photoUrl.isNullOrEmpty()) {
                            loadAvatar(photoUrl)
                        }
                    } else {
                        tvFullName.text = "NEW USER"
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
            val requestManager = Glide.with(this)
            val requestBuilder = if (!url.isNullOrEmpty()) {
                requestManager.load(url)
            } else {
                requestManager.load(R.drawable.home_gif) // Ảnh mặc định
            }

            requestBuilder
                .centerCrop()
                .placeholder(R.drawable.ic_launcher_background)
                .error(R.drawable.ic_launcher_background)
                .into(imgAvatar)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
