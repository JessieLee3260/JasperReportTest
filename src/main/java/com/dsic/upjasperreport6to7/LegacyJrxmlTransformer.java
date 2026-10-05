package com.dsic.upjasperreport6to7;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 以「記憶體中 DOM 重構」把舊版（iReport/JR6 世代）JRXML 轉成 JasperReports 7 新 XML 格式。
 * <p>
 * 結構規則（依 JR7 JacksonUtil 實際 writer 輸出驗證）：
 * <ul>
 *   <li>根元素去 xmlns / xsi:schemaLocation</li>
 *   <li>{@code <subDataset>}→{@code <dataset>}、{@code <queryString>}→{@code <query>}</li>
 *   <li>section 內的 {@code <band ...>} 展開合併到 section 元素（height/splitType 上提）</li>
 *   <li>band 裡的元素種類（textField/staticText/image/line/rectangle/frame/subreport/component…）
 *       一律包裝成 {@code <element kind="...">}；其內 {@code <reportElement>} 的幾何屬性上提到
 *       {@code <element>}，reportElement 的 {@code <box>} 等子元素保留</li>
 *   <li>元素級 expression 子元素統一改名 {@code <expression>}（textFieldExpression/imageExpression/…）</li>
 *   <li>dataset 層 variable/group 的 {@code variableExpression}/​{@code groupExpression}→{@code <expression>}，
 *       {@code fieldDescription} 子元素→屬性 description</li>
 *   <li>屬性：明確改名表 + 通用 {@code is*→*}（isPrintWhenDetailOverflows→printWhenDetailOverflows 等）</li>
 *   <li>刪除：{@code line}、{@code band} 屬性（舊 iReport 視覺輔助）</li>
 * </ul>
 */
public final class LegacyJrxmlTransformer {

    /** 明確屬性改名（舊→新）。 */
    private static final Map<String, String> ATTR_RENAMES = new LinkedHashMap<>();
    /** 必須刪除的屬性。 */
    private static final List<String> DROPPED_ATTRS = List.of("line", "band");
    /** 元素改名（舊→新）。 */
    private static final Map<String, String> ELEMENT_RENAMES = new LinkedHashMap<>();
    /** 元素級 expression 子元素統一改名為 expression。 */
    private static final List<String> EXPRESSION_CHILDREN = List.of(
            "textFieldExpression", "imageExpression", "subreportExpression",
            "variableExpression", "groupExpression", "parameterExpression"
    );
    /** section 名稱（含 group 的 header/footer）：其下的 <band> 要展開。 */
    private static final List<String> SECTIONS = List.of(
            "title", "pageHeader", "columnHeader", "detail", "columnFooter",
            "pageFooter", "lastPageFooter", "summary", "noData", "columnBreak",
            "groupHeader", "groupFooter"
    );
    /** band 內可出現的元素種類。 */
    private static final List<String> ELEMENT_KINDS = List.of(
            "textField", "staticText", "image", "line", "rectangle", "frame",
            "subreport", "component", "verticalBar", "horizontalBar", "anchor"
    );

    static {
        ATTR_RENAMES.put("isDefault", "default");
        ATTR_RENAMES.put("isPdfEmbedded", "pdfEmbedded");
        ATTR_RENAMES.put("isIgnorePagination", "ignorePagination");
        ATTR_RENAMES.put("isFloatColumnFooter", "floatColumnFooter");
        ATTR_RENAMES.put("isForPrompting", "forPrompting");
        ATTR_RENAMES.put("parameterDescription", "description");
        ATTR_RENAMES.put("hAlign", "hTextAlign");
        ATTR_RENAMES.put("vAlign", "vTextAlign");
        ATTR_RENAMES.put("fieldDescription", "description");
        ATTR_RENAMES.put("isStartNewPage", "startNewPage");
        ATTR_RENAMES.put("isStartNewColumn", "startNewColumn");

        ELEMENT_RENAMES.put("subDataset", "dataset");
        ELEMENT_RENAMES.put("queryString", "query");
    }

    public record Result(byte[] xml, List<String> applied) {
    }

    private LegacyJrxmlTransformer() {
    }

