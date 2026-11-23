package dukes.agent;

import jakarta.ai.agent.LLMException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * The LargeLanguageModel that the agents inject, supplied by the application. Because this bean exists, Payara does not create its own default
 * (its AgenticAIExtension skips it when the application has a managed bean of type LargeLanguageModel), so payara.agentic.llm.* is not used.
 * Payara 7.2026.10 knows no OpenAI-format provider; this class adds it next to Ollama and Anthropic.
 * <p>
 * Settings (llm.env, read from system properties or environment variables), the same names in all three applications:
 * <ul>
 *   <li>LLM_PROVIDER: {@code ollama} (default, Ollama's native /api/chat), {@code anthropic} or {@code claude} (Messages API /v1/messages),
 *       {@code openai} (Chat Completions /chat/completions: OpenAI, Groq, OpenRouter, Mistral, Ollama's /v1 and others).</li>
 *   <li>LLM_BASE_URL: the server. For openai it includes the version path, for example https://api.openai.com/v1.</li>
 *   <li>LLM_MODEL, LLM_API_KEY, LLM_MAX_TOKENS (anthropic: default 4096; openai: sent only if set, because newer OpenAI models reject max_tokens).</li>
 * </ul>
 * Stateless: every query is one user message, no conversation is kept between calls.
 */
@ApplicationScoped
public class ConfiguredLlm implements LargeLanguageModel {
    private static final Pattern    PLACEHOLDER = Pattern.compile("\\{\\}");
    private static final Duration   TIMEOUT     = Duration.ofSeconds(240);
    /** The same default as Payara's own Anthropic backend. 1024 was too small for thinking models (for example qwen3 on Ollama), which spend it before they answer. */
    private static final int        ANTHROPIC_DEFAULT_MAX_TOKENS = 4096;
    private final        HttpClient http        = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final        Jsonb      jsonb       = JsonbBuilder.create();


    @Override public String query(final String prompt) {
        if (prompt == null) { throw new IllegalArgumentException("prompt must not be null"); }
        final String provider = setting("LLM_PROVIDER", "ollama").toLowerCase();
        return switch (provider) {
            case "ollama"              -> ollama(prompt);
            case "anthropic", "claude" -> anthropic(prompt);
            case "openai"              -> openai(prompt);
            default                    -> throw new LLMException("Unknown LLM_PROVIDER '" + provider + "'; use ollama, anthropic or openai");
        };
    }

    @Override public <T> T query(final String prompt, final Class<T> resultType) {
        if (resultType == null) { throw new IllegalArgumentException("resultType must not be null"); }
        final String answer = query(prompt);
        if (resultType == String.class) { return resultType.cast(answer); }
        final int from = answer.indexOf('{');
        final int to   = answer.lastIndexOf('}');
        try {
            return jsonb.fromJson(from >= 0 && to > from ? answer.substring(from, to + 1) : answer, resultType);
        } catch (RuntimeException e) {
            throw new LLMException("Failed to deserialize LLM response to " + resultType.getName(), e);
        }
    }

    @Override public String query(final String prompt, final Object... parameters) {
        return query(fill(prompt, parameters));
    }

    @Override public <T> T query(final String prompt, final Class<T> resultType, final Object... parameters) {
        return query(fill(prompt, parameters), resultType);
    }

    @Override public <T> T unwrap(final Class<T> implClass) {
        if (implClass == null) { throw new IllegalArgumentException("implClass must not be null"); }
        if (implClass.isInstance(this)) { return implClass.cast(this); }
        throw new IllegalArgumentException("Cannot unwrap to " + implClass.getName());
    }


    // ******************** Providers *****************************************
    private String ollama(final String prompt) {
        final JsonObject body = Json.createObjectBuilder().add("model", model()).add("stream", false).add("think", false)
                                    .add("messages", Json.createArrayBuilder().add(user(prompt))).build();
        final JsonObject reply = post(baseUrl("http://localhost:11434") + "/api/chat", body, Json.createObjectBuilder().build());
        return text(reply.containsKey("message") ? reply.getJsonObject("message").getString("content", "") : "");
    }

    private String anthropic(final String prompt) {
        final JsonObject body = Json.createObjectBuilder().add("model", model()).add("max_tokens", maxTokens() > 0 ? maxTokens() : ANTHROPIC_DEFAULT_MAX_TOKENS)
                                    .add("messages", Json.createArrayBuilder().add(user(prompt))).build();
        final JsonObject reply = post(withoutV1(baseUrl("https://api.anthropic.com")) + "/v1/messages", body,
                                      Json.createObjectBuilder().add("x-api-key", setting("LLM_API_KEY", "")).add("anthropic-version", "2023-06-01").build());
        final StringBuilder text  = new StringBuilder();
        final StringBuilder kinds = new StringBuilder();
        final var           content = reply.containsKey("content") && !reply.isNull("content") ? reply.getJsonArray("content") : Json.createArrayBuilder().build();
        for (JsonValue block : content) {
            final String type = block.asJsonObject().getString("type", "?");
            kinds.append(kinds.isEmpty() ? "" : ", ").append(type);
            if ("text".equals(type)) { text.append(block.asJsonObject().getString("text", "")); }
        }
        if (text.toString().replaceAll("(?s)<think>.*?</think>", "").isBlank()) {
            final String stop = reply.getString("stop_reason", "unknown");
            throw new LLMException("The model returned no text (stop_reason " + stop + ", content blocks: " + (kinds.isEmpty() ? "none" : kinds) + ")."
                                   + ("max_tokens".equals(stop) ? " The answer limit of " + (maxTokens() > 0 ? maxTokens() : ANTHROPIC_DEFAULT_MAX_TOKENS) + " tokens was used up, probably by the model's thinking: raise LLM_MAX_TOKENS, or for Ollama use LLM_PROVIDER=ollama, which switches thinking off." : ""));
        }
        return text(text.toString());
    }

    private String openai(final String prompt) {
        // max_tokens only if LLM_MAX_TOKENS is set: newer OpenAI models reject it (they want max_completion_tokens), other services accept it
        final JsonObjectBuilder builder = Json.createObjectBuilder().add("model", model()).add("messages", Json.createArrayBuilder().add(user(prompt)));
        if (maxTokens() > 0) { builder.add("max_tokens", maxTokens()); }
        final JsonObject body  = builder.build();
        final JsonObject reply = post(baseUrl("https://api.openai.com/v1") + "/chat/completions", body,
                                      Json.createObjectBuilder().add("authorization", "Bearer " + setting("LLM_API_KEY", "")).build());
        final var choices = reply.getJsonArray("choices");
        if (choices == null || choices.isEmpty()) { throw new LLMException("The model returned no choices: " + reply); }
        final JsonObject message = choices.getJsonObject(0).getJsonObject("message");
        return text(message == null || message.isNull("content") ? "" : message.getString("content", ""));
    }


    // ******************** Helpers *******************************************
    private JsonObject post(final String url, final JsonObject body, final JsonObject headers) {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("content-type", "application/json");
        headers.forEach((name, value) -> request.header(name, ((jakarta.json.JsonString) value).getString()));
        try {
            final HttpResponse<String> response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) { throw new LLMException("The model server at " + url + " answered HTTP " + response.statusCode() + ": " + response.body()); }
            return Json.createReader(new StringReader(response.body())).readObject();
        } catch (IOException e) {
            throw new LLMException("The model server at " + url + " is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Interrupted while waiting for the model", e);
        }
    }

    private static JsonObjectBuilder user(final String prompt) {
        return Json.createObjectBuilder().add("role", "user").add("content", prompt);
    }

    /** The answer without a reasoning block; throws if nothing is left. */
    private static String text(final String raw) {
        final String text = raw.replaceAll("(?s)<think>.*?</think>", "").strip();
        if (text.isEmpty()) { throw new LLMException("The model returned no answer"); }
        return text;
    }

    /** Replaces each {} with the next parameter as JSON, like Payara's own implementation. */
    private String fill(final String prompt, final Object... parameters) {
        if (prompt == null) { throw new IllegalArgumentException("prompt must not be null"); }
        final Object[] values = parameters == null ? new Object[0] : parameters;
        final Matcher  matcher = PLACEHOLDER.matcher(prompt);
        final StringBuilder result = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            if (index >= values.length) { throw new IllegalArgumentException("Prompt has more {} placeholders than the " + values.length + " parameters supplied"); }
            final Object value = values[index++];
            matcher.appendReplacement(result, Matcher.quoteReplacement(value instanceof String s ? s : jsonb.toJson(value)));
        }
        if (index != values.length) { throw new IllegalArgumentException("Prompt has " + index + " {} placeholders but " + values.length + " parameters were supplied"); }
        matcher.appendTail(result);
        return result.toString();
    }

    private String baseUrl(final String fallback) { return setting("LLM_BASE_URL", fallback).replaceAll("/+$", ""); }

    /** A base URL left with /v1 from an openai setup would give /v1/v1/messages, so /v1 is dropped here. */
    private static String withoutV1(final String url) { return url.replaceAll("/v1$", ""); }

    private String model() { return setting("LLM_MODEL", "qwen3.8:27b"); }

    /** LLM_MAX_TOKENS, or 0 if not set (anthropic then uses 4096, openai sends no limit). */
    private int maxTokens() { return Integer.parseInt(setting("LLM_MAX_TOKENS", "0")); }

    private static String setting(final String name, final String fallback) { return Settings.get(name, fallback); }
}
