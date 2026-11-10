# Book Scanner (Android)

Chụp liên tục/chọn nhiều ảnh vào hàng đợi, bấm *Xử lý* sau. Mỗi ảnh là một trang sách mở (2 trang liền nhau) → app tự tìm viền sách, nắn phẳng,
tìm rãnh gáy, tách thành 2 trang theo thứ tự (trái → phải), xoá bóng, chuyển đen trắng,
chỉnh nghiêng, cắt theo nội dung + lề dư 1 cm (chỉnh được), lưu JPEG vào `Pictures/BookScanner`. Có nút gộp PDF (`Download/BookScanner`).

## Build bằng GitHub Actions
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên nhánh `main`.
2. Vào tab **Actions** → workflow **Build APK** chạy tự động (hoặc bấm *Run workflow*).
3. Chạy xong, mở lần chạy đó → mục **Artifacts** → tải `BookScanner-debug-apk`, giải nén lấy `app-debug.apk` cài lên máy.
4. Muốn có bản Release: `git tag v1.0 && git push --tags`, APK sẽ được đính vào Releases.

Không cần Gradle wrapper: workflow dùng `gradle/actions/setup-gradle` với Gradle 8.7.
Build cục bộ: Android Studio mở thư mục này, hoặc `gradle assembleDebug`.

## Ghi chú
- minSdk 29 (Android 10+), dùng OpenCV 4.9.0 từ Maven Central.
- Sách xoay dọc trong ảnh: chọn "Luôn tách đôi" (app tự xoay 90°).
- Nền quanh sách có vật sáng dính vào sách làm sai viền: bỏ tick "Tự phát hiện viền" rồi chụp sát sách.
