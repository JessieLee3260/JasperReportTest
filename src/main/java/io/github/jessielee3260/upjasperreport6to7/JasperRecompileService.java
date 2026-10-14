package io.github.jessielee3260.upjasperreport6to7;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 批次編譯：走訪 .jrxml（唯讀）→ 記憶體轉譯 → JR7 載入 → 編譯 .jasper → 載入驗證。
 * dry-run 時不寫任何檔；正式跑時寫到 outDir 鏡像目錄（或 --in-place 原地替換 .jasper）。
 */
@Service
public class JasperRecompileService {

    private static final Logger log = LoggerFactory.getLogger(JasperRecompileService.class);

    /** 單一檔案結果。stage：transform / load / compile / verify / write。 */
    public record FileResult(String path, boolean ok, int jasperBytes, long ms, String stage, String error) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BatchReport(int total, int ok, int failed, List<FileResult> failures, Path jasperOut, boolean written, int skipped, boolean cancelled) {
    }

    /** CLI 批次：不帶逐檔回呼。 */
    public BatchReport run(RecompileProperties p) throws Exception {
        return run(p, r -> {
        });
    }

    /** 可回傳逐檔結果的回呼版本（Web UI 進度追蹤用）；每處理完一檔即回呼該檔結果。 */
    public BatchReport run(RecompileProperties p, Consumer<FileResult> onFile) throws Exception {
        return run(p, onFile, () -> false);
    }

    /**
     * Web UI 版（帶軟取消）：{@code checkCancelled} 在「每個檔案之間」評估，回 true 即停止、
     * 剩餘檔案標記為 skipped（已處理的部分保留，報告照寫）。不中斷檔案處理中段，
     * 避免寫出半套 .jasper。
     */
    public BatchReport run(RecompileProperties p, Consumer<FileResult> onFile, BooleanSupplier checkCancelled) throws Exception {
        Path sourceDir = Paths.get(p.sourceDir());
        // outDir 空/null = .jasper 寫到來源 .jrxml 同位置（等同就地覆寫）
        Path outDir = (p.outDir() == null || p.outDir().isBlank()) ? null : Paths.get(p.outDir());
        Path reportFile = Paths.get(p.reportFile());
        List<Path> files = listJrxml(sourceDir);
        log.info("jrxml files under {} = {}", sourceDir, files.size());

        var ctx = DefaultJasperReportsContext.getInstance();
        String compilerClasspath = CompilerClasspath.apply(ctx);
        log.info("JR compiler classpath entries = {}", compilerClasspath == null ? 0 : compilerClasspath.split(java.io.File.pathSeparator).length);
        var compileMgr = JasperCompileManager.getInstance(ctx);

        int okCount = 0;
        int processed = 0;
        boolean cancelled = false;
        List<FileResult> failures = new ArrayList<>();
        List<FileResult> all = new ArrayList<>();

        ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader compilerClassLoader = CompilerClasspath.newClassLoader();
        if (compilerClassLoader != null && compilerClassLoader != originalContextClassLoader) {
            Thread.currentThread().setContextClassLoader(compilerClassLoader);
        }
        try {
        for (Path jrxml : files) {
            if (checkCancelled.getAsBoolean()) {
                cancelled = true;
                break; // 軟取消：剩餘檔案不處理
            }
            long t0 = System.currentTimeMillis();
            try {
                byte[] raw = Files.readAllBytes(jrxml);
                LegacyJrxmlTransformer.Result tr = LegacyJrxmlTransformer.transform(raw);

                JasperDesign design;
                try (InputStream is = new ByteArrayInputStream(tr.xml())) {
                    design = JRXmlLoader.load(ctx, is);
                }

                byte[] jasper;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                compileMgr.compileToStream(design, bos);
                jasper = bos.toByteArray();

                // round-trip：確認產出的 .jasper 真的能被 JR7 載入
                try (InputStream jis = new ByteArrayInputStream(jasper)) {
                    Object loaded = JRLoader.loadObject(jis);
                    if (!(loaded instanceof JasperReport)) {
                        throw new IllegalStateException("round-trip loaded " + loaded);
                    }
                }

                Path target = null;
                if (!p.dryRun()) {
                    target = targetFor(jrxml, sourceDir, outDir, p.inPlace());
                    Files.createDirectories(target.getParent());
                    Files.write(target, jasper);
                }

                okCount++;
                FileResult r = new FileResult(rel(sourceDir, jrxml), true, jasper.length,
                        System.currentTimeMillis() - t0, target == null ? "verified" : "written", null);
                all.add(r);
                onFile.accept(r);
                processed++;
                log.debug("OK {} ({} bytes, {}ms)", r.path(), jasper.length, r.ms());
            } catch (Exception e) {
                FileResult r = new FileResult(rel(sourceDir, jrxml), false, 0,
                        System.currentTimeMillis() - t0, stageOf(e), String.valueOf(e.getMessage()));
                failures.add(r);
                all.add(r);
                onFile.accept(r);
                processed++;
                log.warn("FAIL {} : {}", r.path(), e.getMessage());
            }
        }

        Path reportPath = reportFile.toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        int skipped = files.size() - processed;
        var report = new BatchReport(files.size(), okCount, failures.size(), failures,
                outDir, !p.dryRun(), skipped, cancelled);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), report);

        log.info("==== SUMMARY: total={} ok={} fail={} skipped={} cancelled={} dryRun={} report={} ====",
                report.total(), report.ok(), report.failed(), skipped, cancelled, p.dryRun(), reportPath);
        failures.stream().limit(20).forEach(f -> log.info("  FAIL [{}] {}", f.path(), f.error()));
        return report;
        } finally {
            if (compilerClassLoader != originalContextClassLoader) {
                Thread.currentThread().setContextClassLoader(originalContextClassLoader);
            }
        }
    }

    private static String stageOf(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("UnrecognizedProperty") || m.contains("Unrecognized field")) return "load(jackson)";
        if (m.contains("Unable to load report")) return "load";
        return "compile";
    }

    private static Path targetFor(Path jrxml, Path sourceDir, Path outDir, boolean inPlace) {
        if (inPlace || outDir == null) {
            return jrxml.resolveSibling(jrxml.getFileName().toString().replaceFirst("\\.jrxml$", ".jasper"));
        }
        Path rel = sourceDir.relativize(jrxml);
        return outDir.resolve(rel).resolveSibling(
                jrxml.getFileName().toString().replaceFirst("\\.jrxml$", ".jasper"));
    }

    private static String rel(Path base, Path f) {
        try {
            // 統一用 '/' 分隔（JSON 報告、UI 顯示跨平台一致）
            return base.relativize(f).toString().replace('\\', '/');
        } catch (Exception e) {
            return f.toString().replace('\\', '/');
        }
    }

    /** 列舉目錄下 .jrxml（Web UI scan 用；同 package 可見）。 */
    static List<Path> listJrxml(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(x -> x.toString().endsWith(".jrxml"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    /** 統計目錄下 .jrxml 檔數（供 Web UI 進度列）；目錄不存在回 0。 */
    static int countJrxml(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return (int) s.filter(x -> x.toString().endsWith(".jrxml")).count();
        } catch (Exception e) {
            return 0;
        }
    }
}
