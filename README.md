# Book Scanner (Android)

Chụp liên tục/chọn nhiều ảnh vào hàng đợi, bấm *Xử lý* sau. Mỗi ảnh là một trang sách mở (2 trang liền nhau) → app tự tìm viền sách, nắn phẳng,
tìm rãnh gáy, tách thành 2 trang theo thứ tự (trái → phải), xoá bóng, chuyển đen trắng,
chỉnh nghiêng, cắt theo nội dung + lề dư 1 cm (chỉnh được), lưu JPEG vào `Pictures/BookScanner`. Có nút gộp PDF (`Download/BookScanner`).

## Build bằng GitHub Actions
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên nhánh `main`.
2. Vào tab **Actions** → workflow **Build APK** chạy tự động (hoặc bấm *Run workflow*).
3. Chạy xong, mở lần chạy đó → mục **Artifacts** → tải `BookScanner-debug-apk`, giải nén lấy file `BookScanner_Version-1.0.<số lần build>_<ngày>_<mã commit>.apk` cài lên máy (phiên bản hiển thị trong app cũng là 1.0.<số lần build>).
4. Muốn có bản Release: `git tag v1.0 && git push --tags`, APK sẽ được đính vào Releases.

Không cần Gradle wrapper: workflow dùng `gradle/actions/setup-gradle` với Gradle 8.7.
Build cục bộ: Android Studio mở thư mục này, hoặc `gradle assembleDebug`.

## Ghi chú
- minSdk 29 (Android 10+), dùng OpenCV 4.9.0 từ Maven Central.
- Sách xoay dọc trong ảnh: chọn "Luôn tách đôi" (app tự xoay 90°).
- Nền quanh sách có vật sáng dính vào sách làm sai viền: bỏ tick "Tự phát hiện viền" rồi chụp sát sách.

## Thuật toán tách trang v2 (dùng kích thước thật của sách)

Nhập **rộng × cao của sách mở (cm)**, chụp bằng camera **ngang**, sách nằm ngang (trang trái – gáy – trang phải).

1. Tìm khối chữ thân văn bản → 4 đường biên khối chữ (bao lồi).
2. Từ kích thước sách suy ra px/cm và vị trí dự đoán mép giấy (biên chữ + lề điển hình).
3. Mỗi cạnh "bắt dính" vào mép giấy thật (bước nhảy sáng/tối nhiều tỉ lệ + sắc độ giấy so với nền, RANSAC); cạnh không đủ tin cậy giữ vị trí dự đoán; mọi nét chữ luôn nằm trong khung.
4. Nắn tạm cả sách để dò gáy (bóng gáy + khoảng trống chữ, dò riêng nửa trên/nửa dưới), chiếu đường gáy về ảnh gốc, rồi tách 2 bước: **(1) cắt thô từng trang kèm nền** (tứ giác mép ngoài + đường gáy, nới 1,5 cm ra ngoài); **(2) từng trang tự dò lại 3 mép ngoài** (trên/dưới/ngoài) rồi nắn bằng **homography riêng** về chữ nhật đúng 1/2 bề rộng × chiều cao nhập vào. Sau đó mới xử lý riêng từng trang (xoá bóng, đen trắng, chỉnh nghiêng, cắt theo nội dung).
5. Ảnh JPEG ghi DPI thật; PDF có đúng kích thước cm.

Không thấy chữ (trang trống) → tự dùng thuật toán cũ làm dự phòng.

Kết quả: sai số góc trung bình ≈ 6 mm (thuật toán cũ ≈ 180 mm), 0 ca cắt vào chữ; 2/90 ca nghiêng cực đoan trên nền vải/gỗ còn lẹm nhẹ sang trang bên kia.

**Ghép lại:** tuỳ chọn "Xử lý riêng từng trang rồi ghép lại thành 1 ảnh 2 trang" – mỗi trang được xử lý độc lập (xoá bóng, đen trắng, chỉnh nghiêng, cắt nội dung) rồi ghép cạnh nhau cùng tỉ lệ px/cm.

