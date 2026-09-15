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

Bổ sung yêu cầu người dùng: **khi player mở Ender Chest, đồ đã có phải được giữ
nguyên; lỗi đọc dữ liệu không được biến thành rương trống.** Có bản dữ liệu tốt
đã xác thực thì ưu tiên tiếp tục hiển thị đồ, thay vì mặc định chặn mọi lần mở.
Đây là tiêu chí cần triển khai và kiểm thử, chưa phải tính năng đã hoàn tất.

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
| Một UUID có dữ liệu hỏng | Có bản tốt đã xác thực: mở bản xem được bảo vệ, giữ đồ; chưa có: báo đang phục hồi, không mở rương trống; chặn sửa/claim/import | Người khác dùng bình thường, giữ dữ liệu gốc và thử tìm bản tốt | Bản phục hồi được đối soát; xác nhận lưu an toàn mới cho chuyển đồ |
| H2 bị khóa/mất kết nối khi khởi động | Tạm chặn toàn bộ thao tác rương tùy chỉnh | Plugin vẫn enabled; listener chặn truy cập và lệnh chẩn đoán còn hoạt động | Kết nối lại đúng database, kiểm tra schema và dữ liệu chờ |
| Database lỗi khi đang dùng | Ngừng nhận thao tác chuyển đồ mới; không hoàn nguyên mù quáng các thay đổi đã nhận | Giữ snapshot đã tin cậy và nhật ký chờ lưu; báo trạng thái bảo vệ | Đối soát revision và commit mọi thay đổi hợp lệ |
| Chỉ backup lỗi | Rương bình thường nếu storage vẫn khỏe | Báo backup lỗi, giữ bản tốt cũ, thử lại có giới hạn | Backup mới được kiểm chứng |
| Nhật ký/ổ đĩa đầy hoặc không ghi được | Chặn thao tác trước khi nhận thêm thay đổi | Plugin vẫn chạy nhưng không hứa “đã lưu”; cảnh báo rõ | Khôi phục nơi lưu bền vững và đối soát |

Không cho rơi về rương vanilla, không tự chuyển sang YML/database trống để ghi.
Khi lỗi, **không mở GUI có thể sửa**, nhưng phải hỗ trợ xem đồ từ bản tốt đã xác
thực ngay trong đợt triển khai này. Bản xem bảo vệ phải chặn đủ click, drag,
shift-click, hotbar, double-click, drop, phím đổi tay và các API ghi. Không cho
autosave hoặc sự kiện đóng GUI ghi bản xem này đè lên dữ liệu nguồn.

### Bổ sung ưu tiên P0: giữ đồ khi player mở rương có dữ liệu lỗi

**Mục tiêu:** đồ không biến mất khỏi rương vì lỗi đọc/giải mã; vẫn giữ đúng slot,
số lượng và thuộc tính. Việc hiển thị được đồ và việc cho phép lấy/cất đồ là hai
điều kiện khác nhau: có thể xem bản được bảo vệ trong lúc phục hồi, nhưng chỉ
chuyển đồ khi xác nhận được trạng thái hiện tại và khả năng lưu an toàn.

1. **Đã tải được đồ trước khi lỗi:** giữ nguyên trạng thái đáng tin của phiên
   hiện tại; tạo bản chụp sâu trên đúng thread. Load/retry thất bại không được
   gọi `clear`, ghi `AIR`, thay cache bằng mảng rỗng hoặc hoàn nguyên về bản cũ.
   Chuyển sang chế độ bảo vệ trước khi nhận thêm thao tác. Xử lý cả item đang ở
   con trỏ; không đóng/mở GUI mù quáng làm rơi hoặc trả item sai chỗ.
2. **Mở lại sau restart, nguồn chính hỏng:** tìm bản chụp bền vững hợp lệ theo UUID,
   ID database, định dạng, checksum và revision, rồi đối chiếu nhật ký các lần
   thay đổi. Chỉ bản tái dựng có căn cứ mới được coi là trạng thái hiện tại.
   Không chọn bản chỉ vì tên file/thời gian mới nhất; không tự đọc rương vanilla.
3. **Chỉ có backup cũ:** có thể hiển thị rõ là “bản phục hồi tại thời điểm …”,
   chỉ đọc, không coi đó là đồ hiện tại và không tự phát lại đồ từng được lấy ra.
   Thiếu lịch sử hoặc có giao dịch chưa rõ kết quả thì cần đối soát trước khi
   mở sửa; việc dùng bản cũ làm dữ liệu chính phải được xác nhận khi có rủi ro.
4. **Một item/slot không giải mã được:** không chuyển thành slot trống rồi lưu
   phần còn đọc được. Giữ nguyên payload lỗi và các bản tốt. Ưu tiên bản đầy đủ
   đã xác thực; nếu không thể khôi phục slot đó thì ghi rõ chưa phục hồi được,
   giữ khóa ghi, không tự xóa hoặc tạo item thay thế để giả vờ đầy đủ dữ liệu.
5. **Nguồn lưu đã phục hồi:** đối soát main, overflow, nhật ký và phiên hiện tại;
   kiểm tra tổng đồ/thuộc tính và revision, commit trạng thái đã xác nhận, rồi
   mới bỏ khóa thao tác. Không chép bản backup đè thẳng lên dữ liệu mới.
