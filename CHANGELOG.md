# Changelog

## 3.6.10 — Bản chính thức

Bản chính thức này tổng hợp các cải tiến từ 3.6.2.PRE đến 3.6.9.PRE đã được giữ lại trong ứng dụng sau giai đoạn thử nghiệm.

- **Trang chủ và điều hướng:** tối ưu thanh điều hướng cho thiết bị dùng phím hệ thống; làm gọn trang Thông tin; chuyển PiperOS QR từ Beta sang Trang chủ; bổ sung bảng tài khoản trượt với hồ sơ, bảo mật, hỗ trợ và đăng xuất. Bảng này ẩn thanh menu dưới khi mở và chừa khoảng an toàn cho thanh trạng thái.
- **Cập nhật và khởi động:** kiểm tra Internet, GitHub, Firebase Auth và phiên bản mới khi khởi động; hỗ trợ tiếp tục Offline hoặc thử lại. Màn cập nhật hiển thị tiến độ tải và kết quả xác minh APK, đồng thời kiểm tra chữ ký, package và version trước khi cài.
- **PiperOS ADB và ứng dụng:** dùng chung phiên ADB đã ghép đôi cho các công cụ; thao tác bật/tắt ứng dụng hiển thị tiến trình và phản hồi từ Android, đồng bộ trạng thái nhanh, đồng thời giải thích khi hệ thống chặn thao tác. PiperOS Runtime xử lý lỗi cài đặt và hủy tải rõ ràng hơn.
- **Thông tin hệ thống:** trang Thông tin có kiểm tra theo yêu cầu cho Internet, GitHub API và Firebase Auth, kèm thời gian phản hồi; phép kiểm tra Firebase dùng App Check và không giả lập thử đăng nhập hay gửi SMS.
- **Media và AirPlay:** bộ lọc PiperOS Media theo màu giao diện sáng/tối; bổ sung chẩn đoán Receiver AirPlay về dịch vụ, thiết bị, luồng video và tốc độ nhận dữ liệu.
- **Tài khoản và bảo mật:** tiếp tục hỗ trợ đăng nhập email, Google, số điện thoại và Firebase App Check với Play Integrity; xử lý lại xác thực vân tay khi quay về ứng dụng sau khi màn hình tắt hoặc người dùng rời app.
- **Android và giao diện:** nhắm Android 17 (API 37), giữ vùng an toàn cho thanh trạng thái, thu gọn ảnh tiến trình cập nhật và giữ màu gốc của icon tính năng trên trang chủ.
- **Nút bảng tài khoản:** tăng nhẹ kích thước nút `window`, đặt trong nền tròn có viền và hiệu ứng chạm, giúp dễ nhận ra và dễ bấm hơn.
- Nâng versionCode lên 73 để cập nhật đè từ 3.6.9.PRE.

## 3.6.9.PRE

- Thu nhỏ ảnh tiến trình tải/xác minh APK trong màn hình Cập nhật từ 112dp xuống 48dp để gọn hơn.
- Chừa vùng an toàn theo thanh trạng thái cho trang chủ/bảng tài khoản; thu nhỏ icon bảng `window` và giữ nguyên màu gốc icon tính năng trang chủ.
- Nâng versionCode lên 72 để cập nhật đè từ 3.6.8.PRE.

## 3.6.8.PRE

- Cập nhật compileSdk/targetSdk lên Android 17 (API 37) và nâng versionCode lên 71.
- Thiết kế lại trang chủ gọn hơn, giữ trình duyệt và Fake Map GPS; chuyển PiperOS QR từ Beta sang Trang chủ.
- Thêm bảng Tài khoản trượt từ bên phải với Thông tin cá nhân, Mật khẩu bảo mật, Hỗ trợ và Đăng xuất; thanh điều hướng dưới ẩn/hiện theo bảng.
- Chuyển thông tin hồ sơ và thiết bị đăng nhập khỏi trang Thông tin; đặt thiết bị đăng nhập trong Mật khẩu bảo mật.
- Đưa Đổi mật khẩu và Quản lý liên kết vào trạng thái BETA màu xám, chưa thể mở; chuyển Đăng xuất khỏi Cài đặt.

