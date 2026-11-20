package eu.hansolo.mcp.client;

import eu.hansolo.mcp.client.internal.ListCache;
import eu.hansolo.mcp.client.internal.ListenLoop;
import eu.hansolo.mcp.client.internal.FormValidator;
import eu.hansolo.mcp.client.internal.McpTransport;
import eu.hansolo.mcp.client.internal.OAuthClient;
import eu.hansolo.mcp.client.internal.ParamHeaders;
import eu.hansolo.mcp.client.internal.ToolSchemas;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A client for MCP 2026-07-28 over Streamable HTTP. The protocol is stateless, so an instance holds no session; it is
 * thread-safe and meant to be shared.
 */
public final class McpClient {

    public static final String PROTOCOL_VERSION = "2026-07-28";

    private final McpTransport          transport;
    private final ToolSchemas           schemas;
    private final Elicitation.Handler   elicitation;
    private final Set<Elicitation.Mode> elicitationModes;
    private final int                   maxInputRounds;
    private final boolean               validateElicitation;
    private final Supplier<String>      token;
    private final ListCache             toolsCache;
    private final ListCache             promptsCache;
    private final ListCache             resourcesCache;
    private final ListCache             templatesCache;
    private final Duration              minBackoff;
    private final Duration              maxBackoff;
    private       JsonObject            discovered;
    private       long                  discoveredUntil;
    private       boolean               discoveredPrivate;
    private       String                discoveredFor;


    private McpClient(Builder builder) {
        OAuthClient oauth = builder.oauthClientId == null ? null : new OAuthClient(builder.http, builder.endpoint, builder.oauthClientId, builder.oauthClientSecret, builder.oauthScopes, builder.oauthTokenEndpoint, builder.timeout, builder.authorizer);
        this.token            = oauth != null ? oauth::token : builder.token;
        this.transport        = new McpTransport(builder.http, builder.endpoint, builder.clientName, builder.clientVersion, capabilitiesOf(builder), this.token, builder.timeout, builder.streamIdle, oauth);
        this.minBackoff       = builder.minBackoff;
        this.maxBackoff       = builder.maxBackoff;
        this.toolsCache       = new ListCache(params -> transport.call("tools/list", null, params), "tools", this::callerKey);
        this.promptsCache     = new ListCache(params -> transport.call("prompts/list", null, params), "prompts", this::callerKey);
        this.resourcesCache   = new ListCache(params -> transport.call("resources/list", null, params), "resources", this::callerKey);
        this.templatesCache   = new ListCache(params -> transport.call("resources/templates/list", null, params), "resourceTemplates", this::callerKey);
        this.schemas          = new ToolSchemas(toolsCache);
        this.elicitation      = builder.elicitation;
        this.elicitationModes = EnumSet.copyOf(builder.elicitationModes);
        this.maxInputRounds   = builder.maxInputRounds;
        this.validateElicitation = builder.validateElicitation;
    }


    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    /**
     * Returns the result of {@code server/discover} (supported versions, capabilities, extensions, serverInfo), kept for the server's {@code ttlMs}.
     * An answer marked {@code cacheScope: private} is reused only while the bearer token is the same.
     */
    public synchronized JsonObject discover() {
        if (discovered == null || System.nanoTime() - discoveredUntil >= 0 || (discoveredPrivate && !Objects.equals(discoveredFor, callerKey()))) {
            JsonObject fresh = transport.call("server/discover", null, Json.createObjectBuilder().build());
            discoveredFor     = callerKey();
            discoveredPrivate = ListCache.isPrivate(fresh);
            discoveredUntil   = System.nanoTime() + Math.max(ListCache.ttlMs(fresh), 0) * 1_000_000L;
            discovered        = fresh;
        }
        return discovered;
    }

    /** Calls {@code tools/list} and returns the first page of the result object as the server sent it, without using the cache. See {@link #tools()}. */
    public JsonObject listTools() {
        return transport.call("tools/list", null, Json.createObjectBuilder().build());
    }

