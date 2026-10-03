# Changelog

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