## 3.6.7.PRE

- Cập nhật log tải APK với ảnh tiến trình theo các mốc 10%, 20%, 25%, 30%, 40%, 50%, 60%, 70%, 75%, 80%, 90% và 100%.
- Hiện ảnh xác minh thành công sau khi chữ ký/gói/phiên bản APK hợp lệ; hiện ảnh lỗi nếu tải hoặc xác minh thất bại.
- Nâng versionCode lên 70 để cập nhật đè từ 3.6.6.PRE.

## 3.6.6.PRE

- Thêm bảng chẩn đoán AirPlay có log khởi tạo, quảng bá dịch vụ, nhận kết nối, nhận dạng luồng màn hình và tốc độ nhận video; trạng thái thành công và lỗi được tô màu riêng.
- Căn giữa theo chiều dọc bảng kiểm tra khởi động và đặt nội dung trong thẻ nền bo góc.
- Tiếp tục yêu cầu vân tay sau khi màn hình tắt hoặc người dùng rời app rồi quay lại, tránh treo ở màn hình khởi động.
- Nâng versionCode lên 69 để cập nhật đè từ 3.6.5.PRE.

## 3.6.5.PRE
- Sửa PiperOS ADB bị ngắt khi rời màn hình trong trường hợp service chỉ được khởi tạo bằng binding; khi người dùng đã bật ADB, service được chuyển sang trạng thái chạy bền để giữ kết nối dùng chung qua các Activity.
- Trạng thái tắt vẫn không tự khởi động lại ADB; chỉ khôi phục kết nối khi tùy chọn bật đã được người dùng lưu.
- Làm rõ lỗi khi Android/ColorOS bảo vệ một ứng dụng hệ thống khỏi thao tác bật qua ADB, đồng thời ẩn stack trace khỏi hộp thoại.
- Nâng versionCode lên 68 để cập nhật đè từ 3.6.4.PRE.

## 3.6.4.PRE
- Bổ sung tiến trình và log có timestamp cho thao tác bật/tắt app bằng PiperOS ADB, ghi thời gian xử lý Package Manager và phản hồi thật từ Android.
- Cập nhật trạng thái app ngay sau xác nhận của Android để không phải chờ quét lại toàn bộ danh sách ứng dụng.
- Sửa công tắc **Tùy chọn sâu ứng dụng** đọc nhầm trạng thái ADB cũ giữa các process; lấy trạng thái bật và kết nối trực tiếp từ dịch vụ PiperOS ADB.
- Đổi thao tác **Tắt ứng dụng** thành vô hiệu hóa package qua PiperOS ADB; ứng dụng đã tắt có thể bật lại ngay từ cửa sổ chi tiết.
- Chỉ PiperOS ADB được phép khởi tạo/kết nối lại ADB; các trang khác chỉ dùng phiên shell đang hoạt động và dẫn người dùng về PiperOS ADB khi cần.
- Sửa nhận diện kết nối PiperOS ADB cho **Tùy chọn sâu ứng dụng**, chờ dịch vụ khởi tạo đủ lâu và đồng bộ trạng thái trước khi báo lỗi.

- Sửa trạng thái cài PiperOS Runtime bị treo ở bước chuẩn bị: lỗi khởi động foreground service được báo thành lỗi, thao tác hủy ngắt kết nối đang tải và trạng thái cài đặt luôn được giải phóng.
- PiperOS Media theo dõi theme hiện hành; nhóm lọc Tất cả/Âm thanh/Video/Danh sách riêng dùng màu bề mặt, viền và màu nhấn đúng với theme sáng/tối.
- Thêm công tắc ép mở Activity riêng tư bằng PiperOS ADB, chỉ bật được khi dịch vụ ADB thật sự kết nối.
- Cửa sổ chi tiết ứng dụng có thao tác xác nhận dừng ứng dụng và gỡ ứng dụng người dùng qua các lệnh ADB giới hạn; làm mới danh sách sau thao tác.
- Cửa sổ chọn Activity dùng hộp thoại PiperOS và giải thích khi Android chặn Activity không export.
- Nâng versionCode lên 67 để cập nhật đè từ 3.6.3.PRE.