## GitHub Actions
Mỗi lần push lên main/master, workflow `Build APK` tự build APK, rồi cập nhật Releases → **Bản mới nhất** (tải APK ở đó, không cần vào Actions). Push tag `v1.0` để tạo bản phát hành chính thức. Cũng có thể chạy tay: tab Actions → Run workflow.

## Chụp tự động định thời
Nút **Chụp tự động**: đặt *Chờ trước*, *Mỗi ảnh cách* (giây) và *Số ảnh* (0 = không giới hạn) rồi bấm. Camera trong app tự chạy và tự chụp theo giờ, không có bước OK/chụp lại. Trong lúc chụp có **Tạm dừng/Tiếp tục** và **Dừng và lưu** (hoặc bấm Back); ảnh chụp được đưa vào hàng đợi để xử lý. Màn chụp luôn nằm ngang.

## Loại giấy
Mục **Loại giấy** chọn khổ 1 trang: A4 (mặc định, 21 × 29,7 cm), A5, A3, B5, B4, Letter, Legal hoặc Tuỳ chỉnh. Ô Rộng/Cao hiển thị thông số và sửa được; sửa khác khổ chuẩn thì tự chuyển sang "Tuỳ chỉnh". App tính sách mở = 2 × rộng × cao (camera ngang).

## Loại ảnh lưu
Mục **Loại ảnh lưu**: *Nguyên gốc (ảnh màu)* – chỉ cắt, nắn phẳng và tách trang, giữ nguyên màu; hoặc *Đen trắng* – xoá bóng, nhị phân hoá, chỉnh nghiêng, cắt theo nội dung + lề. Ở chế độ đen trắng có thanh **Độ đậm** (0–30%, mặc định 5%): càng cao chữ càng đậm.

## Phiên bản 1.1 – điểm mới
- **Giao diện:** chia thành 4 thẻ (Chụp/chọn ảnh, Hàng đợi, Giấy và trang, Ảnh kết quả), ô nhập cùng kích thước, căn đều; hiển thị số phiên bản.
- **Nắn cong theo mép giấy** (`EdgeWarp.kt`): dò mép trên/dưới của từng trang thành đường cong rồi nắn phẳng về đúng khổ giấy; nếu mép không rõ thì tự quay về nắn homography. Bật/tắt bằng ô "Nắn cong theo mép giấy".
- **Xoay ảnh trước khi xử lý:** 180° (ảnh chụp ngược), 90° phải/trái.
- Giữ các tính năng 1.0.x: chụp tự động định thời có Tạm dừng, chọn khổ giấy (A4 mặc định, sửa được), ảnh nguyên gốc hoặc đen trắng với độ đậm chỉnh được.

Khi bật **Ghép 2 trang thành 1 ảnh** (đen trắng): hai trang được làm sạch, ghép lại rồi mới cắt theo nội dung và thêm lề quanh cả ảnh ghép (khe giữa hai trang giữ nguyên tự nhiên, không bị cộng đôi lề).

## Hiệu chuẩn nền / giấy / chữ (v1.1)
- **Tự động (mặc định):** ước lượng mô hình màu giấy và nền từ chính ảnh đang xử lý, dùng làm thêm một tín hiệu khi tìm mép giấy.
- **Nâng cao:** bấm "Chọn ảnh mẫu & vùng…", mở ảnh mẫu (hoặc ảnh đầu hàng đợi), khoanh vài vùng Nền / Giấy / Chữ rồi lưu. Hồ sơ được lưu trong máy và dùng cho cả loạt ảnh cùng bối cảnh. Vùng Chữ (tuỳ chọn) chỉ dùng để chỉnh ngưỡng đen trắng (chưa kiểm thử thực tế).
- Mô phỏng Python: cải thiện khiêm tốn trên nền khó (sai số trung bình 13.4 → 12.4 mm tự động, 11.8 mm nâng cao), không làm xấu ca bình thường. Mã Kotlin chưa được biên dịch.