    /** All tools (every page of {@code tools/list}), cached for the server's {@code ttlMs} and dropped by a {@code tools/list_changed} notification of a {@link #listen} stream. */
    public List<JsonObject> tools() {
        return toolsCache.get();
    }

    /** All prompts, cached like {@link #tools()}. A server without prompts answers -32601. */
    public List<JsonObject> prompts() {
        return promptsCache.get();
    }

    /** All fixed resources, cached like {@link #tools()}. A server without resources answers -32601. */
    public List<JsonObject> resources() {
        return resourcesCache.get();
    }

    /** All resource templates, cached like {@link #tools()}. */
    public List<JsonObject> resourceTemplates() {
        return templatesCache.get();
    }

    /** Forgets everything cached: discovery and the four lists. */
    public synchronized void clearCaches() {
        discovered = null;
        toolsCache.invalidate();
        promptsCache.invalidate();
        resourcesCache.invalidate();
        templatesCache.invalidate();
    }

    private String callerKey() {
        String bearer = token.get();
        return bearer == null ? "" : bearer;
    }

    // ---- notifications --------------------------------------------------------------------------------------------

    /**
     * Opens a {@code subscriptions/listen} stream and keeps it open: after the server ends it (token expiry, shutdown) or it fails, the client
     * connects again with backoff (see {@link Builder#listenBackoff}), asking the token supplier for a fresh token each time. It gives up only
     * when the server refuses the request, which {@link McpListener#onDisconnected} reports with {@code willRetry == false}. The caches of this client
     * are kept in step: a list-changed notification drops the matching cache, a lost stream drops all of them.
     */
    public Subscription listen(ListenFilter filter, McpListener listener) {
        JsonObject params = Json.createObjectBuilder().add("notifications", filter.toJson()).build();
        ListenLoop loop   = new ListenLoop(transport, params, new ListenLoop.Callbacks() {

            @Override
            public void message(JsonObject message) {
                route(message, listener);
            }

            @Override
            public void disconnected(Throwable cause, boolean willRetry) {
                clearCaches();
                guarded(() -> listener.onDisconnected(cause, willRetry));
            }
        }, minBackoff, maxBackoff);
        loop.start();
        return new Subscription(loop::close);
    }

    /** Listens for list changes only, to keep {@link #tools()} and the other cached lists current. Close the result when the client is no longer needed. */
    public Subscription keepListsFresh() {
        return listen(ListenFilter.create().listsChanged(), new McpListener() {
        });
    }

    private void route(JsonObject message, McpListener listener) {
        JsonObject params = message.get("params") instanceof JsonObject object ? object : Json.createObjectBuilder().build();
        switch (message.getString("method", "")) {
            case "notifications/subscriptions/acknowledged" -> guarded(() -> listener.onSubscribed(params.get("notifications") instanceof JsonObject honored ? honored : Json.createObjectBuilder().build()));
            case "notifications/tools/list_changed" -> {
                toolsCache.invalidate();
                guarded(listener::onToolsListChanged);
            }
            case "notifications/prompts/list_changed" -> {
                promptsCache.invalidate();
                guarded(listener::onPromptsListChanged);
            }
            case "notifications/resources/list_changed" -> {
                resourcesCache.invalidate();
                templatesCache.invalidate();
                guarded(listener::onResourcesListChanged);
            }
            case "notifications/resources/updated" -> guarded(() -> listener.onResourceUpdated(params.getString("uri", "")));
            case "notifications/tasks" -> guarded(() -> listener.onTaskUpdated(TaskState.of(params)));
            default -> {
            }
        }
    }

