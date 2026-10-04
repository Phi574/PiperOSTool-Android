# Changelog

## 3.5.0.Beta

- Hiển thị thông báo dự án tại Trang chủ: dự kiến dừng LiquidGlass và chuyển toàn bộ về giao diện Hiện Đại ở bản tiếp theo.
- Bản này chỉ công bố kế hoạch; chưa xóa Kiểu Giao Diện, LiquidGlass hoặc thay đổi các tính năng hiện tại.
- Nâng versionCode lên 51 và versionName lên 3.5.0.Beta để hỗ trợ cập nhật trong ứng dụng.

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