## 3.6.3.PRE

- Thêm bước kiểm tra khởi động theo thứ tự Internet, GitHub, Firebase Auth và bản cập nhật; hiển thị trạng thái từng bước ngay trên màn hình khởi động.
- Khi mất Internet, cho phép tiếp tục Offline hoặc thử lại toàn bộ quy trình. Khi có bản mới, người dùng chọn bỏ qua hoặc mở trình cập nhật.
- Chuyển các dự án mã nguồn mở từ Cài đặt sang Thông tin; thêm PiperOS Tool PC và Module PiperOS Tool.
- Thêm thông tin liên hệ Email, ba số điện thoại và các trang Facebook; hỗ trợ mở email, trình gọi và liên kết trực tiếp.
- Nâng versionCode lên 66 để cập nhật đè từ 3.6.2.PRE.

## 3.6.2.PRE

- Tối ưu thanh menu dưới cùng theo vùng điều hướng hệ thống; tự thêm khoảng cách khi thiết bị dùng thanh điều hướng ba nút để menu không đè lên các phím hệ thống.
- Làm gọn trang Thông tin, chỉ giữ thông tin thiết bị, phiên bản ứng dụng và danh sách các công cụ PiperOS chính.
- Thêm PiperOS ADB và PiperOS QR vào danh sách tính năng trên trang Thông tin.
- Thêm kiểm tra theo yêu cầu cho kết nối Internet, GitHub API và Firebase Authentication, hiển thị chấm trạng thái cùng thời gian phản hồi; kiểm tra Firebase đính kèm App Check token từ Play Integrity.
- Không thử phương thức đăng nhập Google/email, không gửi SMS; trạng thái Firebase chỉ phản ánh phản hồi của Auth backend và App Check.
- Nâng versionCode lên 65 để cập nhật đè từ 3.6.1.

## 3.6.1 — Bản chính thức

Bản chính thức tổng hợp các tính năng đã qua thử nghiệm và đã được phát hành trong nhánh 3.4.5.PRE–3.6.1.PRE. Các mục chỉ xuất hiện dưới dạng giới thiệu/thử nghiệm nhưng không có trong ứng dụng hoàn thiện được lược bỏ.

### Tính năng và cải tiến được giữ lại

- **Cập nhật trong ứng dụng (3.4.5–3.5.2):** Kiểm tra GitHub Releases, xem mô tả phiên bản, tải APK có phần trăm và log, hủy tải, rồi kiểm tra SHA-256 nếu có, chữ ký, package và version trước khi mở trình cài. Sửa việc ROM OnePlus chuyển nhầm APK cũ bằng tên tệp và URI riêng theo release.
- **Giao diện Hiện Đại (3.5.1–3.5.5):** Thanh tab dưới cùng dùng trạng thái chọn rõ ràng; có màu Theo hệ thống, Sáng và Tối; thẻ, nút và ô nhập được bo góc. Hình nền tùy chỉnh áp dụng trên Home, Beta, Ứng dụng, Cài đặt và Thông tin. Chỉ dùng font tích hợp và hộp thoại trong app.
- **PiperOS Fake Map GPS (3.5.6):** Chuyển từ Beta sang Home; tiếp tục hỗ trợ đặt vị trí và hành trình giả lập trên bản đồ.
- **PiperOS QR (3.5.7, 3.6.1):** Tạo và quét QR theo hai tab; hỗ trợ văn bản, URL, Wi‑Fi, điện thoại, SMS, email, vCard, vị trí, sự kiện, mạng xã hội, deep link, thanh toán, sản phẩm, vé, hồ sơ nhân viên/sinh viên, tài liệu và JSON. Có thể lưu hoặc chia sẻ ảnh QR. Khi quét, xác nhận trước khi mở nội dung ngoài app bằng hộp thoại PiperOS Tool.
- **Tài khoản (3.5.8–3.5.10):** Đăng nhập email, Google và số điện thoại; xác minh email và SMS, gửi lại email xác minh, đặt lại mật khẩu và sửa điều hướng xác minh số điện thoại. Tích hợp Firebase App Check với Play Integrity trong ứng dụng.
- **PiperOS ADB và Truy cập chuyên sâu (3.6.0):** Thiết lập Wireless debugging một lần, lưu cặp ghép và dùng công tắc để tự kết nối lại. Nếu chưa ghép đôi, công tắc tự tắt và hướng dẫn thiết lập; tắt công tắc sẽ đóng phiên ADB. Trình quản lý tệp dùng lại kết nối chung và kiểm tra quyền shell trước khi thao tác chuyên sâu.
- **Hoàn thiện giao diện (3.6.1):** Đặt thanh tiêu đề PiperOS View Remote và Trình quản lý tệp dưới vùng status bar, chuẩn hóa tên hiển thị thành **PiperOS Tool**.

