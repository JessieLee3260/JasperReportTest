package io.github.jessielee3260.upjasperreport6to7;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Web UI REST API（GET /index.html 同源的 single-page 介面用）。
 * 批次本身背景執行；UI 以 2s 輪詢 /api/status。
 */
@RestController
@RequestMapping("/api")
public class RecompileApiController {

    private final RecompileJobService jobs;
    private final RecompileProperties defaults;
    private final BrowserShutdownWatcher watcher;

    public RecompileApiController(RecompileJobService jobs, RecompileProperties defaults, BrowserShutdownWatcher watcher) {
        this.jobs = jobs;
        this.defaults = defaults;
        this.watcher = watcher;
    }

    /** 目前預設設定（UI 表單初始化用）。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        // UI 預填刻意留空：來源資料夾空值 = 沿用 upj.source-dir 預設；
        // 搭配 /api/browse 目錄視窗讓使用者自行選目錄
        m.put("sourceDir", "");
        // UI 預填刻意留空：輸出資料夾空值 = 寫到來源資料夾（與 .jrxml 同位置）；
        // CLI 批次仍走 upj.out-dir 屬性預設（out-jasper）
        m.put("outDir", "");
        m.put("reportFile", defaults.reportFile());
        m.put("inPlace", defaults.inPlace());
        m.put("dryRun", defaults.dryRun());
        return m;
    }

    /**
     * 目錄瀏覽器：回傳「目前目錄」的子目錄、上一層與快速起點（home/磁碟根）。
     * 供 Web UI 的目錄視窗挑選伺服器端路徑（瀏覽器拿不到 server 端絕對路徑）。
     * 僅列出資料夾；讀取失敗回 400 + 白話 error。
     */
    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browse(@RequestParam(defaultValue = "") String path) {
        Path target;
        try {
            target = path.isBlank() ? Paths.get("").toAbsolutePath()
                    : Paths.get(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "無效路徑：" + e.getMessage()));
        }
        try {
            if (!Files.exists(target)) {
                return ResponseEntity.badRequest().body(Map.of("error", "目錄不存在：" + target));
            }
            if (!Files.isDirectory(target)) {
                return ResponseEntity.badRequest().body(Map.of("error", "不是目錄：" + target));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "無法存取路徑：" + e.getMessage()));
        }
        List<String> dirs;
        try (Stream<Path> s = Files.list(target).filter(Files::isDirectory)) {
            dirs = s.map(Path::toString).sorted().toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "無法讀取目錄：" + e.getMessage()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", target.toString());
        m.put("parent", target.getParent() == null ? "" : target.getParent().toString());
        m.put("dirs", dirs);
        m.put("home", System.getProperty("user.home"));
        m.put("roots", buildRoots());
        return ResponseEntity.ok(m);
    }

    /** 目錄視窗的快速起點：家目錄、目前工作目錄、各磁碟根（Windows）等。 */
    private static List<String> buildRoots() {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        roots.add(System.getProperty("user.home"));
        roots.add(Paths.get("").toAbsolutePath().toString());
        for (File root : File.listRoots()) {
            roots.add(root.getAbsolutePath());
        }
        return List.copyOf(roots);
    }

    /** 瀏覽器心跳：頁面開著時前端定期 POST，更新活動時間戳記（避免被閒置安全網誤停）。 */
    @PostMapping("/beat")
    public Map<String, Object> beat() {
        watcher.recordActivity();
        return Map.of("ok", true, "time", System.currentTimeMillis());
    }

    /** 瀏覽器關閉訊號（前端 pagehide sendBeacon）→ 停止 Spring Boot。 */
    @PostMapping("/close")
    public Map<String, Object> close() {
        watcher.requestClose("瀏覽器關閉訊號（POST /api/close）");
        return Map.of("closing", true);
    }

    /** 軟取消執行中批次：目前檔案跑完後停止，剩餘標記 skipped；409 = 沒有批次執行中。 */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel() {
        if (!jobs.requestCancel()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("ok", false, "reason", "目前沒有執行中的批次"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 預覽來源目錄的 .jrxml 清單（不執行任何轉換）。 */
    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(defaultValue = "") String sourceDir) {
        Path dir = Paths.get(sourceDir.isBlank() ? defaults.sourceDir() : sourceDir);
        List<String> files;
        try {
            files = JasperRecompileService.listJrxml(dir).stream()
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage()), "count", 0));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceDir", dir.toString());
        m.put("count", files.size());
        m.put("files", files);
        return ResponseEntity.ok(m);
    }

    /** 啟動背景批次；已在執行中回 409。 */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody RecompileRunRequest req) {
        boolean started = jobs.start(req);
        if (!started) {
            Map<String, Object> m = Map.of("accepted", false, "status", jobs.status().state().name());
            return ResponseEntity.status(409).body(m);
        }
        Map<String, Object> m = Map.of("accepted", true, "status", "RUNNING");
        return ResponseEntity.ok(m);
    }

    /** 批次狀態（UI 每 2s 輪詢；state: IDLE/RUNNING/DONE/FAILED）。 */
    @GetMapping("/status")
    public RecompileJobService.JobStatus status() {
        return jobs.status();
    }

    /** 最近一組批次的失敗明細；還沒跑過回 404。 */
    @GetMapping("/report")
    public ResponseEntity<RecompileJobService.BatchSnapshot> report() {
        RecompileJobService.BatchSnapshot s = jobs.lastRun();
        if (!s.hasResult() && s.error() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(s);
    }
}
