package dukes.agent;

import java.util.List;


/**
 * What the code found out about the duck. recentVisits are the visits of the last 90 days, newest first ("date: description").
 *
 * @param blocker null if the agent may decide, otherwise NO_RECENT_VISIT or OPEN_REFERRAL
 */
public record ReferralFacts(long duckId, String duckName, String duckType, String birthDate, String ownerLastName, String ownerName, String ownerCity, List<String> recentVisits, String blocker, String message) {
}
