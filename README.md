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

## Cắt trang
Mặc định giữ nguyên khổ trang đã nắn phẳng (chỉ xoá vệt tối chạm mép như bóng gáy), nên trang ít chữ vẫn đúng kích thước và vị trí. Bật "Cắt theo khối nội dung" nếu muốn cắt sát chữ kèm lề dư (cm).

## Tab "Một trang" (v1.1.41)
Giao diện có hai tab: **Hai trang** (như trước) và **Một trang** (mỗi ảnh chụp đúng một trang, chụp thẳng góc, trang chiếm gần đầy khung). Hai tab dùng chung hàng đợi, khổ giấy (1 trang), loại ảnh lưu và PDF; chỉ khác phần xử lý (`SinglePageScanner.kt`):
1. Dò khung theo khổ **một** trang (lề trái = lề phải), kết hợp khung theo chữ và khung theo giấy/nền như tab Hai trang.
2. **Kiểm tra khổ giấy:** suy tỉ lệ rộng/cao từ 4 góc (tiêu cự giả định 0,6 × đường chéo ảnh); lệch > 10% thì báo trong dòng trạng thái. Không phân biệt A4 với A5 và không chứng minh khung đúng.
3. Mục **Vị trí gáy** (trái / phải / không có): cạnh gáy coi là cạnh trong, không bắt dính; 3 hoặc 4 cạnh còn lại được dò lại rồi nắn cong theo mép giấy như cũ.
4. **Nắn theo hàng chữ** (`RowStraightener`): mặt nạ mực → góc nghiêng chung theo độ sắc profile chiếu → đỉnh = hàng chữ → đo độ lệch từng hàng theo cửa sổ cột 1,5 cm so với vùng xa gáy → hồi quy d = α(x)·(y − ȳ) + β(x), làm trơn bậc 3 → remap. Tự bỏ qua khi < 8 hàng chữ, góc nghiêng chạm ±3°, phủ dưới nửa bề rộng hoặc độ lệch quá nhỏ. Bật/tắt bằng ô "Nắn nghiêng và xòe theo hàng chữ".

Chưa làm: hiệu chỉnh nén ngang gần gáy (mới thử trong mô phỏng), kéo tay 4 góc. Thuật toán dò hàng và nắn dọc mới thử bằng prototype Python trên ảnh hai trang; mã Kotlin của tab này **chưa được biên dịch hay chạy thử**.

## Cập nhật 1.1.42
- **Dò khung theo biên giấy:** mặc định lấy khung theo biên giấy/nền (`EdgeDetector.paperFirst = true`); chỉ khi không dò được biên giấy mới dùng khung theo khối chữ. Đặt `paperFirst = false` để về cách cũ (kết hợp chữ + giấy). Lưu ý: trong mô phỏng, khung chỉ theo biên giấy yếu hơn trên nền lộn xộn/ít tương phản; chưa đo lại bằng mã Kotlin.
- **Lề ảnh ghép:** ô "Lề quanh ảnh ghép (cm)" (hiện khi bật Ghép 2 trang). Dương = thêm lề trắng, âm = cắt lẹm vào; giới hạn ±5 cm và không cắt quá 40% mỗi chiều.
- **Khoảng trắng phía gáy:** ô "Khoảng trắng phía gáy mỗi trang (cm)". Dương = thêm dải trắng ở cạnh gáy, âm = cắt bớt; áp dụng cho từng trang khi tách (cả khi ghép, nên chỉnh được khe giữa hai trang) và cho tab Một trang khi đã chọn vị trí gáy.

## Cập nhật 1.1.43 – kéo giãn chữ bị nén ngang gần gáy (tab Một trang)
`HorizontalFlattener.kt`: sách dày mở chụp chính diện làm chữ gần gáy hẹp lại (≈ cos của độ dốc mặt giấy). Ước lượng hệ số nén c(x) bằng bề rộng "từ" (trung bình theo ô 1,5 cm so với vùng phẳng, đa thức bậc 4) và bằng mô hình cos(θ·exp(−(d/σ)²)) khớp bình phương tối thiểu, lấy trung bình hai cách; dãn cột theo 1/c rồi chuẩn hoá giữ bề rộng trang. Có cổng: chỉ nắn khi c trung bình trong 3 cm sát gáy < 0,92 và có ≥ 60 từ; cần chọn vị trí gáy (trái/phải). Ô bật/tắt "Kéo giãn chữ bị nén ngang gần gáy".

Mô phỏng Python (sai số tổng, cm; V0 = chỉ homography → nắn dọc → thêm nắn ngang): sách dày 0,669 → 0,613 → 0,303; rất dày 1,153 → 1,085 → 0,585; vừa 0,277 → 0,240 → 0,219 (cổng chỉ mở 1/8 ca); phẳng và nhẹ không đổi (cổng đóng). Trên ảnh thật hai trang chưa áp dụng được (không thấy nén rõ, quá ít từ). Mã Kotlin chưa biên dịch/chạy thử.

