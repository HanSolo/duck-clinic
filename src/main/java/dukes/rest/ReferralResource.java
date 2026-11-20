package dukes.rest;

import dukes.agent.Referral;
import dukes.agent.ReferralService;
import dukes.agent.ReferralStore;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;


/** Referrals to the physical therapy clinic. POST starts the clinic's referral agent for one duck and returns at once (202), the rest happens in the background and shows in /agent/log. */
@Path("/referrals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReferralResource {

    @Inject
    private ReferralService service;
    @Inject
    private ReferralStore   referrals;


    /** Body: {"ownerLastName": "Keller", "duckName": "Sir Quacksalot", "ownerConsent": true} */
    @POST
    public Response start(JsonObject body) {
        try {
            String id = service.start(body.getString("ownerLastName", ""), body.getString("duckName", ""), body.getBoolean("ownerConsent", false));
            return Response.accepted(Json.createObjectBuilder().add("referralId", id).add("message", "The clinic agent is looking at the duck. Follow it in /agent/log.").build()).build();
        } catch (IllegalArgumentException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return error(Response.Status.CONFLICT, e.getMessage());
        }
    }

    @GET
    public JsonArray list() {
        JsonArrayBuilder array = Json.createArrayBuilder();
        for (Referral r : referrals.all()) array.add(Json.createObjectBuilder().add("referralId", r.getId()).add("duckName", r.getDuckName()).add("status", r.getStatus()).add("sessions", r.getSessions()).add("diagnosis", r.getDiagnosis()).add("message", r.getMessage() == null ? "" : r.getMessage()));
        return array.build();
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Json.createObjectBuilder().add("error", message).build()).build();
    }
}
