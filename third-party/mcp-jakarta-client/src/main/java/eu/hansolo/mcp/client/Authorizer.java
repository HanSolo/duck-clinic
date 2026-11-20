package eu.hansolo.mcp.client;

import java.io.IOException;
import java.net.URI;

/**
 * The user's side of the OAuth authorization code flow: sends a person to the authorization server's page and brings back where the browser was redirected to. The client
 * adds PKCE ({@code S256}), a {@code state} value and the {@code resource} parameter, and checks all of them. {@link LoopbackAuthorizer} is the ready-made version for programs on
 * the user's own machine; implement this interface to use a different way of getting the user to the page (an embedded browser, a test double).
 *
 * @see McpClient.Builder#oauthAuthorizationCode
 */
public interface Authorizer {

    /**
     * The redirect URI to register with the authorization server and to send in the request. Called before {@link #authorize}; it must return the same value every time.
     * Only {@code https} URIs and {@code http} URIs of the loopback interface are accepted.
     */
    URI redirectUri();

    /**
     * Shows the page to the user and waits until the authorization server has redirected the browser back.
     *
     * @param authorizationUrl the complete authorization request
     * @return the full URI the browser was redirected to, with its query ({@code code} and {@code state}, or {@code error})
     * @throws IOException          if the user did not answer in time or the redirect could not be received
     * @throws InterruptedException if the calling thread was interrupted
     */
    URI authorize(URI authorizationUrl) throws IOException, InterruptedException;
}
