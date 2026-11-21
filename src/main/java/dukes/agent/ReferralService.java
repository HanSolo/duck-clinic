package dukes.agent;

import dukes.model.Duck;
import dukes.model.Owner;
import dukes.service.OwnerService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;


/** Starts the referral agent for one duck. The agent calls the model and the therapy clinic, which takes a while, so it runs in the background. */
@ApplicationScoped
public class ReferralService {
    private static final Logger LOG = Logger.getLogger(ReferralService.class.getName());

    @Resource
    private ManagedExecutorService   executor;
    @Inject
    private Event<ReferralRequest>   requests;
    @Inject
    private OwnerService             owners;
    @Inject
    private ReferralStore            referrals;
    @Inject
    private AgentLog                 log;
    @Inject
    private TherapyClient            therapy;


    /** @return the referral id; the agent's work shows up in the log and, when it is done, as a referral in the store */
    public String start(String ownerLastName, String duckName, boolean ownerConsent) {
        Duck duck = owners.findByLastName(ownerLastName).stream().filter(o -> o.getLastName().equalsIgnoreCase(ownerLastName)).map(Owner::getDucks).flatMap(java.util.List::stream).filter(d -> d.getName().equalsIgnoreCase(duckName)).findFirst().orElseThrow(() -> new IllegalArgumentException("The clinic has no duck " + duckName + " of owner " + ownerLastName));
        if (!ownerConsent) throw new IllegalStateException("The owner has not consented, so the clinic does not share anything and makes no referral.");
        referrals.openFor(duck.getId()).ifPresent(r -> {
            throw new IllegalStateException(duck.getName() + " already has an open referral: " + r.getId());
        });
        String id = referrals.nextId();
        log.add(id, "Vet", AgentLog.AGENT, "REQUEST", "Duck " + duck.getName() + " (owner " + ownerLastName + ") is recovering. Should I refer her to physiotherapy? The owner consents to sharing the data.");
        ReferralRequest request = new ReferralRequest(id, duck.getId());
        executor.submit(() -> {
            try {
                requests.fire(request);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Referral workflow " + id + " failed", e);
            }
        });
        return id;
    }

    /**
     * The vet cancels a scheduled referral. Only a referral that is scheduled or in treatment can be cancelled. The therapy clinic's agent does the rest: it cancels the booked sessions,
     * has the insurer release the coverage that was reserved for them and reports CANCELLED back to this clinic.
     */
    public String cancel(String referralId, String reason) {
        Referral referral = referrals.get(referralId).orElseThrow(() -> new IllegalArgumentException("This clinic made no referral " + referralId));
        String   status   = referral.getStatus();
        if (!(status.equals("SCHEDULED") || status.equals("IN_TREATMENT"))) throw new IllegalStateException("Referral " + referralId + " is " + status + ", only a scheduled referral can be cancelled.");
        String why = reason == null || reason.isBlank() ? "the owner asked to stop the treatment" : reason.strip();
        log.add(referralId, "Vet", AgentLog.AGENT, "REQUEST", "Please cancel the physiotherapy of " + referral.getDuckName() + ": " + why + ".");
        log.say(referralId, "Therapy Agent", "REQUEST", "Please cancel referral " + referralId + " (" + why + "). Sessions that are done stay billed, the booked ones can go.");
        try {
            String answer = therapy.cancel(referralId, why);
            log.say(referralId, null, "THOUGHT", "The therapy clinic answered: " + answer);
            return answer;
        } catch (RuntimeException e) {
            log.say(referralId, null, "ERROR", "The cancellation could not be handed over: " + e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
