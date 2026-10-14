# UpJasperReport6To7

**JasperReports 6 → 7 批次重編譯工具**：把舊版（JR6 風格）`.jrxml` 報表批次轉譯、編譯成 JasperReports 7 的 `.jasper`，全程**不修改**來源 `.jrxml`（唯讀保護）。

工作流程：

1. 遞迴掃描來源資料夾的 `*.jrxml`。
2. 記憶體中把 legacy JRXML 結構轉成 JR7 相容格式（DOM 轉換，非正規表示式亂改）。
3. 以 JasperReports 7 載入轉譯後的 XML。
4. 編譯成 `.jasper` 位元組。
5. 再載入一次 `.jasper`（round-trip）確認真的可用。
6. 依設定把 `.jasper` 寫到「輸出資料夾」或「來源同位置（就地覆寫）」；dry-run 則完全不寫檔。
7. 產出 JSON 報告（成功/失敗/跳過明細）。

提供兩種使用方式：

| 模式 | 啟動方式 | 行為 |
| --- | --- | --- |
| **Web UI（預設）** | `mvn spring-boot:run` | http://localhost:31415，手動觸發批次、背景執行、進度輪詢 |
| **CLI 批次** | 啟動時加 `--spring.profiles.active=cli` | 啟動即跑批次（預設 dry-run） |

---

## 我還沒安裝 JasperStudio 7.0.8+，可以使用這個 jar 嗎？

**可以，完全不需要安裝 JasperStudio。**

- 本工具已把 JasperReports 7.0.8 引擎封裝在專案 Maven 依賴中，只要 **JDK＋Maven** 就能執行，JasperStudio 不在執行必要條件裡。
- JasperStudio 只在**一個情境**會用到：批次結果中若有少数 `.jrxml` 自動轉換失敗（顯示於 Web UI 的「需要注意的異常檔案」），需要**人工**開啟該 `.jrxml` 檢視、修正後重新編譯存檔——這個動作建議用 **JasperStudio 7.0.8 以上版本**做（或其他能產出 JR7 格式 .jasper 的編輯工具）。
- 換句話說：**JasperStudio 是「人工救急」工具，不是本工具的先決條件。**

---

## 環境需求

| 項目 | 需求 |
| --- | --- |
| JDK | 17 以上（本專案以 JDK 25 建置測試） |
| Maven | 3.6+ |
| JasperReports | 不需要另外安裝（Maven 自動取得 7.0.8 依賴；需本機 Maven 快取或網路下載） |
| JasperStudio | **不需要**（僅人工修正失敗報表時才用得到） |
| 作業系統 | Windows / macOS / Linux（Windows PowerShell 範例另附） |

確認環境：

```bash
java -version
mvn -version
```

---

## 快速開始：Web UI 模式（推薦）

### 1. 啟動

```bash
mvn spring-boot:run
```

- 啟動成功後會**自動開啟瀏覽器** http://localhost:31415（π 的前五碼）。
  不想自動開啟：`mvn spring-boot:run -Dspring-boot.run.arguments="--upj.open-browser=false"`
- **關閉瀏覽器視窗 → 服務自動停止**（前端 `pagehide` 時 sendBeacon `POST /api/close`；若瀏覽器被強殺，由 `upj.idle-shutdown-seconds`（預設 180 秒無活動）安全網接手）。

### 2. 頁面操作步驟

1. **批次參數設定**
   - 「報表來源資料夾」：`.jrxml` 所在目錄（會自動遞迴掃描）。可手動輸入**伺服器端**路徑，或按「📂 瀏覽…」用目錄視窗挑選（瀏覽器讀不到伺服器端路徑，由後端 `GET /api/browse` 列出子目錄）。**留空＝沿用 `upj.source-dir` 預設值**。
   - 「輸出資料夾」：`.jasper` 存放位置。**空值＝寫入來源資料夾**（與 `.jrxml` 同位置，等同就地覆寫）。驗證時（dry-run）免填。
   - 「JSON 報告存放路徑」：留空＝系統預設路徑；用「瀏覽」選資料夾時會自動存成該資料夾下的 `recompile-report.json`。
   - 勾選「只驗證、不寫入檔案（dry-run）」與「就地覆寫」**互斥**：勾一個會自動取消另一個。
