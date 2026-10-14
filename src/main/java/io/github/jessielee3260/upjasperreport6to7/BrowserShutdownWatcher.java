package io.github.jessielee3260.upjasperreport6to7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 瀏覽器關閉監控：web 模式（會自動開瀏覽器）下，瀏覽器關掉（或 crash）後
 * 停掉 Spring Boot，與「啟動後自動開啟瀏覽器」對稱。
 *
 * <ul>
 *   <li>前端 pagehide 時 sendBeacon「POST /api/close」→ {@link #requestClose} 立即停止</li>
 *   <li>安全網：/api 請求與心跳更新 lastActivity；monitor 每 5s 檢查，
 *       超過 upj.idle-shutdown-seconds（預設 180s）無活動 → 自動停止
 *       （防 sendBeacon 沒發出去，例如瀏覽器被強殺）</li>
 *   <li>CLI 批次模式（upj.auto-run=true）monitor 完全不啟動，批次跑完前不會被停掉</li>
 * </ul>
 *
 * 注意：批次執行緒（upj-recompile）為 daemon，ctx.close() 後 JVM 可正常結束。
 */
@Component
public class BrowserShutdownWatcher {

    private static final Logger log = LoggerFactory.getLogger(BrowserShutdownWatcher.class);

    private final ConfigurableApplicationContext ctx;
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final ScheduledExecutorService monitor;
    private final boolean monitorActive;

    public BrowserShutdownWatcher(ConfigurableApplicationContext ctx, Environment env) {
        this.ctx = ctx;
        boolean webMode = UpJasperReport6To7Application.shouldOpenBrowser(env);
        int idleSeconds = Integer.parseInt(env.getProperty("upj.idle-shutdown-seconds", "180"));
        if (webMode && idleSeconds > 0) {
            ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upj-browser-shutdown-monitor");
                t.setDaemon(true);
                return t;
            });
            s.scheduleWithFixedDelay(() -> {
                long idleMs = System.currentTimeMillis() - lastActivity.get();
                if (idleMs >= idleSeconds * 1000L) {
                    requestClose("瀏覽器關閉：已 " + (idleMs / 1000) + " 秒無 /api 活動");
                }
            }, 5, 5, TimeUnit.SECONDS);
            this.monitor = s;
            this.monitorActive = true;
            log.info("瀏覽器關閉監控啟用：{} 秒無 /api 活動將自動停止 Spring Boot", idleSeconds);
        } else {
            this.monitor = null;
            this.monitorActive = false;
            log.info("瀏覽器關閉監控停用（{}）", webMode ? "idle-shutdown-seconds<=0" : "cli 批次模式");
        }
    }

    /** 任何 /api 請求（filter）或心跳到時更新活動時間。 */
    public void recordActivity() {
        lastActivity.set(System.currentTimeMillis());
    }

    /**
     * 停止 Spring Boot（冪等：只有第一次有效）。ctx 為 null（測試用）時只記錄不關閉。
     * <p>關閉動作放到獨立 daemon 緒延遲 500ms 執行：讓觸發來源（例如 /api/close 的 Tomcat 請求緒）
     * 先回完 response，再 ctx.close()。若在同一緒 close，graceful shutdown 會等「所有 active
     * 請求完成」（包含自己）→ 卡到 connector 強停 → JVM 被 kill、exit code -1。
     * 批次執行緒（upj-recompile）為 daemon，close 後 JVM 可正常結束（exit 0）。
     */
    public void requestClose(String reason) {
        if (closing.compareAndSet(false, true)) {
            log.info("停止 Spring Boot：{}", reason);
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(500); // 讓 /api/close 等回應先發出
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (ctx != null) {
                    ctx.close();
                }
            }, "upj-browser-shutdown");
            t.setDaemon(true);
            t.start();
        }
    }

    /** monitor 是否啟用（web 模式且 idle-shutdown-seconds>0）。 */
    public boolean isMonitorActive() {
        return monitorActive;
    }

    @PreDestroy
    public void destroy() {
        if (monitor != null) {
            monitor.shutdownNow();
        }
    }
}
