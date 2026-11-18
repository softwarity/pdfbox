package com.softwarity.pdfbox.pdf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Makes an SVG safe and self-contained before Batik sees it, whether it is written inline in the
 * HTML or arrives as a file (attached or fetched).
 *
 * <ul>
 *   <li><b>No script.</b> {@code <script>} elements and {@code on*} event attributes are removed.
 *       Batik is also configured not to run scripts; this is a second, independent barrier.</li>
 *   <li><b>No reference Batik would load itself.</b> Every external {@code href}/{@code xlink:href}
 *       and every external {@code url(...)} is handed to an {@code inliner}, which returns a
 *       {@code data:} URI (resolved through the same policy as the rest of the document) or
 *       {@code null} when the reference cannot be resolved — it is then dropped, and the inliner has
 *       already recorded why. Fragment references ({@code #id}) and {@code data:} URIs are kept.
 *       Batik itself is only allowed to read {@code data:} URIs.</li>
 *   <li><b>Bundled fonts for text.</b> The default font stack is set on the root and appended as a
 *       fallback to every explicit {@code font-family}, so SVG text is drawn with the embedded Noto
 *       faces rather than whatever fonts the host happens to have.</li>
 * </ul>
 */
@Component
public class SvgSanitizer {

    private static final Pattern CSS_URL =
            Pattern.compile("url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT =
            Pattern.compile("@import\\s+(?:url\\(\\s*)?(['\"]?)([^'\");\\s]+)\\1\\s*\\)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_FONT_FAMILY =
            Pattern.compile("(font-family\\s*:\\s*)([^;}\"]+|\"[^\"]*\")", Pattern.CASE_INSENSITIVE);

    /** Elements that are invalid for Batik without an href (lower-cased local names). */
    private static final java.util.Set<String> REQUIRES_HREF = java.util.Set.of("image", "use", "feimage");

    /** The CSS default font stack, minus the trailing generic (Batik would map it to a host font). */
    private final String fontStack;

    public SvgSanitizer(@Value("${pdfbox.default-font-family}") String defaultFontFamily) {
        this.fontStack = withoutGenerics(defaultFontFamily);
    }

    /** Sanitizes an SVG document given as bytes; returns the cleaned document as UTF-8 bytes. */
    public byte[] sanitize(byte[] svg, UnaryOperator<String> inliner) {
        return sanitize(svg, inliner, false);
    }

    /**
     * @param flattenTransparency PDF/A-1: also fold every opacity into solid colours over white (see
     *                            {@link SvgTransparencyFlattener})
     */
    public byte[] sanitize(byte[] svg, UnaryOperator<String> inliner, boolean flattenTransparency) {
        Document doc = Jsoup.parse(new String(svg, StandardCharsets.UTF_8), "", Parser.xmlParser());
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);
        for (Element root : doc.select("svg")) {
            if (root.parents().stream().noneMatch(p -> p.normalName().endsWith("svg"))) {
                sanitize(root, inliner, flattenTransparency);
            }
        }
        return doc.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    /** Sanitizes an SVG subtree in place (an inline {@code <svg>} of an HTML document, for instance). */
    public void sanitize(Element svgRoot, UnaryOperator<String> inliner) {
        sanitize(svgRoot, inliner, false);
    }

    /** Sanitizes in place; with {@code flattenTransparency}, also removes all transparency (PDF/A-1). */
    public void sanitize(Element svgRoot, UnaryOperator<String> inliner, boolean flattenTransparency) {
        if (flattenTransparency) {
            SvgTransparencyFlattener.flatten(svgRoot);
        }
        // Collect first: the subtree is modified while walking it.
        List<Element> elements = new ArrayList<>(svgRoot.select("*"));
        for (Element el : elements) {
            String name = localName(el.tagName());
            if (name.equals("script")) {
                el.remove();
                continue;
            }
            boolean dropElement = false;
            for (Attribute attr : new ArrayList<>(el.attributes().asList())) {
                String key = attr.getKey();
                String local = localName(key).toLowerCase(Locale.ROOT);
                if (local.startsWith("on")) {
                    el.removeAttr(key);
                } else if (local.equals("href")) {
                    String rewritten = rewriteReference(attr.getValue(), inliner);
                    el.removeAttr(key);
                    if (rewritten != null) {
                        // Batik implements SVG 1.1, which only knows xlink:href (SVG 2's bare href
                        // is ignored): always hand it the xlink form.
                        el.attr("xlink:href", rewritten);
                    } else if (REQUIRES_HREF.contains(name)) {
                        // Batik rejects the WHOLE drawing over one <image>/<use> without href: drop
                        // just that element (the inliner has already recorded why).
                        dropElement = true;
                    }
                } else if (local.equals("style")) {
                    el.attr(key, withFontFallback(rewriteCss(attr.getValue(), inliner)));
                } else if (local.equals("font-family")) {
                    el.attr(key, appendStack(attr.getValue()));
                } else if (attr.getValue().toLowerCase(Locale.ROOT).contains("url(")) {
                    // Presentation attributes such as fill, filter, clip-path, mask, marker-*.
                    el.attr(key, rewriteCss(attr.getValue(), inliner));
                }
            }
            if (dropElement) {
                el.remove();
                continue;
            }
            if (name.equals("style")) {
                rewriteStyleElement(el, inliner);
            }
        }
        if (!svgRoot.hasAttr("font-family")) {
            svgRoot.attr("font-family", fontStack);
        }
        if (!svgRoot.hasAttr("xmlns:xlink")) {
            svgRoot.attr("xmlns:xlink", "http://www.w3.org/1999/xlink");
        }
    }

    private void rewriteStyleElement(Element style, UnaryOperator<String> inliner) {
        for (Node child : new ArrayList<>(style.childNodes())) {
            String css = child instanceof DataNode d ? d.getWholeData()
                    : child instanceof TextNode t ? t.getWholeText() : null;
            if (css == null) {
                continue;
            }
            String rewritten = withFontFallback(rewriteCss(css, inliner));
            if (child instanceof DataNode d) {
                d.setWholeData(rewritten);
            } else {
                ((TextNode) child).text(rewritten);
            }
        }
    }

    /** {@code #id} and {@code data:} stay as they are; anything else goes through the inliner. */
    private static String rewriteReference(String ref, UnaryOperator<String> inliner) {
        String trimmed = ref == null ? "" : ref.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return trimmed;
        }
        if (isData(trimmed)) {
            // Handed to the inliner too, which may rewrite it (PDF/A-1: images flattened over white).
            return inliner == null ? trimmed : inliner.apply(trimmed);
        }
        return inliner == null ? null : inliner.apply(trimmed);
    }

    private static String rewriteCss(String css, UnaryOperator<String> inliner) {
        Matcher imports = CSS_IMPORT.matcher(css);
        StringBuilder out = new StringBuilder();
        while (imports.find()) {
            // An @import inside an SVG would make Batik fetch a stylesheet: never allowed. Record it
            // through the inliner (so it is reported) and drop it.
            if (inliner != null && !isData(imports.group(2))) {
                inliner.apply(imports.group(2));
            }
            imports.appendReplacement(out, "");
        }
        imports.appendTail(out);

        Matcher urls = CSS_URL.matcher(out.toString());
        StringBuilder result = new StringBuilder();
        while (urls.find()) {
            String rewritten = rewriteReference(urls.group(2), inliner);
            String replacement = rewritten == null ? "none" : "url(\"" + rewritten + "\")";
            urls.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        urls.appendTail(result);
        return result.toString();
    }

    private String withFontFallback(String css) {
        Matcher m = CSS_FONT_FAMILY.matcher(css);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + appendStack(m.group(2))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String appendStack(String families) {
        String value = families.trim();
        if (value.isEmpty()) {
            return fontStack;
        }
        if (value.contains("Noto Sans")) {
            return value;
        }
        return withoutGenerics(value).isEmpty() ? fontStack : withoutGenerics(value) + "," + fontStack;
    }

    private static String withoutGenerics(String stack) {
        List<String> kept = new ArrayList<>();
        for (String family : stack.split(",")) {
            String f = family.trim();
            String bare = f.replace("'", "").replace("\"", "").toLowerCase(Locale.ROOT);
            if (!f.isEmpty() && !List.of("serif", "sans-serif", "monospace", "cursive", "fantasy",
                    "system-ui").contains(bare)) {
                kept.add(f);
            }
        }
        return String.join(",", kept);
    }

    private static boolean isData(String ref) {
        return ref.regionMatches(true, 0, "data:", 0, 5);
    }

    private static String localName(String qualified) {
        int colon = qualified.indexOf(':');
        return (colon >= 0 ? qualified.substring(colon + 1) : qualified).toLowerCase(Locale.ROOT);
    }
}
