package com.piperostool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TrustedAppsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: TrustedAppAdapter
    private lateinit var searchEditText: EditText // Thêm biến cho ô tìm kiếm

    // Danh sách gốc chứa tất cả app
    private val fullTrustedList = mutableListOf<TrustedAppInfo>()
    // Danh sách để hiển thị (có thể đã được lọc)
    private val displayList = mutableListOf<TrustedAppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trusted_apps)

        // Ánh xạ Views
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        recyclerView = findViewById(R.id.recyclerTrustedApps)
        tvEmpty = findViewById(R.id.tvEmpty)
        searchEditText = findViewById(R.id.etSearch) // Ánh xạ EditText

        setupRecyclerView()
        setupSearch() // Gọi hàm cài đặt tìm kiếm
        loadTrustedApps()
    }

    private fun setupRecyclerView() {
        adapter = TrustedAppAdapter(displayList, // Dùng displayList thay vì trustedList
            onRemoveClick = { appInfo ->
                removeFromWhitelist(appInfo.packageName)
            },
            onUninstallClick = { appInfo ->
                uninstallApp(appInfo.packageName)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    // HÀM MỚI: Cài đặt logic tìm kiếm
    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                filterList(s.toString())
            }
        })
    }

    // HÀM MỚI: Lọc danh sách dựa trên từ khóa
    private fun filterList(query: String) {
        displayList.clear() // Xóa danh sách hiển thị hiện tại

        if (query.isEmpty()) {
            // Nếu không tìm kiếm, hiển thị lại toàn bộ danh sách
            displayList.addAll(fullTrustedList)
        } else {
            // Nếu có tìm kiếm, lặp qua danh sách gốc
            for (app in fullTrustedList) {
                // Kiểm tra xem tên app hoặc tên gói có chứa từ khóa không (không phân biệt hoa thường)
                if (app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)) {
                    displayList.add(app)
                }
            }
        }
        adapter.notifyDataSetChanged() // Cập nhật lại RecyclerView
    }

    private fun loadTrustedApps() {
        fullTrustedList.clear()
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", emptySet()) ?: emptySet()

        if (whitelist.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val pm = packageManager
            for (pkgName in whitelist) {
                try {
                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    fullTrustedList.add(TrustedAppInfo(pkgName, label, icon))
                } catch (e: Exception) {
                    removeFromWhitelist(pkgName, showToast = false)
                }
            }
            // Sắp xếp danh sách theo tên app
            fullTrustedList.sortBy { it.label }
            // Sau khi tải xong, lọc lại danh sách theo nội dung ô search hiện tại
            filterList(searchEditText.text.toString())
        }
    }

    private fun removeFromWhitelist(pkgName: String, showToast: Boolean = true) {
        val prefs = getSharedPreferences("PiperSecurityPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("whitelist", HashSet())?.toMutableSet()

        if (whitelist?.remove(pkgName) == true) {
            prefs.edit().putStringSet("whitelist", whitelist).apply()

            // Xóa mục khỏi cả hai danh sách
            val itemToRemoveFromFull = fullTrustedList.find { it.packageName == pkgName }
            val itemToRemoveFromDisplay = displayList.find { it.packageName == pkgName }

            if (itemToRemoveFromFull != null) fullTrustedList.remove(itemToRemoveFromFull)
            if (itemToRemoveFromDisplay != null) {
                val position = displayList.indexOf(itemToRemoveFromDisplay)
                displayList.removeAt(position)
                adapter.notifyItemRemoved(position)
            }

            if (showToast) Toast.makeText(this, "Đã xóa khỏi danh sách tin tưởng", Toast.LENGTH_SHORT).show()

            if (fullTrustedList.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun uninstallApp(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE)
        intent.data = Uri.parse("package:$pkgName")
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Reload lại để kiểm tra xem có app nào vừa bị gỡ không
        loadTrustedApps()
    }

    // --- Data Class & Adapter (Giữ nguyên) ---
    data class TrustedAppInfo(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable)

    class TrustedAppAdapter(
        private val list: List<TrustedAppInfo>,
        private val onRemoveClick: (TrustedAppInfo) -> Unit,
        private val onUninstallClick: (TrustedAppInfo) -> Unit
    ) : RecyclerView.Adapter<TrustedAppAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPkg: TextView = view.findViewById(R.id.tvPkgName)
            val btnRemove: Button = view.findViewById(R.id.btnRemoveTrust)
            val btnUninstall: Button = view.findViewById(R.id.btnUninstall)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trusted_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvName.text = item.label
            holder.tvPkg.text = item.packageName
            holder.imgIcon.setImageDrawable(item.icon)

            holder.btnRemove.setOnClickListener { onRemoveClick(item) }
            holder.btnUninstall.setOnClickListener { onUninstallClick(item) }
        }

        override fun getItemCount() = list.size
    }
}
