package eu.hansolo.mcp.client;

import java.util.List;

/**
 * Suggested values for one argument of a prompt or a resource template (completion/complete).
 *
 * @param values  the suggestions, best first; the protocol allows at most 100
 * @param total   how many values exist in all, or -1 if the server did not say
 * @param hasMore true if the server has more values than it returned
 */
public record Completion(List<String> values, int total, boolean hasMore) {
}
