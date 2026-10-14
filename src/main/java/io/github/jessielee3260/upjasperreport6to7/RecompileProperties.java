package io.github.jessielee3260.upjasperreport6to7;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 批次重編譯參數。可用 application.properties 或 --upj.* 啟動參數覆蓋。
 *
 * 預設：
 *  - source-dir 指向 LANDWEBAP 的 WEB-INF 模板目錄（唯讀，絕不修改 .jrxml）
 *  - dry-run=true 只做「轉譯 + 載入 + 編譯 + 載入 .jasper 驗證」，不寫任何檔
 *
 * 路徑欄位刻意用 String（非 java.nio.file.Path）：Spring 對 Path 屬性的
 * String→Path 轉換會把值當成資源路徑正規化，「..」開頭（如 ../LANDWEBAP）
 * 會拋 IllegalArgumentException 導致啟動失敗。Path 在 JasperRecompileService /
 * RecompileJobService 執行邊界才用 Paths.get() 解析（相對本程式工作目錄）。
 */
@ConfigurationProperties(prefix = "upj")
public record RecompileProperties(
        String sourceDir,
        String outDir,
        boolean inPlace,
        boolean dryRun,
        String reportFile
) {
}