### Tính năng đã loại bỏ

- Gỡ LiquidGlass và lựa chọn kiểu giao diện; chỉ giữ giao diện Hiện Đại.
- Gỡ chức năng thêm font thủ công; người dùng chỉ chọn font được tích hợp.
- Gỡ Trung tâm VPN, Quản lý Tài khoản trong Browser và Xóa rác ứng dụng.

- Nâng versionCode lên 64 để cập nhật đè từ 3.6.1.PRE.

## 3.6.1.PRE

- Sửa khoảng cách thanh tiêu đề PiperOS View Remote và Trình quản lý tệp PiperOS với thanh trạng thái.
- Dùng hộp thoại xác nhận theo giao diện PiperOS Tool khi quét QR cần mở Wi-Fi, số điện thoại hoặc ứng dụng ngoài.
- Chuẩn hóa tên ứng dụng thành PiperOS Tool trên màn hình khởi động, launcher và các thông báo liên quan.
- Nâng versionCode lên 63 để cập nhật đè từ 3.6.0.PRE.

## 3.6.0.PRE

- Thêm PiperOS ADB thành tính năng riêng trong Beta với màn hình trạng thái, kết nối lại, ghép đôi Wireless debugging và hướng dẫn thiết lập.
- Thêm công tắc bật/tắt PiperOS ADB; khi bật hệ thống kết nối bằng quyền đã lưu, khi không kết nối được công tắc tự tắt và yêu cầu ghép đôi; khi tắt phiên ADB được đóng.
- Truy cập chuyên sâu dùng lại PiperOS ADB đã ghép đôi, kiểm tra lại quyền trực tiếp và dẫn sang PiperOS ADB nếu chưa kết nối.
- Hiển thị trạng thái ROOT và nhận diện ứng dụng Shizuku/SUI; không coi việc cài Shizuku là quyền đã được cấp.
- Dịch vụ ADB giữ kết nối khi công tắc bật, tái kết nối bằng khóa ghép đôi đã lưu và dừng hẳn khi tắt.
- Nâng versionCode lên 62 để cập nhật đè từ 3.5.10.PRE.

## 3.5.10.PRE

- Tích hợp Firebase App Check với Play Integrity và khởi tạo trước khi dùng Firebase Auth, Firestore hoặc Realtime Database.
- Nâng versionCode lên 61 để cập nhật đè từ 3.5.9.PRE.

## 3.5.9.PRE

- Sửa điều hướng phiên đăng nhập để màn hình xác minh số điện thoại không bị đẩy ngược về Login khi bắt đầu từ Login hoặc Đăng ký.
- Nâng versionCode lên 60 để cập nhật đè từ 3.5.8.PRE.

## 3.5.8.PRE

