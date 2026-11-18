package com.softwarity.pdfbox;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The showcase documents published in the README and the doc site ({@code src/test/resources/showcase}).
 *
 * <p>Each example is rendered with its standard, must conform to it (veraPDF) and must produce exactly
 * the expected page sizes — so the page-format claims shown in the doc (portrait/landscape mix, named
 * pages, A3/A5/Letter/Legal/custom sizes...) are guaranteed by the build, not only illustrated.
 *
 * <p>With {@code -Dshowcase.write=true} it also (re)writes the doc assets: the HTML source, the PDF and
 * a PNG contact sheet of every page at a common scale (so an A3 page looks bigger than an A5 one):
 * <pre>mvn test -Dtest=ShowcaseTest -Dshowcase.write=true</pre>
 * A PDF is only rewritten when its contact sheet changed (PDFs embed a creation date and ID, so
 * rewriting them unconditionally would make every regeneration a diff).
 *
 * <p>Fonts: the test config shadows the default font stack with Liberation Sans; the showcase uses the
 * production Noto stack instead (the Noto faces are on the classpath), so the images match what the
 * service actually produces.
 */
@SpringBootTest(properties = "pdfbox.default-font-family='Noto Sans','Noto Sans Hebrew','Noto Sans Arabic',"
        + "'Noto Sans Devanagari','Noto Sans Tamil','Noto Sans Thai','Noto Sans Georgian','Noto Sans Armenian',"
        + "'Noto Sans Ethiopic','Noto Sans JP','Noto Sans Symbols','Noto Sans Symbols2','Noto Sans Math',"
        + "'Noto Emoji',sans-serif")
class ShowcaseTest {

    private static final Path OUTPUT = Path.of(System.getProperty("showcase.dir", "docs/src/assets/showcase"));
    private static final boolean WRITE = Boolean.getBoolean("showcase.write");

    /** Contact-sheet scale in pixels per PDF point (A4 portrait = 298 × 421 px). */
    private static final float SHEET_SCALE = 0.5f;
    private static final int SHEET_PADDING = 24;
    private static final int SHEET_GAP = 20;
    private static final Color SHEET_BACKGROUND = new Color(0xE9EDF2);
    private static final Color PAGE_SHADOW = new Color(0xC3CCD7);
    private static final Color PAGE_BORDER = new Color(0xB4BECB);

    private static final double MM_PER_POINT = 25.4 / 72;

    @Autowired
    PdfGenerationService service;

    /** One showcase document and the page sizes (width × height, mm) it must produce. */
    record Example(String id, PdfAStandard standard, double[]... pages) {
        @Override
        public String toString() {
            return id + " (" + standard + ")";
        }
    }

    private static final double[] A4 = {210, 297};
    private static final double[] A4_LANDSCAPE = {297, 210};
    private static final double[] A5 = {148, 210};
    private static final double[] A5_LANDSCAPE = {210, 148};

