package com.dsic.upjasperreport6to7;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Legacy (JR6, iReport-era) .jrxml → JR 7.0.8 Jackson XML 模型 的「記憶體」轉譯器。
 * 只產生新字串，不碰任何檔案。
 *
 * 規則來源（2026-10-19 於 jr7-sandbox 以 JacksonReportLoader 錯誤訊息學習 +
 * JacksonUtil.writeXml round-trip 驗證）：
 *  1. 根元素去掉 sourceforge.net 名稱空間（JR7 Jackson 模型要求空名稱空間）
 *  2. 元素改名：subDataset → dataset、queryString → query（新格式 sub-dataset 併入 dataset 元素、
 *     查詢直接寫在 jasperReport 下的 query 元素，CDATA 內容保留）
 *  3. 屬性改名：JR7 把 isX 布林屬性改為小寫 x；hAlign/vAlign/parameterDescription 也有對映
 *  4. 無對映的舊屬性直接刪除（例如 band 的 line="true" 只是 iReport 視覺輔助線）
 *
 * 若出現新的 UnrecognizedPropertyException，把屬性加進 ATTR_RENAMES / DROPPED_ATTRS 再跑。
 */
public final class LegacyJrxmlTransformer {

    /** 舊屬性 → 新屬性（全域、以「空白+屬性名="」錨定，避免誤傷屬性值）。 */
    private static final List<String[]> ATTR_RENAMES = List.of(
            new String[]{"isDefault", "default"},                          // style
            new String[]{"isPdfEmbedded", "pdfEmbedded"},                 // style
            new String[]{"isIgnorePagination", "ignorePagination"},       // jasperReport root
            new String[]{"isFloatColumnFooter", "floatColumnFooter"},     // jasperReport root
            new String[]{"isForPrompting", "forPrompting"},               // parameter
            new String[]{"parameterDescription", "description"},          // parameter
            new String[]{"hAlign", "hTextAlign"},                        // style
            new String[]{"vAlign", "vTextAlign"},                        // style
            new String[]{"fieldDescription", "description"},            // field
            new String[]{"variableExpression", "expression"},           // variable
            new String[]{"isStartNewPage", "startNewPage"},             // group
            new String[]{"isStartNewColumn", "startNewColumn"}          // group
    );

    /** 新模型沒有對應、必須刪除的舊屬性。 */
    private static final List<String> DROPPED_ATTRS = List.of(
            "line",  // band 的 iReport 視覺輔助線屬性
            "band"   // band 元素舊屬性，無對應
    );

    private LegacyJrxmlTransformer() {
    }

    public record Result(byte[] xml, List<String> applied) {
    }

    public static Result transform(byte[] legacyXml) {
        String s = new String(legacyXml, StandardCharsets.UTF_8);
        List<String> applied = new ArrayList<>();

        // 1) 名稱空間相關屬性（全部只在根元素出現）
        s = replaceAllCounted(s, " xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"", "", applied, "xmlns-strip");
        s = replaceAllCounted(s, " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"", "", applied, "xmlns-xsi-strip");
        s = replaceAllCounted(s, " xsi:schemaLocation=\"http://jasperreports.sourceforge.net/jasperreports http://jasperreports.sourceforge.net/xsd/jasperreport.xsd\"", "", applied, "schemaLocation-strip");

        // 2) 元素改名
        s = replaceAllCounted(s, "<subDataset", "<dataset", applied, "el:subDataset->dataset");
        s = replaceAllCounted(s, "</subDataset>", "</dataset>", applied, "el:/subDataset->/dataset");
        s = replaceAllCounted(s, "<queryString", "<query", applied, "el:queryString->query");
        s = replaceAllCounted(s, "</queryString>", "</query>", applied, "el:/queryString->/query");

        // 3) 屬性改名
        for (String[] r : ATTR_RENAMES) {
            Pattern p = Pattern.compile("(?<= )(" + r[0] + ")=\"");
            s = p.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(" " + r[1] + "=\""));
        }

        // 4) 無對映屬性刪除
        for (String attr : DROPPED_ATTRS) {
            Pattern p = Pattern.compile(" (?:" + attr + ")=\"[^\"]*\"");
            s = p.matcher(s).replaceAll("");
        }

        return new Result(s.getBytes(StandardCharsets.UTF_8), applied);
    }

    private static String replaceAllCounted(String s, String from, String to, List<String> applied, String rule) {
        int count = countOccurrences(s, from);
        if (count > 0) {
            s = s.replace(from, to);
            applied.add(rule + " x" + count);
        }
        return s;
    }

    private static int countOccurrences(String s, String sub) {
        int c = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            c++;
            idx += sub.length();
        }
        return c;
    }
}
