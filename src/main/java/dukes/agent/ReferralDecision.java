package dukes.agent;


/**
 * @param refer    false if no referral is made
 * @param sessions suggested number of physiotherapy sessions, 1 to 6 (0 if no referral)
 * @param source   "agentic-ai" if the language model judged, "rules" if only the code decided
 */
public record ReferralDecision(boolean refer, String diagnosis, int sessions, String reason, String source) {
}
