package eu.hansolo.mcp.client;

import jakarta.json.JsonObject;

/**
 * Receives what a {@code subscriptions/listen} stream reports. Every method does nothing by default. They run on the stream's own thread, one at
 * a time, so a slow one delays the next event: hand long work to another thread. An exception is logged and ignored.
 */
public interface McpListener {

    /** The server accepted the subscription; {@code honored} is the part of the filter it will serve. Also called after every reconnect. */
    default void onSubscribed(JsonObject honored) {
    }

    default void onToolsListChanged() {
    }

    default void onPromptsListChanged() {
    }

    default void onResourcesListChanged() {
    }

    default void onResourceUpdated(String uri) {
    }

    /** A watched task changed (also once for its state when the stream opens). */
    default void onTaskUpdated(TaskState task) {
    }

    /**
     * The stream is gone. Events may have been missed meanwhile (there is no resumption), so the client has already dropped its list caches; read
     * the resources and tasks you care about again after the next {@link #onSubscribed}.
     *
     * @param cause     why, or null if the server ended the stream normally (its token expired, say)
     * @param willRetry false if the client gave up (the server refused the request); the subscription is then dead
     */
    default void onDisconnected(Throwable cause, boolean willRetry) {
    }
}