    /** 轉換結果：xml = 新格式字串；applied = 適用過的規則摘要（供報告/除錯）。 */
    public static Result transform(byte[] legacyXml) throws Exception {
        List<String> applied = new ArrayList<>();
        Document src = parse(legacyXml);
        Element root = src.getDocumentElement();

        Document out = newDocument();
        Element newRoot = out.createElement("jasperReport");
        copyAttributes(root, newRoot, out, applied);
        for (Node c : children(root)) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                newRoot.appendChild(convert(out, (Element) c, newRoot, applied));
            }
        }
        out.appendChild(newRoot);
        byte[] xml = serialize(out).getBytes(StandardCharsets.UTF_8);
        applied.add("dom-transform");
        return new Result(xml, applied);
    }

    /** 依元素名稱分派轉換；回傳新文件中的對應節點。 */
    private static Element convert(Document out, Element e, Element parent, List<String> applied) {
        String name = ELEMENT_RENAMES.getOrDefault(e.getLocalName(), e.getLocalName());
        Element ne = out.createElement(name);
        copyAttributes(e, ne, out, applied);

        switch (name) {
            case "parameter" -> {
                for (Node c : children(e)) {
                    if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element cc = (Element) c;
                    if (cc.getLocalName().equals("defaultValueExpression")) {
                        ne.appendChild(cloneText(out, cc, "defaultValueExpression"));
                    } else if (cc.getLocalName().equals("parameterExpression")) {
                        ne.appendChild(cloneText(out, cc, "expression"));
                    } else {
                        ne.appendChild(cloneRaw(out, cc, applied));
                    }
                }
            }
            case "field" -> {
                for (Node c : children(e)) {
                    if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element cc = (Element) c;
                    if (cc.getLocalName().equals("fieldDescription")) {
                        // 舊 <fieldDescription>文字</fieldDescription> → 新屬性 description
                        ne.setAttribute("description", textOf(cc));
                        applied.add("fieldDescription->attr:field");
                    } else {
                        ne.appendChild(cloneRaw(out, cc, applied));
                    }
                }
            }
            case "variable" -> {
                for (Node c : children(e)) {
                    if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element cc = (Element) c;
                    if (cc.getLocalName().equals("variableExpression")) {
                        ne.appendChild(cloneText(out, cc, "expression"));
                        applied.add("variableExpression->expression");
                    } else if (cc.getLocalName().equals("initialValueExpression")) {
                        ne.appendChild(cloneText(out, cc, "initialValueExpression"));
                    } else {
                        ne.appendChild(cloneRaw(out, cc, applied));
                    }
                }
            }
            case "group" -> {
                for (Node c : children(e)) {
                    if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element cc = (Element) c;
                    String cn = cc.getLocalName();
                    if (cn.equals("groupExpression")) {
                        ne.appendChild(cloneText(out, cc, "expression"));
                        applied.add("groupExpression->expression");
                    } else if (cn.equals("groupHeader") || cn.equals("groupFooter")) {
                        ne.appendChild(mergeBand(out, cc, applied));
                    } else {
                        ne.appendChild(cloneRaw(out, cc, applied));
                    }
                }
            }
            case "dataset" -> {
                for (Node c : children(e)) {
                    if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element cc = (Element) c;
                    switch (cc.getLocalName()) {
                        case "variable" -> ne.appendChild(convert(out, cc, ne, applied));
                        case "group" -> ne.appendChild(convert(out, cc, ne, applied));
                        case "field" -> ne.appendChild(convert(out, cc, ne, applied));
                        default -> ne.appendChild(cloneRaw(out, cc, applied));
                    }
                }
            }
            case "style" -> {
                for (Node c : children(e)) {
                    if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                    ne.appendChild(cloneRaw(out, (Element) c, applied));
                }
            }
            case "background", "noData" -> {
                mergeSectionBands(out, e, ne, applied);
            }
            default -> {
                if (SECTIONS.contains(name)) {
                    mergeSectionBands(out, e, ne, applied);
                } else {
                    for (Node c : children(e)) {
                        if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                        ne.appendChild(cloneRaw(out, (Element) c, applied));
                    }
                }
            }
        }
        return ne;
    }

    /** section（title/pageHeader/detail/...）：把 <band ...> 的屬性合併上提、展開。 */
    private static void mergeSectionBands(Document out, Element sectionEl, Element newSection, List<String> applied) {
        boolean sawBand = false;
        for (Node c : children(sectionEl)) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            if (ce.getLocalName().equals("band")) {
                sawBand = true;
                copyAttributes(ce, newSection, out, applied);   // height/splitType 上提
                for (Node b : children(ce)) {
                    if (b.getNodeType() != Node.ELEMENT_NODE) continue;
                    newSection.appendChild(convertElementOrRaw(out, (Element) b, applied));
                }
            } else {
                newSection.appendChild(convertElementOrRaw(out, ce, applied));
            }
        }
        if (sawBand) applied.add("band-unwrap:" + newSection.getNodeName());
    }

    /** band（或 frame）內的節點：元素種類包裝成 <element kind=...>，其餘保留。 */
    private static Element convertElementOrRaw(Document out, Element e, List<String> applied) {
        String kind = e.getLocalName();
        if (!ELEMENT_KINDS.contains(kind)) {
            return cloneRaw(out, e, applied);
        }
        Element el = out.createElement("element");
        el.setAttribute("kind", kind);
        copyAttributes(e, el, out, applied);

        // <reportElement> 的幾何/uuid 屬性上提；其子元素（box 等）保留到 element 下
        Element rep = firstChildElement(e, "reportElement");
        if (rep != null) {
            copyAttributes(rep, el, out, applied);
            applied.add("reportElement-hoist:" + kind);
            for (Node r : children(rep)) {
                if (r.getNodeType() == Node.ELEMENT_NODE) {
                    el.appendChild(cloneRaw(out, (Element) r, applied));
                }
            }
        }

        for (Node c : children(e)) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            String cn = ce.getLocalName();
            if (cn.equals("reportElement")) continue;
            if (EXPRESSION_CHILDREN.contains(cn)) {
                el.appendChild(cloneText(out, ce, "expression"));
                applied.add(cn + "->expression");
            } else if (ELEMENT_KINDS.contains(cn)) {
                // frame 巢狀元素
                el.appendChild(convertElementOrRaw(out, ce, applied));
            } else if (cn.equals("subreportParameter")) {
                Element p = out.createElement("subreportParameter");
                copyAttributes(ce, p, out, applied);
                for (Node pc : children(ce)) {
                    if (pc.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element pcc = (Element) pc;
                    if (pcc.getLocalName().equals("parameterExpression")) {
                        p.appendChild(cloneText(out, pcc, "expression"));
                        applied.add("subreportParameterExpression->expression");
                    } else {
                        p.appendChild(cloneRaw(out, pcc, applied));
                    }
                }
                el.appendChild(p);
            } else if (cn.equals("componentParameter")) {
                Element p = out.createElement("componentParameter");
                copyAttributes(ce, p, out, applied);
                p.appendChild(out.createTextNode(textOf(ce).trim()));
                el.appendChild(p);
            } else if (cn.equals("text")) {
                el.appendChild(cloneText(out, ce, "text"));
            } else {
                el.appendChild(cloneRaw(out, ce, applied));
            }
        }
        applied.add("element-wrap:" + kind);
        return el;
    }

    /** group 的 groupHeader/groupFooter：展開 band。 */
    private static Element mergeBand(Document out, Element e, List<String> applied) {
        Element ne = out.createElement(e.getLocalName());
        mergeSectionBands(out, e, ne, applied);
        return ne;
    }

    private static Element firstChildElement(Element parent, String name) {
        for (Node c : children(parent)) {
            if (c.getNodeType() == Node.ELEMENT_NODE && c.getLocalName().equals(name)) {
                return (Element) c;
            }
        }
        return null;
    }

    /** 複製屬性（含改名/刪除/通用 is* 規則）。 */
    private static void copyAttributes(Element src, Element dst, Document out, List<String> applied) {
        NamedNodeMap attrs = src.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String name = a.getName();
            if (name.equals("xmlns") || name.startsWith("xsi:") || name.equals("xmlns:xsi")) continue;
            if (DROPPED_ATTRS.contains(name)) {
                applied.add("drop-attr:" + name);
                continue;
            }
            String target = ATTR_RENAMES.get(name);
            if (target == null && name.startsWith("is") && name.length() > 2
                    && Character.isUpperCase(name.charAt(2))) {
                target = Character.toLowerCase(name.charAt(2)) + name.substring(3);
                applied.add("is*-attr:" + name + "->" + target);
            }
            dst.setAttribute(target == null ? name : target, a.getValue());
            if (target != null && !ATTR_RENAMES.containsKey(name)) applied.add("attr:" + name + "->" + target);
        }
    }

    /** 把舊元素原樣複製（含子元素遞迴，不含結構轉換）。 */
    private static Element cloneRaw(Document out, Element e, List<String> applied) {
        Element ne = out.createElement(e.getLocalName());
        copyAttributes(e, ne, out, applied);
        for (Node c : children(e)) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                ne.appendChild(cloneRaw(out, (Element) c, applied));
            } else if (c.getNodeType() == Node.TEXT_NODE) {
                String t = c.getTextContent();
                if (!t.trim().isEmpty()) ne.appendChild(out.createTextNode(t.trim()));
            }
        }
        return ne;
    }

    /** 舊元素的文字內容複製成指定名稱的 CDATA 元素。 */
    private static Element cloneText(Document out, Element e, String newName) {
        Element ne = out.createElement(newName);
        String t = textOf(e).trim();
        if (!t.isEmpty()) ne.appendChild(out.createCDATASection(t));
        return ne;
    }

    private static String textOf(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node c : children(e)) {
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            }
        }
        return sb.toString();
    }

    // ---- utilities ----

    private static List<Node> children(Node n) {
        List<Node> l = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) l.add(c);
        return l;
    }

    private static Document newDocument() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().newDocument();
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return f.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    private static String serialize(Document doc) {
        StringBuilder sb = new StringBuilder();
        writeNode(sb, doc.getDocumentElement(), 0);
        return sb.toString();
    }

    private static void writeNode(StringBuilder sb, Node n, int depth) {
        if (!(n instanceof Element el)) {
            if (n instanceof CDATASection c) sb.append("<![CDATA[").append(c.getTextContent()).append("]]>");
            else if (n instanceof Text t && !t.getTextContent().isBlank()) sb.append(t.getTextContent().trim());
            return;
        }
        sb.append("<").append(el.getNodeName());
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) sb.append(' ').append(attrs.item(i).getNodeName()).append("=\"").append(escape(attrs.item(i).getNodeValue())).append('"');
        int childCount = 0;
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE || c instanceof CDATASection) childCount++;
        }
        if (childCount == 0) {
            sb.append("/>");
            return;
        }
        sb.append(">");
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE && !(c instanceof CDATASection)) continue;
            sb.append('\n');
            writeNode(sb, c, depth + 1);
            sb.append('\n');
        }
        sb.append("</").append(el.getNodeName()).append(">");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