2. **先預覽**：按「🔍 先預覽檔案清單」→ 顯示找到幾份 `.jrxml`（不執行任何轉換）。
3. **開始執行**：按「🚀 開始執行編譯」。
   - 正式寫入來源資料夾（勾「就地覆寫」或輸出資料夾為空）且未勾 dry-run 時，瀏覽器會先跳確認對話框。
   - 執行中：進度條＋成功/失敗計數；「檔案掃描結果」與「需要注意的異常檔案」會自動收合清空。
   - 想中途停：「⏹ 停止本次執行」＝**軟取消**——跑完目前檔案後停止，剩餘檔案標記未執行（skipped），已寫入的保留。
4. **結果**：
   - 完成後狀態變「已完成／中斷／已取消」，可按「📥 下載分析報告 (JSON)」。
   - 有失敗時：「需要注意的異常檔案」列出路徑、停在哪個步驟、耗時、錯誤原因，並提示**自行用 JasperStudio 7.0.8 以上版本開啟重新編譯**。
5. **頁面底部版號列**：`jar <版本>`（依版本規範 `MAJOR.MINOR.PATCH+YYYYMMDD`）＋ `build <前端建置號> <時間>`，可一眼確認執行中的是不是新版。

### 3. 輸出位置速查

| 勾選 | 輸出資料夾 | .jasper 寫到哪 |
| --- | --- | --- |
| 只驗證（dry-run） | （任意） | **不寫任何檔**，只驗證 |
| 不勾 dry-run | 有填 | 輸出資料夾的鏡像目錄結構 |
| 不勾 dry-run | 空 / 勾「就地覆寫」 | **來源資料夾**（與 .jrxml 同位置，會取代舊 .jasper；必經確認對話框） |

---

## CLI 批次模式

預設服務模式不自動跑批次；要「啟動即跑」加 `--spring.profiles.active=cli`（或 `--upj.auto-run=true`）。

### 前置：產生 runtime classpath

```bash
mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
```

### 執行（exploded classpath）

```bash
java -cp "target/classes;$(cat cp.txt)" io.github.jessielee3260.upjasperreport6to7.UpJasperReport6To7Application
```

Windows PowerShell：

```powershell
$cp = Get-Content cp.txt -Raw
java -cp "target/classes;$cp" io.github.jessielee3260.upjasperreport6to7.UpJasperReport6To7Application
```

**為什麼用 exploded classpath 而非 `java -jar` fat jar？**
JasperReports 可能以 JDK 編譯器動態編譯報表表達式；fat jar 內的巢狀依賴 jar 對該編譯器不完全是常規檔案系統 classpath 項目，exploded classpath 可避開這個問題。

### 步驟一：dry-run（最安全的第一步）

```bash
java -cp "target/classes;$(cat cp.txt)" io.github.jessielee3260.upjasperreport6to7.UpJasperReport6To7Application \
  --spring.profiles.active=cli \
  --upj.source-dir="/path/to/reports" \
  --upj.dry-run=true \
  --upj.report-file="recompile-report.json"
```

成功摘要：

```text
==== SUMMARY: total=N ok=N fail=0 skipped=0 cancelled=false dryRun=true report=... ====
```

### 步驟二：產出到輸出資料夾（來源不動）

```bash
java -cp "target/classes;$(cat cp.txt)" io.github.jessielee3260.upjasperreport6to7.UpJasperReport6To7Application \
  --spring.profiles.active=cli \
  --upj.source-dir="/path/to/reports" \
  --upj.out-dir="out-jasper" \
  --upj.in-place=false \
  --upj.dry-run=false \
  --upj.report-file="recompile-report.json"
```

`/path/to/reports/a/b/sample.jrxml` → `out-jasper/a/b/sample.jasper`（鏡像結構）。

### 步驟三：就地重編（dry-run 通過後才用）

