# Changelog

## 3.9.0.BETA — Bản thử nghiệm
- Chuyển các thay đổi giao diện và điều hướng từ dự án Preview sang bản chính: tiêu đề theo từng trang nằm ở Activity Home, cửa sổ Window dùng menu cấu hình, và cập nhật các trang Home, Apps, Beta, Settings, Info.
- Tích hợp đầy đủ xử lý JSON cho Welcome, đăng nhập, đăng ký, khóa bảo mật, quên mật khẩu và xác thực điện thoại; giữ các activity Kotlin và renderer cần thiết.
- Đưa `auth.json`, JSON màn hình và `window.json` vào nhánh chính; app lấy cấu hình từ repo chính thức `Phi574/PiperOSTool-Android`.
- Chuyển phần lịch sử thiết bị, đổi mật khẩu và quản lý liên kết vào menu bảo mật theo cấu hình Window.
- Nâng versionCode lên 108.

## 3.9.0.PRE — Bản xem trước
- Chuyển Welcome, đăng nhập, đăng ký, khóa bảo mật, quên mật khẩu và xác thực điện thoại sang giao diện JSON do `config/auth.json` và từng file màn hình quản lý.
- Tải cấu hình từ GitHub, lưu cache và dùng JSON đóng gói trong app khi offline hoặc GitHub không phản hồi; giữ nguyên xử lý Firebase trong Kotlin.
- Tài khoản đã đăng nhập tiếp tục dùng luồng phiên và khóa cục bộ khi offline. Khách chưa đăng nhập gặp màn hình PiperOS có dấu chấm than và thông báo kiểm tra mạng.
- Nâng versionCode lên 107.

## 3.8.9 — Bản chính thức
- Phát hành ổn định các cải tiến PiperOS Data Transfer, PiperOS Media và PiperOS 2FA đã được kiểm tra ở bản 3.8.9.beta.
- Nâng versionCode lên 106 để nâng cấp tuần tự từ 3.8.9.beta.
## 3.8.9.beta — Bản thử nghiệm (BETA)
- Làm mới PiperOS Data Transfer theo giao diện hiện đại, tách màn hình tiến trình truyền và màn hình quản lý dữ liệu đã nhận.
- Dùng Photo Picker để chọn ảnh/video từ album; giữ nguyên lựa chọn trước khi mở bộ chọn khác, đồng thời cho xem trước và bỏ từng mục trước khi gửi.
- Hiển thị icon ứng dụng cùng tên gói và dung lượng APK trong bộ chọn ứng dụng; màn dữ liệu nhận chia nhóm ứng dụng, ảnh, video và tệp khác.
- Tiến trình truyền hiển thị trạng thái, phần trăm và dung lượng theo từng mục lẫn toàn phiên; sau 10 giây không chạm chỉ còn chữ PiperOS Data Transfer trên nền đen.
- Thiết kế lại PiperOS Media theo giao diện PiperOS hiện đại, hỗ trợ màu sáng/tối đồng nhất, bố cục thẻ mới và thanh điều khiển rõ ràng hơn; màn xem ảnh/video cũng dùng thanh tiêu đề và màu hệ thống đồng bộ.
- Chuyển PiperOS 2FA sang layout XML riêng, thêm thanh tiêu đề an toàn theo vùng status bar, thẻ giới thiệu, nhóm nút nhập/xuất và nút thêm nổi.
- Làm mới thẻ mã OTP với bố cục phân cấp, hiển thị thuật toán/số chữ số/chu kỳ, mã dễ đọc và thao tác làm mới/xóa gọn hơn; giữ nguyên đồng bộ Firebase, TOTP/HOTP/Steam, quét QR, nhập/xuất QR và JSON.
- Nâng versionCode lên 105.

