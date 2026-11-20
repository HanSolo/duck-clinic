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
}
