package dukes.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


/** The referrals of this clinic. In memory only, like the demo data, which is recreated on every start. */
@ApplicationScoped
public class ReferralStore {
    private final Map<String, Referral> referrals = new ConcurrentHashMap<>();
    private final AtomicInteger         numbers   = new AtomicInteger();


    /** The next referral id. The id is shared with the therapy clinic and the insurer: they carry it through every call. */
    public String nextId() {
        return "REF-%04d".formatted(numbers.incrementAndGet());
    }

    public void save(Referral referral) {
        referrals.put(referral.getId(), referral);
    }

    public Optional<Referral> get(String id) {
        return Optional.ofNullable(id == null ? null : referrals.get(id.strip()));
    }

    public Optional<Referral> openFor(long duckId) {
        return referrals.values().stream().filter(r -> r.getDuckId() == duckId && r.isOpen()).findFirst();
    }

    public List<Referral> all() {
        List<Referral> list = new ArrayList<>(referrals.values());
        list.sort((a, b) -> a.getId().compareTo(b.getId()));
        return list;
    }

    public void clear() {
        referrals.clear();
    }
}
