package eu.hansolo.mcp.client.internal;

import eu.hansolo.mcp.client.Authorizer;
import eu.hansolo.mcp.client.McpClientException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal. OAuth for a machine client (client credentials): finds the authorization server through the protected resource metadata
 * (RFC 9728) and the authorization server metadata (RFC 8414 or OpenID discovery), asks for a token for the endpoint (RFC 8707 {@code resource}), keeps it until
 * shortly before it expires, and answers a 401 with a token and a 403 {@code insufficient_scope} with a token for the larger set of scopes. Neither secret nor token is logged
 * or put into an exception message.
 */
public final class OAuthClient implements McpTransport.Authenticator {

    private static final long    SAFETY_NANOS = 30_000_000_000L;
    private static final long    DEFAULT_LIFETIME_SECONDS = 300;
    private static final Pattern PARAMETER    = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)=(?:\"([^\"]*)\"|([^\\s,]+))");

    private final HttpClient  http;
    private final URI         resource;
    private final String      clientId;
    private final String      clientSecret;
    private final Duration    timeout;
    private final Set<String> scopes = new LinkedHashSet<>();
    private final Authorizer authorizer;
    private final SecureRandom random = new SecureRandom();
    private       URI         tokenEndpoint;
    private       URI         authorizationEndpoint;
    private       String      issuer;
    private       boolean     pkce;
    private       String      refreshToken;
    private       String      accessToken;
    private       long        expiresAt;


    /**
     * @param resource      the MCP endpoint; it is the {@code resource} of every token request
     * @param scopes        scopes to ask for from the start; the server's challenges add to them
     * @param tokenEndpoint the token endpoint if it is known; null to find it through discovery after the first 401
     */
    public OAuthClient(HttpClient http, URI resource, String clientId, String clientSecret, Collection<String> scopes, URI tokenEndpoint, Duration timeout, Authorizer authorizer) {
        this.http          = http;
        this.resource      = resource;
        this.clientId      = clientId;
        this.clientSecret  = clientSecret;
        this.timeout       = timeout;
        this.tokenEndpoint = tokenEndpoint;
        this.authorizer    = authorizer;
        scopes.forEach(this::addScopes);
        if (tokenEndpoint != null) {
            requireSecure(tokenEndpoint, "The token endpoint");
        }
    }


    /** The current access token, fetched again if it has expired and the token endpoint is known. Null until the first challenge has led to discovery, or if the token could not be fetched (the next request then gets a 401 and reports the cause). */
    public synchronized String token() {
        if (accessToken != null && System.nanoTime() - expiresAt < 0) {
            return accessToken;
        }
        if (authorizer != null) {
            return refreshToken != null && tokenEndpoint != null && refresh() ? accessToken : null;   // never opens a browser here
        }
        if (tokenEndpoint != null) {
            try {
                fetchToken();
                return accessToken;
            } catch (McpClientException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public synchronized boolean onChallenge(int status, String wwwAuthenticate) {
        Map<String, String> challenge = parseChallenge(wwwAuthenticate);
        if (status != 401 && !(status == 403 && "insufficient_scope".equals(challenge.get("error")))) {
            return false;
        }
        if (status == 401 && !challenge.containsKey("error") && accessToken != null && System.nanoTime() - expiresAt < 0) {
            return true;    // "no credentials" although a token is held now: the request was sent before the sign-in finished
        }
        int before = scopes.size();
        addScopes(challenge.get("scope"));
        if (tokenEndpoint == null) {
            discover(challenge.get("resource_metadata"));
        }
        if (status == 403 && scopes.size() == before) {
            return false;   // the token already was asked for these scopes: asking again cannot help
        }
        fetchToken();
        return true;
    }

    /** The parameters of a {@code Bearer} challenge, for example {@code error}, {@code scope} and {@code resource_metadata}. Empty for any other scheme. */
    public static Map<String, String> parseChallenge(String header) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (header == null || !header.regionMatches(true, 0, "Bearer", 0, 6)) {
            return parameters;
        }
        Matcher matcher = PARAMETER.matcher(header.substring(6));
        while (matcher.find()) {
            parameters.putIfAbsent(matcher.group(1).toLowerCase(java.util.Locale.ROOT), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return parameters;
    }

    private void addScopes(String spaceSeparated) {
        if (spaceSeparated != null) {
            for (String scope : spaceSeparated.trim().split("\\s+")) {
                if (!scope.isEmpty()) {
                    scopes.add(scope);
                }
            }
        }
    }

    // ---- discovery ------------------------------------------------------------------------------------------------

    private void discover(String resourceMetadataUrl) {
        List<URI> candidates = new ArrayList<>();
        if (resourceMetadataUrl != null) {
            candidates.add(requireSecure(URI.create(resourceMetadataUrl), "The resource metadata URL"));
        } else {
            candidates.add(wellKnown(resource, "oauth-protected-resource", true));
            candidates.add(wellKnown(resource, "oauth-protected-resource", false));
        }
        JsonObject metadata = firstJson(candidates, null);
        if (metadata == null) {
            throw new McpClientException("The server's protected resource metadata could not be read", null);
        }
        if (!sameResource(metadata.getString("resource", ""), resource)) {
            throw new McpClientException("The protected resource metadata is for another resource: " + metadata.getString("resource", "(none)"), null);
        }
        if (!(metadata.get("authorization_servers") instanceof JsonArray servers) || servers.isEmpty() || !(servers.get(0) instanceof JsonString first)) {
            throw new McpClientException("The protected resource metadata names no authorization server", null);
        }
        if (scopes.isEmpty() && metadata.get("scopes_supported") instanceof JsonArray supported) {
            for (JsonValue scope : supported) {
                if (scope instanceof JsonString text) {
                    scopes.add(text.getString());
                }
            }
        }
        URI issuer = requireSecure(URI.create(first.getString()), "The authorization server");
        List<URI> asCandidates = new ArrayList<>();
        String path = issuer.getPath() == null || issuer.getPath().equals("/") ? "" : issuer.getPath();
        asCandidates.add(wellKnown(issuer, "oauth-authorization-server", true));
        asCandidates.add(wellKnown(issuer, "openid-configuration", true));
        if (!path.isEmpty()) {
            asCandidates.add(URI.create(issuer.toString().replaceAll("/+$", "") + "/.well-known/openid-configuration"));
        }
        JsonObject as = firstJson(asCandidates, "token_endpoint");
        if (as == null) {
            throw new McpClientException("The authorization server's metadata could not be read: " + issuer, null);
        }
        if (as.containsKey("issuer") && !issuer.toString().replaceAll("/+$", "").equals(as.getString("issuer", "").replaceAll("/+$", ""))) {
            throw new McpClientException("The authorization server metadata names another issuer than the one it was found for", null);
        }
        tokenEndpoint = requireSecure(URI.create(as.getString("token_endpoint")), "The token endpoint");
        this.issuer   = issuer.toString();
        if (as.get("authorization_endpoint") instanceof JsonString endpoint) {
            authorizationEndpoint = requireSecure(URI.create(endpoint.getString()), "The authorization endpoint");
        }
        pkce = false;
        if (as.get("code_challenge_methods_supported") instanceof JsonArray methods) {
            for (JsonValue method : methods) {
                pkce |= method instanceof JsonString text && text.getString().equals("S256");
            }
        }
    }

    /** The first answer with status 200 and a JSON object that has the required key (if any). */
    private JsonObject firstJson(List<URI> candidates, String requiredKey) {
        for (URI candidate : candidates) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(candidate).timeout(timeout).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = Json.createReader(new StringReader(response.body())).readObject();
                    if (requiredKey == null || json.get(requiredKey) instanceof JsonString) {
                        return json;
                    }
                }
            } catch (IOException | JsonException | IllegalStateException e) {
                // try the next candidate
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpClientException("Interrupted during discovery", e);
            }
        }
        return null;
    }

    /** {@code https://h/.well-known/<name>} with the path of the URI appended (RFC 8414 and RFC 9728 insert the well-known segment between host and path) or, if {@code withPath} is false, without it. */
    private static URI wellKnown(URI base, String name, boolean withPath) {
        String path = base.getPath() == null || base.getPath().equals("/") || !withPath ? "" : base.getPath().replaceAll("/+$", "");
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + "/.well-known/" + name + path);
    }

    private static boolean sameResource(String advertised, URI resource) {
        return advertised.replaceAll("/+$", "").equals(resource.toString().replaceAll("/+$", ""));
    }

    /** https, or http to a loopback address (local development). Anything else would send a client secret in the clear. */
    private static URI requireSecure(URI uri, String what) {
        String  scheme   = uri.getScheme();
        String  host     = uri.getHost();
        boolean loopback = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        if (host == null || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback))) {
            throw new McpClientException(what + " must be an https URL (http is accepted only for localhost): " + uri, null);
        }
        return uri;
    }

    // ---- token ----------------------------------------------------------------------------------------------------

    private void fetchToken() {
        if (authorizer != null) {
            authorize();
        } else {
            store(postForm("grant_type=client_credentials&resource=" + encode(resource.toString()) + scopeParameter(), true));
        }
    }

    private String scopeParameter() {
        return scopes.isEmpty() ? "" : "&scope=" + encode(String.join(" ", scopes));
    }

    // ---- authorization code with PKCE (RFC 7636, RFC 9207, OAuth 2.1) -----------------------------------------------

    private void authorize() {
        if (authorizationEndpoint == null) {
            throw new McpClientException("The authorization server's metadata has no authorization_endpoint", null);
        }
        if (!pkce) {
            throw new McpClientException("The authorization server does not advertise PKCE with S256 (code_challenge_methods_supported); the sign-in is refused without PKCE", null);
        }
        URI    redirect  = requireSecure(authorizer.redirectUri(), "The redirect URI");
        String verifier  = randomText(32);
        String state     = randomText(16);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String query     = "response_type=code&client_id=" + encode(clientId) + "&redirect_uri=" + encode(redirect.toString()) + "&state=" + state + "&code_challenge=" + challenge + "&code_challenge_method=S256&resource=" + encode(resource.toString()) + scopeParameter();
        String base      = authorizationEndpoint.toString();
        URI    request   = URI.create(base + (authorizationEndpoint.getRawQuery() == null ? "?" : "&") + query);

        URI callback;
        try {
            callback = authorizer.authorize(request);
        } catch (IOException e) {
            throw new McpClientException("The sign-in did not complete: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("Interrupted during the sign-in", e);
        }
        Map<String, String> answer = parseQuery(callback.getRawQuery());
        if (answer.containsKey("error")) {
            throw new McpClientException("The sign-in was refused: " + answer.get("error") + (answer.containsKey("error_description") ? " (" + answer.get("error_description") + ")" : ""), null);
        }
        if (!state.equals(answer.get("state"))) {
            throw new McpClientException("The redirect carries another state than the request: the answer is not for this sign-in", null);
        }
        if (answer.containsKey("iss") && issuer != null && !answer.get("iss").replaceAll("/+$", "").equals(issuer.replaceAll("/+$", ""))) {
            throw new McpClientException("The redirect names another issuer than the authorization server that was asked", null);
        }
        String code = answer.get("code");
        if (code == null || code.isEmpty()) {
            throw new McpClientException("The redirect carries no authorization code", null);
        }
        store(postForm("grant_type=authorization_code&code=" + encode(code) + "&redirect_uri=" + encode(redirect.toString()) + "&client_id=" + encode(clientId) + "&code_verifier=" + verifier + "&resource=" + encode(resource.toString()), false));
    }

    private boolean refresh() {
        try {
            store(postForm("grant_type=refresh_token&refresh_token=" + encode(refreshToken) + "&client_id=" + encode(clientId) + "&resource=" + encode(resource.toString()), false));
            return true;
        } catch (McpClientException e) {
            accessToken  = null;
            refreshToken = null;
            return false;
        }
    }

    private String randomText(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                parameters.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }

    // ---- the token endpoint ------------------------------------------------------------------------------------------

    /** Posts the form (client secret as HTTP Basic if {@code basic} and there is one) and returns the JSON answer of a 200; any other answer is an exception without secrets in it. */
    private JsonObject postForm(String form, boolean basic) {
        HttpRequest.Builder request = HttpRequest.newBuilder(tokenEndpoint).timeout(timeout).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(form));
        if (basic && clientSecret != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8)));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject           json     = parseObject(response.body());
            if (response.statusCode() != 200 || json == null || !(json.get("access_token") instanceof JsonString)) {
                String detail = json == null ? "" : ": " + json.getString("error", "") + (json.containsKey("error_description") ? " (" + json.getString("error_description", "") + ")" : "");
                throw new McpClientException("The token request was refused with HTTP " + response.statusCode() + detail, null);
            }
            return json;
        } catch (IOException e) {
            throw new McpClientException("The token request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("Interrupted during the token request", e);
        }
    }

    private void store(JsonObject json) {
        if (!"bearer".equalsIgnoreCase(json.getString("token_type", "Bearer"))) {
            throw new McpClientException("The authorization server issued a token of type " + json.getString("token_type", "") + ", not Bearer", null);
        }
        long lifetime = json.get("expires_in") instanceof JsonNumber seconds ? seconds.longValue() : DEFAULT_LIFETIME_SECONDS;
        accessToken = json.getString("access_token");
        expiresAt   = System.nanoTime() + Math.max(lifetime * 1_000_000_000L - SAFETY_NANOS, 0);
        if (authorizer != null) {
            refreshToken = json.get("refresh_token") instanceof JsonString next ? next.getString() : refreshToken;
        }
    }

    private static JsonObject parseObject(String text) {
        try {
            return Json.createReader(new StringReader(text)).readObject();
        } catch (JsonException | IllegalStateException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
