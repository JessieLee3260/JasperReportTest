package io.github.jessielee3260.upjasperreport6to7;

/**
 * Web UI 的批次執行參數（POST /api/run）。
 * 欄位皆為可選：null 時 fallback 到 application.properties 的 upj.* 預設值。
 */
public record RecompileRunRequest(
        String sourceDir,
        String outDir,
        String reportFile,
        Boolean inPlace,
        Boolean dryRun
) {
}
