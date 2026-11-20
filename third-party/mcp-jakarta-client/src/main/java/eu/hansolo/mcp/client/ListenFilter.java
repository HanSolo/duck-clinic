package eu.hansolo.mcp.client;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.util.ArrayList;
import java.util.List;

/** What a {@code subscriptions/listen} stream should report. The server leaves out what it cannot or may not give; {@link McpListener#onSubscribed} says what is left. Not thread-safe while you fill it. */
public final class ListenFilter {

    private boolean      tools;
    private boolean      prompts;
    private boolean      resources;
    private List<String> resourceUris = new ArrayList<>();
    private List<String> taskIds      = new ArrayList<>();


    private ListenFilter() {
    }


    public static ListenFilter create() {
        return new ListenFilter();
    }

    public ListenFilter toolsListChanged() {
        this.tools = true;
        return this;
    }

    public ListenFilter promptsListChanged() {
        this.prompts = true;
        return this;
    }

    public ListenFilter resourcesListChanged() {
        this.resources = true;
        return this;
    }

    /** All three list-changed notifications. */
    public ListenFilter listsChanged() {
        return toolsListChanged().promptsListChanged().resourcesListChanged();
    }

    /** Reports {@code resources/updated} for these exact URIs. */
    public ListenFilter resourceUpdates(String... uris) {
        resourceUris.addAll(List.of(uris));
        return this;
    }

    /** Reports the state of these tasks (needs {@link McpClient.Builder#tasks()}); the server watches at most 50 per stream. */
    public ListenFilter tasks(String... ids) {
        taskIds.addAll(List.of(ids));
        return this;
    }

    JsonObject toJson() {
        JsonObjectBuilder json = Json.createObjectBuilder();
        if (tools) {
            json.add("toolsListChanged", true);
        }
        if (prompts) {
            json.add("promptsListChanged", true);
        }
        if (resources) {
            json.add("resourcesListChanged", true);
        }
        if (!resourceUris.isEmpty()) {
            JsonArrayBuilder uris = Json.createArrayBuilder();
            resourceUris.forEach(uris::add);
            json.add("resourceSubscriptions", uris);
        }
        if (!taskIds.isEmpty()) {
            JsonArrayBuilder ids = Json.createArrayBuilder();
            taskIds.forEach(ids::add);
            json.add("taskIds", ids);
        }
        return json.build();
    }
}
