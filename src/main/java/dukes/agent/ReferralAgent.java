package dukes.agent;

import dukes.model.Duck;
import dukes.model.Visit;
import dukes.service.DuckService;
import dukes.service.VisitService;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.Decision;
import jakarta.ai.agent.HandleException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Result;
import jakarta.ai.agent.Trigger;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * The duck clinic's agent: a vet asks "should this duck go to physiotherapy?". Same model as the insurer's agents (draft Jakarta Agentic AI 1.0 M1): a CDI event starts the workflow, then trigger, action, decision and outcome run in this order.
 * <ol>
 *   <li>{@link #start}: a vet asked.</li>
 *   <li>{@link #gather}: reads the duck and its visits of the last 90 days, checks for an open referral. Pure code.</li>
 *   <li>{@link #judge}: if a rule already decides, the code answers. Otherwise the language model, instructed with the policy file, decides whether a referral makes sense and how many sessions to suggest. The code clamps its answer.</li>
 *   <li>{@link #record}: writes the referral and hands it to the therapy clinic's agent (MCP tool propose_treatment_plan). The insurer is not asked here: the therapy clinic knows the price, so it asks.</li>
 *   <li>{@link #fallBack}: if something fails, the referral is marked FAILED and nothing is sent.</li>
 * </ol>
 */
@Agent
public class ReferralAgent {
    private static final Logger LOG = Logger.getLogger(ReferralAgent.class.getName());

    @Inject
    private LargeLanguageModel model;
    @Inject
    private DuckService        ducks;
    @Inject
    private VisitService       visits;
    @Inject
    private ReferralStore      referrals;
    @Inject
    private TherapyClient      therapy;
    @Inject
    private AgentLog           log;


    @Trigger
    void start(ReferralRequest request) {
        log.say(request.referralId(), null, "THOUGHT", "A vet asked whether duck " + request.duckId() + " should be referred to physiotherapy. The owner has given consent.");
    }

    @Action
    ReferralFacts gather(ReferralRequest request) {
        ReferralFacts facts = facts(request);
        log.say(request.referralId(), null, "POLICY", facts.duckName() + " (" + facts.duckType() + ", owner " + facts.ownerName() + "): " + facts.recentVisits().size() + " visit(s) in the last " + ClinicPolicy.RECENT_VISIT_DAYS + " days. " + (facts.recentVisits().isEmpty() ? "" : String.join(" | ", facts.recentVisits())) + (facts.blocker() == null ? "" : " Blocked by rule " + facts.blocker() + "."));
        return facts;
    }

    /** Always continues the workflow: the decision travels in the details of the result. */
    @Decision
    Result judge(ReferralFacts facts) {
        if (facts.blocker() != null) return new Result(true, new ReferralDecision(false, "", 0, facts.message(), "rules"));
        String prompt = """
                %s

                A vet asks whether to refer this duck to the Duck Physical Therapy Clinic.
                Duck: %s (%s), born %s, owner %s from %s.
                Visits of the last %d days, newest first:
                %s
                """.formatted(ClinicPolicy.text(), facts.duckName(), facts.duckType(), facts.birthDate(), facts.ownerName(), facts.ownerCity(), ClinicPolicy.RECENT_VISIT_DAYS, String.join("\n", facts.recentVisits().stream().map(v -> "- " + v).toList()));
        String text = model.query(prompt);
        if (text == null || text.isBlank()) throw new IllegalStateException("The model returned no answer");
        return new Result(true, parse(text, facts));
    }

    @Outcome
    void record(ReferralRequest request, ReferralDecision decision) {
        ReferralFacts facts = facts(request);
        String id = request.referralId();
        if (!decision.refer()) {
            referrals.save(new Referral(id, facts.duckId(), facts.duckName(), facts.ownerLastName(), "", 0, null, "DECLINED", decision.reason()));
            log.say(id, "Vet", "ANSWER", "No referral for " + facts.duckName() + ". " + decision.reason() + (decision.source().equals("rules") ? " [decided by the rules]" : ""));
            return;
        }
        String document = document(id, facts, decision);
        Referral referral = new Referral(id, facts.duckId(), facts.duckName(), facts.ownerLastName(), decision.diagnosis(), decision.sessions(), document, "SENT", "Handed to the physical therapy clinic.");
        referrals.save(referral);
        log.say(id, "Vet", "ANSWER", "Referral " + id + " is sensible: " + decision.reason() + " Suggested: " + decision.sessions() + " session(s).");
        log.say(id, "Therapy Agent", "REQUEST", "Please plan " + decision.sessions() + " physiotherapy session(s) for " + facts.duckName() + " (owner " + facts.ownerLastName() + "). Diagnosis: " + decision.diagnosis() + " Owner consent is on file. I share only this, the full referral document follows if the insurer needs it.");
        try {
            String answer = therapy.propose(id, facts.ownerLastName(), facts.duckName(), decision.diagnosis(), decision.sessions());
            log.say(id, null, "THOUGHT", "The therapy clinic accepted the referral (" + answer + ") and will report back.");
        } catch (RuntimeException e) {
            referral.update("FAILED", e.getMessage());
            log.say(id, null, "ERROR", "The referral could not be handed over: " + e.getMessage());
        }
    }

    @HandleException
    void fallBack(RuntimeException e, ReferralRequest request) {
        LOG.log(Level.WARNING, "Referral agent failed for " + request.referralId(), e);
        log.say(request.referralId(), "Vet", "ERROR", "The referral agent failed (" + e.getMessage() + "). Nothing was sent.");
        if (referrals.get(request.referralId()).isEmpty()) referrals.save(new Referral(request.referralId(), request.duckId(), "duck " + request.duckId(), "", "", 0, null, "FAILED", String.valueOf(e.getMessage())));
        throw e;
    }

    /** The model should answer with JSON; the answer is clamped to the limits. If it cannot be read, a keyword rule decides (and the source says so). */
    static ReferralDecision parse(String text, ReferralFacts facts) {
        try {
            int from = text.indexOf('{'), to = text.lastIndexOf('}');
            JsonObject json = Json.createReader(new StringReader(text.substring(from, to + 1))).readObject();
            boolean refer = json.getBoolean("refer");
            if (!refer) return new ReferralDecision(false, "", 0, json.getString("reason", "The model sees no need for physiotherapy."), "agentic-ai");
            int sessions = Math.max(1, Math.min(ClinicPolicy.MAX_SESSIONS, json.getInt("sessions", 4)));
            return new ReferralDecision(true, json.getString("diagnosis", facts.recentVisits().get(0)), sessions, json.getString("reason", "Physiotherapy helps the recovery."), "agentic-ai");
        } catch (RuntimeException e) {
            String relevant = facts.recentVisits().stream().filter(v -> v.toLowerCase().matches(".*(surgery|fracture|injur|strain|sprain|lame|hook|operat).*")).findFirst().orElse(null);
            if (relevant == null) return new ReferralDecision(false, "", 0, "No recent visit that physiotherapy helps with (the model's answer could not be read).", "rules");
            return new ReferralDecision(true, relevant, 4, "A recent visit shows a condition that physiotherapy helps with (the model's answer could not be read).", "rules");
        }
    }

    private ReferralFacts facts(ReferralRequest request) {
        Duck duck = ducks.findById(request.duckId()).orElseThrow(() -> new IllegalStateException("There is no duck " + request.duckId()));
        LocalDate limit = LocalDate.now().minusDays(ClinicPolicy.RECENT_VISIT_DAYS);
        List<String> recent = visits.findByDuck(duck.getId()).stream().filter(v -> !v.getVisitDate().isBefore(limit)).map(ReferralAgent::line).toList();
        String blocker = null;
        String message = null;
        if (recent.isEmpty()) {
            blocker = "NO_RECENT_VISIT";
            message = duck.getName() + " had no visit in the last " + ClinicPolicy.RECENT_VISIT_DAYS + " days, so there is nothing to refer.";
        } else if (referrals.openFor(duck.getId()).filter(r -> !r.getId().equals(request.referralId())).isPresent()) {
            blocker = "OPEN_REFERRAL";
            message = duck.getName() + " already has an open referral.";
        }
        return new ReferralFacts(duck.getId(), duck.getName(), duck.getType().getName(), String.valueOf(duck.getBirthDate()), duck.getOwner().getLastName(), duck.getOwner().getFullName(), duck.getOwner().getCity(), recent, blocker, message);
    }

    /** The full referral document. Only released on request (get_referral_document), because the owner consented to that and not to more. */
    private static String document(String id, ReferralFacts facts, ReferralDecision decision) {
        StringBuilder text = new StringBuilder();
        text.append("VETERINARY REFERRAL ").append(id).append('\n');
        text.append("Date: ").append(LocalDate.now()).append('\n');
        text.append("Referring clinic: Duck Clinic\n");
        text.append("Patient: ").append(facts.duckName()).append(" (").append(facts.duckType()).append(", born ").append(facts.birthDate()).append("), owner ").append(facts.ownerName()).append(", ").append(facts.ownerCity()).append('\n');
        text.append("Diagnosis: ").append(decision.diagnosis()).append('\n');
        text.append("Visits in the last ").append(ClinicPolicy.RECENT_VISIT_DAYS).append(" days:\n");
        facts.recentVisits().forEach(v -> text.append("- ").append(v).append('\n'));
        text.append("Recommendation: ").append(decision.sessions()).append(" physiotherapy session(s)\n");
        text.append("Owner consent to share this document with the physical therapy clinic and the owner's insurer: yes\n");
        return text.toString();
    }

    private static String line(Visit visit) {
        return visit.getVisitDate() + ": " + visit.getDescription();
    }
}
