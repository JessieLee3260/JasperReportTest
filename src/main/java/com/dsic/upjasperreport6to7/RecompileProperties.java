package com.dsic.upjasperreport6to7;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 批次重編譯參數。可用 application.properties 或 --upj.* 啟動參數覆蓋。
 *
 * 預設：
 *  - source-dir 指向 LANDWEBAP 的 WEB-INF 模板目錄（唯讀，絕不修改 .jrxml）
 *  - dry-run=true 只做「轉譯 + 載入 + 編譯 + 載入 .jasper 驗證」，不寫任何檔
 */
@ConfigurationProperties(prefix = "upj")
public record RecompileProperties(
        Path sourceDir,
        Path outDir,
        boolean inPlace,
        boolean dryRun,
        Path reportFile
) {
}
