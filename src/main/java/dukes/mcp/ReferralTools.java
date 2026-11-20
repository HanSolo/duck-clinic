package dukes.mcp;

import dukes.agent.AgentLog;
import dukes.agent.Referral;
import dukes.agent.ReferralStore;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolExecutionException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;


/**
 * The inbox of the clinic's agent: what the therapy clinic's agent may ask or tell. Tasks, not CRUD. The endpoint has no authentication (demo, localhost only), so nothing here changes anything but the status text of a referral.
 */
@ApplicationScoped
public class ReferralTools {
    private static final Set<String> STATUSES = Set.of("PLANNING", "SCHEDULED", "NOT_COVERED", "REJECTED", "IN_TREATMENT", "COMPLETED");

    private ReferralStore referrals;
    private AgentLog      log;


    /** For the CDI proxy. */
    ReferralTools() { }

    @Inject
    public ReferralTools(ReferralStore referrals, AgentLog log) {
        this.referrals = referrals;
        this.log       = log;
    }


    public record Document(String referralId, String document) { }

    public record Ack(String referralId, String status) { }


    @McpTool(description = "The therapy clinic's agent asks for the full veterinary referral document, because the owner's insurer wants to see it before it pays. Only released for a referral this clinic made, with the owner's consent that was given when the referral was created.", readOnlyHint = McpTool.Hint.TRUE)
    public Document get_referral_document(@McpParam(description = "Referral id, like REF-0001") String referralId) {
        Referral referral = referrals.get(referralId).orElseThrow(() -> new ToolExecutionException("This clinic made no referral " + referralId));
        if (referral.getDocument() == null) throw new ToolExecutionException("The referral " + referralId + " has no document (it was declined)");
        log.say(referralId, "Therapy Agent", "ANSWER", "Releasing the referral document of " + referralId + " (owner consent is on file): " + referral.getDocument().lines().count() + " lines, diagnosis and recent visits.");
        return new Document(referralId, referral.getDocument());
    }

    @McpTool(description = "The therapy clinic's agent tells how a referral is going: PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT or COMPLETED, with a message for the vet.", destructiveHint = McpTool.Hint.FALSE)
    public Ack report_referral_status(@McpParam(description = "Referral id, like REF-0001") String referralId, @McpParam(description = "PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT or COMPLETED") String status, @McpParam(description = "Message for the vet, up to 1000 characters") String message) {
        Referral referral = referrals.get(referralId).orElseThrow(() -> new ToolExecutionException("This clinic made no referral " + referralId));
        String clean = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(clean)) throw new ToolExecutionException("status must be one of " + STATUSES);
        String text = message == null ? "" : message.length() > 1000 ? message.substring(0, 1000) : message;
        referral.update(clean, text);
        log.say(referralId, null, "ACTION", "Noted: referral " + referralId + " of " + referral.getDuckName() + " is now " + clean + ".");
        return new Ack(referralId, clean);
    }
}
