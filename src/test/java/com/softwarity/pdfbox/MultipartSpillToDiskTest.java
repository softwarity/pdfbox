package com.softwarity.pdfbox;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDFBOX_UPLOAD_MEMORY_THRESHOLD lowered to 1 KB on a real Tomcat: a larger attached file is written
 * to a temporary file while the request runs (observed mid-render, the render being held by a slow
 * allowed resource), still renders correctly when read back, and the temporary file is gone once the
 * request has completed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pdfbox.upload.max-part-size=1MB",
        "pdfbox.upload.max-total-size=2MB",
        "pdfbox.upload.memory-threshold=1KB"})
class MultipartSpillToDiskTest {

    static final CountingHttpServer SERVER;

    static {
        try {
            SERVER = new CountingHttpServer().slow("/slow.png", "image/png", PdfTestSupport.redPng(), 1_500);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void policy(DynamicPropertyRegistry registry) {
        registry.add("pdfbox.fetch.allowed-hosts", SERVER::authority);
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    ServletContext servletContext;

    @Autowired
    MultipartConfigElement multipartConfig;

    @Test
    void largePartGoesToATemporaryFileForTheRequestOnly() throws Exception {
        assertThat(multipartConfig.getFileSizeThreshold()).isEqualTo(1024);
        File tempDir = (File) servletContext.getAttribute(ServletContext.TEMPDIR);

        // ~200 KB stylesheet: a green box, padded with a long comment.
        char[] padding = new char[200 * 1024];
        Arrays.fill(padding, 'x');
        byte[] css = (".box{background:#00ff00;width:300px;height:100px}/*" + new String(padding) + "*/")
                .getBytes(StandardCharsets.UTF_8);
        String html = "<link rel=\"stylesheet\" href=\"page.css\"/><div class=\"box\"></div>"
                + "<img src=\"" + SERVER.url("/slow.png") + "\" alt=\"slow\"/>";

        CompletableFuture<HttpResponse<byte[]>> response = HttpClient.newHttpClient().sendAsync(
                multipart(html, css), HttpResponse.BodyHandlers.ofByteArray());
        while (SERVER.requestCount("/slow.png") == 0 && !response.isDone()) {
            Thread.sleep(10); // rendering has started and is held by the slow resource
        }
        long duringRender = tempFiles(tempDir);

        HttpResponse<byte[]> done = response.get();
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(PdfTestSupport.countPixels(done.body(), PdfTestSupport::green)).isGreaterThan(15_000);
        assertThat(duringRender).as("the 200 KB part is on disk while the request runs").isGreaterThan(0);
        Thread.sleep(200); // parts are deleted when the request is recycled
        assertThat(tempFiles(tempDir)).as("and deleted once it has completed").isZero();
    }

    private static long tempFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("upload_") && name.endsWith(".tmp"));
        return files == null ? 0 : files.length;
    }

    private HttpRequest multipart(String html, byte[] css) {
        String boundary = "pdfbox-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "file", "a.html", "text/html", html.getBytes(StandardCharsets.UTF_8));
        part(body, boundary, "resources", "page.css", "text/css", css);
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/pdf"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private static void part(ByteArrayOutputStream body, String boundary, String name, String filename,
                             String type, byte[] data) {
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(data);
        body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
