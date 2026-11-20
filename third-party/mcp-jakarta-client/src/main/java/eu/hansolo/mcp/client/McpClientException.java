package eu.hansolo.mcp.client;

import jakarta.json.JsonValue;

/** A failure reported by the server (JSON-RPC error or non-JSON HTTP error) or a transport problem. */
public class McpClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int                 httpStatus;
    private final int                 code;
    private final String              wwwAuthenticate;
    private final transient JsonValue data;


    public McpClientException(int httpStatus, int code, String message, JsonValue data, String wwwAuthenticate) {
        super(message);
        this.httpStatus      = httpStatus;
        this.code            = code;
        this.data            = data;
        this.wwwAuthenticate = wwwAuthenticate;
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus      = 0;
        this.code            = 0;
        this.data            = null;
        this.wwwAuthenticate = null;
    }


    /** The HTTP status, or 0 if the failure happened before a response arrived. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The JSON-RPC error code, or 0 if the server sent none. */
    public int code() {
        return code;
    }

    /** The error data (for -32022 it holds the supported versions), or null. */
    public JsonValue data() {
        return data;
    }

    /** The {@code WWW-Authenticate} header of a 401 or 403 answer, or null. */
    public String wwwAuthenticate() {
        return wwwAuthenticate;
    }
}
