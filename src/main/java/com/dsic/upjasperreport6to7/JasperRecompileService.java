package com.dsic.upjasperreport6to7;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public record BatchReport(int total, int ok, int failed, List<FileResult> failures, Path jasperOut, boolean written) {
    }

    public BatchReport run(RecompileProperties p) throws Exception {
        List<Path> files = listJrxml(p.sourceDir());
        log.info("jrxml files under {} = {}", p.sourceDir(), files.size());

        var ctx = DefaultJasperReportsContext.getInstance();
        var compileMgr = JasperCompileManager.getInstance(ctx);

        int okCount = 0;
        List<FileResult> failures = new ArrayList<>();
        List<FileResult> all = new ArrayList<>();

        for (Path jrxml : files) {
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
                    target = targetFor(jrxml, p);
                    Files.createDirectories(target.getParent());
                    Files.write(target, jasper);
                }

                okCount++;
                FileResult r = new FileResult(rel(p.sourceDir(), jrxml), true, jasper.length,
                        System.currentTimeMillis() - t0, target == null ? "verified" : "written", null);
                all.add(r);
                log.debug("OK {} ({} bytes, {}ms)", r.path(), jasper.length, r.ms());
            } catch (Exception e) {
                FileResult r = new FileResult(rel(p.sourceDir(), jrxml), false, 0,
                        System.currentTimeMillis() - t0, stageOf(e), String.valueOf(e.getMessage()));
                failures.add(r);
                all.add(r);
                log.warn("FAIL {} : {}", r.path(), e.getMessage());
            }
        }

        Path reportPath = p.reportFile().toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        var report = new BatchReport(files.size(), okCount, failures.size(), failures,
                p.outDir(), !p.dryRun());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), report);

        log.info("==== SUMMARY: total={} ok={} fail={} dryRun={} report={} ====",
                report.total(), report.ok(), report.failed(), p.dryRun(), reportPath);
        failures.stream().limit(20).forEach(f -> log.info("  FAIL [{}] {}", f.path(), f.error()));
        return report;
    }

    private static String stageOf(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("UnrecognizedProperty") || m.contains("Unrecognized field")) return "load(jackson)";
        if (m.contains("Unable to load report")) return "load";
        return "compile";
    }

    private Path targetFor(Path jrxml, RecompileProperties p) {
        if (p.inPlace()) {
            return jrxml.resolveSibling(jrxml.getFileName().toString().replaceFirst("\\.jrxml$", ".jasper"));
        }
        Path rel = p.sourceDir().relativize(jrxml);
        return p.outDir().resolve(rel).resolveSibling(
                jrxml.getFileName().toString().replaceFirst("\\.jrxml$", ".jasper"));
    }

    private static String rel(Path base, Path f) {
        try {
            return base.relativize(f).toString();
        } catch (Exception e) {
            return f.toString();
        }
    }

    private static List<Path> listJrxml(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(x -> x.toString().endsWith(".jrxml"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }
}
