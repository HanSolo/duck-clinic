package eu.hansolo.mcp.client.internal;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal. Reads {@code x-mcp-header} from a tool's input schema and mirrors matching arguments into {@code Mcp-Param-*} headers. */
public final class ParamHeaders {

    public static final String ANNOTATION = "x-mcp-header";
    public static final String PREFIX     = "Mcp-Param-";

    private static final long MAX_SAFE_INTEGER = 9007199254740991L;


    private ParamHeaders() {
    }


    /** A property that is mirrored into a header. {@code path} is the chain of property names from the root. */
    public record Param(List<String> path, String name, String type) {

        public String headerName() {
            return PREFIX + name;
        }
    }


    /** Finds the annotated properties reachable through {@code properties} alone. A malformed annotation is ignored: the server owns validation of its own schema. */
    public static List<Param> declared(JsonObject schema) {
        List<Param> found = new ArrayList<>();
        if (schema != null) {
            scan(schema, List.of(), true, found);
        }
        return found;
    }

    private static void scan(JsonObject schema, List<String> path, boolean root, List<Param> found) {
        if (!root && schema.get(ANNOTATION) instanceof JsonString name && !name.getString().isEmpty() && schema.get("type") instanceof JsonString type && Set.of("string", "integer", "boolean").contains(type.getString())) {
            found.add(new Param(path, name.getString(), type.getString()));
        }
        if (schema.get("properties") instanceof JsonObject properties) {
            for (var property : properties.entrySet()) {
                if (property.getValue() instanceof JsonObject child) {
                    List<String> next = new ArrayList<>(path);
                    next.add(property.getKey());
                    scan(child, next, false, found);
                }
            }
        }
    }

    /** The headers to send for these arguments. A missing or null argument gets no header. */
    public static Map<String, String> headers(List<Param> params, JsonObject arguments) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (arguments == null) {
            return headers;
        }
        for (Param param : params) {
            String text = text(param.type(), valueAt(arguments, param.path()));
            if (text != null) {
                headers.put(param.headerName(), HeaderValues.encode(text));
            }
        }
        return headers;
    }

    private static String text(String type, JsonValue value) {
        if (value == null) {
            return null;
        }
        switch (type) {
            case "string":
                return value instanceof JsonString text ? text.getString() : null;
            case "boolean":
                return value.getValueType() == JsonValue.ValueType.TRUE ? "true" : value.getValueType() == JsonValue.ValueType.FALSE ? "false" : null;
            default:
                if (value instanceof JsonNumber number) {
                    BigDecimal decimal = number.bigDecimalValue();
                    boolean    whole   = decimal.stripTrailingZeros().scale() <= 0;
                    return whole && decimal.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0 ? decimal.toBigInteger().toString() : null;
                }
                return null;
        }
    }

    private static JsonValue valueAt(JsonObject arguments, List<String> path) {
        JsonValue current = arguments;
        for (String step : path) {
            if (!(current instanceof JsonObject object)) {
                return null;
            }
            current = object.get(step);
        }
        return current;
    }
}
