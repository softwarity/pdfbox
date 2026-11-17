package com.softwarity.pdfbox;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.softwarity.pdfbox.pdf.PdfAStandard;
import com.softwarity.pdfbox.pdf.PdfGenerationService;
import com.softwarity.pdfbox.resource.Attachments;
import com.softwarity.pdfbox.resource.MissingResourcePolicy;
import com.softwarity.pdfbox.resource.UnresolvedResource;
import com.softwarity.pdfbox.resource.UnresolvedResource.Reason;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resource policy with one allowed host (a local test server, {@code 127.0.0.1:<port>}), a 1 s
 * timeout and a 4 KB size cap. {@code localhost:<port>} reaches the very same server but is NOT
 * listed: it stands for "another host" without leaving the machine.
 */
@SpringBootTest
class ResourcePolicyAllowListTest {

    static final CountingHttpServer SERVER;

    static {
        try {
            SERVER = new CountingHttpServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] red = PdfTestSupport.redPng();
        String port = SERVER.authority().substring(SERVER.authority().indexOf(':') + 1);
        SERVER.serve("/red.png", "image/png", red)
                .serve("/dedup.png", "image/png", red)
                .serve("/page.css", "text/css",
                        "body{background-color:#00ff00}".getBytes())
                .serve("/big.png", "image/png", new byte[8 * 1024])
                .chunked("/big-chunked.png", "image/png", new byte[8 * 1024])
                .slow("/slow.png", "image/png", red, 3_000)
                .redirect("/to-listed", "/red.png")
                .redirect("/to-unlisted", "http://localhost:" + port + "/target.png")
                .serve("/target.png", "image/png", red)
                .serve("/map.svg", "image/svg+xml", ("<svg xmlns='http://www.w3.org/2000/svg' "
                        + "xmlns:xlink='http://www.w3.org/1999/xlink' width='200' height='100'>"
                        + "<image xlink:href='red.png' width='200' height='100'/></svg>").getBytes());
    }

    @DynamicPropertySource
    static void policy(DynamicPropertyRegistry registry) {
        registry.add("pdfbox.fetch.allowed-hosts", SERVER::authority);
        registry.add("pdfbox.fetch.timeout", () -> "1s");
        registry.add("pdfbox.fetch.max-bytes", () -> "4KB");
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Autowired
    PdfGenerationService service;

    private PdfGenerationService.RenderResult render(String html) {
        return service.render(html, PdfAStandard.PDF_A_2B, Attachments.none(), MissingResourcePolicy.REPORT);
    }

    @Test
    void imageOnListedHostIsIncluded() throws Exception {
        var result = render("<img src=\"" + SERVER.url("/red.png") + "\" width=\"200\" height=\"100\" alt=\"r\"/>");

        assertThat(result.unresolved()).isEmpty();
        assertThat(PdfTestSupport.countPixels(result.pdf(), PdfTestSupport::red)).isGreaterThan(10_000);
    }

    @Test
    void stylesheetOnListedHostIsApplied() throws Exception {
        var result = render("<html><head><link rel=\"stylesheet\" href=\"" + SERVER.url("/page.css")
                + "\"/></head><body><p>x</p></body></html>");

        assertThat(result.unresolved()).isEmpty();
        assertThat(PdfTestSupport.countPixels(result.pdf(), PdfTestSupport::green)).isGreaterThan(100_000);
    }

    @Test
    void resourceCitedSeveralTimesIsFetchedOnce() throws Exception {
        String url = SERVER.url("/dedup.png");
        int before = SERVER.requestCount("/dedup.png");

        var result = render("<style>div{background-image:url(" + url + ");height:20px}</style>"
                + "<img src=\"" + url + "\" alt=\"a\"/><img src=\"" + url + "\" alt=\"b\"/><div></div>"
                + "<svg width=\"40\" height=\"20\"><image href=\"" + url + "\" width=\"40\" height=\"20\"/></svg>");

        assertThat(result.unresolved()).isEmpty();
        assertThat(SERVER.requestCount("/dedup.png") - before).isEqualTo(1);
    }

    @Test
    void redirectWithinListedHostIsFollowed() throws Exception {
        var result = render("<img src=\"" + SERVER.url("/to-listed") + "\" width=\"200\" height=\"100\" alt=\"r\"/>");

        assertThat(result.unresolved()).isEmpty();
        assertThat(PdfTestSupport.countPixels(result.pdf(), PdfTestSupport::red)).isGreaterThan(10_000);
    }

    @Test
    void redirectToUnlistedHostIsRefusedWithoutFollowingIt() {
        int before = SERVER.requestCount("/target.png");

        var result = render("<img src=\"" + SERVER.url("/to-unlisted") + "\" alt=\"r\"/>");

        assertThat(result.unresolved()).singleElement().satisfies(u -> {
            assertThat(u.reference()).isEqualTo(SERVER.url("/to-unlisted"));
            assertThat(u.reason()).isEqualTo(Reason.NOT_ALLOWED);
            assertThat(u.detail()).contains("redirected to http://localhost:");
        });
        assertThat(SERVER.requestCount("/target.png")).isEqualTo(before);
    }

    @Test
    void unlistedHostIsRefusedEvenOnTheSameMachine() {
        String unlisted = SERVER.url("/red.png").replace("127.0.0.1", "localhost");
        int before = SERVER.requestCount();

        var result = render("<img src=\"" + unlisted + "\" alt=\"r\"/>");

        assertThat(result.unresolved()).extracting(UnresolvedResource::reason).containsExactly(Reason.NOT_ALLOWED);
        assertThat(SERVER.requestCount()).isEqualTo(before);
    }

    @Test
    void timeoutIsEnforced() {
        long start = System.nanoTime();

        var result = render("<img src=\"" + SERVER.url("/slow.png") + "\" alt=\"s\"/>");

        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.unresolved()).extracting(UnresolvedResource::reason).containsExactly(Reason.TIMEOUT);
        assertThat(millis).isLessThan(2_800);
    }

