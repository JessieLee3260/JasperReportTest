package io.github.jessielee3260.upjasperreport6to7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web UI API 端到端測試（真實 JR7 管線：轉譯→載入→編譯→驗證）。
 * 用極小 .jrxml（無 expression，免 JDT 表達式編譯）在暫存目錄跑 dry-run。
 * MockMvc 用 standaloneSetup 組裝真實 service 物件（Boot 4 移除了 web test 支援類別，
 * 避免依賴被拆走/改包的 autoconfigure 註解）。
 */
class RecompileApiControllerTest {

    private MockMvc mvc;
    private RecompileProperties props;
    private RecompileJobService jobs;

    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() {
        JasperRecompileService jasperService = new JasperRecompileService();
        props = new RecompileProperties(
                "../sample-reports/WEB-INF",
                "out-jasper-test",
                false,
                true,
                "recompile-report.json");
        jobs = new RecompileJobService(jasperService, props);
        // watcher 用 null ctx（單元測試不關 context）＋ MockEnvironment（web 模式，monitor daemon 无害）
        mvc = MockMvcBuilders.standaloneSetup(
                new RecompileApiController(jobs, props, new BrowserShutdownWatcher(null, new MockEnvironment()))).build();
    }

    private static String minimalJrxml() {
        return """
                <jasperReport name="t" columnCount="1">
                  <queryString language="json"><![CDATA[json]]></queryString>
                  <field name="t" class="java.lang.String"/>
                  <detail/>
                </jasperReport>
                """;
    }

    @Test
    void configExposesDefaults() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                // 來源/輸出資料夾預填刻意留空：空值分別沿用 upj.source-dir 預設、寫入來源資料夾
                .andExpect(jsonPath("$.sourceDir").value(""))
                .andExpect(jsonPath("$.outDir").value(""))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.inPlace").value(false));
    }

    @Test
    void browseListsSubdirectories() throws Exception {
        Path dir = Files.createTempDirectory("upj-browse");
        Files.createDirectories(dir.resolve("sub-a"));
        Files.createDirectories(dir.resolve("sub-b"));
        Files.writeString(dir.resolve("note.txt"), "file not listed");

        mvc.perform(get("/api/browse").param("path", dir.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(dir.toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.parent").isNotEmpty())
                .andExpect(jsonPath("$.dirs[0]").value(
                        dir.resolve("sub-a").toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.dirs[1]").value(
                        dir.resolve("sub-b").toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.home").isNotEmpty())
                .andExpect(jsonPath("$.roots").isNotEmpty());
    }

    @Test
    void browseRejectsMissingDirectory() throws Exception {
        mvc.perform(get("/api/browse").param("path", "C:\\no-such-dir-upj-test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void beatRecordsActivity() throws Exception {
        mvc.perform(post("/api/beat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void cancelWhenNoRunningBatchReturnsConflict() throws Exception {
        mvc.perform(post("/api/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void closeEndpointStopsApplication() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            BrowserShutdownWatcher watcher = new BrowserShutdownWatcher(ctx, new MockEnvironment());
            MockMvc m = MockMvcBuilders.standaloneSetup(
                    new RecompileApiController(jobs, props, watcher)).build();
            m.perform(post("/api/close"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closing").value(true));
            // 關閉在獨立緒延遲 500ms 執行 → 輪詢等待
            long deadline = System.currentTimeMillis() + 10_000;
            while (ctx.isActive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(ctx.isActive(), "POST /api/close 應已關閉 Spring context");
        }
    }

    @Test
    void scanListsJrxmlFiles() throws Exception {
        Path dir = Files.createTempDirectory("upj-scan");
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/sample.jrxml"), minimalJrxml());
        Files.writeString(dir.resolve("a/notes.txt"), "ignore me");

        mvc.perform(get("/api/scan").param("sourceDir", dir.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.files[0]").value("a/b/sample.jrxml"));
    }

    /** 輪詢 /api/status 直到 DONE/FAILED（上限 90s），回傳最後一次 state。 */
    private String awaitDone() throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        String state = "RUNNING";
        String lastBody = "";
        while (System.currentTimeMillis() < deadline) {
            lastBody = mvc.perform(get("/api/status"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            state = om.readTree(lastBody).get("state").asText();
            if (!state.equals("RUNNING")) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals("DONE", state, "批次應成功：\n" + lastBody);
        return state;
    }

    @Test
    void runDryRunEndToEndAndReport() throws Exception {
        Path dir = Files.createTempDirectory("upj-run");
        Files.writeString(dir.resolve("one.jrxml"), minimalJrxml());

        RecompileRunRequest req =
                new RecompileRunRequest(dir.toString(), "out-jasper-test", dir.resolve("report.json").toString(), false, true);
        mvc.perform(post("/api/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        awaitDone();

        // dry-run 不寫 .jasper；.jrxml 本身絕不被改寫
        assertTrue(Files.readString(dir.resolve("one.jrxml")).contains("queryString"),
                ".jrxml 不得被改寫");
        assertFalse(Files.exists(dir.resolve("one.jasper")), "dry-run 不得寫 .jasper");

        mvc.perform(get("/api/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void runBlankOutDirWritesJasperBesideSource() throws Exception {
        Path dir = Files.createTempDirectory("upj-inplace");
        Files.writeString(dir.resolve("one.jrxml"), minimalJrxml());

        // 輸出資料夾空 + 非 dry-run：.jasper 寫到來源資料夾（與 .jrxml 同位置）
        RecompileRunRequest req =
                new RecompileRunRequest(dir.toString(), "", dir.resolve("report.json").toString(), false, false);
        mvc.perform(post("/api/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        awaitDone();

        Path jasper = dir.resolve("one.jasper");
        assertTrue(Files.exists(jasper), "空輸出資料夾應把 .jasper 寫到來源資料夾");
        assertTrue(Files.size(jasper) > 0, ".jasper 應非空");
        assertFalse(Files.exists(dir.resolve("out-jasper")), "不該建立鏡像目錄");
        assertTrue(Files.readString(dir.resolve("one.jrxml")).contains("queryString"),
                ".jrxml 不得被改寫");
    }
}
