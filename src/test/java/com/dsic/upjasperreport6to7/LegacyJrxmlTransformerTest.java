package com.dsic.upjasperreport6to7;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyJrxmlTransformerTest {

    @Test
    void preservesJsonQueryTextCdata() throws Exception {
        String legacy = """
                <jasperReport name="t" columnCount="1">
                  <dataset name="main">
                    <queryString language="json"><![CDATA[json]]></queryString>
                    <field name="t" class="java.lang.String"/>
                  </dataset>
                  <detail/>
                </jasperReport>
                """;
        LegacyJrxmlTransformer.Result r =
                LegacyJrxmlTransformer.transform(legacy.getBytes(StandardCharsets.UTF_8));
        String xml = new String(r.xml(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<query"), "應產出 <query> 元素：\n" + xml);
        assertTrue(xml.contains("<![CDATA[json]]>") || xml.contains(">json<"),
                "query 文字 json 應被保留：\n" + xml);
    }

    @Test
    void preservesReportLevelJsonQueryTextCdata() throws Exception {
        // doccontent 格式：main dataset 元素直接在 jasperReport 根層
        String legacy = """
                <jasperReport name="t" columnCount="1">
                  <queryString language="json"><![CDATA[json]]></queryString>
                  <field name="t" class="java.lang.String"/>
                </jasperReport>
                """;
        LegacyJrxmlTransformer.Result r =
                LegacyJrxmlTransformer.transform(legacy.getBytes(StandardCharsets.UTF_8));
        String xml = new String(r.xml(), StandardCharsets.UTF_8);
        String pattern = "(?s).*<query language=\"json\">\\s*<\\!\\[CDATA\\[json\\]\\]>\\s*</query>.*";
        assertTrue(xml.matches(pattern), "report 層 query 文字 json 應被保留：\n" + xml);
    }

    @Test
    void preservesSubDatasetJsonQueryTextCdata() throws Exception {
        String legacy = """
                <jasperReport name="t" columnCount="1">
                  <detail/>
                  <subDataset name="DatasetReceiver">
                    <queryString language="json"><![CDATA[]]></queryString>
                    <field name="t" class="java.lang.String"/>
                  </subDataset>
                </jasperReport>
                """;
        LegacyJrxmlTransformer.Result r =
                LegacyJrxmlTransformer.transform(legacy.getBytes(StandardCharsets.UTF_8));
        String xml = new String(r.xml(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<query"), "subDataset 應轉為含 <query> 的 dataset：\n" + xml);
    }
}
