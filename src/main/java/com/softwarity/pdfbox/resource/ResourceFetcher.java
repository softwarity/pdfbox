package com.softwarity.pdfbox.resource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.softwarity.pdfbox.resource.UnresolvedResource.Reason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches {@code http(s)} resources for a render, under the allow-list and limits configured at
 * startup. Redirects are followed by hand (at most {@value #MAX_REDIRECTS}) and every hop is checked
 * against the allow-list again: a redirect to an unlisted host is a refusal. One overall deadline
 * covers connection, redirects and body; the body is capped at {@code maxBytes} while it streams in.
 */
@Component
public class ResourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ResourceFetcher.class);

    static final int MAX_REDIRECTS = 5;

    /** A successful fetch ({@code data} set) or a failure ({@code failure} set). */
    public record Outcome(byte[] data, String contentType, URI finalUri, UnresolvedResource failure) {
        static Outcome ok(byte[] data, String contentType, URI finalUri) {
            return new Outcome(data, contentType, finalUri, null);
        }

        static Outcome failed(String reference, Reason reason, String detail) {
            return new Outcome(null, null, null, new UnresolvedResource(reference, reason, detail));
        }
    }

    private final HostAllowList allowList;
    private final Duration timeout;
    private final long maxBytes;
    private final HttpClient client;

    public ResourceFetcher(FetchProperties properties) {
        this.allowList = new HostAllowList(properties.allowedHosts());
        this.timeout = properties.timeout();
        this.maxBytes = properties.maxBytes().toBytes();
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        log.info("Remote resources: {}", allowList.isEmpty()
                ? "never fetched (PDFBOX_FETCH_ALLOWED_HOSTS is empty)"
                : "fetched from " + properties.allowedHosts() + " only (timeout " + timeout
                        + ", max " + properties.maxBytes() + " per resource)");
    }

    public HostAllowList allowList() {
        return allowList;
    }

    /** Fetches {@code uri}, which the caller has already checked against the allow-list. */
    public Outcome fetch(URI uri) {
        String reference = uri.toString();
        long deadline = System.nanoTime() + timeout.toNanos();
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowList.permits(current)) {
                return Outcome.failed(reference, Reason.NOT_ALLOWED, "redirected to " + current
                        + ", whose host '" + HostAllowList.authority(current)
                        + "' is not in PDFBOX_FETCH_ALLOWED_HOSTS");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return timedOut(reference);
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofNanos(remaining))
                    .header("Accept", "*/*")
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<byte[]>> future =
                    client.sendAsync(request, info -> bodyFor(info));
            HttpResponse<byte[]> response;
            try {
                response = future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return timedOut(reference);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.failed(reference, Reason.FETCH_ERROR, "interrupted");
            } catch (ExecutionException e) {
                return failure(reference, e.getCause());
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                var location = response.headers().firstValue("Location");
                if (location.isEmpty()) {
                    return Outcome.failed(reference, Reason.FETCH_ERROR, "HTTP " + status + " without Location");
                }
                current = current.resolve(location.get().trim());
                continue;
            }
            if (status == 404 || status == 410) {
                return Outcome.failed(reference, Reason.NOT_FOUND, "HTTP " + status + " from " + current);
            }
            if (status < 200 || status >= 300) {
                return Outcome.failed(reference, Reason.FETCH_ERROR, "HTTP " + status + " from " + current);
            }
            return Outcome.ok(response.body(),
                    response.headers().firstValue("Content-Type").orElse(null), current);
        }
        return Outcome.failed(reference, Reason.FETCH_ERROR, "more than " + MAX_REDIRECTS + " redirects");
    }

    /** Only a 2xx body is read; it is refused up-front when announced too large, else capped. */
    private BodySubscriber<byte[]> bodyFor(HttpResponse.ResponseInfo info) {
        if (info.statusCode() < 200 || info.statusCode() >= 300) {
            return BodySubscribers.replacing(null);
        }
        long announced = info.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (announced > maxBytes) {
            return new Failing(new TooLarge(announced));
        }
        return new Capped(maxBytes);
    }

    private Outcome timedOut(String reference) {
        return Outcome.failed(reference, Reason.TIMEOUT, "no complete response within " + timeout
                + " (PDFBOX_FETCH_TIMEOUT)");
    }

    private Outcome failure(String reference, Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && !(root instanceof TooLarge)) {
            root = root.getCause();
        }
        if (root instanceof TooLarge tooLarge) {
            return Outcome.failed(reference, Reason.TOO_LARGE, (tooLarge.size > 0
                    ? tooLarge.size + " bytes" : "more than " + maxBytes + " bytes")
                    + ", limit is " + maxBytes + " (PDFBOX_FETCH_MAX_BYTES)");
        }
        if (root instanceof java.net.http.HttpTimeoutException) {
            return timedOut(reference);
        }
        return Outcome.failed(reference, Reason.FETCH_ERROR,
                root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage()));
    }

    /** Signals a body over the size limit (announced or streamed). */
    static final class TooLarge extends RuntimeException {
        final long size;

        TooLarge(long size) {
            super("too large", null, false, false);
            this.size = size;
        }
    }

    /** Accumulates the body and aborts as soon as it exceeds the cap. */
    private static final class Capped implements BodySubscriber<byte[]> {
        private final long cap;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Capped(long cap) {
            this.cap = cap;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer b : buffers) {
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                if (out.size() + (long) chunk.length > cap) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge(-1));
                    return;
                }
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    /** Refuses the body straight away (announced Content-Length over the cap). */
    private static final class Failing implements BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final RuntimeException error;

        Failing(RuntimeException error) {
            this.error = error;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.cancel();
            result.completeExceptionally(error);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.completeExceptionally(error);
        }
    }
}
