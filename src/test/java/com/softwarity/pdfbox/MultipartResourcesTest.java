package com.softwarity.pdfbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multipart rendering with attached resources cited by plain name, on both {@code /pdf} and
 * {@code /pdf/upload}. Size limits are lowered for the test (1 MB per part, 1.5 MB in total).
 */
@SpringBootTest(properties = {
        "pdfbox.upload.max-part-size=1MB",
        "pdfbox.upload.max-total-size=1536KB"})
@AutoConfigureMockMvc
class MultipartResourcesTest {

    @Autowired
    MockMvc mockMvc;

    static final String HTML = "<html><head><title>Briefing</title>"
            + "<link rel=\"stylesheet\" href=\"page.css\"/>"
            + "<style>@font-face{font-family:LogoFont;src:url(logo-font.ttf)}</style></head><body>"
            + "<h1 class=\"brand\">Flight briefing</h1>"
            + "<div class=\"box\"></div>"
            + "<img src=\"red.png\" width=\"200\" height=\"100\" alt=\"chart\"/>"
            + "<img src=\"./sigwx.svg\" width=\"200\" height=\"100\" alt=\"sigwx\"/>"
            + "</body></html>";

    static MockMultipartFile html(String content) {
        return new MockMultipartFile("file", "briefing.html", MediaType.TEXT_HTML_VALUE,
                content.getBytes(StandardCharsets.UTF_8));
    }

    static MockMultipartFile resource(String name, String type, byte[] data) {
        return new MockMultipartFile("resources", name, type, data);
    }

    static MockMultipartFile css() {
        return resource("page.css", "text/css",
                ".box{background:#00ff00;width:300px;height:100px} .brand{font-family:LogoFont}".getBytes());
    }

    static MockMultipartFile png() {
        return resource("red.png", "image/png", PdfTestSupport.redPng());
    }

