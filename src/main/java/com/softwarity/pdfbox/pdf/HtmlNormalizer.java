package com.softwarity.pdfbox.pdf;

import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parses arbitrary (possibly malformed) HTML with jsoup, injects a broad default font stack so
 * exotic scripts render even when the source sets no font, fills in the title / language / subject
 * that the accessible PDF/A "A" levels (PDF/UA) require, and converts the result to the W3C DOM
 * consumed by openhtmltopdf.
 *
 * <p>The title/lang/subject injections are <em>guard-rails</em>: an explicit {@code <title>},
 * {@code lang} attribute or {@code <meta name="subject">} in the source always wins. They keep the
 * tagged levels (PDF_A_1A/2A/3A) from being emitted without the metadata PDF/UA mandates, but the
 * defaults are generic — a caller targeting strict accessibility should still supply accurate values.
 */
@Component
public class HtmlNormalizer {

    private final String defaultFontFamily;
    private final String defaultLang;
    private final String defaultTitle;
    private final W3CDom w3cDom = new W3CDom();

    public HtmlNormalizer(@Value("${pdfbox.default-font-family}") String defaultFontFamily,
                          @Value("${pdfbox.default-lang:en}") String defaultLang,
                          @Value("${pdfbox.default-title:Document}") String defaultTitle) {
        this.defaultFontFamily = defaultFontFamily;
        this.defaultLang = defaultLang;
        this.defaultTitle = defaultTitle;
    }

    public org.w3c.dom.Document toW3c(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);

        ensureLang(doc);

        Element head = doc.head();
        if (head.selectFirst("meta[charset]") == null) {
            head.prependElement("meta").attr("charset", "UTF-8");
        }
        ensureTitleAndSubject(doc, head);

        // Lowest-priority default font, placed first so author styles override it.
        head.prependElement("style")
                .text("html{font-family:" + defaultFontFamily + ";}");

        return w3cDom.fromJsoup(doc);
    }

    /** Sets a document language when the source omits one ({@code <html lang>} is required by PDF/UA). */
    private void ensureLang(Document doc) {
        Element root = doc.selectFirst("html");
        if (root != null && root.attr("lang").isBlank()) {
            root.attr("lang", defaultLang);
        }
    }

    /**
     * Guarantees a non-empty {@code <title>} (PDF/UA mandatory, also used as the PDF document title)
     * and a {@code <meta name="subject">}, falling back to the first heading then the configured
     * default title.
     *
     * <p>The subject — not {@code <meta name="description">} — is what openhtmltopdf maps to the PDF
     * {@code Subject} and then writes as the PDF/UA document description; injecting a {@code
     * description} meta would leave the "No document description" warning standing.
     */
    private void ensureTitleAndSubject(Document doc, Element head) {
        String title = doc.title().isBlank() ? firstHeading(doc).orElse(defaultTitle) : doc.title();
        doc.title(title);
        if (head.selectFirst("meta[name=subject]") == null) {
            head.appendElement("meta").attr("name", "subject").attr("content", title);
        }
    }

    private static Optional<String> firstHeading(Document doc) {
        return Optional.ofNullable(doc.selectFirst("h1, h2, h3"))
                .map(Element::text)
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }
}
