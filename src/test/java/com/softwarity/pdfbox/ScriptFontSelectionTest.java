package com.softwarity.pdfbox;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-regression for the per-render, content-driven font selection: each non-Latin script present in
 * the source pulls in its bundled Noto face (and only that one), and shared CJK Han ideographs pick
 * their variant from the document {@code lang}. Uses the Noto faces bundled on the classpath, which
 * the test configuration does not replace.
 */
@SpringBootTest
class ScriptFontSelectionTest {

    @Autowired
    PdfGenerationService service;

    /**
     * The test profile swaps the default font stack for Liberation Sans, so the documents name the
     * production-style Noto stack themselves: a script face is only used when the stack names it.
     */
    private static final String STACK = "<style>body{font-family:'Noto Sans','Noto Sans Hebrew',"
            + "'Noto Sans Arabic','Noto Sans Thai','Noto Sans Devanagari','Noto Sans JP','Noto Sans KR',"
            + "'Noto Sans SC','Noto Sans TC',sans-serif}</style>";

    @Test
    void hebrewArabicAndThaiEmbedTheirNotoFaces() throws IOException {
        Set<String> fonts = embeddedFonts(service.render(
                STACK + "<p>שלום עולם</p><p>مرحبا بالعالم</p><p>สวัสดีชาวโลก</p>", PdfAStandard.PDF_A_2U, null));

        assertThat(fonts).anyMatch(f -> f.contains("NotoSansHebrew"));
        assertThat(fonts).anyMatch(f -> f.contains("NotoSansArabic"));
        assertThat(fonts).anyMatch(f -> f.contains("NotoSansThai"));
        assertThat(fonts).noneMatch(f -> f.contains("NotoSansDevanagari"));
    }

    @Test
    void latinOnlyDocumentDoesNotPullScriptFaces() throws IOException {
        Set<String> fonts = embeddedFonts(service.render(STACK + "<p>Hello world</p>", PdfAStandard.PDF_A_2U, null));

        assertThat(fonts).noneMatch(f -> f.contains("Hebrew") || f.contains("Arabic") || f.contains("Thai")
                || f.contains("JP"));
    }

    @Test
    void hanIdeographsUseJapaneseFaceWhenLangIsJa() throws IOException {
        Set<String> fonts = embeddedFonts(service.render(
                "<html lang=\"ja\"><head>" + STACK + "</head><body><p>漢字</p></body></html>", PdfAStandard.PDF_A_2U, null));

        assertThat(fonts).anyMatch(f -> f.contains("NotoSansJP"));
    }

    @Test
    void hanIdeographsFollowLangAndDoNotFallBackToJapaneseForKorean() throws IOException {
        // Noto Sans KR ships in the Docker image, not in the jar: with lang=ko the selection must pick
        // KR (absent here), so the Japanese face must NOT be registered for these Han ideographs.
        Set<String> fonts = embeddedFonts(service.render(
                "<html lang=\"ko\"><head>" + STACK + "</head><body><p>漢字</p></body></html>", PdfAStandard.NONE, null));

        assertThat(fonts).noneMatch(f -> f.contains("NotoSansJP"));
    }

    static Set<String> embeddedFonts(byte[] pdf) throws IOException {
        Set<String> names = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                collect(page.getResources(), names);
            }
        }
        return names;
    }

    private static void collect(PDResources resources, Set<String> names) throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            if (font != null) {
                names.add(font.getName());
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                collect(form.getResources(), names);
            }
        }
    }
}