    static MockMultipartFile svg() {
        return resource("sigwx.svg", "image/svg+xml", ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" "
                + "height=\"100\"><rect width=\"200\" height=\"100\" fill=\"#0000ff\"/></svg>").getBytes());
    }

    static MockMultipartFile ttf() throws Exception {
        return resource("logo-font.ttf", "font/ttf",
                Files.readAllBytes(Path.of("src/test/resources/fonts/LiberationSans-Bold.ttf")));
    }

    @Test
    void htmlCitingAttachedCssPngSvgAndTtfRendersThemAll() throws Exception {
        for (String path : new String[]{"/api/v1/pdf", "/api/v1/pdf/upload"}) {
            byte[] pdf = mockMvc.perform(multipart(path).file(html(HTML))
                            .file(css()).file(png()).file(svg()).file(ttf()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::green)).as("CSS on %s", path)
                    .isGreaterThan(15_000);
            assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::red)).as("PNG on %s", path)
                    .isGreaterThan(10_000);
            assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::blue)).as("SVG on %s", path)
                    .isGreaterThan(10_000);
            assertThat(PdfTestSupport.imageCount(pdf)).as("PNG embedded as an image on %s", path)
                    .isGreaterThanOrEqualTo(1);
            // The @font-face TTF is embedded: only the attached file provides LiberationSans-Bold here.
            assertThat(PdfTestSupport.embeddedFonts(pdf)).as("attached font on %s", path)
                    .anyMatch(f -> f.contains("LiberationSans-Bold"));
        }
    }

    @Test
    void attachedWebpIsConvertedAndDrawn() throws Exception {
        byte[] webp = Files.readAllBytes(Path.of("src/test/resources/attachments/blue.webp"));
        byte[] pdf = mockMvc.perform(multipart("/api/v1/pdf")
                        .file(html("<img src=\"blue.webp\" width=\"200\" height=\"100\" alt=\"w\"/>"))
                        .file(resource("blue.webp", "image/webp", webp)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::blue)).isGreaterThan(10_000);
    }

    @Test
    void attachedSvgScriptNeverRuns() throws Exception {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"100\" "
                + "onload=\"document.getElementById('r').setAttribute('fill','#00ff00')\">"
                + "<rect id=\"r\" width=\"200\" height=\"100\" fill=\"#ff0000\"/><script>"
                + "document.getElementById('r').setAttribute('fill','#00ff00')</script></svg>").getBytes();
        byte[] pdf = mockMvc.perform(multipart("/api/v1/pdf")
                        .file(html("<img src=\"s.svg\" width=\"200\" height=\"100\" alt=\"s\"/>"))
                        .file(resource("s.svg", "image/svg+xml", svg)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::red)).isGreaterThan(10_000);
        assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::green)).isZero();
    }

    @Test
    void singleFilePartBehavesAsBefore() throws Exception {
        mockMvc.perform(multipart("/api/v1/pdf/upload").file(html("<h1>Hello</h1>")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mockMvc.perform(multipart("/api/v1/pdf").file(html("<h1>Hello</h1>")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void citedButNotAttachedIs422() throws Exception {
        mockMvc.perform(multipart("/api/v1/pdf").file(html(HTML)).file(css()).file(png()).file(ttf()))
                .andExpect(status().is(422))
                .andExpect(content().string(containsString("./sigwx.svg : not-found")));
    }

    @Test
    void invalidNamesAreRefused() throws Exception {
        for (String name : new String[]{"../red.png", "img/red.png", "img\\red.png", "red\u0001.png", "..red.png"}) {
            mockMvc.perform(multipart("/api/v1/pdf").file(html("<p>x</p>"))
                            .file(resource(name, "image/png", PdfTestSupport.redPng())))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Invalid attached file name")));
        }
    }

    @Test
    void duplicateNamesAreRefused() throws Exception {
        mockMvc.perform(multipart("/api/v1/pdf").file(html("<p>x</p>")).file(png()).file(png()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Duplicate attached file name 'red.png'")));
    }

    @Test
    void unexpectedTypesAreA400() throws Exception {
        expect400(resource("notes.txt", "text/plain", "hi".getBytes()), "Unsupported attached file type 'notes.txt'");
        expect400(resource("fake.png", "image/png", "not a png".getBytes()), "'fake.png' is not a PNG image");
        expect400(resource("map.svg", "image/svg+xml", PdfTestSupport.redPng()), "'map.svg' is not an SVG document");
        expect400(resource("font.ttf", "font/ttf", "OTTO and some CFF".getBytes()), "OpenType/CFF");
        expect400(resource("page.css", "text/css", new byte[]{'a', 0, 'b'}), "'page.css' is not a UTF-8 text stylesheet");
    }

    @Test
    void unexpectedPartNameIsA400() throws Exception {
        mockMvc.perform(multipart("/api/v1/pdf").file(html("<p>x</p>"))
                        .file(new MockMultipartFile("image", "red.png", "image/png", PdfTestSupport.redPng())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Unexpected file part 'image'")));
    }

    @Test
    void missingHtmlPartIsA400() throws Exception {
        mockMvc.perform(multipart("/api/v1/pdf").file(png()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Missing multipart part 'file'")));
    }

    @Test
    void partAndTotalSizeLimitsAreEnforced() throws Exception {
        byte[] big = new byte[1100 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        mockMvc.perform(multipart("/api/v1/pdf").file(html("<p>x</p>"))
                        .file(resource("big.css", "text/css", big)))
                .andExpect(status().is(413))
                .andExpect(content().string(allOf(containsString("big.css"),
                        containsString("PDFBOX_UPLOAD_MAX_PART_SIZE"))));

        byte[] medium = new byte[600 * 1024];
        java.util.Arrays.fill(medium, (byte) 'a');
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/pdf").file(html("<p>x</p>"));
        for (int i = 0; i < 3; i++) {
            request.file(resource("part" + i + ".css", "text/css", medium));
        }
        mockMvc.perform(request)
                .andExpect(status().is(413))
                .andExpect(content().string(containsString("PDFBOX_UPLOAD_MAX_TOTAL_SIZE")));
    }

    private void expect400(MockMultipartFile resource, String message) throws Exception {
        mockMvc.perform(multipart("/api/v1/pdf").file(html("<p>x</p>")).file(resource))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(message)));
    }
}
