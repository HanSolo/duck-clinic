package dukes.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


/**
 * The hard limits of the referral agent, enforced in code. The language model only decides inside them.
 * The same rules are written in plain language in policies/clinic-agent-policy.md, which the model gets as its instructions: change both together.
 */
public final class ClinicPolicy {
    public static final int MAX_SESSIONS        = 6;
    public static final int RECENT_VISIT_DAYS   = 90;

    private ClinicPolicy() {
    }


    public static String text() {
        try (InputStream in = ClinicPolicy.class.getResourceAsStream("/policies/clinic-agent-policy.md")) {
            return in == null ? "(policy file missing)" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(policy file unreadable)";
        }
    }
}
