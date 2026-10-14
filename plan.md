# Plan: JR6 → JR7 .jasper 批次編譯（UpJasperReport6To7）

## 目標
- 336 筆舊版 .jrxml（JR6 / iReport 世代、含 legacy xmlns）批次重編譯為 JR 7.0.8 的 `.jasper`
- 舊 `.jasper` 二進位 7.0.8 讀不了（`InvalidClassException`）→ 全數重編，一次性切換
- **.jrxml 來源檔一律唯讀、不修改**；所有轉換在記憶體中完成
- 產出：就地替換 336 個 `.jasper` + `recompile-report.json`（逐檔 OK/FAIL）

## 工具
- `UpJasperReport6To7`：Spring Boot 4 + Maven + JDK 25（package `io.github.jessielee3260.upjasperreport6to7`）
- **用 Maven；Gradle 棄用**（SB 4.1.1 gradle plugin 在 central/mirror 缺件）
- 核心元件：`jasperreports` / `-json` / `-groovy` / `-fonts` 7.0.8 + `org.apache.groovy:groovy`

## 批次管線（已實作）
1. 走訪來源目錄列舉 `*.jrxml`
2. `LegacyJrxmlTransformer`（DOM 重構，非 regex）：
   - 根元素去 `xmlns` / `xmlns:xsi` / `xsi:schemaLocation`
   - 元素改名：`subDataset`→`dataset`、`queryString`→`query`
   - `<section><band attrs>...</band></section>`：展開 band、屬性上提（height/splitType）
   - band / frame 內的元素種類一律包 `<element kind="...">`（textField / staticText / image / line / rectangle / frame / subreport / component…）
   - `<reportElement>` 幾何/uuid 屬性上提到 `<element>`；其 `<box>` 等子元素保留
   - expression 子元素統一 `<expression>`（textFieldExpression / imageExpression / subreportExpression / variableExpression / groupExpression / parameterExpression）
   - `fieldDescription` 子元素 → 屬性 `description`
   - 屬性改名：isDefault→default、isPdfEmbedded→pdfEmbedded、isIgnorePagination→ignorePagination、isFloatColumnFooter→floatColumnFooter、isForPrompting→forPrompting、hAlign→hTextAlign、vAlign→vTextAlign、isStartNewPage→startNewPage、isStartNewColumn→startNewColumn
   - 通用規則：`is*` 屬性 → 首字母小寫（isPrintWhenDetailOverflows→printWhenDetailOverflows 等）
   - 屬性刪除：`line`、`band`（iReport 視覺輔助）
3. `JRXmlLoader.load`（新格式）→ `JasperCompileManager.compileToStream` → `.jasper`
4. `JRLoader.loadObject` round-trip 驗證
5. 預設 dry-run；`--upj.in-place=true` 才寫入；逐檔 JSON 報告

## 未完成 / 進行中（empirical 驗證）
- [ ] **group 結構**：jackson 顯示新模型 `JasperDesign.group` = `List<JRDesignSection>`（knowns: `part`、`band`）→ group / groupHeader / groupFooter 的轉換要依 writer dump 調整
- [ ] `<box>` / `<textElement>` / `<font>` / `<paragraph>` 在 `<element>` 子元素是否被接受
- [ ] image（2 檔）、subreport（11 檔）、component / barbecue（2 檔，`<barbecue type="3of9">` Code39）逐樣本驗證
- [ ] 1 支樣本原始 XML 就有語法問題（JsonParseException 未預期 `<`）→ 單獨處理
- [ ] dataset 內 `query` 是否為 JasperDesign 層級屬性（jackson knowns 有 `query`）→ 依 writer dump 定

