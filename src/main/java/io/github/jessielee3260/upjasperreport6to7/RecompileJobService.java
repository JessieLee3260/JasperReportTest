package io.github.jessielee3260.upjasperreport6to7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 背景批次執行管理（Web UI 用）。
 * 同一時間只跑一組批次；第二組要求回 409（由 controller 轉譯）。
 * 逐檔結果透過 {@link JasperRecompileService#run(RecompileProperties, java.util.function.Consumer)} 即時更新進度。
 */
@Service
public class RecompileJobService {

    private static final Logger log = LoggerFactory.getLogger(RecompileJobService.class);

    public enum State { IDLE, RUNNING, DONE, FAILED, CANCELLED }

    /** 批次狀態快照（GET /api/status，UI 每 2s 輪詢）。 */
    public record JobStatus(
            State state,
            long startedAt,
            Long finishedAt,
            int total,
            int processed,
            int ok,
            int failed,
            int skipped,
            String currentPath,
            String sourceDir,
            String error
    ) {
    }

    /** 最近一組批次的結果摘要（GET /api/report；error!=null 代表批次例外）。 */
    public record BatchSnapshot(
            List<JasperRecompileService.FileResult> failures,
            Path reportFile,
            int total,
            int ok,
            int failed,
            int skipped,
            String error,
            boolean cancelled
    ) {
        static BatchSnapshot empty() {
            return new BatchSnapshot(List.of(), null, 0, 0, 0, 0, null, false);
        }

        boolean hasResult() {
            return total > 0;
        }
    }

    private final JasperRecompileService service;
    private final RecompileProperties defaults;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "upj-recompile");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<BatchSnapshot> lastRun = new AtomicReference<>(BatchSnapshot.empty());
    private final AtomicReference<RunningMeta> runningMeta = new AtomicReference<>(RunningMeta.empty());

    /** 執行中進度（執行緒安全計數器集合）。 */
    private record RunningMeta(long startedAt, Path sourceDir, int total,
                               AtomicInteger processed, AtomicInteger ok, AtomicInteger failed,
                               AtomicReference<String> currentPath) {
        static RunningMeta empty() {
            return new RunningMeta(0, null, 0, new AtomicInteger(), new AtomicInteger(),
                    new AtomicInteger(), new AtomicReference<>(null));
        }
    }

    public RecompileJobService(JasperRecompileService service, RecompileProperties defaults) {
        this.service = service;
        this.defaults = defaults;
    }

    /**
     * 軟取消：只設旗標，執行迴圈在「目前檔案跑完後」的下一個檔案之間檢查到旗標即停，
     * 剩餘檔案標記 skipped、報告照寫（避免中斷中斷出半套 .jasper）。
     * 回傳 false = 沒有批次執行中（no-op）。
     */
    public boolean requestCancel() {
        if (!running.get()) {
            return false;
        }
        cancelRequested.set(true);
        log.info("批次取消請求：跑完目前檔案後停止");
        return true;
    }

    /** 啟動背景批次。回傳 false = 已有批次執行中。 */
    public boolean start(RecompileRunRequest req) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        String src = req.sourceDir() != null && !req.sourceDir().isBlank() ? req.sourceDir() : defaults.sourceDir();
        // 輸出資料夾空 = .jasper 寫到來源資料夾（與 .jrxml 同位置，等同就地覆寫）
        boolean hasOut = req.outDir() != null && !req.outDir().isBlank();
        String out = hasOut ? req.outDir() : null;
        String repFile = req.reportFile() != null && !req.reportFile().isBlank() ? req.reportFile() : defaults.reportFile();
        Path sourceDir = Paths.get(src);
        Path outDir = out == null ? null : Paths.get(out);
        Path reportFile = Paths.get(repFile);
        boolean inPlace = req.inPlace() != null ? req.inPlace() : defaults.inPlace();
        boolean dryRun = req.dryRun() != null ? req.dryRun() : defaults.dryRun();
        boolean effectiveInPlace = inPlace || outDir == null;
        RecompileProperties runProps = new RecompileProperties(src, out, inPlace, dryRun, repFile);

        int total = JasperRecompileService.countJrxml(sourceDir);
        RunningMeta m = new RunningMeta(System.currentTimeMillis(), sourceDir, total,
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicReference<>(null));
        cancelRequested.set(false);
        runningMeta.set(m);
        log.info("job start: sourceDir={} outDir={} inPlace={} effectiveInPlace={} dryRun={} total={}",
                sourceDir, outDir == null ? "(空 = 來源同位置)" : outDir, inPlace, effectiveInPlace, dryRun, total);

        executor.submit(() -> {
            try {
                JasperRecompileService.BatchReport rep = service.run(runProps, r -> {
                    m.processed().incrementAndGet();
                    if (r.ok()) {
                        m.ok().incrementAndGet();
                    } else {
                        m.failed().incrementAndGet();
                    }
                    m.currentPath().set(r.path());
                }, () -> cancelRequested.get());
                lastRun.set(new BatchSnapshot(rep.failures(), reportFile, rep.total(), rep.ok(), rep.failed(),
                        rep.skipped(), null, rep.cancelled()));
                log.info("job done: total={} ok={} failed={} skipped={} cancelled={}",
                        rep.total(), rep.ok(), rep.failed(), rep.skipped(), rep.cancelled());
            } catch (Exception e) {
                log.error("job failed", e);
                lastRun.set(new BatchSnapshot(List.of(), reportFile, 0, 0, 0, 0, String.valueOf(e.getMessage()), false));
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** 目前批次狀態。 */
    public JobStatus status() {
        if (running.get()) {
            RunningMeta m = runningMeta.get();
            return new JobStatus(State.RUNNING, m.startedAt(), null,
                    m.total(), m.processed().get(), m.ok().get(), m.failed().get(), 0,
                    m.currentPath().get(), m.sourceDir().toString(), null);
        }
        BatchSnapshot s = lastRun.get();
        if (s.cancelled()) {
            return new JobStatus(State.CANCELLED, 0, null, s.total(), s.total() - s.skipped(), s.ok(), s.failed(),
                    s.skipped(), null, null, null);
        }
        if (s.error() != null) {
            return new JobStatus(State.FAILED, 0, null, 0, 0, 0, 0, 0, null, null, s.error());
        }
        if (s.hasResult()) {
            return new JobStatus(State.DONE, 0, null, s.total(), s.total(), s.ok(), s.failed(), 0, null, null, null);
        }
        return new JobStatus(State.IDLE, 0, null, 0, 0, 0, 0, 0, null, null, null);
    }

    /** 最近一組批次結果。 */
    public BatchSnapshot lastRun() {
        return lastRun.get();
    }
}
