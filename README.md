# theMythOfCrawl (Java / Montoya)

Burp Suite extension viết bằng **Java + Montoya API**, bản kế thừa của
[theMythOfCrawl](https://github.com/hau2212/theMythOfCrawl) (Jython). Repo này tách riêng để dễ phân biệt.

## Cài đặt

**Cách 1 - tải file có sẵn:** vào **Releases → latest**, tải `theMythOfCrawl.jar`
(GitHub tự build lại mỗi lần có commit mới trên `main`).

**Cách 2 - tự build** (cần JDK 21):

```bat
cd D:\ANTIGRAVITY_PROJECT\mythOfcrawl\theMythOfCrawl_java
gradlew.bat build
```

File ra ở `build\libs\theMythOfCrawl.jar`.

**Nạp vào Burp:** Extensions → Installed → **Add** → Extension type **Java** → chọn `theMythOfCrawl.jar`.
Không cần Jython.

## Workspace - dữ liệu không mất khi tắt Burp / đổi project

Mọi thứ đã bắt được (request/response gốc, nhánh, cấu hình, danh sách Excel) lưu vào thư mục
**`theMythOfCrawl_workspace`** nằm ngay trong thư mục project (cạnh file `.jar`; nếu chạy từ
`build\libs` thì vẫn là thư mục gốc của project):

```
D:\ANTIGRAVITY_PROJECT\mythOfcrawl\theMythOfCrawl_java\
└── theMythOfCrawl_workspace\
    └── <Tên project Burp>\          ← cấp 1: project (Community: "Temporary Project")
        └── <Tên workspace>\          ← cấp 2: workspace
            ├── workspace.json        nhánh, danh sách API, cấu hình, danh sách Excel
            └── messages\             request / response gốc của từng API
```

- Tự lưu liên tục (ghi kiểu nguyên tử, không hỏng file khi Burp bị tắt đột ngột).
- Mở lại Burp: extension tự mở lại workspace dùng gần nhất.
- **Burp Pro:** mỗi file project `.burp` nhớ workspace của riêng nó - mở project nào, extension mở đúng workspace đó.
- **Burp Community** (chỉ có Temporary Project): vẫn chạy bình thường nhờ workspace nằm ngoài Burp;
  mang dữ liệu từ máy Pro sang Community chỉ cần chép thư mục `theMythOfCrawl_workspace`.
- Tab **Workspace** trong extension: cây 2 cấp project → workspace, tạo mới / mở / xóa / mở thư mục,
  đổi thư mục gốc.
- Thư mục `theMythOfCrawl_workspace/` đã nằm trong `.gitignore`: dữ liệu bắt được (có thể chứa token, cookie)
  không bao giờ bị đẩy lên GitHub.

## Đã có

- Bắt API từ Proxy với bộ lọc: Target Scope, file tĩnh, Regex path, OPTIONS/HEAD, chống trùng
  (tùy chọn coi id/UUID là `{id}`, chống trùng theo nhánh). Chuột phải → lưu request bất kỳ vào workspace.
- Nhánh (vị trí) dạng `/ > contact > button "search"`.
- **Danh sách API từ Excel** (STT | PROTOCOL | PATH | POSITION): tham số `{id}` `[id]` `(id)` `<id>` `:id` `*`
  là giá trị bất kỳ; chế độ chỉ bắt API trong danh sách; tự xếp nhánh theo POSITION; tab **Độ phủ Excel**.
- **Xuất Excel (.xlsx)**: sheet Độ phủ (giữ nguyên cột của file gốc) + sheet API.
- Bộ điều phối gửi request chống lỗi 429 (lõi đã có + test; nối vào giao diện ở giai đoạn sau).

## Lộ trình

1. ✅ Khung project, CI, workspace lưu trữ, bắt API, Excel, giao diện cơ bản.
2. ⏳ Role / profile và gửi request theo từng role, giao diện so sánh kết quả, Log, đẩy Repeater.
3. ⏳ So sánh nội dung body giữa các role, các tính năng chỉ Montoya có.

## Kiểm thử

```bat
gradlew.bat test
```
