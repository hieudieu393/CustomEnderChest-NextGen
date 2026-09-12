# Nghiệm thu và kế hoạch phục hồi không tắt plugin

Ngày: 2026-09-12. Repository: `hieudieu393/CustomEnderChest-NextGen`.
Bản được rà soát: PR #1, commit `499efb6851b59f3828a70d4f89fcb3e7717f9efb`.

## 1. Kết luận nghiệm thu

**CHƯA ĐẠT — chưa merge hoặc dùng trên server chính.**

Bản vá đã chặn một số đường nguy hiểm: không tự thay file H2 khi mở lỗi,
không ghi ngược dữ liệu trong `loadEnderChest`, không đổi lỗi Base64 của rương
chính thành rương trống, bảo vệ trường hợp mất quyền mở rương. Đây mới là
kết quả đọc mã, không phải xác nhận đã hết mất đồ trên server.

Yêu cầu mới thay thế cách xử lý khởi động của bản vá cũ:
**lỗi dữ liệu không được làm tắt toàn bộ plugin; cô lập phần lỗi và giữ chức năng
quản trị/phục hồi hoạt động.** Không đồng nghĩa với việc vẫn cho chuyển đồ khi
chưa thể lưu an toàn.

Chưa có database, log hoặc cấu hình thực tế của server, nên chưa xác định được
nguyên nhân của sự cố đã xảy ra. H2 có cơ chế thu gọn và tái sử dụng vùng trống;
file nhỏ đi không tự chứng minh bản ghi đã bị xóa. Phải đối chiếu UUID, dữ liệu
rương/đồ dư, thời điểm và bản sao lưu. [Tài liệu H2](https://h2database.com/html/features.html#compacting).

### Phát hiện còn chặn nghiệm thu

| Mã | Mức | Bằng chứng trong mã | Hậu quả / yêu cầu |
| --- | --- | --- | --- |
| R01 | P0 | `StorageManager` ném lỗi khi H2 mở thất bại; `EnderChest.onEnable` chưa đăng ký listener/command trước bước này | Không đạt yêu cầu duy trì plugin; phải có chế độ bảo vệ khởi động |
| R02 | P0 | `H2Storage.loadOverflowItems` bắt lỗi giải mã và trả mảng rỗng; MySQL có đường tương tự | Dữ liệu hỏng bị hiểu thành hết đồ; cần kết quả lỗi riêng và chặn ghi theo UUID |
| R03 | P0 | `checkForUpgradeAndRestore` theo dõi `overallFuture` trong cùng `pendingSaves` mà `enqueueSave` dùng | Khôi phục chờ lưu con; lưu con lại chờ khôi phục: có thể tự chờ vô hạn |
| R04 | P0 | Lưu rương chính và lưu/xóa overflow là các lệnh riêng trong join/resize/restore/claim | Crash hoặc lỗi giữa hai lần ghi có thể mất/nhân đôi đồ; cần một giao dịch có revision |
| R05 | P0 | Quit xóa cache trước khi save thành công; `shutdownSave` trả ngay khi cache rỗng, không đợi mọi pending save | Lưu lỗi không có bản bền vững để thử lại; shutdown có thể bỏ sót người vừa thoát |
| R06 | P0 | `cleanInventoryForSave` giữ tham chiếu ItemStack; autosave nhánh không Folia còn đọc inventory trong tác vụ async | Bản chụp có thể thay đổi trước lúc worker lưu; cần clone sâu trên đúng thread |
| R07 | P0 | `clearOverflowItems`/`deleteEnderChest` nuốt lỗi SQL | Future báo thành công dù thao tác thất bại, gây sai bước xử lý tiếp theo |
| R08 | P1 | Chưa có session token, revision hoặc chủ sở hữu khóa; admin có nhiều bản nhìn/sửa song song | Callback cũ hay admin view cũ có thể ghi đè dữ liệu mới |
| R09 | P1 | Writer chưa giới hạn byte từng item như reader (1.000.000); chưa giới hạn tổng payload | Có thể ghi dữ liệu chính mình không đọc được; nguy cơ cấp phát quá lớn |
| R10 | P1 | H2 chỉ thêm `IFEXISTS` nếu file hiện tồn tại; chưa có dấu nhận diện database đã dùng | File cũ biến mất/đường dẫn đổi vẫn có thể tạo database mới trống |
| R11 | P1 | Backup dùng tên temp cố định; chưa kiểm tra restore trước xoay vòng bản cũ | Hai backup đụng nhau hoặc bản lỗi được coi là hợp lệ |
| R12 | P1 | `OverflowManager` có chức năng tự xóa đồ hết hạn; mặc định tắt, chưa biết cấu hình thực tế | Cần kiểm tra config/log; đóng băng xóa hết hạn trong sự cố, không đoán là H2 tự xóa |

P0: chặn phát hành. P1: phải xử lý và kiểm chứng trước nghiệm thu đầy đủ.

Luồng tự chờ R03 được nhận diện trực tiếp:
`restore tổng → chờ save con → enqueueSave chờ pending tổng → restore tổng`.
Không sửa bằng cách chỉ thêm timeout: SQL có thể vẫn chạy dù future đã timeout.

## 2. Môi trường test khởi tạo trong đợt này

Đây là thiết lập kiểm thử và cập nhật kế hoạch; **chưa triển khai kiến trúc phục hồi
ở mục 3–4**. Chỉ sửa thêm metadata đóng gói để `plugin.yml` lấy đúng version từ Gradle
(trước đó tên JAR 2.1.3 nhưng plugin khai báo 2.1.2).

- Workflow: `.github/workflows/gradle-build.yml`, tên `Data Safety CI`.
- Trigger: push `master`, `dev`, `fix/**`; PR vào `master`/`dev`; chạy tay.
- Runner Ubuntu, Temurin JDK 21, Gradle Wrapper được kiểm tra, timeout 25 phút.
- Chỉ cấp `contents: read`, không dùng secrets, không deploy, không phát hành release.
- `clean build` thực sự chạy JUnit 5; không chạy test lần hai dư thừa.
- Luôn thử lưu báo cáo JUnit XML/HTML, kể cả khi test thất bại.
- Gate kiểm tra đủ ba suite, ít nhất 26 lượt test, không lỗi và không skip.
- JAR chỉ được upload khi gate đạt; kiểm tra CRC, class H2/Hikari đã đóng gói,
  version, không lẫn thư viện test/server và tạo SHA-256.
- Artifact ghi rõ `candidate-NOT-PRODUCTION`; CI xanh không thay thế test server.

### Test đã viết (chưa xác nhận chạy)

| Suite | Lượt test dự kiến | Phạm vi |
| --- | ---: | --- |
| `ItemSerializerSafetyTest` | 13 | Base64 sai, header/slot thiếu, giá trị trống hợp lệ, 9–4096 slot, overflow 257 slot, giới hạn byte |
| `H2StorageSafetyTest` | 9 | H2 file thật trong thư mục tạm: SELECT không sửa dữ liệu, phân biệt chưa có/rỗng/lỗi, lỗi SQL, reopen, cách ly lỗi giải mã ở tầng storage |
| `EnderChestManagerSafetyTest` | 4 | Gọi helper production: thứ tự lưu, vòng tự chờ, shutdown cache rỗng còn pending, clone sâu |

Có **7 yêu cầu dự kiến báo đỏ theo mã hiện tại**, không bị tắt hoặc đánh dấu skip:
giới hạn byte writer, overflow hỏng, xóa overflow lỗi, xóa main lỗi,
vòng tự chờ, shutdown bỏ pending, snapshot không clone sâu.
Đây là dự đoán từ code, **không phải kết quả chạy 7 test đã thất bại**.

Giới hạn của bộ test ban đầu:

- Dịch vụ plugin được mock; H2 và SQL/decoder là thật.
- Test rương rỗng kiểm tra cấu trúc/độ dài, chưa chứng minh NBT/item thật giữ nguyên.
- Test manager dùng reflection vào helper để tránh khởi tạo scheduler; chưa phải
  kiểm thử đầy đủ thao tác người chơi/Folia.
- Chưa có kiểm thử Paper/Folia chạy thật, mất điện, lỗi ổ đĩa, crash recovery,
  phiên đăng nhập chồng nhau, admin đồng thời hoặc restore backup.

### Trạng thái thực thi tại thời điểm viết

- `git diff --check`: đã chạy, không báo lỗi whitespace.
- YAML đã parse và kiểm tra trigger/quyền; script gate qua kiểm tra cú pháp Python.
  Khi chưa có báo cáo JUnit, gate đã từ chối đúng với lỗi `No JUnit reports`.
- `bash ./gradlew clean test --no-daemon --console=plain`: bị chặn trước compile,
  không tải được Gradle (`Network is unreachable`); runtime tại đây chỉ có Java 17.
- Commit bản vá cũ chưa có workflow run theo API. Chưa đủ bằng chứng kết luận
  Actions bị tắt; cũng chưa có build thành công hoặc JAR được nghiệm thu.
- Cần xem workflow run cho commit CI mới sau khi đẩy cấu hình. Nếu GitHub yêu cầu
  bật/cho phép Actions, chủ repo xác nhận tại tab Actions; không vượt quyền cài đặt.

Tài liệu cấu hình: [Gradle JVM tests](https://docs.gradle.org/current/userguide/java_testing.html),
[quản lý Actions](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository).

## 3. Thiết kế “plugin vẫn chạy, dữ liệu lỗi được bảo vệ”

### Chính sách hành vi

| Tình huống | Người chơi | Plugin / quản trị | Điều kiện mở lại |
| --- | --- | --- | --- |
| Một UUID có dữ liệu hỏng | Chặn mở/sửa/claim/import với UUID đó; không hiện rương trống | Người khác dùng bình thường, ghi nhận lỗi không spam | Bản dữ liệu phục hồi được kiểm tra và xác nhận |
| H2 bị khóa/mất kết nối khi khởi động | Tạm chặn toàn bộ thao tác rương tùy chỉnh | Plugin vẫn enabled; listener chặn truy cập và lệnh chẩn đoán còn hoạt động | Kết nối lại đúng database, kiểm tra schema và dữ liệu chờ |
| Database lỗi khi đang dùng | Ngừng nhận thao tác chuyển đồ mới; không hoàn nguyên mù quáng các thay đổi đã nhận | Giữ snapshot đã tin cậy và nhật ký chờ lưu; báo trạng thái bảo vệ | Đối soát revision và commit mọi thay đổi hợp lệ |
| Chỉ backup lỗi | Rương bình thường nếu storage vẫn khỏe | Báo backup lỗi, giữ bản tốt cũ, thử lại có giới hạn | Backup mới được kiểm chứng |
| Nhật ký/ổ đĩa đầy hoặc không ghi được | Chặn thao tác trước khi nhận thêm thay đổi | Plugin vẫn chạy nhưng không hứa “đã lưu”; cảnh báo rõ | Khôi phục nơi lưu bền vững và đối soát |

Không cho rơi về rương vanilla, không tự chuyển sang YML/database trống để ghi.
Ban đầu ưu tiên **không mở GUI có thể sửa** khi lỗi. Nếu cần xem chỉ đọc về sau,
phải chặn đủ click, drag, shift-click, hotbar, double-click, drop và các API ghi.

### Trạng thái và phục hồi

- Toàn hệ thống: `STARTING → HEALTHY`; lỗi storage chuyển `DEGRADED`;
  thử lại chuyển `RECOVERING`; chỉ về `HEALTHY` sau kiểm tra/đối soát.
- Từng UUID: `LOADING`, `READY`, `BLOCKED`, `PENDING_SAVE`; chỉ `READY`
  được phép thay đổi, tùy trạng thái toàn hệ thống.
- Kết nối lại một tác vụ duy nhất, backoff có jitter, ví dụ 5s → 10s → 20s → tối đa
  60s; không giữ main/region thread chờ IO. Lỗi cấu hình/quyền ghi/dữ liệu hỏng
  cần can thiệp, không lặp vô hạn gây spam.
- Nhận diện nguồn dữ liệu bằng đường dẫn chuẩn hóa + ID lưu bền vững + schema;
  phân biệt lần cài mới với database đã dùng nay bị mất. Không tự “khôi phục” bằng
  tạo file trống. Dữ liệu cũ cần bước ghi nhận baseline có kiểm tra.
- Tách hàng đợi ghi theo UUID khỏi danh sách theo dõi tác vụ tổng. Mỗi write có
  revision dự kiến; callback có session token và khóa có chủ sở hữu.
- Giữ nguyên payload lỗi và bằng chứng phục hồi; không tự sửa/xóa/migrate trên read.
  Nhật ký lỗi chỉ lưu mã, UUID, revision, hash; không in toàn bộ dữ liệu item.
- Lệnh dự kiến, **chưa tồn tại**: `/cec health`, `/cec retry-storage`,
  `/cec quarantine <uuid>`; giới hạn quyền admin, retry không bỏ qua kiểm tra.

## 4. Plan triển khai tiếp theo

| Bước | Công việc | Điều kiện hoàn tất |
| --- | --- | --- |
| 0 — CI và baseline | Đưa workflow/test này vào nhánh; ghi nhận run ID, commit và lỗi thực tế; không bỏ test đỏ để lấy JAR | Có báo cáo CI đọc được; phân biệt lỗi build với lỗi nghiệm thu |
| 1 — Duy trì plugin an toàn | Tách lifecycle storage khỏi onEnable; đăng ký lệnh/listener bảo vệ sớm; thêm health state, storage facade trả lỗi có kiểu, retry có giới hạn | Lỗi mở H2 không tắt plugin, không truy cập rương vanilla, không tạo kho trống |
| 2 — Dữ liệu lỗi và thứ tự ghi | Phân biệt missing/empty/corrupt/unavailable cho main+overflow; propagate mọi lỗi; tách pending/queue; clone sâu đúng thread; session/revision/owner lock | R02/R03/R06/R07/R09 hết; test out-of-order và stale callback đạt |
| 3 — Giao dịch và nhật ký bền vững | Main+overflow cùng transaction, operation ID và revision; journal snapshot/intent bền vững, giới hạn dung lượng; đợi cả dữ liệu người đã quit | Crash tại từng điểm không mất/nhân đôi đồ, không báo thành công trước commit |
| 4 — Admin, import, migration, hết hạn | Một writer cho mỗi UUID; mọi đường ghi đi chung guard/queue; pause hết hạn khi dữ liệu lỗi; ngăn admin snapshot cũ ghi đè | Hai admin, player và tác vụ nền cùng thao tác không phá dữ liệu |
| 5 — Backup và recovery | Backup single-flight, tên tạm riêng, dùng backend thực tế; verify archive và restore trong thư mục riêng; giữ bản tốt gần nhất | Backup lỗi không xóa bản tốt; restore kiểm tra được UUID/slot/item/hash |
| 6 — Server test và nghiệm thu | Chạy trên bản sao cô lập, cùng phiên bản Paper/Folia và plugin phụ của server; xác nhận số lượng+metadata đồ trước/sau lỗi | CI đạt + báo cáo test thật + không còn P0/P1 dữ liệu chưa giải quyết |

Nhật ký phải được thiết kế cùng giao thức nhận thao tác: không phải chỉ “ghi lại
sau khi phát hiện DB lỗi”. Nếu đã cho người chơi chuyển đồ nhưng chưa có bản ghi
bền vững, crash vẫn có thể mất dữ liệu. Không dùng hàng đợi RAM làm bảo đảm lưu.

Chuyển đồ giữa main/overflow trong cùng DB có thể dùng một transaction. Nếu thao
tác liên quan inventory người chơi, vanilla playerdata hoặc tiền Vault, đó là
nhiều nguồn lưu khác nhau: cần intent/reconciliation; trạng thái không chắc đã
phát đồ/trừ tiền phải tạm giữ để xử lý, không tự replay gây nhân đôi/trừ hai lần.
Không hứa “exactly once” chỉ bằng một transaction H2.

## 5. Checklist nghiệm thu bắt buộc ở giai đoạn sửa tiếp

1. Khởi động khi file H2 bị khóa, thiếu, hỏng hoặc hết quyền ghi: plugin còn enabled,
   lệnh health hoạt động, không đổi tên/xóa/tạo thay thế file nguồn.
2. Một player lỗi payload: chỉ player đó bị khóa; player khác đọc/ghi bình thường.
3. Mất kết nối trong lúc mở rương: không nhận thêm chuyển đồ, không có GUI vanilla
   dự phòng; dữ liệu chờ được đối soát trước khi mở lại.
4. Các thao tác click/drag/hotbar/claim/admin/import/API đều tuân thủ trạng thái bảo vệ.
5. Database phục hồi đúng ID; revision cũ không ghi đè revision mới; retry không nhân đôi.
6. 54 → 27 → 54 slot, có overflow cũ; crash giữa mỗi bước transaction: tổng đồ và
   metadata giữ nguyên, kể cả nhiều stack giống nhau.
7. Join → quit → join nhanh, load chậm, autosave chồng close, hai admin sửa cùng UUID:
   session cũ không sửa cache mới hoặc nhả khóa của tác vụ khác.
8. Quit rồi shutdown ngay khi cache rỗng vẫn còn write: drain đủ, journal còn nguyên
   nếu chưa commit; không đóng pool trong lúc writer còn dùng.
9. Snapshot bị thay đổi inventory gốc sau khi đưa vào queue: bản lưu vẫn độc lập.
10. Crash trước/sau fsync và trước/sau SQL commit; journal đầy/hỏng: không mất dấu
    trạng thái chưa chắc chắn; không retry thao tác phát đồ một cách mù quáng.
11. Item thật: tên/lore, enchantment, NBT/PDC, shulker chứa đồ, sách, item nhiều dữ
    liệu; so sánh số lượng và metadata, không chỉ chiều dài mảng hoặc dung lượng file.
12. Backup đang chạy thì tác vụ khác yêu cầu backup; backup lỗi; restore và xoay vòng:
    không dùng chung temp, không mất bản tốt gần nhất.
13. Kiểm tra hết hạn overflow và log xóa với cấu hình bản sao thực tế; không tự bật
    chính sách xóa trong đợt điều tra.
14. Paper và Folia: không đọc/sửa inventory sai thread; callback khi entity đã rời
    server vẫn kết thúc tác vụ và giải phóng đúng tài nguyên.

## 6. Quy tắc triển khai thử

- Chỉ dùng DB mới tổng hợp hoặc bản sao đã được cho phép; không trỏ CI vào production.
- Trước thử server: giữ bản sao nguyên trạng database, thư mục plugin, config và log.
- Không copy nóng file H2 đang mở làm bản backup hợp lệ; dùng `BACKUP TO` hoặc
  sao chép sau khi DB đã đóng an toàn.
- Ghi nhận run ID, SHA commit/JAR, server build, cấu hình và dữ liệu trước/sau.
- Nghiệm thu bằng số UUID/bản ghi và nội dung rương + overflow; file H2 giảm dung
  lượng chỉ là tín hiệu điều tra, không dùng làm tiêu chí duy nhất.
- Chưa thay plugin trên server chính, chưa merge PR và chưa tự phục hồi dữ liệu thật.
