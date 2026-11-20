package eu.hansolo.mcp.client.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Internal. Closes a stream that has delivered no byte for the idle time, so that a half-open connection ends in an IOException instead of waiting for ever. Keep-alive comments count as data. */
final class IdleGuard extends FilterInputStream {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mcp-client-idle-guard");
        thread.setDaemon(true);
        return thread;
    });

    private final    Duration           idle;
    private volatile long               last    = System.nanoTime();
    private volatile boolean            expired;
    private volatile ScheduledFuture<?> watcher;


    IdleGuard(InputStream in, Duration idle) {
        super(in);
        this.idle = idle;
        long period = Math.max(idle.toMillis() / 4, 10);
        this.watcher = TIMER.scheduleAtFixedRate(this::check, period, period, TimeUnit.MILLISECONDS);
    }


    private void check() {
        if (System.nanoTime() - last > idle.toNanos()) {
            expired = true;
            ScheduledFuture<?> task = watcher;
            if (task != null) {
                task.cancel(false);
            }
            try {
                in.close();
            } catch (IOException ignored) {
                // the connection is being dropped anyway
            }
        }
    }

    @Override
    public int read() throws IOException {
        try {
            int value = super.read();
            if (value >= 0) {
                last = System.nanoTime();
            }
            return value;
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                last = System.nanoTime();
            }
            return count;
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public void close() throws IOException {
        ScheduledFuture<?> task = watcher;
        if (task != null) {
            task.cancel(false);
        }
        super.close();
    }

    private IOException translate(IOException e) {
        return expired ? new IOException("No data from the server for " + idle.toMillis() + " ms; the connection was closed", e) : e;
    }
}
