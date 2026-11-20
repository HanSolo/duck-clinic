package eu.hansolo.mcp.client.internal;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.McpClientException;
import eu.hansolo.mcp.client.Progress;
import eu.hansolo.mcp.client.ProgressListener;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Internal. One POST per request with the standard headers and a per-request {@code _meta}; answers are JSON or SSE. */
public final class McpTransport {

    private static final System.Logger LOG     = System.getLogger(McpTransport.class.getName());
    private static final Executor      READERS = Executors.newVirtualThreadPerTaskExecutor();

    private final HttpClient       http;
    private final URI              endpoint;
    private final String           clientName;
    private final String           clientVersion;
    private final JsonObject       capabilities;
    private final Supplier<String> token;
    private final Duration         timeout;
    private final Duration         streamIdle;
    private final Authenticator    authenticator;
    private final AtomicLong       ids = new AtomicLong(1);


    public McpTransport(HttpClient http, URI endpoint, String clientName, String clientVersion, JsonObject capabilities, Supplier<String> token, Duration timeout, Duration streamIdle, Authenticator authenticator) {
        this.http          = http;
        this.endpoint      = endpoint;
        this.clientName    = clientName;
        this.clientVersion = clientVersion;
        this.capabilities  = capabilities;
        this.token         = token;
        this.timeout       = timeout;
        this.streamIdle    = streamIdle;
        this.authenticator = authenticator;
    }


    /** Reacts to a 401 or 403 answer: returns true if it changed something (a new token, say) so that the request is worth sending once more. */
    public interface Authenticator {

        boolean onChallenge(int status, String wwwAuthenticate);
    }

    /** A request in flight: the future of the {@code result} object and the way to drop the connection. */
    public record Running(CompletableFuture<JsonObject> result, Runnable cancel) {
    }


    /** Sends the request and waits for the {@code result} object, or throws {@link McpClientException}. {@code name} fills {@code Mcp-Name} and may be null. */
    public JsonObject call(String method, String name, JsonObject params) {
        return call(method, name, params, Map.of(), null);
    }

    /** Like {@link #call(String, String, JsonObject)} with additional request headers and an optional progress listener. Interrupting the thread cancels the request. */
    public JsonObject call(String method, String name, JsonObject params, Map<String, String> extraHeaders, ProgressListener progress) {
        Running running = start(method, name, params, extraHeaders, progress);
        try {
            return running.result().get();
        } catch (InterruptedException e) {
            running.cancel().run();
            Thread.currentThread().interrupt();
            throw new McpClientException("Interrupted during " + method, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof McpClientException failure) {
                throw failure;
            }
            throw new McpClientException("Request " + method + " failed: " + e.getCause(), e.getCause());
        }
    }

    /** Starts the request without waiting. Progress events go to the listener on a reader thread. */
    public Running start(String method, String name, JsonObject params, Map<String, String> extraHeaders, ProgressListener progress) {
        long   id            = ids.getAndIncrement();
        String progressToken = progress == null ? null : "p-" + id;
        return launch(id, method, name, params, extraHeaders, progressToken, progress == null ? null : message -> dispatch(message, progressToken, progress));
    }

    /**
     * Starts a long-lived request such as {@code subscriptions/listen}: every notification of the stream goes to {@code notifications} on a reader
     * thread; the result completes when the server ends the stream with its final response, exceptionally if the stream fails.
     */
    public Running startStream(String method, JsonObject params, Consumer<JsonObject> notifications) {
        return launch(ids.getAndIncrement(), method, null, params, Map.of(), null, notifications);
    }

