package eu.hansolo.mcp.client;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

/**
 * The result of a {@code tools/call}. A tool that failed is a result with {@code isError} set, not an exception. Applications create
 * none of these and should not rely on the record components beyond the accessors; more may be added in minor releases.
 *
 * @param resultType        {@code complete}, {@code input_required} or another value a newer server sends; absent is reported as {@code complete}
 * @param isError           true if the tool reported a failure
 * @param content           the content blocks, never null
 * @param structuredContent the structured result, or null
 * @param raw               the complete result object as sent by the server
 */
public record ToolResult(String resultType, boolean isError, JsonArray content, JsonObject structuredContent, JsonObject raw) {

    /** True if the call finished; false if the server wants more input (multi-round-trip) or sent a kind this version does not know. */
    public boolean complete() {
        return "complete".equals(resultType);
    }

    /** True if the server started a task instead of answering at once (only for a client that declared the tasks extension). See {@link #task()}. */
    public boolean isTask() {
        return "task".equals(resultType);
    }

    /** The task that was started, or null if this is not a task result. */
    public TaskState task() {
        return isTask() ? TaskState.of(raw) : null;
    }

    /** The opaque state the server sent with an {@code input_required} result, or null. Pass it back unchanged and never keep it for another caller. */
    public String requestState() {
        return raw.getString("requestState", null);
    }

    /** The {@code inputRequests} of an {@code input_required} result, or null. Only seen when the client has no elicitation handler. */
    public JsonObject inputRequests() {
        return raw.get("inputRequests") instanceof JsonObject requests ? requests : null;
    }
}