    @Test
    void maxBytesIsEnforcedWhetherAnnouncedOrStreamed() {
        var result = render("<img src=\"" + SERVER.url("/big.png") + "\" alt=\"a\"/>"
                + "<img src=\"" + SERVER.url("/big-chunked.png") + "\" alt=\"b\"/>");

        assertThat(result.unresolved()).extracting(UnresolvedResource::reason)
                .containsExactly(Reason.TOO_LARGE, Reason.TOO_LARGE);
        assertThat(result.unresolved()).allMatch(u -> u.detail().contains("PDFBOX_FETCH_MAX_BYTES"));
    }

    @Test
    void missingRemoteFileIsNotFound() {
        var result = render("<img src=\"" + SERVER.url("/nope.png") + "\" alt=\"n\"/>");

        assertThat(result.unresolved()).singleElement().satisfies(u -> {
            assertThat(u.reason()).isEqualTo(Reason.NOT_FOUND);
            assertThat(u.detail()).contains("HTTP 404");
        });
    }

    @Test
    void remoteSvgAndItsRelativeImageResolveAgainstTheListedHost() throws Exception {
        var result = render("<img src=\"" + SERVER.url("/map.svg") + "\" width=\"200\" height=\"100\" alt=\"m\"/>");

        assertThat(result.unresolved()).isEmpty();
        assertThat(PdfTestSupport.countPixels(result.pdf(), PdfTestSupport::red)).isGreaterThan(10_000);
    }

    @Test
    void inlineSvgImageFollowsTheSameRule() throws Exception {
        String listed = SERVER.url("/red.png");
        String unlisted = listed.replace("127.0.0.1", "localhost");

        var result = render("<svg width=\"200\" height=\"100\"><image href=\"" + listed
                + "\" width=\"200\" height=\"100\"/><image xlink:href=\"" + unlisted
                + "\" width=\"10\" height=\"10\"/></svg>");

        assertThat(PdfTestSupport.countPixels(result.pdf(), PdfTestSupport::red)).isGreaterThan(10_000);
        assertThat(result.unresolved()).singleElement().satisfies(u -> {
            assertThat(u.reference()).isEqualTo(unlisted);
            assertThat(u.reason()).isEqualTo(Reason.NOT_ALLOWED);
        });
    }
}
