package dukes.rest;

import dukes.agent.AgentLog;
import dukes.agent.ReferralStore;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;


/** The log of this clinic's agent, for the conversation page of DuckInsurance. DELETE forgets the log and the referrals (visits stay), so that the demo can be shown again. */
@Path("/agent/log")
@Produces(MediaType.APPLICATION_JSON)
public class AgentLogResource {

    @Inject
    private AgentLog      log;
    @Inject
    private ReferralStore referrals;


    @GET
    public JsonObject get() {
        return Json.createObjectBuilder().add("source", "Duck Clinic").add("entries", log.toJson()).build();
    }

    @DELETE
    public Response clear() {
        log.clear();
        referrals.clear();
        return Response.noContent().build();
    }
}
