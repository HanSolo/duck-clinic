package eu.hansolo.mcp.client.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Internal. Checks the content of an elicitation answer against the requested form schema (the flat subset of the 2026-07-28 specification): required properties,
 * type, string length and format, number range, allowed values of single and multiple choices, and item counts.
 */
public final class FormValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");


    private FormValidator() {
    }


    /** @return what is wrong with the content, empty if it fits. A schema without {@code properties} accepts only empty content. */
    public static List<String> problems(JsonObject schema, JsonObject content) {
        List<String> problems   = new ArrayList<>();
        JsonObject   properties = schema != null && schema.get("properties") instanceof JsonObject p ? p : JsonObject.EMPTY_JSON_OBJECT;
        JsonObject   given      = content == null ? JsonObject.EMPTY_JSON_OBJECT : content;
        for (String name : given.keySet()) {
            if (!properties.containsKey(name)) {
                problems.add("unexpected property '" + name + "'");
            }
        }
        Set<String> required = new HashSet<>();
        if (schema != null && schema.get("required") instanceof JsonArray names) {
            names.forEach(value -> {
                if (value instanceof JsonString text) {
                    required.add(text.getString());
                }
            });
        }
        for (String name : properties.keySet()) {
            JsonValue value = given.get(name);
            if (value == null || value == JsonValue.NULL) {
                if (required.contains(name)) {
                    problems.add("'" + name + "' is required");
                }
            } else if (properties.get(name) instanceof JsonObject property) {
                check(name, property, value, problems);
            }
        }
        return problems;
    }

    private static void check(String name, JsonObject property, JsonValue value, List<String> problems) {
        String type = property.getString("type", "");
        switch (type) {
            case "string"  -> checkString(name, property, value, problems);
            case "number"  -> checkNumber(name, property, value, false, problems);
            case "integer" -> checkNumber(name, property, value, true, problems);
            case "boolean" -> {
                if (value.getValueType() != JsonValue.ValueType.TRUE && value.getValueType() != JsonValue.ValueType.FALSE) {
                    problems.add("'" + name + "' must be true or false");
                }
            }
            case "array"   -> checkArray(name, property, value, problems);
            default        -> { /* a type this client does not know: the server decides */ }
        }
    }

    private static void checkString(String name, JsonObject property, JsonValue value, List<String> problems) {
        if (!(value instanceof JsonString text)) {
            problems.add("'" + name + "' must be a string");
            return;
        }
        String s = text.getString();
        Set<String> allowed = choices(property);
        if (allowed != null && !allowed.contains(s)) {
            problems.add("'" + name + "' must be one of " + allowed);
            return;
        }
        int length = s.codePointCount(0, s.length());
        if (property.containsKey("minLength") && length < property.getInt("minLength")) {
            problems.add("'" + name + "' must have at least " + property.getInt("minLength") + " characters");
        }
        if (property.containsKey("maxLength") && length > property.getInt("maxLength")) {
            problems.add("'" + name + "' must have at most " + property.getInt("maxLength") + " characters");
        }
        String format = property.getString("format", "");
        if (!format.isEmpty() && !formatFits(format, s)) {
            problems.add("'" + name + "' is not a valid " + format);
        }
    }

    private static boolean formatFits(String format, String s) {
        try {
            switch (format) {
                case "email"     -> { return EMAIL.matcher(s).matches(); }
                case "uri"       -> { return URI.create(s).isAbsolute(); }
                case "date"      -> { LocalDate.parse(s); return true; }
                case "date-time" -> { OffsetDateTime.parse(s); return true; }
                default          -> { return true; }
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void checkNumber(String name, JsonObject property, JsonValue value, boolean whole, List<String> problems) {
        if (!(value instanceof JsonNumber number)) {
            problems.add("'" + name + "' must be a number");
            return;
        }
        BigDecimal v = number.bigDecimalValue();
        if (whole && v.stripTrailingZeros().scale() > 0) {
            problems.add("'" + name + "' must be a whole number");
        }
        if (property.get("minimum") instanceof JsonNumber min && v.compareTo(min.bigDecimalValue()) < 0) {
            problems.add("'" + name + "' must be at least " + min);
        }
        if (property.get("maximum") instanceof JsonNumber max && v.compareTo(max.bigDecimalValue()) > 0) {
            problems.add("'" + name + "' must be at most " + max);
        }
    }

    private static void checkArray(String name, JsonObject property, JsonValue value, List<String> problems) {
        if (!(value instanceof JsonArray array)) {
            problems.add("'" + name + "' must be a list");
            return;
        }
        if (property.containsKey("minItems") && array.size() < property.getInt("minItems")) {
            problems.add("'" + name + "' needs at least " + property.getInt("minItems") + " values");
        }
        if (property.containsKey("maxItems") && array.size() > property.getInt("maxItems")) {
            problems.add("'" + name + "' allows at most " + property.getInt("maxItems") + " values");
        }
        Set<String> allowed = property.get("items") instanceof JsonObject items ? choices(items) : null;
        Set<String> seen    = new HashSet<>();
        for (JsonValue item : array) {
            if (!(item instanceof JsonString text)) {
                problems.add("'" + name + "' may only contain strings");
                return;
            }
            if (allowed != null && !allowed.contains(text.getString())) {
                problems.add("'" + name + "' contains '" + text.getString() + "', which is not one of " + allowed);
            }
            if (!seen.add(text.getString())) {
                problems.add("'" + name + "' contains '" + text.getString() + "' twice");
            }
        }
    }

    /** The allowed values of a choice ({@code enum}, {@code oneOf} or {@code anyOf} with {@code const}), or null if the property is free text. */
    private static Set<String> choices(JsonObject property) {
        Set<String> values = new HashSet<>();
        if (property.get("enum") instanceof JsonArray array) {
            array.forEach(v -> { if (v instanceof JsonString s) { values.add(s.getString()); } });
        }
        for (String key : new String[]{"oneOf", "anyOf"}) {
            if (property.get(key) instanceof JsonArray options) {
                options.forEach(o -> { if (o instanceof JsonObject option && option.get("const") instanceof JsonString s) { values.add(s.getString()); } });
            }
        }
        return values.isEmpty() ? null : values;
    }
}
