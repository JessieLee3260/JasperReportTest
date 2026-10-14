package io.github.jessielee3260.upjasperreport6to7;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 啟動後自動開啟瀏覽器的守衛邏輯（UpJasperReport6To7Application.shouldOpenBrowser）。
 * 純邏輯測試，不實際開啟瀏覽器（Desktop/rundll32 有副作用，不測）。
 */
class UpJasperReport6To7ApplicationBrowserTest {

    @Test
    void webModeOpensBrowserByDefault() {
        assertTrue(UpJasperReport6To7Application.shouldOpenBrowser(new MockEnvironment()));
    }

    @Test
    void cliAutoRunModeDoesNotOpenBrowser() {
        MockEnvironment env = new MockEnvironment().withProperty("upj.auto-run", "true");
        assertFalse(UpJasperReport6To7Application.shouldOpenBrowser(env));
    }

    @Test
    void openBrowserCanBeDisabled() {
        MockEnvironment env = new MockEnvironment().withProperty("upj.open-browser", "false");
        assertFalse(UpJasperReport6To7Application.shouldOpenBrowser(env));
    }

    @Test
    void explicitOpenBrowserTrueStillGatedByAutoRun() {
        MockEnvironment off = new MockEnvironment()
                .withProperty("upj.open-browser", "true")
                .withProperty("upj.auto-run", "true");
        assertFalse(UpJasperReport6To7Application.shouldOpenBrowser(off));

        MockEnvironment on = new MockEnvironment()
                .withProperty("upj.open-browser", "true");
        assertTrue(UpJasperReport6To7Application.shouldOpenBrowser(on));
    }
}
