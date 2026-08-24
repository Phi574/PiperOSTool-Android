package com.piperostool

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Khi người dùng VỪA CẤP QUYỀN xong
    }

    // Khi người dùng bấm nút "Hủy kích hoạt" trong Cài đặt
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // CHỈ TRẢ VỀ TEXT (Không gọi khóa màn hình ở đây để tránh lỗi mất Dialog)

        val warningMessage = """
            ⚠️ CẢNH BÁO BẢO MẬT TỪ PIPER OS TOOL ⚠️

            Hành động hủy kích hoạt quyền Quản trị viên (Device Admin) sẽ ngay lập tức dẫn đến các rủi ro nghiêm trọng:

            1. Vô hiệu hóa hệ thống bảo vệ: Các tính năng cốt lõi của Piper OS Tool sẽ ngừng hoạt động, khiến thiết bị mất đi lớp phòng thủ an toàn nhất.
            2. Nguy cơ rò rỉ dữ liệu: Hệ thống chống trộm và bảo vệ thông tin cá nhân sẽ bị tắt hoàn toàn.
            3. Cảnh báo xâm nhập: Nếu hành động này không phải do bạn thực hiện, rất có thể thiết bị đang bị kẻ gian cố gắng chiếm quyền kiểm soát.

            Để đảm bảo an toàn tuyệt đối, chúng tôi khuyến cáo KHÔNG NÊN tiếp tục.

            Bạn có chắc chắn muốn hủy quyền và tự chịu trách nhiệm cho mọi rủi ro bảo mật?
        """.trimIndent()

        return warningMessage
    }

    // Nếu người dùng VẪN BẤM OK TRONG BẢNG CẢNH BÁO Ở TRÊN (Hủy quyền thành công)
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        AccountDataScope.preferences(context, "PiperPrefs").edit().clear().apply()
        android.widget.Toast.makeText(
            context,
            "⚠️ CẢNH BÁO: Đã hủy quyền Admin! Toàn bộ dữ liệu của Piper OS tự xóa để bảo mật!",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
