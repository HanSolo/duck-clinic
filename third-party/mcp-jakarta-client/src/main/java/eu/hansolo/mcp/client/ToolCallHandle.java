package eu.hansolo.mcp.client;

import java.util.concurrent.CompletableFuture;

/** A tool call that is running. {@link #cancel()} closes the connection, which is how the protocol cancels a request. */
public final class ToolCallHandle {

    private final CompletableFuture<ToolResult> result;
    private final Runnable                      canceller;


    ToolCallHandle(CompletableFuture<ToolResult> result, Runnable canceller) {
        this.result    = result;
        this.canceller = canceller;
    }


    /** Completes with the result, exceptionally with {@link McpClientException}, or as cancelled after {@link #cancel()}. */
    public CompletableFuture<ToolResult> result() {
        return result;
    }

    /** Drops the connection and completes {@link #result()} as cancelled. The server learns of it by the closed stream; there is no cancel message. Safe to call more than once or after completion. */
    public void cancel() {
        canceller.run();
    }
}