## 3.8.8.PRE — Bản xem trước
- Thêm PiperOS Data Transfer vào trang Beta và mục Công cụ PiperOS trong Info.
- Chuyển nhiều tệp, ảnh, video, tài liệu và APK qua cùng mạng; hỗ trợ quét thiết bị, ghép bằng QR có khóa ngẫu nhiên hoặc nhập IP rồi xác nhận cùng mã sáu số.
- Có thể chọn ứng dụng đã cài có một APK cơ sở để gửi dưới dạng APK; ứng dụng dùng split APK cần cả bộ và chưa được đóng gói trong bản này.
- Thêm tạo điểm phát Wi‑Fi cục bộ, Turbo điều chỉnh khối truyền, tiến trình làm mờ màn hình sau 10 giây và trở lại giao diện sáng khi chạm.
- Mã hóa phiên bằng ECDH P‑256, HKDF‑SHA256 và AES‑GCM; tệp nhận được giữ trong vùng chờ riêng, chỉ nhập sau khi hoàn tất và được người dùng chọn thư mục đích.
- Hỗ trợ mở trình cài Android cho APK đã nhận, cài lần lượt hoặc chọn từng APK, rồi kiểm tra phiên bản gói đã cài.
- Giới hạn Android: chưa hỗ trợ cáp USB‑C trực tiếp, dữ liệu riêng app khác, mật khẩu Wi‑Fi lưu, SMS/lịch sử gọi tự động, danh bạ/lịch tự động. Những mục này cần API/vai trò hệ thống riêng; ứng dụng không xin quyền rộng để giả lập khả năng đó.
- Nâng versionCode lên 103.

## 3.8.7.PRE — Bản xem trước
- Thiết kế lại PiperOS SetEdit thành một danh sách cuộn duy nhất; chọn System, Secure, Global Settings hoặc nhóm thuộc tính từ menu gọn.
- Tạo và quản lý nhiều mốc sao lưu Settings, xem chi tiết hoặc xóa từng mốc; khôi phục bằng cách so sánh và chỉ hoàn tác các khóa khác biệt, đồng thời tự lưu trạng thái hiện tại trước khi khôi phục.
- Kiểm tra lại các quyền còn thiếu mỗi lần mở ứng dụng; bổ sung Quản trị viên thiết bị và điều hướng từng quyền đỏ tới đúng màn hình cấp quyền của Android.
- Gỡ PiperOS Family khỏi trang Beta.
- Sửa khai báo AccessibilityService để Android bind được dịch vụ điều khiển; giữ đăng ký sự kiện trợ năng tối thiểu thay vì đặt về 0 khi chạy.
- Tính tọa độ chạm theo kích thước màn hình thật và giới hạn điểm chạm trong vùng hiển thị, giúp cử chỉ từ xa khớp trên các thiết bị và hướng màn hình.
- Nâng versionCode lên 102.

## 3.8.6.PRE — Bản xem trước
- Thêm PiperOS SetEdit để tìm kiếm, thêm, sửa, xóa và sao lưu/hoàn tác System, Secure, Global Settings; danh sách hiển thị gọn theo hai cột tên khóa và giá trị. Android Properties, Java Properties và Linux Environment được hiển thị chỉ đọc.
- Thêm trạng thái và công tắc cấp quyền `shell_commands` trong PiperOS ADB, kèm cảnh báo rủi ro cho Secure/Global Settings và xác nhận trước khi khôi phục toàn bảng.
- Thêm xác nhận lưu theo giao diện PiperOS và chức năng khôi phục các khóa SetEdit đã chỉnh về giá trị trước lần sửa đầu tiên.
- Sửa thông báo ghép đôi PiperOS ADB để dọn trạng thái xác minh/loading khi Android đã cấp quyền; bổ sung PiperOS 2FA và PiperOS SetEdit vào Công cụ PiperOS trong trang Info.
- Bật quản lý liên kết đăng nhập Firebase: xem provider đang liên kết, thêm Google cùng email tài khoản, liên kết số điện thoại và gỡ liên kết số điện thoại.
- Sửa khoảng đệm vùng an toàn trên trang Đổi mật khẩu để nội dung không chạm thanh trạng thái.
- Nâng versionCode lên 101.

## 3.8.5.PRE — Bản xem trước
- Tích hợp Firebase Cloud Messaging để nhận thông báo từ Firebase khi app chạy nền và xử lý thông báo/tải trọng dữ liệu khi app đang mở.
- Thêm Firebase In-App Messaging để hiển thị chiến dịch trong ứng dụng dựa trên Analytics.
- Bật Firebase Performance Monitoring để đo thời gian khởi động, hiển thị màn hình và yêu cầu mạng HTTP/S.
- Tự đăng ký và cập nhật Firebase Installation ID (FID) theo API FCM hiện hành; tiếp tục dùng quyền thông báo hiện có trên Android 13 trở lên.
- Nâng versionCode lên 100.

