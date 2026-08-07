package com.piperostool

import android.view.View
import android.widget.TextView

object PiperUiText {
    fun apply(view: View) {
        if (PiperUiPreferences.language(view.context) != "en") return
        view.contentDescription?.toString()?.let { value ->
            translate(value).takeIf { it != value }?.let { view.contentDescription = it }
        }
        if (view is TextView) {
            val value = view.text?.toString().orEmpty()
            translate(value).takeIf { it != value }?.let { view.text = it }
            val hint = view.hint?.toString().orEmpty()
            translate(hint).takeIf { it != hint }?.let { view.hint = it }
        }
    }

    fun translate(value: String): String {
        exact[value]?.let { return it }
        dynamic.forEach { (pattern, replacement) ->
            if (pattern.containsMatchIn(value)) return pattern.replace(value, replacement)
        }
        return value
    }

    private val dynamic = listOf(
        Regex("^(\\d+) ứng dụng$") to "\$1 apps",
        Regex("^Người dùng \\((\\d+)\\)$") to "User (\$1)",
        Regex("^Hệ thống \\((\\d+)\\)$") to "System (\$1)",
        Regex("^Đã tắt \\((\\d+)\\)$") to "Disabled (\$1)",
        Regex("^(\\d+) thư mục • (\\d+) tệp$") to "\$1 folders · \$2 files",
        Regex("^ĐÃ CHỌN (\\d+)$") to "\$1 SELECTED",
        Regex("^Phiên bản (.+)$") to "Version \$1",
        Regex("^(\\d+) quyền$") to "\$1 permissions",
        Regex("^Đã giải nén (\\d+) tệp$") to "Extracted \$1 files"
    )

    private val exact = mapOf(
        "0 thư mục • 0 tệp" to "0 folders · 0 files",
        "0 ứng dụng" to "0 apps",
        "Âm thanh" to "Audio",
        "Backup APK gốc" to "Back up original APK",
        "BẢO MẬT & QUYỀN" to "SECURITY & PERMISSIONS",
        "Bảo mật, quyền truy cập và giao diện" to "Security, access and appearance",
        "BỘ NHỚ & KERNEL" to "STORAGE & KERNEL",
        "BỘ NHỚ RAM" to "MEMORY",
        "Bộ nhớ trong" to "Internal storage",
        "BỘ XỬ LÝ" to "PROCESSOR",
        "Các quyền đã được cấp:" to "Granted permissions:",
        "CÀI ĐẶT" to "SETTINGS",
        "Chi tiết" to "Details",
        "Chi Tiết Hoạt Động Ngầm" to "Background activity details",
        "Cho phép khóa và bảo vệ thiết bị" to "Allow device locking and protection",
        "Cho phép tự khởi chạy (Autostart)" to "Allow autostart",
        "CHỌN" to "SELECT",
        "Chọn loại khóa bảo mật" to "Choose security lock type",
        "Chọn một mục để kiểm tra thông tin chi tiết." to "Choose a section to inspect its details.",
        "Công cụ" to "Tools",
        "Đã cài đặt:" to "Installed:",
        "Đã cập nhật:" to "Updated:",
        "Đã tắt" to "Disabled",
        "Đang chuẩn bị workspace..." to "Preparing workspace...",
        "Đang đọc ứng dụng..." to "Reading applications...",
        "ĐIỂM QUA" to "WAYPOINT",
        "Đóng" to "Close",
        "Đóng bảng" to "Close panel",
        "Ép Khởi Chạy" to "Force launch",
        "Ghim Lối Tắt" to "Pin shortcut",
        "GIẢI NÉN" to "EXTRACT",
        "GIAO DIỆN" to "APPEARANCE",
        "Gói:" to "Package:",
        "Hệ thống" to "System",
        "Hình nền" to "Background",
        "HỦY" to "CANCEL",
        "Khác" to "Other",
        "Khóa bằng vân tay" to "Fingerprint lock",
        "Khởi chạy" to "Launch",
        "Khôi phục nền mặc định" to "Restore default background",
        "Không có tệp phù hợp" to "No matching files",
        "Làm mới" to "Refresh",
        "Làm mới ứng dụng" to "Refresh applications",
        "LƯU" to "SAVE",
        "Mã PIN 4 số" to "4-digit PIN",
        "Mã PIN 6 số" to "6-digit PIN",
        "Mặc định" to "Default",
        "Mật khẩu khóa" to "Lock password",
        "Mật khẩu tùy chỉnh (Chữ & Số)" to "Custom password (letters and numbers)",
        "MỞ APK" to "OPEN APK",
        "Mở bằng ứng dụng khác" to "Open with another app",
        "Mỗi tab có môi trường và tiến trình riêng." to "Each tab has its own environment and processes.",
        "NGÔN NGỮ" to "LANGUAGE",
        "Người dùng" to "User",
        "Nhãn" to "Label",
        "Quản lý Quyền & Tối ưu hóa" to "Permissions & optimization",
        "Quản lý thông báo" to "Manage notifications",
        "Quản trị viên thiết bị" to "Device administrator",
        "Quay lại" to "Back",
        "Quyền & thông báo" to "Permissions & notifications",
        "Quyền hệ thống và Live Update" to "System permissions and Live Update",
        "Quyền:" to "Permissions:",
        "SAO CHÉP TẤT CẢ" to "COPY ALL",
        "SAO CHÉP THÔNG TIN" to "COPY INFORMATION",
        "SẮP XẾP" to "SORT",
        "SAU" to "NEXT",
        "SDK:" to "SDK:",
        "SỬA" to "EDIT",
        "TÀI KHOẢN" to "ACCOUNT",
        "Tắt mã khóa" to "Disable lock",
        "Tắt tối ưu hóa Pin" to "Disable battery optimization",
        "Tên Activity" to "Activity name",
        "Tên app, package hoặc activity..." to "App, package or activity name...",
        "Thiết lập hoặc thay đổi mã khóa" to "Set or change lock code",
        "THÔNG TIN THIẾT BỊ" to "DEVICE INFORMATION",
        "Tìm tệp trong thư mục hiện tại..." to "Search files in this folder...",
        "Tìm trong thư mục hiện tại..." to "Search this folder...",
        "Tối ưu hóa hệ thống:" to "System optimization:",
        "Trang PDF" to "PDF page",
        "Trạng thái:" to "Status:",
        "TRƯỚC" to "PREVIOUS",
        "TUYẾN GỢI Ý" to "SUGGESTED ROUTES",
        "ỨNG DỤNG & APK" to "APPS & APK",
        "Xác thực nhanh khi mở PiperOS" to "Quick authentication when opening PiperOS",
        "XÂY DỰNG APK" to "BUILD APK",
        "Xem ảnh" to "View image",
        "Xem trước ảnh" to "Image preview",
        "XÓA ĐIỂM CUỐI" to "REMOVE LAST POINT",
        "Đang chạy" to "Running",
        "Hoạt động ngầm" to "Background activity",
        "Ngủ đông" to "Sleeping",
        "Đã dừng (Sleeping)" to "Stopped (sleeping)",
        "Ứng dụng" to "Application",
        "ĐÃ TẮT" to "DISABLED",
        "ĐANG CHẠY" to "RUNNING",
        "HỆ THỐNG" to "SYSTEM",
        "NGƯỜI DÙNG" to "USER",
        "Act Ngầm" to "Background activity",
        "Tùy chỉnh" to "Custom",
        "Thiết lập mã khóa mới" to "Set a new lock code",
        "Thay đổi / Tắt mã khóa" to "Change / disable lock code"
    )
}
