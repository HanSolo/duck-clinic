package eu.hansolo.mcp.client.internal;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Internal. The header-mirroring parameters of each tool, derived from the cached {@code tools/list}. */
public final class ToolSchemas {

    private static final long UNKNOWN_REFRESH_GAP = 5_000_000_000L;

    private final ListCache                             tools;
    private final Map<String, List<ParamHeaders.Param>> params = new HashMap<>();
    private       long                                  builtFrom = -1;
    private       long                                  forcedAt  = Long.MIN_VALUE / 2;


    public ToolSchemas(ListCache tools) {
        this.tools = tools;
    }


    public synchronized List<ParamHeaders.Param> paramsFor(String tool) {
        rebuild();
        if (!params.containsKey(tool) && System.nanoTime() - forcedAt >= UNKNOWN_REFRESH_GAP) {
            forcedAt = System.nanoTime();
            tools.invalidate();
            rebuild();
        }
        return params.getOrDefault(tool, List.of());
    }

    /** Forces a reload on the next lookup, for example after the server answered with a header mismatch. */
    public synchronized void invalidate() {
        tools.invalidate();
    }

    private void rebuild() {
        List<JsonObject> items = tools.get();
        if (tools.version() == builtFrom) {
            return;
        }
        params.clear();
        for (JsonObject tool : items) {
            if (tool.get("name") instanceof JsonString name) {
                params.put(name.getString(), ParamHeaders.declared(tool.get("inputSchema") instanceof JsonObject schema ? schema : null));
            }
        }
        builtFrom = tools.version();
        forcedAt  = System.nanoTime();
    }
}