## 3.8.4 — Bản chính thức
- Cập nhật icon ứng dụng theo thiết kế mới, thu nhỏ và căn giữa hình trong nền launcher để hiển thị cân đối hơn.
- Bổ sung biểu tượng đơn sắc cho themed icons trên Android hỗ trợ.
- Nâng versionCode lên 99 để cập nhật trực tiếp từ bản 3.8.3.

## 3.8.3 — Bản chính thức
- Phát hành ổn định các cải tiến và bản vá đã có trong 3.8.3.Beta.
- Cập nhật icon ứng dụng PiperOS Tool.
- Nâng versionCode lên 98 để cập nhật từ 3.8.3.Beta.


## 3.8.3.Beta — Bản Beta
- Gồm các bản vá 3.8.3.PRE: mô tả thẻ tính năng, lịch sử thiết bị đăng nhập riêng và đổi mật khẩu Firebase.
- Cập nhật giới hạn gửi email đặt lại mật khẩu: tối đa 3 email trong 24 giờ cho mỗi tài khoản trên thiết bị.
- Nâng versionCode lên 97 để cập nhật từ 3.8.3.PRE.

## 3.8.3.PRE — Bản xem trước
- Thêm mô tả ngắn cho PiperOS Browser, PiperOS Fake Map GPS và PiperOS QR trên trang chủ.
- Tách lịch sử thiết bị thành thẻ riêng trong trang Mật khẩu bảo mật.
- Bổ sung luồng đổi mật khẩu qua Firebase Auth, xác minh mật khẩu hiện tại và kiểm tra đủ chính sách mật khẩu Firebase.
- Thêm gửi email đặt lại mật khẩu cho tài khoản đang đăng nhập, giới hạn tối đa 3 lần trong 24 giờ trên thiết bị.
- Nâng versionCode lên 96 để cập nhật từ 3.8.2.

## 3.8.0 — Bản chính thức

- Sửa trang **Có gì mới** để nhận đúng phiên bản hiện tại kể cả tiêu đề có phần mô tả; hiện đầy đủ lịch sử phiên bản theo thứ tự mới nhất trước.
- Thêm bộ lọc nhanh **Preview**, **Beta** và **Stable**; thẻ Preview có nền xanh nước nhạt, Beta xanh nhạt kèm biểu tượng và nhãn BETA, bản chính thức xanh lá nhạt.
- Thêm màn hình thiết lập quyền ở lần khởi động đầu tiên của phiên bản: liệt kê quyền còn thiếu, cho phép cấp các quyền runtime cùng lúc và mở đúng trang Cài đặt cho quyền đặc biệt. Có thể bỏ qua và bật sau.
- Nâng versionCode lên 93 để cập nhật từ 3.7.9.

## 3.7.9 — Bản chính thức

- Tổng hợp luồng cập nhật bắt buộc: thiết bị dưới 3.7.7 cài cầu nối 3.7.7 trước, chuyển chứng chỉ qua 3.7.8, rồi tiếp tục cập nhật các bản mới.
- Sửa lỗi văng ứng dụng khi tải cập nhật trên Android 15/16; giữ tiến trình tải nền và trạng thái tải/hủy, giới hạn tự thử lại tối đa 3 lần.
- Chỉ ký bằng chứng chỉ PiperOS mới; không dùng khóa ký cũ hoặc signing lineage trong APK 3.7.9.
- Nâng versionCode lên 92 để cập nhật từ 3.7.8.

## 3.7.8.PRE

- **Bản cập nhật bắt buộc:** người dùng dưới 3.7.7.PRE chỉ được cập nhật lên đúng 3.7.7.PRE trước; khóa chọn bỏ qua bản nền ký này trong luồng cập nhật PiperOS.
- Đăng ký kiểm tra GitHub định kỳ bằng WorkManager khi ứng dụng đã được mở và có mạng; thông báo phiên bản mới kể cả khi đã rời ứng dụng, mở thẳng trang cập nhật khi chạm thông báo.
- Thêm nút làm mới ở đầu trang Cập nhật để kiểm tra GitHub ngay, không cần thoát vào lại.
- Tải APK bằng tác vụ nền có thông báo tiến độ; tải tiếp tục khi rời trang, APK đã xác minh được giữ trong dữ liệu ứng dụng để mở lại và cài sau.
- Thêm nút xóa APK đã tải; không giữ bản cài trong cache dễ bị Android dọn tự động.
- Nâng versionCode lên 84 để cập nhật đè từ 3.7.7.PRE.
