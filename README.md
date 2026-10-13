# ElementCheck

Android UI inspector chạy native trên điện thoại (Java thuần, không thư viện ngoài, APK ~125 KB).
Dùng **AccessibilityService** để đọc toàn bộ cây phần tử, chụp màn hình, hiện overlay nổi, và mở
**web inspector trên cổng 8765** để xem/điều khiển từ máy tính.

Hỗ trợ **Android 6.0 (API 23) → Android 17 (API 37)**.

## Tính năng

**Trên điện thoại**
- Overlay nổi kéo thả được (không cần quyền "hiển thị trên ứng dụng khác"):
  `EC` = kéo / chạm để thu gọn · `◉` = Capture · `☰` = mở app
- Capture = ảnh màn hình + toàn bộ node tại cùng thời điểm, lưu trong bộ nhớ app
- Xem lại capture ngay trong app: chạm vào phần tử để xem chi tiết (chạm lần nữa để chọn phần tử chồng bên dưới)

**Web (`http://<ip-điện-thoại>:8765`)**
- Trái: màn hình + khung trong suốt của từng phần tử (xanh lá = clickable), hover/click để chọn
- Giữa: cây node (tất cả các cửa sổ: app, bàn phím, system bar, dialog…), cuộn ảo hoá nên mượt với cây lớn
- Phải: chi tiết node — class, resource-id, text, content-desc, hint, tooltip, pane title, state description,
  bounds, bounds in parent, size, center, index, depth, drawing order, window, 24 cờ trạng thái
  (clickable, focusable, scrollable, editable, password, visible…), input type, selection, collection/range info,
  actions, extras, đường dẫn cha
- Locator tự sinh kèm kiểm tra độ duy nhất: XPath theo id/text/desc/kết hợp/indexed/absolute, UiSelector, Appium id, accessibility id. Bấm vào giá trị để copy
- Tìm theo text/id/desc/class hoặc chạy **XPath** trực tiếp (định dạng tương thích uiautomator dump)
- **Realtime**: tự cập nhật khi màn hình thay đổi
- **Control**: bấm lên ảnh = tap thật, kéo = swipe, giữ = long press; nút Back/Home/Recents/Notifications;
  thao tác trên node: click, long click, tap, focus, scroll, show on screen, set text
- Mở các capture đã lưu trên máy; tải JSON / XML (uiautomator) / ảnh

Phím tắt: `R` refresh · `L` realtime · `C` capture · `T` control · `↑↓←→` duyệt cây · `Esc` bỏ chọn

## Cài đặt & sử dụng

1. Build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (ký bằng debug key, cài được ngay)
2. Mở app → **Mở cài đặt Trợ năng** → bật **ElementCheck**.
   - Android 13+ nếu bị mờ "Restricted setting": Thông tin ứng dụng → ⋮ → *Allow restricted settings*.
   - Android 6–10: bấm **Cấp quyền chụp màn hình** (MediaProjection) — Android 11+ không cần.
3. Truy cập web:
   - Cùng Wi-Fi: `http://<ip-điện-thoại>:8765` (IP hiện trong app)
   - Qua USB: `adb forward tcp:8765 tcp:8765` rồi mở `http://localhost:8765`

## API

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/info` | Thông tin thiết bị |
| GET | `/api/version` | Bộ đếm thay đổi UI (cho realtime) |
| GET | `/api/snapshot?scale=0.5&q=70&img=1` | Chụp + dump cây (JSON) |
| GET | `/api/screen.jpg` | Ảnh của snapshot gần nhất |
| POST | `/api/capture` | Chụp đầy đủ + lưu |
| GET | `/api/captures` | Danh sách capture |
| GET | `/api/captures/<id>/tree.json` \| `screen.jpg` | Dữ liệu capture |
| DELETE | `/api/captures/<id>` | Xoá capture |
| POST | `/api/action` | `{seq, i, action, text}` — thao tác trên node |
| POST | `/api/global` | `{action: back\|home\|recents\|notifications\|quickSettings\|power\|lock}` |
| POST | `/api/gesture` | `{type: tap\|long\|swipe, x, y, x2, y2, duration}` (Android 7+) |

## Ghi chú

- Server không có xác thực: chỉ dùng trong mạng tin cậy, hoặc qua `adb forward`.
- Chụp màn hình: Android 11+ dùng `AccessibilityService.takeScreenshot` (giới hạn ~3 ảnh/giây);
  Android 6–10 dùng MediaProjection.
- Gesture (tap/swipe) cần Android 7+; các action trên node hoạt động trên mọi phiên bản.