    private Running launch(long id, String method, String name, JsonObject params, Map<String, String> extraHeaders, String progressToken, Consumer<JsonObject> sink) {
        var    metaBuilder   = Json.createObjectBuilder().add("io.modelcontextprotocol/protocolVersion", McpClient.PROTOCOL_VERSION).add("io.modelcontextprotocol/clientInfo", Json.createObjectBuilder().add("name", clientName).add("version", clientVersion)).add("io.modelcontextprotocol/clientCapabilities", capabilities);
        if (progressToken != null) {
            metaBuilder.add("progressToken", progressToken);
        }
        String body = Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id).add("method", method).add("params", Json.createObjectBuilder(params).add("_meta", metaBuilder)).build().toString();
        Supplier<HttpRequest> requests = () -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION).header("Mcp-Method", method);
            if (name != null) {
                request.header("Mcp-Name", HeaderValues.encode(name));
            }
            extraHeaders.forEach(request::header);
            String bearer = token.get();
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            return request.build();
        };
        Exchange exchange = new Exchange(id, method, sink, requests);
        exchange.send(false);
        return new Running(exchange.result, exchange::cancel);
    }

    /** One request and, after a 401 or 403 that the authenticator can answer, its single repetition. */
    private final class Exchange {

        private final long                                                       id;
        private final String                                                     method;
        private final Consumer<JsonObject>                                       sink;
        private final Supplier<HttpRequest>                                      requests;
        private final CompletableFuture<JsonObject>                              result = new CompletableFuture<>();
        private final AtomicReference<InputStream>                               open   = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> sent = new AtomicReference<>();


        private Exchange(long id, String method, Consumer<JsonObject> sink, Supplier<HttpRequest> requests) {
            this.id       = id;
            this.method   = method;
            this.sink     = sink;
            this.requests = requests;
        }


        private void send(boolean retried) {
            try {
                CompletableFuture<HttpResponse<InputStream>> future = http.sendAsync(requests.get(), HttpResponse.BodyHandlers.ofInputStream());
                sent.set(future);
                future.whenCompleteAsync((response, failure) -> handle(response, failure, retried), READERS);
            } catch (RuntimeException e) {
                result.completeExceptionally(e instanceof McpClientException ? e : new McpClientException("Request " + method + " failed: " + e.getMessage(), e));
            }
        }

        private void cancel() {
            result.cancel(true); // first: closing the stream below makes the reader fail, and that failure must not win
            CompletableFuture<HttpResponse<InputStream>> future = sent.get();
            if (future != null) {
                future.cancel(true);
            }
            InputStream stream = open.get();
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // the connection is being dropped anyway
                }
            }
        }

        private void handle(HttpResponse<InputStream> response, Throwable failure, boolean retried) {
            if (failure != null) {
                if (!(failure instanceof CancellationException)) {
                    result.completeExceptionally(new McpClientException("Request " + method + " failed: " + failure.getMessage(), failure));
                }
                return;
            }
            open.set(response.body());
            if (result.isCancelled()) {
                cancel();
                return;
            }
            int    status    = response.statusCode();
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
            try (InputStream stream = response.body()) {
                if (authenticator != null && (status == 401 || status == 403)) {
                    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    if (!retried && authenticator.onChallenge(status, challenge)) {
                        send(true);
                        return;
                    }
                    result.complete(unwrap(status, parse(text), challenge));
                    return;
                }
                boolean    sse         = response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream");
                InputStream source     = sse && streamIdle != null ? new IdleGuard(stream, streamIdle) : stream;
                JsonObject message;
                try {
                    message = sse ? readEvents(source, id, sink) : parse(new String(source.readAllBytes(), StandardCharsets.UTF_8));
                } finally {
                    if (source != stream) {
                        source.close();
                    }
                }
                result.complete(unwrap(status, message, challenge));
            } catch (McpClientException e) {
                result.completeExceptionally(e);
            } catch (IOException e) {
                if (!result.isCancelled()) {
                    result.completeExceptionally(new McpClientException("Request " + method + " failed: " + e.getMessage(), e));
                }
            } catch (RuntimeException e) {
                result.completeExceptionally(new McpClientException("Authorization failed: " + e.getMessage(), e));
            }
        }
    }

    private static JsonObject unwrap(int status, JsonObject message, String challenge) {
        if (message != null && message.containsKey("error") && message.get("error").getValueType() == JsonValue.ValueType.OBJECT) {
            JsonObject error = message.getJsonObject("error");
            throw new McpClientException(status, error.getInt("code", 0), error.getString("message", "Error without message"), error.get("data"), challenge);
        }
        if (status >= 400) {
            throw new McpClientException(status, 0, "HTTP " + status + (challenge == null ? "" : " " + challenge), null, challenge);
        }
        if (message == null || !message.containsKey("result") || message.get("result").getValueType() != JsonValue.ValueType.OBJECT) {
            throw new McpClientException(status, 0, "Response has no result object", null, challenge);
        }
        return message.getJsonObject("result");
    }

    /** Reads SSE events: notifications go to the sink (if any), comment lines are skipped, and the response with the request's id ends the read. */
    private static JsonObject readEvents(InputStream stream, long id, Consumer<JsonObject> sink) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder  data   = new StringBuilder();
        String         line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    JsonObject event = parse(data.toString());
                    data.setLength(0);
                    if (event != null && event.containsKey("method")) {
                        deliver(event, sink);
                    } else if (event != null && event.containsKey("id")) {
                        return event;
                    }
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading());
            }
        }
        if (data.length() > 0) {
            JsonObject last = parse(data.toString());
            if (last != null && !last.containsKey("method")) {
                return last;
            }
        }
        throw new McpClientException("Stream ended without a response to request " + id, null);
    }

    private static void deliver(JsonObject event, Consumer<JsonObject> sink) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "A notification handler failed", e);
        }
    }

    private static void dispatch(JsonObject event, String progressToken, ProgressListener progress) {
        if (progress == null || !"notifications/progress".equals(event.getString("method", "")) || !(event.get("params") instanceof JsonObject params)) {
            return;
        }
        if (progressToken != null && !(params.get("progressToken") instanceof JsonString tokenValue && progressToken.equals(tokenValue.getString()))) {
            return;
        }
        if (!(params.get("progress") instanceof JsonNumber number)) {
            return;
        }
        Double total = params.get("total") instanceof JsonNumber t ? t.doubleValue() : null;
        try {
            progress.onProgress(new Progress(number.doubleValue(), total, params.get("message") instanceof JsonString m ? m.getString() : null));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Progress listener failed", e);
        }
    }

    /** Returns null if the text is empty or not a JSON object (a proxy error page, say). */
    private static JsonObject parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Json.createReader(new StringReader(text)).readObject();
        } catch (JsonException | IllegalStateException e) {
            return null;
        }
    }
}
