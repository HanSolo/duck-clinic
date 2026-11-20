package eu.hansolo.mcp.client;

/** Receives the progress events of one call. It runs on a client thread, not on the caller's in the asynchronous case: keep it short and do not block. An exception it throws is logged and ignored. */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(Progress progress);
}
