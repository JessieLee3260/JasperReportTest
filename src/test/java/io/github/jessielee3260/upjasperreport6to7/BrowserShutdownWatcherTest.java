package io.github.jessielee3260.upjasperreport6to7;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 瀏覽器關閉監控的模式門控與關閉冪等測試（純邏輯，不真的關主 context）。 */
class BrowserShutdownWatcherTest {

    @Test
    void monitorActiveInWebMode() {
        assertTrue(new BrowserShutdownWatcher(null, new MockEnvironment()).isMonitorActive());
    }

    @Test
    void monitorDisabledInCliMode() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("upj.auto-run", "true");
        assertFalse(new BrowserShutdownWatcher(null, env).isMonitorActive());
    }

    @Test
    void monitorDisabledWhenIdleSecondsZero() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("upj.idle-shutdown-seconds", "0");
        assertFalse(new BrowserShutdownWatcher(null, env).isMonitorActive());
    }

    @Test
    void requestCloseIsIdempotentAndStopsContext() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            BrowserShutdownWatcher w = new BrowserShutdownWatcher(ctx, new MockEnvironment());
            w.requestClose("測試第一次");
            w.requestClose("測試第二次（冪等）"); // 第二次不例外、不重關
            awaitInactive(ctx);
        }
    }

    /** requestClose 在獨立緒延遲 500ms 關閉（讓 /api/close 先回 200）→ 輪詢等 context 關閉。 */
    private static void awaitInactive(ConfigurableApplicationContext ctx) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (ctx.isActive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(ctx.isActive(), "requestClose 後 context 應已關閉");
    }
}
