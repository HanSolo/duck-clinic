package eu.hansolo.mcp.client.internal;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Internal. All pages of one list method, kept for the server's {@code ttlMs}. An entry the server marks {@code cacheScope: private} is
 * valid only for the caller it was loaded for; the caller is told apart by its bearer token, so a new token means a new load.
 */
public final class ListCache {

    public static final long DEFAULT_TTL_MS = 30_000;

    private final Function<JsonObject, JsonObject> lister;
    private final String                           arrayKey;
    private final Supplier<String>                 callerKey;
    private       List<JsonObject>                 items = List.of();
    private       boolean                          loaded;
    private       boolean                          privateScope;
    private       String                           loadedFor;
    private       long                             expiresAt;
    private       long                             version;


    /**
     * @param lister    sends the list request with the given params ({@code cursor} for later pages) and returns the result object
     * @param arrayKey  the property of the result that holds the items, for example {@code tools}
     * @param callerKey identifies the current caller, for private entries (the bearer token, or an empty string)
     */
    public ListCache(Function<JsonObject, JsonObject> lister, String arrayKey, Supplier<String> callerKey) {
        this.lister    = lister;
        this.arrayKey  = arrayKey;
        this.callerKey = callerKey;
    }


    /** The items, loaded again if the entry is missing, expired, invalidated or private to another caller. */
    public synchronized List<JsonObject> get() {
        if (!valid()) {
            load();
        }
        return items;
    }

    /** Changes whenever the items were loaded again. Read it after {@link #get()}. */
    public synchronized long version() {
        return version;
    }

    public synchronized void invalidate() {
        loaded = false;
    }

    private boolean valid() {
        return loaded && System.nanoTime() - expiresAt < 0 && (!privateScope || Objects.equals(loadedFor, callerKey.get()));
    }

    private void load() {
        List<JsonObject> fresh   = new ArrayList<>();
        long             ttlMs   = Long.MAX_VALUE;
        boolean          scoped  = false;
        String           cursor  = null;
        String           caller  = callerKey.get();
        do {
            JsonObject page = lister.apply(cursor == null ? Json.createObjectBuilder().build() : Json.createObjectBuilder().add("cursor", cursor).build());
            if (page.get(arrayKey) instanceof jakarta.json.JsonArray array) {
                for (JsonValue item : array) {
                    if (item instanceof JsonObject object) {
                        fresh.add(object);
                    }
                }
            }
            ttlMs  = Math.min(ttlMs, ttlMs(page));
            scoped = scoped || isPrivate(page);
            cursor = page.get("nextCursor") instanceof JsonString next ? next.getString() : null;
        } while (cursor != null);
        items        = List.copyOf(fresh);
        privateScope = scoped;
        loadedFor    = caller;
        expiresAt    = System.nanoTime() + Math.max(ttlMs, 0) * 1_000_000L;
        loaded       = true;
        version++;
    }

    /** The {@code ttlMs} hint of a result, or the default if there is none. */
    public static long ttlMs(JsonObject result) {
        return result.get("ttlMs") instanceof jakarta.json.JsonNumber number ? number.longValue() : DEFAULT_TTL_MS;
    }

    /** True if the result says {@code cacheScope: private}. Anything else (also a missing or unknown value) is treated as public. */
    public static boolean isPrivate(JsonObject result) {
        return result.get("cacheScope") instanceof JsonString scope && scope.getString().equals("private");
    }
}
