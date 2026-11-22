package dukes.agent;


/**
 * The CDI event that starts the referral agent's workflow: a vet asks to consider one duck. The owner's consent was checked before.
 *
 * @param referralId the id that the three applications carry through every call, assigned before the workflow starts so that every log line has it
 */
public record ReferralRequest(String referralId, long duckId) {
}
