package dukes.agent;

import java.time.LocalDateTime;


/**
 * A referral of a duck to the physical therapy clinic, as the clinic sees it.
 * status: DECLINED (the agent found no reason), SENT, PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT, COMPLETED, CANCELLED (the referral was cancelled after it was scheduled), FAILED (the therapy clinic could not be reached).
 */
public class Referral {
    private final String        id;
    private final long          duckId;
    private final String        duckName;
    private final String        ownerLastName;
    private final String        diagnosis;
    private final int           sessions;
    private final String        document;
    private final LocalDateTime createdAt = LocalDateTime.now();
    private       String        status;
    private       String        message;


    public Referral(String id, long duckId, String duckName, String ownerLastName, String diagnosis, int sessions, String document, String status, String message) {
        this.id            = id;
        this.duckId        = duckId;
        this.duckName      = duckName;
        this.ownerLastName = ownerLastName;
        this.diagnosis     = diagnosis;
        this.sessions      = sessions;
        this.document      = document;
        this.status        = status;
        this.message       = message;
    }


    public String getId() { return id; }
    public long getDuckId() { return duckId; }
    public String getDuckName() { return duckName; }
    public String getOwnerLastName() { return ownerLastName; }
    public String getDiagnosis() { return diagnosis; }
    public int getSessions() { return sessions; }
    public String getDocument() { return document; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public synchronized String getStatus() { return status; }
    public synchronized String getMessage() { return message; }

    public synchronized void update(String status, String message) {
        this.status  = status;
        this.message = message;
    }

    /** A referral that still needs attention: a new one for the same duck is not allowed. */
    public boolean isOpen() {
        String s = getStatus();
        return !(s.equals("DECLINED") || s.equals("COMPLETED") || s.equals("NOT_COVERED") || s.equals("REJECTED") || s.equals("FAILED") || s.equals("CANCELLED"));
    }
}
