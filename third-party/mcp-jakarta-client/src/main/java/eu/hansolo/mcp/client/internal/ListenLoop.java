package eu.hansolo.mcp.client.internal;

import eu.hansolo.mcp.client.McpClientException;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Internal. Keeps one {@code subscriptions/listen} stream open: reconnects with backoff after the server ends it or it fails, and gives up on errors a retry cannot fix. */
public final class ListenLoop {

    private static final Set<Integer> FATAL_STATUS = Set.of(400, 403, 404, 405, 406);
    private static final long         STABLE_NANOS = 5_000_000_000L;

    private final McpTransport                 transport;
    private final JsonObject                   params;
    private final Callbacks                    callbacks;
    private final long                         minBackoffMs;
    private final long                         maxBackoffMs;
    private final AtomicReference<McpTransport.Running> current = new AtomicReference<>();
    private       Thread                       thread;
    private volatile boolean                   closed;


    /** What the loop reports. Both run on the loop's thread, so a slow callback delays reading the stream. */
    public interface Callbacks {

        /** A notification of the stream, the acknowledgement included. */
        void message(JsonObject message);

        /** The stream ended or could not be opened. {@code cause} is null if the server ended it with its final response. */
        void disconnected(Throwable cause, boolean willRetry);
    }


    public ListenLoop(McpTransport transport, JsonObject params, Callbacks callbacks, Duration minBackoff, Duration maxBackoff) {
        this.transport    = transport;
        this.params       = params;
        this.callbacks    = callbacks;
        this.minBackoffMs = Math.max(1, minBackoff.toMillis());
        this.maxBackoffMs = Math.max(minBackoffMs, maxBackoff.toMillis());
    }


    public void start() {
        thread = Thread.ofVirtual().name("mcp-listen").unstarted(this::run);
        thread.start();
    }

    public void close() {
        closed = true;
        McpTransport.Running running = current.get();
        if (running != null) {
            running.cancel().run();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        long backoff = minBackoffMs;
        while (!closed) {
            long     started = System.nanoTime();
            Throwable failure = null;
            boolean   fatal   = false;
            McpTransport.Running running = transport.startStream("subscriptions/listen", params, callbacks::message);
            current.set(running);
            try {
                running.result().get();
            } catch (CancellationException e) {
                break;
            } catch (InterruptedException e) {
                running.cancel().run();
                break;
            } catch (ExecutionException e) {
                failure = e.getCause();
                fatal   = isFatal(failure);
            }
            if (closed) {
                break;
            }
            boolean stable = System.nanoTime() - started > STABLE_NANOS;
            if (stable) {
                backoff = minBackoffMs;
            }
            callbacks.disconnected(failure, !fatal);
            if (fatal) {
                break;
            }
            long delay = failure == null && stable ? 0 : backoff;
            if (!stable) {
                backoff = Math.min(backoff * 2, maxBackoffMs);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof McpClientException e && (FATAL_STATUS.contains(e.httpStatus()) || e.code() == -32021 || e.code() == -32022 || e.code() == -32602);
    }
}
