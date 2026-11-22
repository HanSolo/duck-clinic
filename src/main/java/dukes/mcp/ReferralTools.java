package dukes.mcp;

import dukes.agent.AgentLog;
import dukes.agent.Referral;
import dukes.agent.ReferralStore;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpRequestStates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;

import java.util.Optional;
import java.util.Set;


/**
 * The inbox of the clinic's agent: what the therapy clinic's agent may ask or tell. Tasks, not CRUD. The endpoint has no authentication (demo, localhost only), so nothing here changes anything but the status text of a referral.
 */
@ApplicationScoped
public class ReferralTools {
    private static final Set<String> STATUSES = Set.of("PLANNING", "SCHEDULED", "NOT_COVERED", "REJECTED", "IN_TREATMENT", "COMPLETED", "CANCELLED");

    private ReferralStore    referrals;
    private AgentLog         log;
    private McpRequestStates states;


    /** For the CDI proxy. */
    ReferralTools() { }

    @Inject
    public ReferralTools(ReferralStore referrals, AgentLog log, McpRequestStates states) {
        this.referrals = referrals;
        this.log       = log;
        this.states    = states;
    }


    public record Ack(String referralId, String status) { }


    /**
     * Multi round-trip request: the first call is not answered with the document but with a question, "do you confirm the release?", together with a signed state. The caller answers (here an agent, not a person) and repeats the call.
     * The state is signed over the referral id, so a retry for another referral is refused. Nothing is released without the answer.
     */
    @McpTool(description = "The therapy clinic's agent asks for the full veterinary referral document, because the owner's insurer wants to see it before it pays. The clinic asks back: do you confirm the release? Answer the question (a form with a checkbox 'confirm') and the document is returned. Only released for a referral this clinic made, with the owner's consent that was given when the referral was created.", readOnlyHint = McpTool.Hint.TRUE)
    public ToolResult get_referral_document(@McpParam(description = "Referral id, like REF-0001") String referralId, ToolCall call) {
        Referral referral = referrals.get(referralId).orElseThrow(() -> new ToolExecutionException("This clinic made no referral " + referralId));
        if (referral.getDocument() == null) throw new ToolExecutionException("The referral " + referralId + " has no document (it was declined)");
        String question = "Release the referral document of " + referralId + " (" + referral.getDuckName() + ") to the caller? The owner's consent is on file.";
        String context  = "get_referral_document|" + call.caller().subject();

        Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "confirm");
        if (answer.isEmpty()) {
            if (!call.clientSupportsElicitation(Elicitation.Mode.FORM)) throw McpProtocolException.missingClientCapability("elicitation");
            log.say(referralId, "Therapy Agent", "REQUEST", "Before I release the referral document: do you confirm the release? Why do you need it? (asked inside your call, MCP input_required)");
            return ToolResult.ask(InputRequests.of("confirm", confirmation(question)), states.sign(referralId, context));
        }
        if (!referralId.equals(states.require(call.requestState(), context))) throw new ToolExecutionException("The referral changed after the clinic asked. Call get_referral_document again.");
        if (!answer.get().accepted() || !confirmation(question).problems(answer.get()).isEmpty() || !answer.get().bool("confirm").orElse(false)) {
            log.say(referralId, "Therapy Agent", "ANSWER", "You did not confirm, so the referral document stays here.");
            return ToolResult.error("Not released: the caller did not confirm.");
        }
        log.say(referralId, "Therapy Agent", "ANSWER", "Confirmed. Releasing the referral document of " + referralId + " (owner consent is on file): " + referral.getDocument().lines().count() + " lines, diagnosis and recent visits.");
        return ToolResult.structured(Json.createObjectBuilder().add("referralId", referralId).add("document", referral.getDocument()).build());
    }

    private static Elicitation.Form confirmation(String question) {
        return Elicitation.form(question).bool("confirm", p -> p.title("Yes, release the document").required().defaultValue(true));
    }

    @McpTool(description = "The therapy clinic's agent tells how a referral is going: PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT, COMPLETED or CANCELLED, with a message for the vet.", destructiveHint = McpTool.Hint.FALSE)
    public Ack report_referral_status(@McpParam(description = "Referral id, like REF-0001") String referralId, @McpParam(description = "PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT, COMPLETED or CANCELLED") String status, @McpParam(description = "Message for the vet, up to 1000 characters") String message) {
        Referral referral = referrals.get(referralId).orElseThrow(() -> new ToolExecutionException("This clinic made no referral " + referralId));
        String clean = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(clean)) throw new ToolExecutionException("status must be one of " + STATUSES);
        String text = message == null ? "" : message.length() > 1000 ? message.substring(0, 1000) : message;
        referral.update(clean, text);
        log.say(referralId, null, "ACTION", "Noted: referral " + referralId + " of " + referral.getDuckName() + " is now " + clean + ".");
        return new Ack(referralId, clean);
    }
}
