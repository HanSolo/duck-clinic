package eu.hansolo.mcp.client;

/** An open {@code subscriptions/listen}. Closing it drops the connection, which ends the subscription on the server. */
public final class Subscription implements AutoCloseable {

    private final Runnable closer;


    Subscription(Runnable closer) {
        this.closer = closer;
    }


    @Override
    public void close() {
        closer.run();
    }
}