- Thêm đăng nhập Google qua Android Credential Manager và Firebase Authentication; email/password chỉ tiếp tục sau khi email được xác minh.
- Thêm đăng nhập và tạo tài khoản bằng số điện thoại, xác minh mã SMS và xử lý tự động khi Firebase xác thực số điện thoại.
- Gửi lại email xác minh và chuẩn hóa luồng đặt lại mật khẩu theo các action email hiện được Firebase hỗ trợ.
- Thay cấu hình Firebase Android bằng tệp mới; không đưa tệp chứa cấu hình dự án vào Git.
- Nâng versionCode lên 59 để cập nhật đè từ 3.5.7.PRE.

## 3.5.7.PRE

- Thêm Activity PiperOS QR đồng bộ giao diện và theme, gồm hai tab Tạo và Quét.
- Hỗ trợ tạo mã Văn bản, URL, Wi-Fi, Điện thoại, SMS, Email, vCard, Vị trí, Sự kiện, Mạng xã hội, Deep Link, Thanh toán, Sản phẩm, Vé, Nhân viên/Sinh viên, Tài liệu và JSON tùy chỉnh.
- Thêm menu PiperOS để chọn loại nội dung, lưu/chia sẻ ảnh QR và đọc kết quả quét; Activity quét chỉ chạy dọc, các liên kết ngoài cần người dùng xác nhận trước khi mở.
- Chừa khoảng an toàn dưới thanh trạng thái cho tiêu đề PiperOS QR và sửa nhãn nổi của ô nhập để dùng đúng font người dùng đã chọn.
- Nâng versionCode lên 58 để cập nhật đè từ 3.5.6.PRE.

## 3.5.6.PRE

- Chuyển PiperOS Fake Map GPS sang Trang chủ và gỡ thẻ khỏi Beta.
- Nâng versionCode lên 57 để cập nhật đè từ 3.5.5.PRE.

## 3.5.5.PRE

- Gỡ luồng thêm phông chữ thủ công; chỉ cho chọn phông chữ tích hợp sẵn, các lựa chọn cũ không còn hợp lệ sẽ tự về phông chữ mặc định.
- Hiển thị hình nền tùy chỉnh xuyên suốt Home, Beta, Ứng dụng, Cài đặt và Thông tin bằng các thẻ nền trong suốt nhẹ.
- Sửa cửa sổ chọn loại khóa để nội dung tương phản, đọc được và dùng giao diện hộp thoại PiperOS.
- Chuyển xác nhận khởi động lại sau khi đổi hình nền sang hộp thoại trong ứng dụng.
- Nâng versionCode lên 56 để cập nhật đè từ 3.5.4.PRE.

## 3.5.4.PRE

- Cập nhật bố cục, icon và màu giao diện theo tinh chỉnh mới.
- Nâng versionCode lên 55 để cập nhật đè từ 3.5.3.PRE.

## 3.5.3.PRE

- Gỡ module LiquidGlass, mã vẽ kính, tài nguyên và các nhánh giao diện cũ; giao diện Hiện Đại là giao diện duy nhất.
- Bỏ mục Kiểu giao diện trong Cài đặt và xóa lựa chọn cũ đã lưu khi khởi động.
- Chuyển hai thẻ Trang chủ, các nút màn Cập nhật và hộp thoại sang thẻ Hiện Đại bo góc; giữ chế độ Sáng/Tối và các tính năng cập nhật.
- Nâng versionCode lên 54 để cập nhật đè từ 3.5.2.PRE.

## 3.5.2.PRE

- Mỗi bản cập nhật dùng tên APK và FileProvider URI riêng theo tag/SHA-256 để trình cài của ROM không nhận lại đường dẫn tệp của bản trước.
- Trên màn Cập nhật, trạng thái và log tải nêu rõ phiên bản APK đã xác thực và tên tệp đưa cho trình cài đặt.
- Nâng versionCode lên 53; giữ cùng chữ ký APK để cập nhật đè từ 3.5.1.PRE.

## 3.5.1.PRE

