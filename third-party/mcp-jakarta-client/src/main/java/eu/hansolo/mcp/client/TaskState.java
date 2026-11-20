package eu.hansolo.mcp.client;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

/**
 * The state of a task of the {@code io.modelcontextprotocol/tasks} extension, as {@code tasks/get} or a task-creating {@code tools/call}
 * reports it. Applications create none of these. A status this version does not know counts as not terminal.
 *
 * @param taskId         the id, which is also the {@code Mcp-Name} of every {@code tasks/*} request
 * @param status         {@code working}, {@code input_required}, {@code completed}, {@code failed} or {@code cancelled}
 * @param statusMessage  what the task says it is doing, or null
 * @param createdAt      ISO-8601 time, or null
 * @param lastUpdatedAt  ISO-8601 time, or null
 * @param ttlMs          how long the server keeps the task after it ended, or null for no limit or unknown
 * @param pollIntervalMs the server's hint for polling, or null
 * @param inputRequests  the open requests while the task waits for input, otherwise null
 * @param result         the tool result of a completed task (the object a plain {@code tools/call} would have returned), otherwise null
 * @param error          the {@code code} and {@code message} of a failed task, otherwise null
 * @param raw            the object as sent by the server
 */
public record TaskState(String taskId, String status, String statusMessage, String createdAt, String lastUpdatedAt, Long ttlMs, Long pollIntervalMs, JsonObject inputRequests, JsonObject result, JsonObject error, JsonObject raw) {

    static TaskState of(JsonObject json) {
        return new TaskState(json.getString("taskId", null), json.getString("status", ""), json.getString("statusMessage", null), json.getString("createdAt", null), json.getString("lastUpdatedAt", null),
                             json.get("ttlMs") instanceof JsonNumber ttl ? ttl.longValue() : null, json.get("pollIntervalMs") instanceof JsonNumber poll ? poll.longValue() : null,
                             json.get("inputRequests") instanceof JsonObject requests ? requests : null, json.get("result") instanceof JsonObject result ? result : null,
                             json.get("error") instanceof JsonObject error ? error : null, json);
    }

    public boolean working() {
        return "working".equals(status);
    }

    public boolean inputRequired() {
        return "input_required".equals(status);
    }

    public boolean completed() {
        return "completed".equals(status);
    }

    public boolean failed() {
        return "failed".equals(status);
    }

    public boolean cancelled() {
        return "cancelled".equals(status);
    }

    /** True once the task will not change any more. */
    public boolean terminal() {
        return completed() || failed() || cancelled();
    }

    /** The result of a completed task as a {@link ToolResult}; null while the task has none. */
    public ToolResult toolResult() {
        return result == null ? null : McpClient.toResult(result);
    }

    /** The error of a failed task as an exception, or null if there is none. */
    public McpClientException failure() {
        return error == null ? null : new McpClientException(200, error.getInt("code", 0), error.get("message") instanceof JsonString message ? message.getString() : "The task failed", error.get("data"), null);
    }
}