    private static void guarded(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            System.getLogger(McpClient.class.getName()).log(System.Logger.Level.WARNING, "A listener failed", e);
        }
    }

    /** Calls a tool and waits for the result. See {@link #callTool(String, JsonObject, ProgressListener)}. */
    public ToolResult callTool(String name, JsonObject arguments) {
        return callTool(name, arguments, null);
    }

    /**
     * Calls a tool and waits for the result. Arguments that the tool's schema marks with {@code x-mcp-header} are mirrored into
     * {@code Mcp-Param-*} headers; the schemas are fetched with {@code tools/list} and cached for the server's {@code ttlMs}. A header
     * mismatch (-32020) reloads them and retries once. A tool that failed returns a result with {@code isError}; protocol failures throw
     * {@link McpClientException}. Interrupting the calling thread cancels the call. The listener, if given, runs on a client thread.
     */
    public ToolResult callTool(String name, JsonObject arguments, ProgressListener progress) {
        JsonObject args   = arguments == null ? Json.createObjectBuilder().build() : arguments;
        JsonObject params = Json.createObjectBuilder().add("name", name).add("arguments", args).build();
        try {
            return toResult(withInput("tools/call", name, params, headersFor(name, args), progress));
        } catch (McpClientException e) {
            if (e.code() != ErrorCodes.HEADER_MISMATCH) {
                throw e;
            }
            schemas.invalidate();
            return toResult(withInput("tools/call", name, params, headersFor(name, args), progress));
        }
    }

    /** Gets a prompt (prompts/get) and returns the raw result: {@code description} and {@code messages}. Arguments are strings, as the protocol has them; null for none. */
    public JsonObject getPrompt(String name, Map<String, String> arguments) {
        JsonObjectBuilder args = Json.createObjectBuilder();
        if (arguments != null) {
            arguments.forEach(args::add);
        }
        JsonObject params = Json.createObjectBuilder().add("name", name).add("arguments", args).build();
        return withInput("prompts/get", name, params, Map.of(), null);
    }

    /** Reads a resource (resources/read) and returns the raw result: {@code contents}, each entry with {@code uri}, {@code mimeType} and {@code text} or {@code blob}. */
    public JsonObject readResource(String uri) {
        JsonObject params = Json.createObjectBuilder().add("uri", uri).build();
        return withInput("resources/read", uri, params, Map.of(), null);
    }

    /** Suggestions for an argument of a prompt (completion/complete with a prompt reference). {@code context} holds the arguments already filled in; null for none. */
    public Completion completePrompt(String promptName, String argument, String value, Map<String, String> context) {
        return complete(Json.createObjectBuilder().add("type", "ref/prompt").add("name", promptName).build(), argument, value, context);
    }

    /** Suggestions for a variable of a resource template, for example {@code completeResource("ticket://{id}", "id", "1", null)}. */
    public Completion completeResource(String uriTemplate, String argument, String value, Map<String, String> context) {
        return complete(Json.createObjectBuilder().add("type", "ref/resource").add("uri", uriTemplate).build(), argument, value, context);
    }

    private Completion complete(JsonObject ref, String argument, String value, Map<String, String> context) {
        JsonObjectBuilder params = Json.createObjectBuilder().add("ref", ref).add("argument", Json.createObjectBuilder().add("name", argument).add("value", value));
        if (context != null && !context.isEmpty()) {
            JsonObjectBuilder arguments = Json.createObjectBuilder();
            context.forEach(arguments::add);
            params.add("context", Json.createObjectBuilder().add("arguments", arguments));
        }
        JsonObject result = withInput("completion/complete", null, params.build(), Map.of(), null);
        if (!(result.get("completion") instanceof JsonObject completion)) {
            throw new McpClientException("The completion result has no 'completion' object", null);
        }
        List<String> values = new java.util.ArrayList<>();
        if (completion.get("values") instanceof jakarta.json.JsonArray array) {
            for (JsonValue entry : array) {
                if (entry instanceof JsonString text) {
                    values.add(text.getString());
                }
            }
        }
        return new Completion(List.copyOf(values), completion.getInt("total", -1), completion.getBoolean("hasMore", false));
    }

    /**
     * Starts a tool call on its own thread and returns at once; everything {@link #callTool(String, JsonObject, ProgressListener)} does
     * happens there, including the elicitation handler. {@link ToolCallHandle#cancel()} interrupts that thread, which drops the connection.
     */
    public ToolCallHandle startToolCall(String name, JsonObject arguments, ProgressListener progress) {
        CompletableFuture<ToolResult> out    = new CompletableFuture<>();
        Thread                        thread = Thread.ofVirtual().unstarted(() -> {
            try {
                out.complete(callTool(name, arguments, progress));
            } catch (Throwable failure) {
                out.completeExceptionally(failure);
            }
        });
        thread.start();
        return new ToolCallHandle(out, () -> {
            out.cancel(true);
            thread.interrupt();
        });
    }

    /**
     * Sends a request and, while the server answers {@code input_required} and a handler is registered, collects the answers and repeats
     * the same request with {@code inputResponses} and the unchanged {@code requestState}. Without a handler the {@code input_required}
     * result is returned as it is.
     */
    private JsonObject withInput(String method, String name, JsonObject params, Map<String, String> headers, ProgressListener progress) {
        JsonObject current = params;
        for (int round = 0; ; round++) {
            JsonObject result = transport.call(method, name, current, headers, progress);
            if (elicitation == null || !"input_required".equals(result.getString("resultType", ""))) {
                return result;
            }
            if (round >= maxInputRounds) {
                throw new McpClientException("The server still asks for input after " + maxInputRounds + " rounds", null);
            }
            JsonObjectBuilder next    = Json.createObjectBuilder(params);
            JsonObject        answers = answersTo(result.get("inputRequests") instanceof JsonObject requests ? requests : Json.createObjectBuilder().build());
            if (!answers.isEmpty()) {
                next.add("inputResponses", answers);
            }
            if (result.get("requestState") instanceof JsonString state) {
                next.add("requestState", state.getString());
            }
            current = next.build();
        }
    }

    private JsonObject answersTo(JsonObject requests) {
        JsonObjectBuilder answers = Json.createObjectBuilder();
        for (var entry : requests.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject request) || !"elicitation/create".equals(request.getString("method", "")) || !(request.get("params") instanceof JsonObject params)) {
                throw new McpClientException("The server asked for input this client does not support: '" + entry.getKey() + "'", null);
            }
            Elicitation.Mode mode = "url".equals(params.getString("mode", "form")) ? Elicitation.Mode.URL : Elicitation.Mode.FORM;
            if (!elicitationModes.contains(mode)) {
                throw new McpClientException("The server asked in " + mode + " mode, which this client did not declare", null);
            }
            Elicitation.Request asked = new Elicitation.Request(entry.getKey(), mode, params.getString("message", ""), params.get("requestedSchema") instanceof JsonObject schema ? schema : null, params.getString("url", null), request);
            Elicitation.Response response = elicitation.elicit(asked);
            if (response == null) {
                response = Elicitation.Response.cancel();
            }
            JsonObjectBuilder answer = Json.createObjectBuilder().add("action", response.action().name().toLowerCase(java.util.Locale.ROOT));
            if (response.action() == Elicitation.Action.ACCEPT && mode == Elicitation.Mode.FORM) {
                JsonObject content = response.content() == null ? Json.createObjectBuilder().build() : response.content();
                if (validateElicitation) {
                    java.util.List<String> problems = FormValidator.problems(asked.requestedSchema(), content);
                    if (!problems.isEmpty()) {
                        throw new McpClientException("The answer for '" + entry.getKey() + "' does not fit the requested form: " + String.join("; ", problems), null);
                    }
                }
                answer.add("content", content);
            }
            answers.add(entry.getKey(), answer);
        }
        return answers.build();
    }

    private static JsonObject capabilitiesOf(Builder builder) {
        JsonObjectBuilder capabilities = Json.createObjectBuilder(builder.capabilities);
        if (builder.elicitation != null) {
            JsonObjectBuilder modes = Json.createObjectBuilder();
            for (Elicitation.Mode mode : builder.elicitationModes) {
                modes.add(mode == Elicitation.Mode.FORM ? "form" : "url", Json.createObjectBuilder());
            }
            capabilities.add("elicitation", modes);
        }
        if (!builder.extensions.isEmpty()) {
            JsonObjectBuilder extensions = Json.createObjectBuilder();
            if (builder.capabilities.get("extensions") instanceof JsonObject declared) {
                for (var entry : declared.entrySet()) {
                    extensions.add(entry.getKey(), entry.getValue());
                }
            }
            for (var entry : builder.extensions.entrySet()) {
                extensions.add(entry.getKey(), entry.getValue());
            }
            capabilities.add("extensions", extensions);
        }
        return capabilities.build();
    }

    // ---- tasks ----------------------------------------------------------------------------------------------------

    /** Reads the state of a task ({@code tasks/get}). The builder must have declared the extension with {@link Builder#tasks()}, else the server answers -32021. */
    public TaskState getTask(String taskId) {
        return TaskState.of(transport.call("tasks/get", taskId, Json.createObjectBuilder().add("taskId", taskId).build()));
    }

    /** Sends answers to the open requests of a task ({@code tasks/update}); {@code inputResponses} has the shape of the {@code inputResponses} of a multi-round-trip retry. */
    public void updateTask(String taskId, JsonObject inputResponses) {
        transport.call("tasks/update", taskId, Json.createObjectBuilder().add("taskId", taskId).add("inputResponses", inputResponses).build());
    }

    /** Asks the task to stop ({@code tasks/cancel}). Cancellation is cooperative: the task may still end as completed, so read the state afterwards. */
    public void cancelTask(String taskId) {
        transport.call("tasks/cancel", taskId, Json.createObjectBuilder().add("taskId", taskId).build());
    }

    /**
     * Polls a task until it ends and returns its final state. The pause follows the server's {@code pollIntervalMs} (at least 50 ms, at most 5 s).
     * While the task waits for input and an elicitation handler is registered, the handler answers and the answers go to {@code tasks/update};
     * the same open requests are answered again only after 10 polls without change (at most {@code maxInputRounds} answers in all).
     * Throws {@link McpClientException} when the time is up; the task keeps running. Interrupting the thread stops the waiting, not the task.
     */
    public TaskState awaitTask(String taskId, Duration timeout) {
        long   deadline  = System.nanoTime() + timeout.toNanos();
        String answered  = null;
        int    answers   = 0;
        int    sinceLast = 0;
        while (true) {
            TaskState state = getTask(taskId);
            if (state.terminal()) {
                return state;
            }
            if (state.inputRequired() && elicitation != null && state.inputRequests() != null && !state.inputRequests().isEmpty()) {
                String requests = state.inputRequests().toString();
                if (!requests.equals(answered) || sinceLast >= 10) {
                    if (answers >= maxInputRounds) {
                        throw new McpClientException("The task still asks for input after " + maxInputRounds + " answers", null);
                    }
                    answers++;
                    updateTask(taskId, answersTo(state.inputRequests()));
                    answered  = requests;
                    sinceLast = 0;
                } else {
                    sinceLast++;
                }
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) {
                throw new McpClientException("The task did not finish within " + timeout, null);
            }
            long pause = Math.min(Math.max(state.pollIntervalMs() == null ? 1000 : state.pollIntervalMs(), 50), 5000);
            try {
                Thread.sleep(Math.min(pause, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpClientException("Interrupted while waiting for the task", e);
            }
        }
    }

    /**
     * Calls a tool and, if the server started a task, waits for it (see {@link #awaitTask}). A completed task gives its result; a failed one
     * throws the task's error; a cancelled one throws. A server that answers at once, or a client that did not declare the extension,
     * gets the plain result.
     */
    public ToolResult callToolAndAwait(String name, JsonObject arguments, ProgressListener progress, Duration timeout) {
        ToolResult first = callTool(name, arguments, progress);
        if (!first.isTask()) {
            return first;
        }
        TaskState done = first.task().terminal() ? first.task() : awaitTask(first.task().taskId(), timeout);
        if (done.completed()) {
            return done.toolResult();
        }
        if (done.failed()) {
            throw done.failure() == null ? new McpClientException("The task failed", null) : done.failure();
        }
        throw new McpClientException("The task was cancelled", null);
    }

    static ToolResult toResult(JsonObject result) {
        JsonArray  content    = result.get("content") instanceof JsonArray array ? array : Json.createArrayBuilder().build();
        JsonObject structured = result.get("structuredContent") instanceof JsonObject object ? object : null;
        return new ToolResult(result.getString("resultType", "complete"), result.getBoolean("isError", false), content, structured, result);
    }

    private Map<String, String> headersFor(String tool, JsonObject arguments) {
        return ParamHeaders.headers(schemas.paramsFor(tool), arguments);
    }


    public static final class Builder {

        private final URI              endpoint;
        private       HttpClient       http          = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private       String           clientName    = "mcp-jakarta-client";
        private       String           clientVersion = "0.1.0";
        private       JsonObject       capabilities  = Json.createObjectBuilder().build();
        private       Supplier<String> token         = () -> null;
        private       Duration         timeout       = Duration.ofSeconds(30);
        private       Elicitation.Handler   elicitation;
        private       Set<Elicitation.Mode> elicitationModes = EnumSet.allOf(Elicitation.Mode.class);
        private       int                   maxInputRounds   = 8;
        private       boolean               validateElicitation = true;
        private       Duration              streamIdle;
        private       Authorizer            authorizer;
        private       String                oauthClientId;
        private       String                oauthClientSecret;
        private       List<String>          oauthScopes      = List.of();
        private       URI                   oauthTokenEndpoint;
        private       Duration              minBackoff       = Duration.ofSeconds(1);
        private       Duration              maxBackoff       = Duration.ofSeconds(30);
        private final Map<String, JsonObject> extensions       = new LinkedHashMap<>();


        private Builder(URI endpoint) {
            this.endpoint = endpoint;
        }


        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        public Builder clientInfo(String name, String version) {
            this.clientName    = name;
            this.clientVersion = version;
            return this;
        }

        /** The {@code clientCapabilities} object declared in every request, for example {@code {"elicitation":{}}}. */
        public Builder capabilities(JsonObject capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        /** Supplies the bearer token per request (return null for none). This is the non-interactive auth mode: the application owns refresh. */
        public Builder bearerToken(Supplier<String> token) {
            this.token = token;
            return this;
        }

        /**
         * Lets the server ask the user for input. The client declares the given modes (none given: form and URL) in every request and repeats a call
         * that answers {@code input_required} after the handler has answered. Without it the client declares nothing and a server that needs
         * elicitation answers -32021.
         */
        public Builder elicitation(Elicitation.Handler handler, Elicitation.Mode... modes) {
            this.elicitation      = handler;
            this.elicitationModes = modes.length == 0 ? EnumSet.allOf(Elicitation.Mode.class) : EnumSet.copyOf(java.util.List.of(modes));
            return this;
        }

        /** The most times one call is repeated because the server wants more input (default 8). An answer the server refuses starts a new round. */
        /** Whether the client checks every form answer against the requested schema before it sends it (default true); an answer that does not fit fails the call with a {@link McpClientException}. */
        public Builder validateElicitation(boolean validate) {
            this.validateElicitation = validate;
            return this;
        }

        public Builder maxInputRounds(int rounds) {
            this.maxInputRounds = Math.max(1, rounds);
            return this;
        }

        /** Declares an extension in the {@code clientCapabilities} of every request, for example {@code io.modelcontextprotocol/tasks}. */
        public Builder extension(String id, JsonObject settings) {
            this.extensions.put(id, settings == null ? Json.createObjectBuilder().build() : settings);
            return this;
        }

        /** Declares the tasks extension, so that servers may answer a tool call with a task. See {@link McpClient#getTask}. */
        public Builder tasks() {
            return extension("io.modelcontextprotocol/tasks", null);
        }

        /**
         * Closes a streamed answer or a {@link McpClient#listen subscription} that has delivered nothing, not even a keep-alive comment, for this long, so that a
         * half-open connection fails (and a subscription reconnects) instead of waiting for ever. Off by default: set it larger than the server's keep-alive
         * interval, and only if the server sends keep-alive comments, because a tool that is quiet for longer would otherwise be cut off.
         */
        public Builder streamIdleTimeout(Duration idle) {
            this.streamIdle = idle;
            return this;
        }

        /**
         * Authorizes with the OAuth client credentials grant (a confidential client, no user). The client reads the server's 401 challenge, finds the authorization
         * server through the protected resource metadata, asks it for a token for this endpoint, and keeps the token until shortly before it expires. A 403
         * {@code insufficient_scope} makes it ask again for the larger set of scopes (once per request). Replaces {@link #bearerToken}. Use https; http is
         * accepted only for localhost.
         *
         * @param scopes scopes to ask for from the start (optional); those the server's challenges name are added
         */
        public Builder oauthClientCredentials(String clientId, String clientSecret, String... scopes) {
            this.oauthClientId     = clientId;
            this.oauthClientSecret = clientSecret;
            this.oauthScopes       = List.of(scopes);
            this.authorizer        = null;
            return this;
        }

        /**
         * Authorizes with the OAuth authorization code flow and PKCE for a user, with a client that is registered with the authorization server in advance (a public client; dynamic
         * registration is not supported). The first 401 starts the sign-in: the {@link Authorizer} (usually {@link LoopbackAuthorizer}) shows the page to the user and brings back the
         * redirect; the client checks {@code state} and the issuer, redeems the code with the PKCE verifier, and later refreshes the token with the refresh token if the server issued one. When
         * the refresh token is gone or refused, or a 403 asks for more scopes, the next request signs the user in again. The call that triggered the sign-in blocks until it is done.
         *
         * @param scopes scopes to ask for from the start (optional); those the server's challenges name are added
         */
        public Builder oauthAuthorizationCode(String clientId, Authorizer authorizer, String... scopes) {
            this.oauthClientId     = clientId;
            this.oauthClientSecret = null;
            this.oauthScopes       = List.of(scopes);
            this.authorizer        = authorizer;
            return this;
        }

        /** Skips discovery: the token endpoint of the authorization server, so that the first request already carries a token. Only with {@link #oauthClientCredentials}. */
        public Builder oauthTokenEndpoint(URI tokenEndpoint) {
            this.oauthTokenEndpoint = tokenEndpoint;
            return this;
        }

        /** How long {@link McpClient#listen} waits before it connects again: the first pause, doubling up to the second value (default 1 s and 30 s). */
        public Builder listenBackoff(Duration min, Duration max) {
            this.minBackoff = min;
            this.maxBackoff = max;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public McpClient build() {
            String scheme = endpoint.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || endpoint.getHost() == null) {
                throw new IllegalArgumentException("The endpoint must be an absolute http or https URL with a host: " + endpoint);
            }
            if (authorizer != null && oauthTokenEndpoint != null) {
                throw new IllegalStateException("oauthTokenEndpoint belongs to the client credentials grant; the authorization code flow reads the authorization server's metadata");
            }
            return new McpClient(this);
        }
    }
}