- Thay thanh tab dưới cùng bằng giao diện Hiện Đại bo góc, không còn LiquidGlass hay chỉ báo trượt; tab được chọn hiển thị đậm, tab còn lại xám nhạt.
- Gỡ thông báo dự án 3.5.0.Beta khỏi Trang chủ.
- Đổi nền Hiện Đại: chế độ Sáng dùng trắng và xám nhẹ, chế độ Tối dùng xanh đậm và xanh nhạt; Theo hệ thống bám màu thiết bị.
- Tăng bo góc các thẻ, nút và ô nhập của giao diện Hiện Đại; nâng versionCode lên 52.

## 3.4.5.PRE

- Thêm màn Cập nhật trong Cài đặt, lấy cả bản phát hành thử nghiệm từ GitHub Releases và hiển thị thông tin phiên bản mới hoặc trạng thái đã mới nhất.
- Tải APK trong ứng dụng với tiến độ phần trăm, log mở rộng và nút hủy; hiển thị xác nhận trước khi tải.
- Xác thực SHA-256 khi GitHub cung cấp, chữ ký APK, tên gói và mã phiên bản trước khi chuyển cho trình cài đặt Android; báo lỗi rõ nếu chữ ký khác bản đang cài.
- Hỗ trợ quyền cài từ nguồn này, trình cài đặt hệ thống và lựa chọn trình cài APK khác khi cần.
- Nâng versionCode lên 50 và versionName lên 3.4.5.PRE.

## 3.4.3

- Hoàn thiện LiquidGlass cho thẻ PiperOS Browser ở Home.
- Gỡ hoàn toàn tính năng Xóa rác ứng dụng khỏi Cài đặt và mã ứng dụng.
- Dừng dịch vụ Terminal khi người dùng đóng tác vụ và các phiên chỉ đang chờ lệnh; giữ lệnh đang chạy tiếp tục ở nền.
- Giảm nhịp cập nhật của Fake Map khi cố định hoặc tạm dừng, nhả wake lock lúc tạm dừng và không khởi động lại dịch vụ sau khi đã dừng.
- Dịch vụ tải Browser tự dừng nếu không có URL hợp lệ hay tác vụ tải đang theo dõi.
- Chuyển PiperOS Browser từ Beta sang trang Home.
- Nâng versionCode lên 49 và versionName lên 3.4.3.

## 3.4.2

- Sửa crash của tiến trình PPS khi Firebase không khởi tạo trong tiến trình phụ; Start/Stop không còn chờ Binder vô hạn và kết quả khởi động cũ không ghi đè lệnh Dừng.
- Cải thiện duyệt tệp chuyên sâu, báo lỗi quyền thay vì danh sách rỗng; gọi PPS cho thao tác tạo thư mục, đổi tên và xóa vùng được bảo vệ.
- Loại bỏ VPN Center và Browser Account Manager cùng mã giao diện, tính năng lưu mật khẩu của chúng.
- Thêm Activity cấu hình User-Agent với danh mục 1.307 mục nhập từ dữ liệu người dùng, tìm kiếm, chọn hãng/dòng/thiết bị/OS/mã máy và các token trình duyệt; khởi tạo có kiểm tra và nút restart Browser không xóa dữ liệu.
- Thiết kế lại màn hình và hộp chọn User-Agent thành LiquidGlass có ô tìm kiếm, vùng cuộn hai chiều độc lập; thêm bản đồ chọn vị trí HTML5 chỉ trong Browser, không đổi IP hay vị trí hệ thống.
- Nâng versionCode lên 48 và versionName lên 3.4.2.

## 3.4.1

