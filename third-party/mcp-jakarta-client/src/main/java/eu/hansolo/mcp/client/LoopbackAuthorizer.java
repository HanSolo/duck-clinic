package eu.hansolo.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Receives the redirect of the authorization code flow on a port of the loopback interface (RFC 8252, section 7.3): opens the page in the user's browser and waits for the
 * browser to come back to {@code http://127.0.0.1:<port>/callback}. The listener is bound to 127.0.0.1 only and stays open until {@link #close()} so that a later
 * step-up reuses the same redirect URI. Register that URI (or {@code http://127.0.0.1:<port>/*}) with the authorization server; with port 0 the port changes with every run, so
 * use a fixed port for a client that is registered with an exact URI.
 */
public final class LoopbackAuthorizer implements Authorizer, AutoCloseable {

    private final int                   port;
    private final Consumer<URI>         browser;
    private final Duration              timeout;
    private       HttpServer            server;
    private       CompletableFuture<URI> callback = new CompletableFuture<>();


    private LoopbackAuthorizer(int port, Consumer<URI> browser, Duration timeout) {
        this.port    = port;
        this.browser = browser;
        this.timeout = timeout;
    }


    /** A free port, five minutes for the user, and the system browser (see {@link #systemBrowser()}). */
    public static LoopbackAuthorizer create() {
        return new LoopbackAuthorizer(0, systemBrowser(), Duration.ofMinutes(5));
    }

    /**
     * @param port    the port on 127.0.0.1; 0 picks a free one
     * @param browser shows the URL to the user; it may block until the page is shown but not until the user has answered
     * @param timeout how long {@link #authorize} waits for the user
     */
    public static LoopbackAuthorizer create(int port, Consumer<URI> browser, Duration timeout) {
        return new LoopbackAuthorizer(port, browser, timeout);
    }

    /** Opens the URL with the desktop's default browser; where there is none (a server, a container) it prints the URL to the standard error stream for the user to open. */
    public static Consumer<URI> systemBrowser() {
        return url -> {
            try {
                if (!java.awt.GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(url);
                    return;
                }
            } catch (IOException | RuntimeException | LinkageError e) {
                // fall through to printing
            }
            System.err.println("Open this address in a browser to sign in: " + url);
        };
    }


    @Override
    public synchronized URI redirectUri() {
        try {
            if (server == null) {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                server.createContext("/callback", this::receive);
                server.start();
            }
        } catch (IOException e) {
            throw new McpClientException("Cannot listen on 127.0.0.1:" + port + " for the authorization redirect: " + e.getMessage(), e);
        }
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/callback");
    }

    @Override
    public URI authorize(URI authorizationUrl) throws IOException, InterruptedException {
        CompletableFuture<URI> future;
        synchronized (this) {
            redirectUri();
            callback = new CompletableFuture<>();
            future   = callback;
        }
        browser.accept(authorizationUrl);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("The user did not finish signing in within " + timeout.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            throw new IOException("The redirect could not be received: " + e.getCause().getMessage(), e.getCause());
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void receive(HttpExchange exchange) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + exchange.getRequestURI().toString());
        boolean answer = uri.getRawQuery() != null && (uri.getRawQuery().contains("code=") || uri.getRawQuery().contains("error="));
        byte[] page = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Signed in</title></head><body><p>"
                + (answer ? "You can close this window and return to the application." : "Nothing to do here.") + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
        if (answer) {
            callback.complete(uri);
        }
    }
}