6. **Không có bản tốt nào:** không có cơ sở tái tạo chính xác item đã mất. Thông
   báo “Dữ liệu rương đang được phục hồi”, giữ nguyên bằng chứng, thông báo admin;
   không mở rương trống và không tuyên bố đã cứu đủ đồ. Plugin vẫn hoạt động.

Bản dự phòng theo UUID phải được duy trì **trước khi xảy ra lỗi**: lưu revision,
checksum, main và overflow nhất quán; xuất bản snapshot bằng ghi tạm + đồng bộ
xuống đĩa + đổi tên nguyên tử khi được hỗ trợ. Nếu nền tảng không hỗ trợ, dùng
giao thức ghi có thể kiểm chứng; không giả định một thao tác đổi tên là đủ.
Chỉ xoay vòng bản cũ sau khi bản mới được xác thực; giới hạn dung lượng nhưng
không xóa bản tốt duy nhất hoặc bằng chứng của UUID đang lỗi.

Thông báo dự kiến:

- Có bản hiện tại đã xác thực: “Đồ của bạn vẫn được giữ. Rương đang được bảo vệ,
  tạm thời chỉ xem được trong lúc khôi phục lưu dữ liệu.”
- Chỉ có bản cũ: “Đang xem bản phục hồi lúc …, chưa xác nhận là dữ liệu hiện tại.”
- Không có bản đọc được: “Dữ liệu rương đang được phục hồi. Vui lòng báo quản trị viên.”

Không thông báo “rương trống”, “đã lưu” hoặc “đã khôi phục đầy đủ” khi chưa chứng minh được.

### Trạng thái và phục hồi

- Toàn hệ thống: `STARTING → HEALTHY`; lỗi storage chuyển `DEGRADED`;
  thử lại chuyển `RECOVERING`; chỉ về `HEALTHY` sau kiểm tra/đối soát.
- Từng UUID: `LOADING`, `READY`, `PROTECTED_VIEW`, `BLOCKED`, `PENDING_SAVE`; chỉ `READY`
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
| 3A — Giữ đồ khi nguồn chính lỗi (P0) | Bản tốt theo UUID có revision/checksum; tái dựng từ nhật ký; GUI xem được bảo vệ; phục hồi có đối soát | Đồ đang có không bị clear/đổi AIR; bản cũ không được phát lại tự động; chỉ mở sửa sau xác nhận lưu |
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
2. Một player lỗi payload: chỉ khóa sửa của player đó, giữ bản xem đồ nếu có bản tốt;
   player khác đọc/ghi bình thường.
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
15. Đang mở rương có item thật rồi ép lỗi đọc/SQL: đồ không biến thành AIR, không
    đổi slot/số lượng/thuộc tính; item trên con trỏ được bảo toàn; không cho chuyển
    đồ trong chế độ bảo vệ; close/autosave không ghi rương trống.
16. Restart với dữ liệu chính hỏng và snapshot/journal hợp lệ: mở bản phục hồi đúng
    revision; so sánh từng slot, lượng đồ và metadata với trạng thái đã xác nhận.
17. Snapshot mới nhất hỏng checksum: không sử dụng; kiểm tra bản trước cùng nhật ký.
    Không đủ lịch sử thì chỉ xem bản cũ có nhãn thời điểm, không mở sửa tự động.
18. Đồ đã lấy ra sau lần backup: khôi phục không làm đồ đó xuất hiện lần hai. Đồ
    đã cất sau backup phải tái dựng từ nhật ký hoặc được giữ ở trạng thái chờ đối soát.
19. Hỏng đúng một slot: dữ liệu gốc slot đó không bị xóa, phần đọc được không tự
    ghi đè cả rương; không dùng item giả để báo phục hồi thành công.
20. Không có snapshot đọc được: không mở GUI trống, không ghi đè file/bản ghi lỗi;
    plugin còn enabled và admin nhận thông báo đúng tình trạng.
21. Hai lần mở/retry cùng lúc và admin mở rương đang phục hồi: không tạo hai nguồn
    ghi, không nhả khóa sớm và không lưu bản xem/backup cũ thành dữ liệu mới.

Các mục 15–21 là **yêu cầu test bổ sung chưa viết/chưa chạy**, không nằm trong
26 lượt test ban đầu của CI. Nghiệm thu chỉ đạt khi các mục này được thực hiện
với item thật; hiển thị lại một bản backup cũ không đủ chứng minh không mất đồ.

## 6. Quy tắc triển khai thử

- Chỉ dùng DB mới tổng hợp hoặc bản sao đã được cho phép; không trỏ CI vào production.
- Trước thử server: giữ bản sao nguyên trạng database, thư mục plugin, config và log.
- Không copy nóng file H2 đang mở làm bản backup hợp lệ; dùng `BACKUP TO` hoặc
  sao chép sau khi DB đã đóng an toàn.
- Ghi nhận run ID, SHA commit/JAR, server build, cấu hình và dữ liệu trước/sau.
- Nghiệm thu bằng số UUID/bản ghi và nội dung rương + overflow; file H2 giảm dung
  lượng chỉ là tín hiệu điều tra, không dùng làm tiêu chí duy nhất.
- Chưa thay plugin trên server chính, chưa merge PR và chưa tự phục hồi dữ liệu thật.