- Giới hạn marker Fake Map GPS ở 32dp thay cho ảnh nguồn 512px.
- Đồng nhất thanh công cụ Remote, Apple Mirroring và File Manager; sửa nền cửa sổ và chuyển cảnh để tránh chớp trắng khi mở trang.
- Giảm công việc tô lại giao diện trên mỗi lần layout và áp dụng kiểu giao diện trước khung hình đầu.
- Browser luôn hiển thị trong Beta khi offline; bỏ thông báo bị ẩn.
- Splash dùng kiểm tra phiên/mật khẩu có thời hạn và đường vào ngoại tuyến, giữ màn khóa cục bộ hoặc vân tay nếu đã bật.
- Xóa activity khóa tài khoản, giao diện kháng nghị và các tuyến điều hướng liên quan; gỡ quyền ghi trạng thái khóa/kháng nghị khỏi rule Realtime Database, duy trì kiểm tra hết hạn phiên và thu hồi thiết bị.
- Nâng versionCode lên 47 và versionName lên 3.4.1.

## 3.4.0.beta

- Mở rộng LiquidGlass native cho các thanh công cụ, bảng điều khiển và danh sách của Media, Terminal, Fake Map GPS, File Manager, View Remote và Apple Mirroring.
- Đồng bộ LiquidGlass cho dialog và menu nổi trong giao diện Classic; giữ giao diện Modern khi được chọn. Sửa theme của dialog tùy chỉnh để mở được từ màn hình AppCompat.
- Thu gọn icon, ô tìm kiếm, thanh chọn tệp và các bảng quá lớn; Media và bàn phím Terminal có kích thước riêng khi xoay ngang.
- Giữ nguyên vùng phát media, màn hình map, terminal và hình ảnh remote cùng thao tác của các tính năng.
- Nâng versionCode lên 46. Build debug, 23 unit test và lint hoàn tất không có lỗi.


## 3.3.6.Beta

- Raised versionCode to 45 and Android baseline to API 29.
- Integrated the LiquidGlass module, bitmap icons and third-party notices.
- Updated Home, Beta, Apps, Settings, Info and authentication surfaces, including tab transitions and collapsible Info sections.
- Fixed the global session guard redirecting Signup and Forgot Password to Login.
- Added LiquidGlass account profile and device session screens with bounded back icons.
- Added removal of ended sessions from history using a transactional revocation tombstone and owner-scoped Firestore rules.
- Added native glass dialogs for device revocation, password changes and session history removal.
- Included the accumulated browser developer-tools and Android 10+ compatibility improvements.

## 3.2.3.beta

- Added PiperOS View Remote for Android screen sharing and remote control on a
  local network, with nearby discovery, QR pairing and six-digit pairing code.
- Added sharing consent, selectable resolution/FPS, orientation-aware viewing,
  full-screen playback and a clean disconnect flow.
- Added Apple Screen Mirroring receiver for iPhone, iPad and macOS through
  AirPlay/RAOP discovery. The receiver advertises through mDNS as
  `PiperOS View Remote` and runs as an Android foreground service.
- Added View Remote information to the Info section.

## 2.5.8.beta

- Added PiperOS Fake Map GPS to the Beta page.
- Added fixed mock locations and simulated routes with walking, motorbike,
  car and plane presets.
- Added adjustable speed, natural stops, looping, pause/resume and a
  foreground notification.
- Added OpenStreetMap rendering and OSRM road geometry with a direct-route
  fallback when routing is unavailable.
- Added Developer Options guidance and mock-location provider validation.

## 2.5.1.beta

- Added a visible Android Shell/Linux Runtime status panel to PiperOS Terminal.
- Added automatic runtime detection for `$PREFIX/bin/bash` and `$PREFIX/bin/sh`.
- Fixed terminal tab numbering after closing tabs or closing the final tab.
- Added the active terminal mode and app version to the foreground notification.

## 2.5.0.beta

- Added PiperOS Browser with persistent tabs, incognito tabs, downloads,
  history, User-Agent profiles and extension import.
- Added PiperOS Media with audio/video scanning, folder filters, search,
  sorting, playback queue, background playback and Picture-in-Picture.
- Added the first PiperOS Terminal activity with local shell sessions and a
  foreground session service.
- Updated authentication, Settings, Device Info and offline behavior.
- Added Android 16 progress notification support with a standard notification
  fallback for older Android versions.
- Moved the full Linux runtime build to
  [Piperos_termux](https://github.com/Phi574/Piperos_termux).
