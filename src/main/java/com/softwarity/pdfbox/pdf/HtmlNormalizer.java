package com.softwarity.pdfbox.pdf;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parses arbitrary (possibly malformed) HTML with jsoup, injects a broad default font stack so
 * exotic scripts render even when the source sets no font, and converts the result to the W3C DOM
 * consumed by openhtmltopdf.
 */
@Component
public class HtmlNormalizer {

    private final String defaultFontFamily;
    private final W3CDom w3cDom = new W3CDom();

    public HtmlNormalizer(@Value("${pdfbox.default-font-family}") String defaultFontFamily) {
        this.defaultFontFamily = defaultFontFamily;
    }

    public org.w3c.dom.Document toW3c(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);

        Element head = doc.head();
        if (head.selectFirst("meta[charset]") == null) {
            head.prependElement("meta").attr("charset", "UTF-8");
        }
        // Lowest-priority default font, placed first so author styles override it.
        head.prependElement("style")
                .text("html{font-family:" + defaultFontFamily + ";}");

        return w3cDom.fromJsoup(doc);
    }
}
