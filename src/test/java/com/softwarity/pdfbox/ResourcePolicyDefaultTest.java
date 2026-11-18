package com.softwarity.pdfbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;
import com.softwarity.pdfbox.resource.UnresolvedResource.Reason;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The resource policy with its default configuration: PDFBOX_FETCH_ALLOWED_HOSTS empty. Nothing may
 * reach the network or the local filesystem, and nothing unresolved disappears silently.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourcePolicyDefaultTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PdfGenerationService service;

    @Test
    void emptyAllowListSendsNoRequestAndAnswers422NamingTheUrl() throws Exception {
        try (CountingHttpServer server = new CountingHttpServer().serve("/red.png", "image/png",
                PdfTestSupport.redPng())) {
            String url = server.url("/red.png");

            mockMvc.perform(post("/api/v1/pdf").contentType(MediaType.TEXT_HTML)
                            .content("<p>Map</p><img src=\"" + url + "\" alt=\"map\"/>"))
                    .andExpect(status().is(422))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                    .andExpect(content().string(allOf(containsString(url), containsString("not-allowed"),
                            containsString("PDFBOX_FETCH_ALLOWED_HOSTS"))));

            assertThat(server.requestCount()).isZero();
        }
    }

    @Test
    void stylesheetFontAndSvgReferencesAreRefusedWithoutAnyRequestEither() throws Exception {
        try (CountingHttpServer server = new CountingHttpServer()) {
            String html = "<html><head><link rel=\"stylesheet\" href=\"" + server.url("/page.css") + "\"/>"
                    + "<style>@font-face{font-family:F;src:url(" + server.url("/f.ttf") + ")}"
                    + "p{font-family:F;background-image:url(" + server.url("/bg.png") + ")}</style></head>"
                    + "<body><p>x</p><img src=\"" + server.url("/map.svg") + "\" alt=\"m\"/>"
                    + "<svg width=\"10\" height=\"10\"><image href=\"" + server.url("/in-svg.png")
                    + "\" width=\"10\" height=\"10\"/></svg></body></html>";

            var result = service.render(html, PdfAStandard.PDF_A_2B, Attachments.none(),
                    MissingResourcePolicy.REPORT);

            assertThat(server.requestCount()).isZero();
            assertThat(result.unresolved())
                    .allMatch(u -> u.reason() == Reason.NOT_ALLOWED)
                    .extracting(u -> u.reference())
                    .contains(server.url("/page.css"), server.url("/f.ttf"), server.url("/bg.png"),
                            server.url("/map.svg"), server.url("/in-svg.png"));
        }
    }

    @Test
    void fileJarAndClasspathAreRefused() throws Exception {
        Path png = Files.createTempFile("pdfbox-test", ".png");
        Files.write(png, PdfTestSupport.redPng());
        try {
            String html = "<link rel=\"stylesheet\" href=\"classpath:application.yaml\"/>"
                    + "<img src=\"file:///etc/passwd\" alt=\"a\"/>"
                    + "<img src=\"" + png.toUri() + "\" width=\"200\" height=\"100\" alt=\"b\"/>"
                    + "<img src=\"jar:file:/app/pdfbox.jar!/BOOT-INF/classes/application.yaml\" alt=\"c\"/>";

            mockMvc.perform(post("/api/v1/pdf").contentType(MediaType.TEXT_HTML).content(html))
                    .andExpect(status().is(422))
                    .andExpect(content().string(allOf(
                            containsString("file:///etc/passwd : not-allowed"),
                            containsString(png.toUri() + " : not-allowed"),
                            containsString("classpath:application.yaml : not-allowed"),
                            containsString("jar:file:/app/pdfbox.jar"))));

            // Report mode: the PDF is produced, and the local file was really not read (not drawn).
            var result = service.render(html, PdfAStandard.NONE, Attachments.none(), MissingResourcePolicy.REPORT);
            assertThat(PdfTestSupport.countPixels(result.pdf(), PdfTestSupport::red)).isZero();
        } finally {
            Files.deleteIfExists(png);
        }
    }

    @Test
    void relativeReferenceWithoutAttachmentIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/pdf").contentType(MediaType.TEXT_HTML)
                        .content("<img src=\"logo.png\" alt=\"l\"/><img src=\"img/chart.png\" alt=\"c\"/>"))
                .andExpect(status().is(422))
                .andExpect(content().string(allOf(
                        containsString("logo.png : not-found — no attached file named 'logo.png'"),
                        containsString("img/chart.png : not-found — relative paths are not resolved"))));
    }

    @Test
    void reportModeReturnsThePdfAndListsUnresolvedInHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/pdf").param("onMissingResource", "report")
                        .contentType(MediaType.TEXT_HTML)
                        .content("<img src=\"logo.png\" alt=\"l\"/><img src=\"https://maps.example.org/a b.png\" alt=\"m\"/>"
                                + "<img src=\"carte-météo.png\" alt=\"c\"/>"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Pdfbox-Unresolved-Count", "3"))
                .andExpect(header().string("X-Pdfbox-Unresolved-Resources", allOf(
                        containsString("\"logo.png\";reason=not-found"),
                        // The space is URL-encoded (%20), then '%' itself is escaped for the header (%25):
                        // decoding the header value once gives back https://maps.example.org/a%20b.png.
                        containsString("\"https://maps.example.org/a%2520b.png\";reason=not-allowed"),
                        containsString("\"carte-m%C3%A9t%C3%A9o.png\";reason=not-found"))));
    }

    @Test
    void reportModeWithEverythingResolvedSaysZero() throws Exception {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(PdfTestSupport.redPng());
        mockMvc.perform(post("/api/v1/pdf").param("onMissingResource", "REPORT")
                        .contentType(MediaType.TEXT_HTML)
                        .content("<img src=\"" + dataUri + "\" alt=\"r\"/>"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfbox-Unresolved-Count", "0"))
                .andExpect(header().doesNotExist("X-Pdfbox-Unresolved-Resources"));
    }

    @Test
    void dataUriImagesAreDrawn() throws Exception {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(PdfTestSupport.redPng());
        byte[] pdf = service.render("<img src=\"" + dataUri + "\" width=\"200\" height=\"100\" alt=\"r\"/>",
                PdfAStandard.PDF_A_2B);
        assertThat(PdfTestSupport.countPixels(pdf, PdfTestSupport::red)).isGreaterThan(10_000);
    }

    @Test
    void badOnMissingResourceValueIsA400ListingTheChoices() throws Exception {
        mockMvc.perform(post("/api/v1/pdf").param("onMissingResource", "ignore")
                        .contentType(MediaType.TEXT_HTML).content("<p>x</p>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Supported values: fail, report")));
    }
}
