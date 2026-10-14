package io.github.jessielee3260.upjasperreport6to7;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 軟取消（requestCancel / 執行迴圈旗標）行為測試。 */
class RecompileJobServiceCancelTest {

    @TempDir
    Path tmp;

    private JasperRecompileService service;
    private RecompileJobService jobs;

    private static String minimalJrxml() {
        return """
                <jasperReport name="t" columnCount="1">
                  <queryString language="json"><![CDATA[json]]></queryString>
                  <field name="t" class="java.lang.String"/>
                  <detail/>
                </jasperReport>
                """;
    }

    private void writeJrxmls(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            Path f = tmp.resolve(String.format("f%04d.jrxml", i));
            Files.writeString(f, minimalJrxml());
        }
    }

    private RecompileRunRequest req() {
        return new RecompileRunRequest(tmp.toString(), "", tmp.resolve("report.json").toString(), false, true);
    }

    @BeforeEach
    void setup() {
        service = new JasperRecompileService();
        jobs = new RecompileJobService(service,
                new RecompileProperties(tmp.toString(), "", false, true, "report.json"));
    }

    @Test
    void cancelWhenIdleIsNoOp() {
        assertFalse(jobs.requestCancel(), "沒有批次執行中時取消應為 no-op");
    }

    @Test
    void serviceRunHonoursCancelCheckBetweenFiles() throws Exception {
        writeJrxmls(3);
        AtomicInteger processed = new AtomicInteger();
        var rep = service.run(runProps(), r -> processed.incrementAndGet(),
                () -> processed.get() >= 1); // 處理完第 1 檔後即在下一檔案間停止
        assertTrue(rep.cancelled());
        assertEquals(1, rep.ok());
        assertEquals(2, rep.skipped());
        assertEquals(3, rep.total());
    }

    @Test
    void cancelWhileRunningYieldsCancelledState() throws Exception {
        writeJrxmls(600);
        assertTrue(jobs.start(req()));
        // 等到 RUNNING（= 執行緒任務進行中）
        awaitState(jobs, s -> s == RecompileJobService.State.RUNNING, 10_000);
        assertTrue(jobs.requestCancel());
        // 等終態
        awaitState(jobs, s -> terminal(s), 30_000);
        var st = jobs.status();
        assertEquals(RecompileJobService.State.CANCELLED, st.state());
        assertEquals(600, st.processed() + st.skipped(), "已處理＋跳過 = 總數");
        assertTrue(st.skipped() > 0, "應有剩餘檔案被標記跳過");
    }

    private void awaitState(RecompileJobService jobs,
                            java.util.function.Predicate<RecompileJobService.State> pred, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!pred.test(jobs.status().state()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private static boolean terminal(RecompileJobService.State s) {
        return s == RecompileJobService.State.DONE
                || s == RecompileJobService.State.FAILED
                || s == RecompileJobService.State.CANCELLED;
    }

    private RecompileProperties runProps() {
        return new RecompileProperties(tmp.toString(), "", false, true, tmp.resolve("report.json").toString());
    }
}
