package com.softwarity.pdfbox;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.MultipartConfigElement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multipart limits as enforced by the real servlet container (Tomcat), which MockMvc bypasses: the
 * request is rejected before reaching the controller, still as a clear 413. Also checks that parts
 * are kept in memory (threshold = per-part maximum), so no attached file is ever written to disk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pdfbox.upload.max-part-size=256KB",
        "pdfbox.upload.max-total-size=512KB"})
class MultipartContainerLimitsTest {

    @LocalServerPort
    int port;

    @Autowired
    MultipartConfigElement multipartConfig;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void partsNeverSpillToDisk() {
        assertThat(multipartConfig.getMaxFileSize()).isEqualTo(256 * 1024);
        assertThat(multipartConfig.getMaxRequestSize()).isEqualTo(512 * 1024);
        assertThat((long) multipartConfig.getFileSizeThreshold()).isEqualTo(multipartConfig.getMaxFileSize());
    }

    @Test
    void realMultipartWithResourcesRenders() throws Exception {
        HttpResponse<byte[]> response = post(new Part("file", "a.html", "text/html",
                        "<img src=\"red.png\" alt=\"r\"/>".getBytes(StandardCharsets.UTF_8)),
                new Part("resources", "red.png", "image/png", PdfTestSupport.redPng()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(new String(response.body(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void containerRejectsAnOversizedPartWith413() throws Exception {
        byte[] big = new byte[300 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');

        HttpResponse<byte[]> response = post(new Part("file", "a.html", "text/html", "<p>x</p>".getBytes()),
                new Part("resources", "big.css", "text/css", big));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("PDFBOX_UPLOAD_MAX_PART_SIZE");
    }

    private record Part(String name, String filename, String type, byte[] data) {}

    private HttpResponse<byte[]> post(Part... parts) throws Exception {
        String boundary = "pdfbox-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part p : parts) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + p.name()
                    + "\"; filename=\"" + p.filename() + "\"\r\nContent-Type: " + p.type() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.writeBytes(p.data());
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/pdf"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
