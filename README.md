# theMythOfCrawl (Java / Montoya) - 2.0

Burp Suite extension viết bằng **Java 21 + Montoya API**: bản viết lại toàn bộ của
[theMythOfCrawl](https://github.com/hau2212/theMythOfCrawl) (Jython) - đủ mọi chức năng của bản Python,
nhanh hơn, có lưu dữ liệu ra đĩa (workspace) và không cần Jython.

## Cài đặt

**Cách 1 - tải file có sẵn:** vào **Releases → latest**, tải `theMythOfCrawl.jar`
(GitHub tự build lại mỗi lần có commit mới trên `main`).

**Cách 2 - tự build** (cần JDK 21):

```bat
gradlew.bat build
```

File ra ở `build\libs\theMythOfCrawl.jar`.

**Nạp vào Burp:** Extensions → Installed → **Add** → Extension type **Java** → chọn `theMythOfCrawl.jar`.

## Các tab

| Tab | Nội dung |
|---|---|
| **API** | Cây Sub-folder / bảng Nhánh, bảng API (copy STT, badge Excel `#STT · gặp n`, badge trùng `⧉n/m`), so sánh API gốc với N role, request / response sửa trực tiếp và gửi lại, đẩy Repeater gọn / đầy đủ / chỉ role lỗi |
| **Lịch sử** | Cây nhánh + bản tổng hợp audit, xuất Excel / báo cáo .md / ma trận JSON |
| **Roles** | Danh sách role (bật/tắt từng role), profile **API gốc**, sửa rule header / cookie / JWT, dán header / cookie hàng loạt, kiểm tra JWT, nhập / xuất / mẫu |
| **Cấu hình** | 7 mục có màu: bắt API, chống trùng & vị trí, gửi & chống 429, Excel, Repeater, copy STT, workspace |
| **Log** | Nhánh → API → dòng log + kết quả role (chỉ tải khi mở), nhật ký hệ thống |
| **Excel** | Đối chiếu file: dòng nào đã bắt / chưa bắt, số lần gặp, gặp ở đâu, gợi ý gần đúng, chi tiết từng lần gặp |
| **Cây** | Cây lớn, mở được cửa sổ riêng (luôn nằm trên), có request / response bên dưới |
| **Audit** | Cần xử lý (giải thích từng API) · Giữ chỗ · API trùng & ẩn · Nhật ký vị trí (hoàn tác được) |

Thanh trên cùng: Capture, CHỈ CRAWL / GỬI, hàng đợi, **➜ Đích** (request mới vào đâu), **Cần xử lý**,
tùy chọn nhanh, tách cửa sổ, workspace. Icon ⓘ cạnh mỗi nhóm giải thích cách dùng.

## Workspace - dữ liệu không mất khi tắt Burp / đổi project

Mọi thứ (request / response, kết quả từng role, cây nhánh, giữ chỗ, file Excel, lượt gặp, bộ đếm)
lưu vào thư mục **`theMythOfCrawl_workspace`** trong thư mục project chứa file `.jar`
(chạy từ `build\libs` thì là thư mục gốc của repo):

```
theMythOfCrawl_workspace\
└── <Tên project Burp>\
    └── <Tên workspace>\
        ├── workspace.json      tên, thời gian tạo
        ├── state.json          nhánh, giữ chỗ, Excel, lượt gặp, quy tắc role, bộ đếm
        └── apis\<số API>\      meta.json + request / response gốc và của từng role
```

- Tự lưu mỗi 2 giây, chỉ ghi phần thay đổi; ghi kiểu nguyên tử (tắt Burp đột ngột không hỏng file).
- Mở lại Burp: tự mở workspace dùng gần nhất của project đó. Menu **📁 Workspace ▾**: tạo / mở / xóa,
  mở thư mục, đổi thư mục gốc.
- `theMythOfCrawl_workspace/` nằm trong `.gitignore`: dữ liệu bắt được (có token, cookie) không bao giờ
  bị đẩy lên GitHub.

## Hiệu năng (đo bằng `Bench`, 5000 API × 10 role)

| Thao tác | Thời gian |
|---|---|
| Bắt 5000 API (nhân bản 10 role) | ~2.8 s (~0.56 ms / API) |
| Dựng lại cây 5000 API | ~55 ms |
| Nạp lại bảng API | ~105 ms |
| Lưu workspace | ~0.2 s |
| Mở lại workspace 5000 API | ~0.8 s |
| Xuất Excel 7 sheet | ~0.9 s |
| Bộ nhớ giữ lại | ~190 MB |

Giao diện chỉ cập nhật phần thay đổi (gom mỗi 200 ms), cây chỉ tạo nút con khi mở, tab ẩn không vẽ lại.

## Dựng request cho từng role

`src/main/java/themythofcrawl/rolereq/RoleRules.java` dựng request của mỗi role (header / cookie / JWT)
và của profile API gốc từ request đã bắt - giống bản Python. Hành vi được giữ bằng `RoleRulesTest`
(chuyển từ các test của bản Python).

## Kiểm thử

```bat
gradlew.bat test
```

- `CoreTest`, `EngineTest`, `SendDispatcherTest`, `WorkspaceTest`: lõi, bắt / gửi / 429 / giữ chỗ / trùng, lưu & mở lại.
- `RoleRulesTest`: request của từng role (thay / thêm / xóa header, cookie, JWT, profile API gốc, chạy song song).
- `UiSmokeTest`: dựng toàn bộ giao diện (không cần màn hình) và làm mới mọi tab.
- `ExtensionLoadTest`: nạp extension đúng như Burp gọi `initialize`, cho 1 request đi qua Proxy, gỡ extension
  và kiểm tra API đã lưu ra đĩa.
- `UiPreview` / `Bench` (trong `src/test`): chụp màn hình mọi tab / đo hiệu năng.
