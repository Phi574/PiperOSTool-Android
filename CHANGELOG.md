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

## 3.7.7.PRE

- **BẢN CẬP NHẬT QUAN TRỌNG — khuyến khích tất cả người dùng cập nhật ngay.** Bản này thiết lập chứng chỉ ký mới cùng chuỗi chuyển đổi v3 để các bản PiperOS sau có thể cập nhật đè ổn định; bỏ qua bản này có thể khiến việc nhận/cài các bản cập nhật tiếp theo gặp lỗi chữ ký.
- Bản phát hành này giữ khả năng cập nhật đè từ chứng chỉ cũ bằng lineage công khai đi kèm; hãy cài trực tiếp lên ứng dụng hiện tại, không gỡ ứng dụng trước.
- Thêm luồng cập nhật mật khẩu bắt buộc: khi Firebase báo mật khẩu cũ không còn đạt chính sách, người dùng được chuyển tới màn riêng để yêu cầu link đặt lại mật khẩu an toàn qua email.
- Khi đăng ký, kiểm tra trước chính sách mật khẩu hiện hành của PiperOS: tối thiểu 6 ký tự gồm chữ hoa, chữ thường, số và ký tự đặc biệt; Firebase vẫn là nơi xác minh cuối cùng.
- Phân loại lỗi Firebase khi đăng ký/đăng nhập và hiển thị hướng xử lý cho email sai định dạng, email đã tồn tại, sai thông tin đăng nhập, tài khoản bị vô hiệu hóa, chính sách mật khẩu, mạng, giới hạn yêu cầu và cấu hình chưa bật; mã lỗi không nhận diện được cũng được hiển thị để hỗ trợ chẩn đoán.
- Làm rõ trạng thái email chưa xác minh và lỗi gửi email xác minh; giữ luồng gửi lại thư từ màn hình đăng nhập.
- Nâng versionCode lên 83 để cập nhật đè từ 3.7.6.

## 3.7.6 — Bản chính thức

Bản ổn định tổng hợp các cải tiến đã thử nghiệm từ 3.7.0.PRE đến 3.7.5.PRE.

- **PiperOS ADB:** thêm tiến trình đặc quyền và giao tiếp Binder, quyền client theo nhóm cùng nhật ký thao tác; trang Ứng dụng và Trình quản lý tệp dùng phiên ADB đã cấp quyền.
- **Trang chủ và tiện ích:** chuyển PiperOS Media và PiperOS 2FA ra Trang chủ; 2FA đồng bộ các mã OTP của người dùng theo tài khoản PiperOS, hỗ trợ TOTP, HOTP, Steam Guard và nhập/xuất JSON hoặc QR.
- **Thông tin ứng dụng:** thêm thông tin gói, quyền, chứng chỉ và nhận diện chỉ đọc dấu hiệu quảng cáo, xác minh giấy phép, InApp; bỏ PiperOS MOD cùng các thao tác vá APK.
- **Cập nhật:** mặc định nhận bản ổn định, có tùy chọn tham gia Beta/Preview, xem các bản phát hành khác và cảnh báo khi bỏ qua bản trung gian; các cửa sổ nổi dùng giao diện PiperOS.
- **Tương thích:** sửa kết nối mạng cục bộ trên Android 17, thêm kiểm tra/xin quyền cần thiết và cải thiện ổn định khi nén tệp, khôi phục trình duyệt và dùng các tính năng khác.
- Nâng versionCode lên 82 để cập nhật đè từ 3.7.5.PRE.

## 3.7.5.PRE

- Gỡ PiperOS MOD khỏi Trang Ứng dụng và bỏ các mục vá/công cụ; chuyển kiểm tra chỉ đọc dấu hiệu Google Ads, xác minh giấy phép, InApp và bản vá tùy chỉnh vào mục **Thông tin ứng dụng** cùng thông tin gói, quyền và chứng chỉ.
- Màn Cập nhật mặc định đề xuất lần lượt phiên bản mới kế tiếp; thêm danh sách các bản mới hơn để người dùng chủ động chọn bản xa hơn kèm cảnh báo bỏ qua phiên bản trung gian.
- Thêm chương trình Beta/Preview, điều khoản tham gia, lựa chọn kênh và xác nhận khi thoát; mặc định chỉ nhận bản ổn định, Beta nhận bản ổn định/Beta, Preview nhận mọi kênh.
- Chia lịch sử **Có gì mới** thành Preview, Beta và bản ổn định; có thể xem toàn bộ các phiên bản.
- Nâng versionCode lên 81 để cập nhật đè từ 3.7.4.PRE.

## 3.7.4.PRE

- Fix kết nối PiperOS View Remote trên Android 17: khai báo và xin quyền Mạng cục bộ trước khi quét LAN, tìm mã 6 số, quét/kết nối QR hoặc chia sẻ qua Wi‑Fi.
- Giữ luồng USB ADB không yêu cầu quyền Mạng cục bộ; báo rõ khi người dùng từ chối quyền để có thể cấp lại trong Cài đặt ứng dụng.
- Nâng versionCode lên 80 để cập nhật đè từ 3.7.3.PRE.

## 3.7.3.PRE

- Chuyển PiperOS Media và PiperOS 2FA sang Trang chủ, giữ biểu tượng, mô tả ngắn và cách mở activity hiện có.
- Gỡ PiperOS Media, PiperOS 2FA và thẻ PiperOS QR đã ẩn khỏi trang Beta; trang Beta chỉ còn các tính năng thử nghiệm còn lại.
- Trong PiperOS MOD, bỏ mục Xóa ứng dụng và mở Quản lý ứng dụng trực tiếp tới trang Thông tin ứng dụng của Android cho package đang chọn.
- Nâng versionCode lên 79 để nhận cập nhật từ 3.7.2.PRE.

