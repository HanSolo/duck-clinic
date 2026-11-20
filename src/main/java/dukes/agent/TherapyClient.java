package dukes.agent;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.net.URI;
import java.time.Duration;


/** The Duck Physical Therapy Clinic as seen by the DuckClinic: its MCP server, reached with the MCP client library. The endpoint comes from THERAPY_MCP_URL. */
@ApplicationScoped
public class TherapyClient {

    /** The therapy clinic could not be reached or answered with an error. The message is meant for people. */
    public static class TherapyUnavailableException extends RuntimeException {
        public TherapyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }


    public URI endpoint() {
        return URI.create(Settings.get("THERAPY_MCP_URL", "http://host.docker.internal:8082/mcp"));
    }

    /** Hands a referral to the therapy clinic's agent. It answers at once with RECEIVED and reports the result later with report_referral_status. */
    public String propose(String referralId, String ownerLastName, String duckName, String diagnosis, int sessions) {
        JsonObject arguments = Json.createObjectBuilder().add("referralId", referralId).add("ownerLastName", ownerLastName).add("duckName", duckName).add("diagnosis", diagnosis).add("requestedSessions", sessions).add("referringClinic", "Duck Clinic").add("ownerConsent", true).build();
        try {
            McpClient client = McpClient.builder(endpoint()).clientInfo("duckclinic-referral-agent", "1.0").timeout(Duration.ofSeconds(30)).build();
            ToolResult result = client.callTool("propose_treatment_plan", arguments);
            JsonObject data = data(result);
            return data.getString("message", data.getString("status", "RECEIVED"));
        } catch (TherapyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TherapyUnavailableException("The physical therapy clinic is not reachable at " + endpoint() + ": " + e.getMessage(), e);
        }
    }

    /** Servers send the data as structured content, or only as JSON text in the first content block (Quarkus MCP server). */
    private static JsonObject data(ToolResult result) {
        String text = "";
        for (JsonValue block : result.content()) {
            if (block instanceof JsonObject o && "text".equals(o.getString("type", ""))) {
                text = o.getString("text", "");
                break;
            }
        }
        if (result.isError()) throw new TherapyUnavailableException("The therapy clinic refused the referral: " + text, null);
        if (result.structuredContent() != null) return result.structuredContent();
        try {
            return Json.createReader(new StringReader(text)).readObject();
        } catch (RuntimeException e) {
            return Json.createObjectBuilder().add("message", text).build();
        }
    }
}