## 1.1.44 – Bám biên chính xác từng trang (gáy giữ nguyên)
- Thêm `EdgeTracker`: sau khi dò khung từng trang, bám lại 3 mép ngoài (hoặc 4 mép nếu tờ rời) bằng 48 lát cắt + Viterbi, rồi kết hợp với cạnh cũ (trọng số cũ 0,3). Cạnh gáy và hai đầu gáy KHÔNG đổi; mép ngoài để tự do (không ép phối cảnh).
- Áp dụng cho cả tab "Hai trang" (từng trang sau khi tách theo gáy) và tab "Một trang". Tuỳ chọn "Bám biên chính xác từng trang" (mặc định bật).
- Mô phỏng Python (giấy phẳng, khung hạt giống lệch 6 mm/góc): sai số góc ngoài 7,2 → 4,1 mm, gáy không dịch. Ảnh thật chưa có chuẩn nên chỉ thấy lệch tỉ lệ khổ giảm 0,041 → 0,027.
- Bám không đủ tin cậy ở cạnh nào thì giữ cạnh cũ. Chưa biên dịch/chạy trên Android.

## 1.1.45 – Giữ trạng thái khi quay lại app + ghi lỗi crash
- Khi màn hình bị Android tạo lại (ví dụ thoát ra xem ảnh rồi quay về), app khôi phục hàng đợi, danh sách trang đã lưu, bộ đếm, và dựng lại ảnh nhỏ từ các file đã lưu trong Pictures/BookScanner. Nút/trạng thái bận không còn kẹt.
- Ảnh đang xử lý dở khi màn hình bị hủy vẫn được xử lý và lưu xong (không bị cắt ngang).
- Nếu app bị crash, nội dung lỗi được ghi vào `crash.txt` (thư mục dữ liệu ngoài của app) và hiện ở dòng trạng thái lần mở sau; file đổi tên thành `crash_last.txt`.
- Chưa biên dịch/chạy trên Android.

## 1.1.47 – Sửa crash khi mở app (phải xóa dữ liệu mới chạy)
- Nguyên nhân: khoá cài đặt `"cc"` bị dùng cho hai kiểu khác nhau (ô "cắt theo nội dung" lưu boolean, ô số lần chụp lưu chuỗi). Sau khi dùng cả hai chức năng, đọc sai kiểu gây ClassCastException ngay trong `onCreate` → app tự thoát mỗi lần mở, chỉ hết khi xóa dữ liệu.
- Sửa: số lần chụp dùng khoá riêng `"cn"`; đọc `"cc"` bằng cách kiểm tra kiểu nên dữ liệu cũ không còn làm crash. Chưa biên dịch/chạy trên Android.

## 1.1.48 – Làm mềm biên chữ, nối nét đứt (xuất đen trắng)
- Bước đen trắng đổi từ MỘT ngưỡng chung sang HAI ngưỡng (hysteresis): điểm tối hơn ngưỡng (Otsu + độ đậm) là chữ chắc chắn; điểm xám hơn tới +6% chỉ được giữ nếu nối liền với chữ chắc chắn → nét mảnh bị đứt liền lại, nền xám rời rạc không bị đen thêm.
- Sau đó làm mờ nhẹ (σ 0,7 px) và kéo tương phản đường cong S → biên mượt, ít răng cưa. Kết quả là ảnh xám nhiều mức ở biên (không còn 1 bit thuần).
- Ô mới "Làm mềm biên chữ, nối nét đứt" (mặc định bật, cả hai tab); tắt thì về cách cũ (một ngưỡng).
- Mô phỏng Python (chữ mảnh, mực nhạt từng đoạn, độ đậm 11%): tỉ lệ chữ khôi phục 0,974 → 0,996, số mảnh rời giảm 297 → 235 (extra 6%). Cũng làm các chữ sát nhau dễ dính hơn; chưa thử trên ảnh thật, chưa biên dịch trên Android.

## 1.1.49 – Quản lý ảnh đã xử lý: chọn, xóa, xử lý lại thay thế
- Mỗi ảnh kết quả có ô tích ở góc trái trên; nút "Chọn tất cả / Bỏ chọn".
- **Xóa đã chọn**: xóa file ảnh khỏi Pictures/BookScanner và khỏi danh sách (có hỏi xác nhận).
- **Xử lý lại đã chọn, thay thế**: xử lý lại ảnh gốc bằng tùy chọn hiện tại; nếu thành công thì xóa MỌI trang kết quả cũ của ảnh gốc đó và thay bằng trang mới; nếu lỗi thì giữ bản cũ. Cần ảnh gốc còn truy cập được (trong phiên làm việc hiện tại; thông tin ảnh gốc được giữ khi màn hình bị tạo lại nhưng mất khi mở lại app từ đầu).
- **Xóa toàn bộ ảnh trong thư mục đã xử lý**: xóa mọi ảnh trong Pictures/BookScanner mà app có quyền xóa (có hỏi xác nhận). Ảnh do ứng dụng khác tạo (hoặc do bản app ký khác) không xóa được và được báo số lượng. File PDF trong Download/BookScanner không bị xóa.
- Chưa biên dịch/chạy trên Android.

## 1.1.50 – Camera tự động theo tab: một trang chụp dọc, hai trang chụp ngang
- Tab "Một trang": màn hình chụp tự động ở chế độ DỌC (trang khổ dọc chiếm đầy khung, nhiều điểm ảnh hơn, không cần xoay ảnh). Tab "Hai trang": vẫn NGANG như trước.
- Hướng ảnh vẫn do EXIF xử lý (CameraX ghi theo hướng màn hình lúc chụp).
- Chưa biên dịch/chạy trên Android.
