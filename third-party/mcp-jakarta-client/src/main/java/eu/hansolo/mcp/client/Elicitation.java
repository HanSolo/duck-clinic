package eu.hansolo.mcp.client;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Asking the user for input in the middle of a request (elicitation). The server answers a call with {@code input_required}; the client
 * passes each request to an {@link Handler}, then repeats the call with the answers. Register a handler with
 * {@link McpClient.Builder#elicitation(Handler, Mode...)}; without one the client declares no elicitation capability.
 */
public final class Elicitation {

    /** The two ways of asking. */
    public enum Mode { FORM, URL }

    /** What the user did. */
    public enum Action { ACCEPT, DECLINE, CANCEL }


    private Elicitation() {
    }


    /**
     * One thing the server wants to know.
     *
     * @param key             the key of the request; the answer is sent back under it
     * @param mode            form or URL
     * @param message         what to tell the user
     * @param requestedSchema form mode: the JSON-Schema subset describing the fields (empty properties means a plain confirmation); null in URL mode
     * @param url             URL mode: the page to send the user to (the data entered there never reaches this client); null in form mode
     * @param raw             the request object as sent by the server
     */
    public record Request(String key, Mode mode, String message, JsonObject requestedSchema, String url, JsonObject raw) {

        /**
         * What is wrong with an answer to this form: missing required values, wrong types, text that is too short, long or badly formatted, numbers out of range, values that are
         * not among the choices. Empty if it fits. The client runs the same check before it sends an answer, so a handler can use this to ask the user again.
         */
        public java.util.List<String> problems(JsonObject content) {
            return eu.hansolo.mcp.client.internal.FormValidator.problems(requestedSchema, content);
        }
    }


    /**
     * The user's answer. In URL mode {@code accept} only says that the user agreed to open the page, not that the work there is done.
     *
     * @param action  what the user did
     * @param content form mode with {@code accept}: the values by property name; otherwise ignored and may be null
     */
    public record Response(Action action, JsonObject content) {

        public static Response accept(JsonObject content) {
            return new Response(Action.ACCEPT, content);
        }

        /** Accepts a form without fields or a URL request. */
        public static Response accept() {
            return new Response(Action.ACCEPT, Json.createObjectBuilder().build());
        }

        public static Response decline() {
            return new Response(Action.DECLINE, null);
        }

        public static Response cancel() {
            return new Response(Action.CANCEL, null);
        }
    }


    /** Asks the user. It runs on the thread that makes the call and may block. Returning null counts as {@link Response#cancel()}. */
    @FunctionalInterface
    public interface Handler {

        Response elicit(Request request);
    }
}
