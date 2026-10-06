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
import java.util.Set;

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
    /** stretchType 舊值→JR7 合法值。 */
    private static final Map<String, String> STRETCH_TYPE_VALUES = Map.of(
            "RelativeToBandHeight", "CONTAINER_HEIGHT",
            "RelativeToTallest", "ELEMENT_GROUP_HEIGHT",
            "RelativeToTallestObject", "ELEMENT_GROUP_HEIGHT",
            "RelativeToParent", "CONTAINER_BOTTOM"
    );
    /** 舊 <textElement> 屬性→element 屬性。 */
    private static final Map<String, String> TEXT_ELEMENT_ATTRS = Map.of(
            "textAlignment", "hTextAlign",
            "verticalAlignment", "vTextAlign"
    );
    /** 舊 <font> 屬性→element 屬性。 */
    private static final Map<String, String> FONT_TO_ELEMENT = Map.of(
            "size", "fontSize",
            "isBold", "bold",
            "isItalic", "italic",
            "isUnderline", "underline",
            "isStrikeThrough", "strikeThrough",
            "isPdfEmbedded", "pdfEmbedded"
    );
    /** 元素改名（舊→新）。 */
    private static final Map<String, String> ELEMENT_RENAMES = new LinkedHashMap<>();
    /** 元素級 expression 子元素統一改名為 expression。 */
    private static final List<String> EXPRESSION_CHILDREN = List.of(
            "textFieldExpression", "imageExpression", "subreportExpression",
            "variableExpression", "groupExpression", "parameterExpression"
    );
    /** section 名稱：直接屬性型（band 展開、屬性上提；JasperDesign 有 JRBand setter）。 */
    private static final List<String> SECTIONS_DIRECT = List.of(
            "title", "pageHeader", "columnHeader", "columnFooter",
            "pageFooter", "lastPageFooter", "summary", "noData", "background"
    );
    /** section 名稱：band 子元素型（<band> 保留為子元素；property/section 管理）。 */
    private static final List<String> SECTIONS_BAND_CHILD = List.of(
            "detail", "columnBreak", "groupHeader", "groupFooter"
    );
    /** band 內可出現的元素種類。 */
    private static final List<String> ELEMENT_KINDS = List.of(
            "textField", "staticText", "image", "line", "rectangle", "ellipse", "frame",
            "subreport", "component", "break", "verticalBar", "horizontalBar", "anchor"
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
        out.appendChild(newRoot); // 先附到 document，避免 Xerces 重複 append 拋 HIERARCHY_REQUEST_ERR
        for (Node c : children(root)) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            // main dataset 的 group/field/variable 都留在根層（jackson canonical，Rt5 round-trip 驗證）
            newRoot.appendChild(convert(out, ce, newRoot, applied));
        }
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
                    } else if (cc.getLocalName().equals("parameterDescription")) {
                        String desc = textOf(cc).trim();
                        if (!desc.isEmpty()) ne.setAttribute("description", desc);
                        applied.add("parameterDescription->description");
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
                        ne.appendChild(convertBandChildSection(out, cc, applied));
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
                        case "queryString" -> {
                            // JR7 dataset 用 <query>（jackson 已知屬性 query）
                            ne.appendChild(convert(out, cc, ne, applied));
                            applied.add("queryString->query");
                        }
                        default -> ne.appendChild(cloneRaw(out, cc, applied));
                    }
                }
            }
            case "query" -> {
                // JR6 主 dataset 的 <queryString> 在 jasperReport 根層；
                // 保留 CDATA/文字（JsonDataSource 靠 query text 找 JSON 路徑，
                // 丟掉文字會造成 language=json 資料集 0 筆/欄位 null）
                for (Node c : children(e)) {
                    if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                        String t = c.getTextContent();
                        if (!t.trim().isEmpty()) ne.appendChild(out.createCDATASection(t));
                    } else if (c.getNodeType() == Node.ELEMENT_NODE) {
                        ne.appendChild(cloneRaw(out, (Element) c, applied));
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
                if (SECTIONS_DIRECT.contains(name)) {
                    mergeSectionBands(out, e, ne, applied);
                } else if (SECTIONS_BAND_CHILD.contains(name)) {
                    convertBandChildSectionInto(out, e, ne, applied);
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
        if (kind.equals("componentElement")) {
            return convertComponentElement(out, e, applied);
        }
        if (kind.equals("barChart")) {
            return convertLegacyBarChartPlaceholder(out, e, applied);
        }
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
                // 舊 <subreportParameter name=...><subreportParameterExpression> → JR7 <parameter name=...><expression>
                Element p = out.createElement("parameter");
                copyAttributes(ce, p, out, applied);
                for (Node pc : children(ce)) {
                    if (pc.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element pcc = (Element) pc;
                    if (pcc.getLocalName().equals("parameterExpression") || pcc.getLocalName().equals("subreportParameterExpression")) {
                        p.appendChild(cloneText(out, pcc, "expression"));
                        applied.add("subreportParameterExpression->expression");
                    } else {
                        p.appendChild(cloneRaw(out, pcc, applied));
                    }
                }
                el.appendChild(p);
                applied.add("subreportParameter->parameter");
            } else if (cn.equals("componentParameter")) {
                Element p = out.createElement("componentParameter");
                copyAttributes(ce, p, out, applied);
                p.appendChild(out.createTextNode(textOf(ce).trim()));
                el.appendChild(p);
            } else if (cn.equals("text")) {
                el.appendChild(cloneText(out, ce, "text"));
            } else if (cn.equals("textElement")) {
                hoistTextElement(out, el, ce, applied);
            } else if (cn.equals("parameterDescription")) {
                // 舊 <parameterDescription>（描述文字）：JR7 無對應 → 刪除並記錄
                applied.add("drop-parameterDescription");
            } else if (cn.equals("graphicElement")) {
                // 舊 <graphicElement><pen .../></graphicElement>：line/rectangle 接受 <pen> 直屬子；其他 kind 不支援 → 刪除並記錄
                if (kind.equals("line") || kind.equals("rectangle")) {
                    for (Node gc : children(ce)) {
                        if (gc.getNodeType() == Node.ELEMENT_NODE && "pen".equals(((Element) gc).getLocalName())) {
                            el.appendChild(cloneRaw(out, (Element) gc, applied));
                        }
                    }
                    applied.add("graphicElement-pen-hoist:" + kind);
                } else {
                    applied.add("drop-graphicElement:" + kind);
                }
            } else {
                el.appendChild(cloneRaw(out, ce, applied));
            }
        }
        applied.add("element-wrap:" + kind);
        return el;
    }

    /** 舊 legacy barChart 在 JR7 jackson classpath 無對應 type；保留 reportElement 幾何並轉為 frame 佔位。 */
    private static Element convertLegacyBarChartPlaceholder(Document out, Element e, List<String> applied) {
        Element el = out.createElement("element");
        el.setAttribute("kind", "frame");
        Element chart = firstChildElement(e, "chart");
        Element rep = chart == null ? null : firstChildElement(chart, "reportElement");
        if (rep != null) {
            copyAttributes(rep, el, out, applied);
            applied.add("reportElement-hoist:barChart");
        }
        Element prop = out.createElement("property");
        prop.setAttribute("name", "upjasperreport6to7.legacyBarChart.placeholder");
        prop.setAttribute("value", "true");
        el.appendChild(prop);
        applied.add("barChart->frame-placeholder");
        return el;
    }

    /** 舊 JasperReports componentElement → JR7 <element kind="component"><component kind="...">。
     * barcode 元件（bbq）另加 type 屬性：JR7 的 StandardBarbecueComponent 靠 type 選 barcode provider。 */
    private static final Set<String> BARBEQUE_TYPES = Set.of(
            "Barcode2of7", "Barcode3of9", "Bookland", "Codabar", "Code128", "Code128A", "Code128B",
            "Code128C", "Code39", "Code39Extended", "EAN128", "EAN13", "GlobalTradeItemNumber",
            "Int2of5", "Monarch", "NW7", "PDF417", "PostNet", "RandomWeightUPCA", "SCC14",
            "ShipmentIdentificationNumber", "SSCC18", "Std2of5", "UCC128", "UPCA", "USD3", "USD4", "USPS");

    /** 舊 JasperReports componentElement → JR7 <element kind="component"><component kind="...">。 */
    private static Element convertComponentElement(Document out, Element e, List<String> applied) {
        Element el = out.createElement("element");
        el.setAttribute("kind", "component");
        Element rep = firstChildElement(e, "reportElement");
        if (rep != null) {
            copyAttributes(rep, el, out, applied);
            applied.add("reportElement-hoist:componentElement");
        }
        for (Node c : children(e)) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            if (ce.getLocalName().equals("reportElement")) continue;
            Element comp = out.createElement("component");
            comp.setAttribute("kind", ce.getLocalName());
            if (BARBEQUE_TYPES.contains(ce.getLocalName())) {
                comp.setAttribute("type", ce.getLocalName());
                applied.add("barcode-type:" + ce.getLocalName());
            }
            copyAttributes(ce, comp, out, applied);
            if (BARBEQUE_TYPES.contains(ce.getLocalName())) {
                // legacy BBQ 屬性名與 JR7 StandardBarbecueComponent 不符，必須對映，
                // 否則 jackson UnrecognizedPropertyException：
                //   moduleWidth(float pt) → barWidth(Integer)；textPosition → drawText(bool)；
                //   quietZone/verticalQuietZone/horizontalQuietZone 在 JR7 無對應 → 刪除
                String mw = ce.getAttribute("moduleWidth");
                if (!mw.isEmpty()) {
                    comp.setAttribute("barWidth", String.valueOf((int) Math.round(Double.parseDouble(mw))));
                    applied.add("barcode-mw->barWidth:" + mw);
                }
                String tp = ce.getAttribute("textPosition");
                if (!tp.isEmpty()) {
                    comp.setAttribute("drawText", tp.equals("none") ? "false" : "true");
                    applied.add("barcode-textPosition->drawText:" + tp);
                }
                for (String q : new String[] { "moduleWidth", "textPosition", "quietZone",
                        "verticalQuietZone", "horizontalQuietZone" }) {
                    if (ce.hasAttribute(q)) comp.removeAttribute(q);
                }
            }
            for (Node gc : children(ce)) {
                if (gc.getNodeType() == Node.ELEMENT_NODE) comp.appendChild(cloneRaw(out, (Element) gc, applied));
            }
            el.appendChild(comp);
            applied.add("componentElement->component:" + ce.getLocalName());
        }
        return el;
    }

    /** group 的 groupHeader/groupFooter 或 detail：保留 <band> 子元素，只轉換內部元素。 */
    private static Element convertBandChildSection(Document out, Element e, List<String> applied) {
        Element ne = out.createElement(e.getLocalName());
        convertBandChildSectionInto(out, e, ne, applied);
        return ne;
    }

    /** 把舊 section 內的 <band>（含屬性）搬進已建立的 section 元素。 */
    private static void convertBandChildSectionInto(Document out, Element e, Element newSection, List<String> applied) {
        for (Node c : children(e)) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            if (ce.getLocalName().equals("band")) {
                Element b = out.createElement("band");
                copyAttributes(ce, b, out, applied);
                for (Node bnd : children(ce)) {
                    if (bnd.getNodeType() != Node.ELEMENT_NODE) continue;
                    b.appendChild(convertElementOrRaw(out, (Element) bnd, applied));
                }
                newSection.appendChild(b);
                applied.add("band-child:" + newSection.getNodeName());
            } else {
                newSection.appendChild(convertElementOrRaw(out, ce, applied));
            }
        }
    }

    /** 舊 <textElement>+<font> 屬性上提到 <element>；其餘子元素（如 paragraph）保留。 */
    private static void hoistTextElement(Document out, Element el, Element te, List<String> applied) {
        NamedNodeMap attrs = te.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String an = a.getName();
            String t = TEXT_ELEMENT_ATTRS.get(an);
            if (t == null) {
                // 未知屬性：若非 paragraph 相關，仍保留為 element 屬性（jackson 容忍度未知時寧缺毋濫）
                t = an;
            }
            el.setAttribute(t, a.getValue());
            if (!t.equals(an)) applied.add("textElement:" + an + "->" + t);
        }
        for (Node c : children(te)) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ce = (Element) c;
            if (ce.getLocalName().equals("font")) {
                NamedNodeMap fa = ce.getAttributes();
                for (int i = 0; i < fa.getLength(); i++) {
                    Attr a = (Attr) fa.item(i);
                    String an = a.getName();
                    String t = FONT_TO_ELEMENT.getOrDefault(an, an);
                    el.setAttribute(t, a.getValue());
                    if (!t.equals(an)) applied.add("font:" + an + "->" + t);
                }
            } else {
                el.appendChild(cloneRaw(out, ce, applied));
            }
        }
        applied.add("textElement-hoisted");
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
            if (name.equals("isStretchWithOverflow")) {
                // JR7 無 stretchWithOverflow；true → 以 stretchType=ElementGroupBottom 近似，false → 刪除（NoStretch 為預設）
                if ("true".equals(a.getValue()) && !dst.hasAttribute("stretchType")) {
                    dst.setAttribute("stretchType", "ElementGroupBottom");
                    applied.add("stretchWithOverflow->stretchType:ElementGroupBottom");
                } else {
                    applied.add("drop-attr:isStretchWithOverflow");
                }
                continue;
            }
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
            String value = a.getValue();
            String attrName = target == null ? name : target;
            if (attrName.equals("stretchType")) {
                String mapped = STRETCH_TYPE_VALUES.get(value);
                if (mapped != null) {
                    value = mapped;
                    applied.add("stretchType:" + a.getValue() + "->" + mapped);
                }
            }
            dst.setAttribute(attrName, value);
        }
    }

    /** 把舊元素原樣複製（含子元素遞迴與 CDATA 文字，不含結構轉換）。 */
    private static Element cloneRaw(Document out, Element e, List<String> applied) {
        Element ne = out.createElement(e.getLocalName());
        copyAttributes(e, ne, out, applied);
        for (Node c : children(e)) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                ne.appendChild(cloneRaw(out, (Element) c, applied));
            } else if (c.getNodeType() == Node.TEXT_NODE || c instanceof CDATASection) {
                // CDATA（expression 內容、JSON query 路徑等）必須原樣保留，
                // 否則 jackson XML 解成 null expression → printWhen 條件/條碼 codeExpression 靜默遺失
                String t = c.getTextContent();
                if (!t.trim().isEmpty()) ne.appendChild(out.createCDATASection(t));
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
        // jackson-xml 多態解碼要求 <element> 的 kind 屬性必須是第一個屬性；
        // Xerces DOM 會依字母序輸出屬性，故手動把 kind 提前。
        int kindIdx = -1;
        for (int i = 0; i < attrs.getLength(); i++) {
            if (attrs.item(i).getNodeName().equals("kind")) {
                kindIdx = i;
                break;
            }
        }
        if (kindIdx >= 0) {
            sb.append(" kind=\"").append(escape(attrs.item(kindIdx).getNodeValue())).append('"');
        }
        for (int i = 0; i < attrs.getLength(); i++) {
            if (i == kindIdx) continue;
            sb.append(' ').append(attrs.item(i).getNodeName()).append("=\"").append(escape(attrs.item(i).getNodeValue())).append('"');
        }
        int childCount = 0;
        boolean onlyCdata = true;
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE || c instanceof CDATASection) childCount++;
            if (c.getNodeType() == Node.ELEMENT_NODE) onlyCdata = false;
        }
        if (childCount == 0) {
            sb.append("/>");
            return;
        }
        sb.append(">");
        if (onlyCdata) {
            // 純 CDATA 子節（如 <query> 的 JSON 路徑）：inline 輸出，
            // 避免換行混進文字內容（query text 會被當 JSON path，多餘空白會找不到節點）
            for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof CDATASection cd) {
                    sb.append("<![CDATA[").append(cd.getTextContent()).append("]]>");
                }
            }
        } else {
            for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() != Node.ELEMENT_NODE && !(c instanceof CDATASection)) continue;
                sb.append('\n');
                writeNode(sb, c, depth + 1);
                sb.append('\n');
            }
        }
        sb.append("</").append(el.getNodeName()).append(">");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