## 3.7.2.PRE

- Thêm PiperOS MOD vào bảng thao tác của ứng dụng đã chọn trong trang Ứng dụng.
- PiperOS MOD hiển thị icon, tên, package, phiên bản và quét chỉ đọc APK để nhận diện dấu hiệu thư viện quảng cáo, xác minh giấy phép, thanh toán trong ứng dụng; không sửa hoặc bỏ qua cơ chế bảo vệ của ứng dụng.
- Thêm Thông tin ứng dụng với thông tin gói, activity khởi chạy, SHA-1 chứng chỉ, đường dẫn, phiên bản/build, SDK, UID, nguồn cài, ngày cài, dung lượng APK và danh sách quyền kèm trạng thái cấp.
- Thêm trang Thông tin chứng chỉ hiển thị lược đồ ký APK, thông tin X.509, thời hạn, mã băm, CRC32, chứng chỉ Base64 và khóa công khai.
- Mở Menu các bản vá bằng cửa sổ PiperOS; liệt kê các lựa chọn như ảnh tham khảo bằng hàng xám không có icon, mỗi hàng có viên thuốc icon BETA và không thể bấm.
- Mở mục Công cụ bằng cửa sổ PiperOS với các mục nhân bản ứng dụng, phân loại mua hàng, gỡ mục mua đã lưu, sao lưu, Google Play và chia sẻ; tất cả đều xám, có viên thuốc icon BETA và chưa thể bấm.
- Cho phép chạy ứng dụng đã chọn; bỏ mục Xóa ứng dụng và chuyển Quản lý ứng dụng sang trang Thông tin ứng dụng của Android cho package đã chọn.
- Nâng versionCode lên 78 để cập nhật từ 3.7.1.PRE.

## 3.7.1.PRE

- Thêm mục **Có gì mới** trong bảng `window`, hiển thị ghi chú 3.7.1.PRE và cho xem lịch sử chi tiết các phiên bản trước.
- Đặt font thiết bị làm mặc định cho theme, ô nhập và tiêu đề; vẫn cho phép chọn Inter hoặc bộ font song ngữ trong Cài đặt. Font tùy chọn lỗi tải sẽ tự dùng font hệ thống.
- Sửa cửa sổ chọn loại khóa bảo mật và các action sheet dùng chung để áp dụng đúng font người dùng đã chọn.
- Cập nhật PiperOS 2FA: bỏ các bước tạo/mở kho và đồng bộ trực tiếp nhiều tài khoản theo Firebase UID đang đăng nhập.
- Thêm nút nổi để chọn thêm thủ công hoặc quét QR bằng trình quét PiperOS; cửa sổ thêm tài khoản đồng bộ theme ứng dụng và chấp nhận khóa Base32 có dấu cách.
- Cho phép chọn mã tự đổi theo chu kỳ hoặc chỉ đổi khi bấm làm mới; giới hạn quyền đọc/ghi Firestore theo UID tài khoản.
- Thêm tìm kiếm tài khoản 2FA, nhập/xuất tệp JSON PiperOS và nhập/xuất QR chuyển tài khoản tương thích Google Authenticator, gồm hỗ trợ quét nhiều phần QR.
- Bổ sung HOTP theo bộ đếm và Steam Guard 5 ký tự; hỗ trợ nhập/xuất HOTP qua QR Google Authenticator.
- Sửa phục hồi trang đang chọn của HomeActivity sau khi hệ thống tái tạo Activity.
- Trình duyệt lưu lịch sử URL theo từng tab để có thể quay lại trang trước sau khi khôi phục phiên; giữ nút Quay lại hoạt động ở trang không phải Home.
- Cải thiện an toàn và ổn định khi nén: tạo file tạm, chỉ hoàn tất sau khi nén xong, chặn tự nén file đầu ra, bỏ qua symlink và giới hạn số tệp.
- APK Editor cho phép xóa tệp/thư mục khỏi workspace và APK xuất ra; không sửa ứng dụng đã cài.
- Nâng versionCode lên 77 để cập nhật đè từ 3.7.0.PRE và các bản 3.7.1.PRE trước đó.

## 3.7.0.PRE

- PiperOS ADB khởi chạy tiến trình Java `app_process` bằng UID shell 2000 khi người dùng bật kết nối; tiến trình tồn tại xuyên suốt các thao tác và dừng khi PiperOS ADB bị tắt.
- Thêm protocol RPC có request ID, stdin/stdout/exit code, thư mục làm việc, môi trường và timeout giới hạn; truyền tệp theo từng khối thay vì giữ một lệnh `cat` riêng.
- Thêm API giao dịch Binder hệ thống qua server đặc quyền, trả dữ liệu phản hồi cùng loại/lỗi có cấu trúc; quyền này mặc định tắt và có thể thu hồi trong mục Client và quyền truy cập.
- Thêm quyền client theo nhóm: đọc tệp, sửa tệp, quản lý ứng dụng, mở Activity bị chặn, chạy shell tùy chỉnh và giao dịch Binder; chỉ UID ứng dụng PiperOS được bind tới AIDL, mọi lần cấp/thu hồi hoặc từ chối đều được ghi log.
- Trang Ứng dụng, Tùy chọn sâu ứng dụng và Trình quản lý tệp cùng dùng một tiến trình và phiên PiperOS ADB mới cho thao tác package, Activity và tệp.
- Giữ ghép đôi Wireless debugging trong ứng dụng; tiến trình shell không tự khởi chạy sau khi Android reboot nếu người dùng chưa bật lại dịch vụ.
- Nâng versionCode lên 74 để cập nhật đè từ 3.6.10.

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