## 已知坑
- **JDK 25 Xerces：`createElement` 出的節點 `localName=null`** → serializer 一律用 `getNodeName()`
- `JRXmlLoader.load` 會把根因包成 `Unable to load report` → 除錯改直接 `JacksonUtil.loadXml(String, JasperDesign.class)` 看 jackson 錯誤
- `JasperCompileManager.getInstance(null)` NPE → 必傳 `DefaultJasperReportsContext.getInstance()`
- json language 資料源需要 `jasperreports-json` 7.0.8 模組（替代舊 runtime shim）

## Fat jar / double-click 模式 classpath 修正計畫

- GitHub issue：[#1](https://github.com/JessieLee3260/JasperReportTest/issues/1)

### 問題現象
- IDE 或 exploded classpath 執行時，`.jrxml` 批次結果接近全數成功。
- 以 `java -jar` / double-click fat jar 執行時，含 Java expression 的報表會在 `compile` 階段失敗，常見錯誤包含：
  - `cannot find symbol: class JREvaluator`
  - `cannot find symbol: class JRFillField`
  - `package net.sf.jasperreports.engine does not exist`
- 含 Groovy expression 且引用 JasperReports 擴充模組 class 的報表，可能出現 Groovy `unable to resolve class ...`。

### 根因
- JasperReports 的 Java expression compiler 會讀取 `net.sf.jasperreports.compiler.classpath`；若未設定，預設使用 `System.getProperty("java.class.path")`。
- Spring Boot fat jar 模式下，`java.class.path` 只包含外層 app jar，`BOOT-INF/lib/*.jar` 不會是 javac 可直接使用的實體路徑。
- JasperReports 7.0.8 的 `JRGroovyCompiler` 內部未套用傳入的 classpath；Groovy 解析 class 時依賴 thread context classloader，因此 fat jar nested classloader 也需要補強。

### 修正設計
1. 新增 `CompilerClasspath`：
   - 優先使用 Spring Boot launcher 的 `loader.path`（若存在）。
   - 若是 fat jar，偵測外層 jar 的 `BOOT-INF/lib/*.jar`，抽出 `BOOT-INF/classes` 與 nested jar 到 temp 目錄，產生 javac 可使用的實體 classpath。
   - exploded/IDE 模式則回退到 context `URLClassLoader` 或 `java.class.path`。
2. 在 `JasperRecompileService` 批次開始時：
   - 將解析出的 classpath 寫入 `JRCompiler.COMPILER_CLASSPATH`。
   - 以同一批 classpath 建立 `URLClassLoader` 並暫設為目前執行緒的 context classloader，批次結束後恢復原始 classloader。
3. 新增單元測試驗證：
   - `CompilerClasspath.resolve()` 非空。
   - `CompilerClasspath.apply()` 會實際設定 JasperReports compiler classpath 屬性。
   - 建立的 classloader 可解析 JasperReports class。

### 驗證計畫
- 修前基線：以既有 fat jar dry-run 兩個最小樣本：
  - Java expression 樣本重現 `JREvaluator` / `JRFillField` 無法解析。
  - Groovy expression 樣本用於驗證 classloader 補強。
- 修後驗證：
  - `mvn -q test`：28 tests、0 failures、0 errors。
  - `mvn -q package`：通過並產生 fat jar。
  - fat jar CLI dry-run 最小樣本：2 total、2 ok、0 failed。
- 使用者完整資料集驗證：再次以 double-click / `java -jar` 執行，預期原先 19 個 javac classpath 失敗消失；若仍有失敗，應只剩個別 `.jrxml` 語法或 expression 相容性問題。

## 執行指令（Windows / PowerShell 或 Git Bash）
```
mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes;$(cat cp.txt)" io.github.jessielee3260.upjasperreport6to7.UpJasperReport6To7Application
```

## 判定標準
- dry-run：336/336 LOAD OK + COMPILE OK + VERIFY OK（FAIL 為 0）
- in-place：336 個 `.jasper` 全部更新、`.jrxml` git status 無異動
- 後段：runtime 模組升 7.0.8 後 smoke test（PDF/HTML/XLSX 下載、json fill、groovy expression 編譯）