    static Stream<Example> examples() {
        return Stream.of(
                new Example("orientation", PdfAStandard.PDF_A_1B, A4, A4_LANDSCAPE, A4),
                new Example("page-sizes", PdfAStandard.PDF_A_2B,
                        A4, A5, new double[] {420, 297}, new double[] {215.9, 279.4},
                        new double[] {355.6, 215.9}, new double[] {85, 55}),
                new Example("headers-footers", PdfAStandard.PDF_A_3A, A4, A4, A4),
                new Example("long-table", PdfAStandard.PDF_A_2B, A4_LANDSCAPE, A4_LANDSCAPE, A4_LANDSCAPE,
                        A4_LANDSCAPE),
                new Example("svg-chart", PdfAStandard.PDF_A_1B, A5_LANDSCAPE),
                new Example("scripts", PdfAStandard.PDF_A_2U, A5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void rendersConformsAndHasTheExpectedPageSizes(Example example) throws Exception {
        String html = source(example.id());
        byte[] pdf = service.render(html, example.standard());

        assertThat(PdfTestSupport.veraPdfFailures(pdf, example.standard()))
                .as("%s must conform to %s", example.id(), example.standard()).isEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).as("%s page count", example.id()).isEqualTo(example.pages().length);
            for (int i = 0; i < example.pages().length; i++) {
                PDRectangle box = doc.getPage(i).getMediaBox();
                double[] expected = example.pages()[i];
                assertThat(box.getWidth() * MM_PER_POINT).as("%s page %d width (mm)", example.id(), i + 1)
                        .isCloseTo(expected[0], within(1.0));
                assertThat(box.getHeight() * MM_PER_POINT).as("%s page %d height (mm)", example.id(), i + 1)
                        .isCloseTo(expected[1], within(1.0));
            }
        }

        if (WRITE) {
            write(example.id(), html, pdf);
        }
    }

    /**
     * Layout widths follow the default page, not the named page a block lands on: the landscape block
     * of the orientation example sets {@code width: 267mm} so its {@code width: 100%} table spans the
     * whole landscape page (content box 15 → 282 mm) instead of a portrait-wide column (~174 mm).
     */
    @Test
    void theLandscapeTableSpansTheWholeLandscapePage() throws Exception {
        byte[] pdf = service.render(source("orientation"), PdfAStandard.PDF_A_1B);
        BufferedImage page = PdfTestSupport.renderPage(pdf, 1);
        int right = -1;
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                if (isHeaderBlue(page.getRGB(x, y))) {
                    right = Math.max(right, x);
                }
            }
        }
        assertThat(right * MM_PER_POINT).as("right edge of the table header (mm)").isGreaterThan(275);
    }

    /** The table header background, {@code #1f4e79}. */
    private static boolean isHeaderBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Math.abs(r - 0x1F) < 12 && Math.abs(g - 0x4E) < 12 && Math.abs(b - 0x79) < 12;
    }

    private static String source(String id) throws IOException {
        try (InputStream in = ShowcaseTest.class.getResourceAsStream("/showcase/" + id + ".html")) {
            assertThat(in).as("showcase/%s.html", id).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(String id, String html, byte[] pdf) throws IOException {
        Files.createDirectories(OUTPUT);
        Files.writeString(OUTPUT.resolve(id + ".html"), html, StandardCharsets.UTF_8);

        byte[] png = contactSheet(pdf);
        Path pngFile = OUTPUT.resolve(id + ".png");
        Path pdfFile = OUTPUT.resolve(id + ".pdf");
        boolean unchanged = Files.exists(pngFile) && Files.exists(pdfFile)
                && Arrays.equals(Files.readAllBytes(pngFile), png);
        if (!unchanged) {
            Files.write(pngFile, png);
            Files.write(pdfFile, pdf);
        }
    }

    /** Every page side by side, at the same scale, on a light background, as a PNG. */
    private static byte[] contactSheet(byte[] pdf) throws IOException {
        List<BufferedImage> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                // Render at twice the size, then halve: smoother text than rendering small directly.
                pages.add(halve(renderer.renderImage(i, SHEET_SCALE * 2, ImageType.RGB)));
            }
        }
        int width = SHEET_PADDING * 2 + SHEET_GAP * (pages.size() - 1)
                + pages.stream().mapToInt(BufferedImage::getWidth).sum();
        int height = SHEET_PADDING * 2 + pages.stream().mapToInt(BufferedImage::getHeight).max().orElse(0);

        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(SHEET_BACKGROUND);
        g.fillRect(0, 0, width, height);
        int x = SHEET_PADDING;
        for (BufferedImage page : pages) {
            int y = (height - page.getHeight()) / 2;
            g.setColor(PAGE_SHADOW);
            g.fillRect(x + 3, y + 3, page.getWidth(), page.getHeight());
            g.drawImage(page, x, y, null);
            g.setColor(PAGE_BORDER);
            g.drawRect(x - 1, y - 1, page.getWidth() + 1, page.getHeight() + 1);
            x += page.getWidth() + SHEET_GAP;
        }
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(sheet, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage halve(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth() / 2, src.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, dst.getWidth(), dst.getHeight(), null);
        g.dispose();
        return dst;
    }
}