```bash
java -cp "target/classes;$(cat cp.txt)" io.github.jessielee3260.upjasperreport6to7.UpJasperReport6To7Application \
  --spring.profiles.active=cli \
  --upj.source-dir="/path/to/reports" \
  --upj.in-place=true \
  --upj.dry-run=false
```

每個 `sample.jrxml` 旁邊寫入/取代 `sample.jasper`；`.jrxml` 本身不改動。

### 正式交付：打 jar

```bash
mvn -q -DskipTests package
# 產出 target/upjasperreport6to7-<version>.jar
```

> 注意：正式執行仍建議改用上面的 exploded classpath（fat jar 有 JDK 動態編譯器可見度問題，見上節）。

---

## 建議流程

1. `mvn spring-boot:run` 開 Web UI（或 CLI dry-run）。
2. 先跑 **dry-run**，下載/檢視 `recompile-report.json`。
3. 有失敗 → 依「錯誤原因／停在哪個步驟」修正；純人工修不來的用 **JasperStudio 7.0.8+** 手動處理，再重跑 dry-run。
4. `failed=0` 後跑正式批次，**優先用輸出資料夾模式**。
5. 在目標執行環境驗證產出的 `.jasper`。
6. 確認無誤後才用「就地覆寫」（來源資料夾直接換血）。
7. 就地覆寫前務必先備份來源資料夾——取消批次**不會**復原已寫入的 `.jasper`。

---

## 設定參數（`upj.*`）

預設值在 `src/main/resources/application.properties`，皆可由命令列 `--upj.xxx` 或 UI 覆蓋：

| 參數 | 預設 | 說明 |
| --- | --- | --- |
| `upj.source-dir` | （預設為開發者本機相對路徑） | 遞迴掃描 `.jrxml` 的目錄。**正式使用請以 `--upj.source-dir` 指定您的報表目錄**。 |
| `upj.out-dir` | `out-jasper` | `dry-run=false` 且 `in-place=false` 時的輸出目錄；**空值＝寫到來源資料夾**（等同就地）。Web UI 表單預填刻意留空（＝來源同位置）。 |
| `upj.in-place` | `false` | `true`＝`.jasper` 寫在各 `.jrxml` 旁邊，直接取代舊檔。 |
| `upj.dry-run` | `true` | `true`＝只驗證、不寫任何 `.jasper`。 |
| `upj.report-file` | `recompile-report.json` | JSON 報告輸出路徑。 |
| `upj.auto-run` | `false` | `true`＝啟動即跑批次（CLI 行為，由 `cli` profile 開啟）；`false`（預設）＝服務模式。 |
| `upj.open-browser` | `true` | Web 模式啟動後自動開啟瀏覽器；`--upj.open-browser=false` 關閉。 |
| `upj.idle-shutdown-seconds` | `180` | 瀏覽器關閉監控：N 秒無 `/api` 活動→自動停止 Spring Boot；`0`＝停用。前端 `pagehide` sendBeacon `/api/close` 會**立即**停止，此值為強殺瀏覽器的安全網。CLI 模式不啟動。 |

---

