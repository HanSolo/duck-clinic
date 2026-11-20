package eu.hansolo.mcp.client;

/**
 * One {@code notifications/progress} event of a running request.
 *
 * @param progress the work done so far; it increases with every event
 * @param total    the total work, or null if the server does not know it
 * @param message  a human-readable status, or null
 */
public record Progress(double progress, Double total, String message) {
}
