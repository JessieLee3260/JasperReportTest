package com.dsic.upjasperreport6to7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 啟動即批次執行：gradlew bootRun
 *  - 預設 dry-run（只驗證、不寫檔）
 *  - 正式寫 .jasper：  --upj.dry-run=false
 *  - 原地替換 .jasper：  --upj.dry-run=false --upj.in-place=true
 *  - 跳過批次（只起 context）： --upj.auto-run=false
 */
@Component
public class RecompileRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RecompileRunner.class);

    private final JasperRecompileService service;
    private final RecompileProperties props;
    private final boolean autoRun;

    public RecompileRunner(JasperRecompileService service, RecompileProperties props,
                           @org.springframework.beans.factory.annotation.Value("${upj.auto-run:true}") boolean autoRun) {
        this.service = service;
        this.props = props;
        this.autoRun = autoRun;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!autoRun) {
            log.info("upj.auto-run=false，跳過批次");
            return;
        }
        log.info("source-dir={} out-dir={} in-place={} dry-run={}",
                props.sourceDir(), props.outDir(), props.inPlace(), props.dryRun());
        JasperRecompileService.BatchReport r = service.run(props);
        if (r.failed() > 0) {
            log.warn("批次完成但有 {} 個失敗（明細見 {}）", r.failed(), props.reportFile());
            throw new IllegalStateException(r.failed() + " jrxml 編譯失敗，詳見 " + props.reportFile());
        }
        log.info("全部 {} 個 .jrxml 編譯成功{}", r.total(),
                props.dryRun() ? "（dry-run，未寫 .jasper）" : "，.jasper 已寫入");
    }
}