## REST API

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/api/config` | 目前 `upj.*` 預設值（來源/輸出預填刻意留空） |
| GET | `/api/browse?path=...` | 目錄視窗資料：子目錄、上一層、磁碟根（給「📂 瀏覽」鈕） |
| GET | `/api/scan?sourceDir=...` | 列出目錄下 `.jrxml`（不執行轉換） |
| POST | `/api/run` | 啟動背景批次（body：sourceDir/outDir/reportFile/inPlace/dryRun，皆可選）；執行中第二組→409 |
| GET | `/api/status` | 批次狀態＋進度（UI 每 2s 輪詢）：`IDLE/RUNNING/DONE/FAILED/CANCELLED` |
| GET | `/api/report` | 最近一組批次的失敗明細＋計數 |
| POST | `/api/beat` | 瀏覽器心跳：頁面開著時前端定期 POST，更新無活動計時 |
| POST | `/api/close` | 瀏覽器關閉訊號（前端 `pagehide` sendBeacon）→ 停止 Spring Boot（exit 0） |
| POST | `/api/cancel` | 軟取消執行中批次（目前檔案跑完後停止、剩餘標記 skipped；409＝無批次執行中） |

---

## JSON 報告格式

報告寫入 `upj.report-file`。頂層欄位：

| 欄位 | 說明 |
| --- | --- |
| `total` | 找到的 `.jrxml` 數 |
| `ok` | 成功（轉譯、載入、編譯、round-trip 全過）的檔案數 |
| `failed` | 失敗檔案數 |
| `skipped` | 因「停止本次執行」（軟取消）而未處理的檔案數 |
| `cancelled` | 該批次是否被使用者取消 |
| `failures` | 失敗明細清單（全數通過時為空） |
| `jasperOut` | 設定的輸出目錄 |
| `written` | `dry-run=false` 時為 `true` |

失敗明細項：

| 欄位 | 說明 |
| --- | --- |
| `path` | 相對路徑 |
| `ok` | 失敗項固定 `false` |
| `jasperBytes` | 產生位元組數（失敗通常為 0） |
| `ms` | 該檔耗時 |
| `stage` | 大致停在哪：`transform` / `load` / `load(jackson)` / `compile` |
| `error` | 錯誤訊息 |

---

## 轉換說明（`LegacyJrxmlTransformer`）

- 清理/正規化 legacy JRXML namespace。
- `subDataset` → `dataset`；`queryString` → `query`。
- legacy band/frame/element 結構轉成 JR7 友善結構；`reportElement` 屬性提升到 `element`。
- 保留 `box`、`textElement`、`font`、`paragraph` 等樣式子節。
- legacy `isXxx` 布林屬性 → `xxx`。
- **CDATA 保留**：表達式節點（如 `printWhenExpression`）的 CDATA 在 raw clone 時保留，避免把表達式靜默變成空節而改變報表行為。
- 條碼（Barbecue）元件支援：JR7 用 Jackson 映射 XML 元件，專案於 `src/main/resources/jasperreports_extension.properties` 註冊常見條碼類型（Code39、Code128、EAN13/EAN8、UPCA/UPCE、Codabar、PDF417）；legacy 屬性映射：`moduleWidth`→`barWidth`、`textPosition="none"`→`drawText="false"`、其他→`drawText="true"`，JR7 不吃的 quiet-zone 屬性會被移除。

---

## 版號

- **jar 版號**：SemVer `MAJOR.MINOR.PATCH`＋Build Metadata `+YYYYMMDD`（例：`1.0.0+20261014`）；Git Tag 加 `v` 前綴。
  - MAJOR＝重大/不相容變更；MINOR＝向下相容新功能；PATCH＝向下相容修正；同日多次發布以 PATCH 遞增。
- Web UI 底部顯示 `jar <版本>`＋前端 `build <vYYYYMMDDHHMM> <時間>`，可辨認目前執行中的版本。

---

## 疑難排解

| 症狀 | 處理 |
| --- | --- |
| 條碼元件 `InvalidTypeIdException` | 確認 `src/main/resources/jasperreports_extension.properties` 在 runtime classpath，且 Barbecue 相關 JasperReports 依賴存在。 |
| JR7 XML 載入時 `Unrecognized field` | 通常是 legacy 屬性被帶到 JR7 不再接受的元件；需新增轉換規則（改名或移除該屬性）。 |
| `java -jar` fat jar 時表達式編譯錯誤 | 改用 exploded classpath 執行（見上節原因說明）。 |
| `.jrxml` 被意外修改 | 本工具**不寫** `.jrxml`；若發現改動，檢查外部 formatter/編輯器。只有 `upj.dry-run=false` 時才會寫 `.jasper`。 |
| UI 開出 HTML、API 報 JSON parse 錯 | 後端沒跑起來（前端 fallback 抓到非 JSON 內容）；先確認 Spring Boot 服務在 31415。 |
| 批次想中途停 | Web UI「⏹ 停止本次執行」（軟取消）；或關瀏覽器（連服務一起停）。 |

---

## 目前狀態

- 核心批次流程：dry-run 驗證、輸出目錄產出、就地重編、JSON 報告、CDATA 保留、條碼元件映射、軟取消、瀏覽器關閉自動停止。
- 後續改進另開 issue 追蹤。
