package dukes.mcp;

import dukes.model.Duck;
import dukes.model.Owner;
import dukes.model.Vet;
import dukes.model.Visit;
import dukes.service.DuckService;
import dukes.service.OwnerService;
import dukes.service.VetService;
import dukes.service.VisitService;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpRequestStates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * The duck clinic as MCP tools. Reads go through the existing services; booking writes a {@link Visit} through {@link VisitService}, so there is no second data layer.
 * The clinic has no vet or time slot on a visit, so a booking is a duck, a date and a reason; a chosen vet is recorded as a prefix of the description.
 */
@ApplicationScoped
public class ClinicTools {

    static final int MAX_OWNERS = 25;

    private OwnerService      ownerService;
    private DuckService       duckService;
    private VetService        vetService;
    private VisitService      visitService;
    private McpRequestStates  states;


    /** For the CDI proxy. */
    ClinicTools() {
    }

    @Inject
    public ClinicTools(OwnerService ownerService, DuckService duckService, VetService vetService, VisitService visitService, McpRequestStates states) {
        this.ownerService = ownerService;
        this.duckService  = duckService;
        this.vetService   = vetService;
        this.visitService = visitService;
        this.states       = states;
    }


    public record DuckView(long id, String name, String type, String birthDate) {
    }

    public record OwnerView(long id, String name, String city, String telephone, List<DuckView> ducks) {
    }

    public record OwnerList(List<OwnerView> owners, boolean truncated) {
    }

    public record VetView(long id, String name, List<String> specialties) {
    }

    public record VetList(List<VetView> vets) {
    }

    public record VisitView(long id, String date, String description) {
    }

    public record VisitList(long duckId, String duckName, List<VisitView> visits) {
    }


    @McpTool(description = "Finds duck owners with their ducks (ids included). Filter by part of the last name; without a filter the first owners are returned.", readOnlyHint = McpTool.Hint.TRUE)
    public OwnerList find_owners(@McpParam(description = "Part of the last name, case-insensitive") Optional<String> lastName) {
        String filter = lastName.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        List<Owner> owners = filter == null ? ownerService.findAll() : ownerService.findByLastName(filter);
        List<OwnerView> views = owners.stream().limit(MAX_OWNERS).map(o -> new OwnerView(o.getId(), o.getFullName(), o.getCity(), o.getTelephone(), o.getDucks().stream().map(ClinicTools::view).toList())).toList();
        return new OwnerList(views, owners.size() > MAX_OWNERS);
    }

    @McpTool(description = "Lists the vets with their specialties (ids included).", readOnlyHint = McpTool.Hint.TRUE)
    public VetList list_vets() {
        return new VetList(vetService.findAll().stream().map(v -> new VetView(v.getId(), v.getFullName(), v.getSpecialties().stream().map(s -> s.getName()).toList())).toList());
    }

    @McpTool(description = "Lists the visits (past and booked) of one duck, newest first. The clinic has no time slots: use this to avoid booking a duck twice on one day.", readOnlyHint = McpTool.Hint.TRUE)
    public VisitList list_visits(@McpParam(description = "Duck id from find_owners") long duckId) {
        Duck duck = duckService.findById(duckId).orElseThrow(() -> new ToolExecutionException("There is no duck " + duckId));
        List<VisitView> visits = visitService.findByDuck(duckId).stream().map(v -> new VisitView(v.getId(), v.getVisitDate().toString(), v.getDescription())).toList();
        return new VisitList(duck.getId(), duck.getName(), visits);
    }

    /**
     * Booking writes data, so the user is asked first (multi round-trip). The request state is signed over the arguments, so a retry that changes the duck, date, vet or reason after the user said yes is refused.
     */
    @McpTool(description = "Books a visit for a duck on a date, after the user confirms. Requires a client that can ask the user (elicitation).", readOnlyHint = McpTool.Hint.FALSE, destructiveHint = McpTool.Hint.FALSE, idempotentHint = McpTool.Hint.FALSE)
    public ToolResult book_visit(@McpParam(description = "Duck id from find_owners") long duckId, @McpParam(description = "Date as yyyy-MM-dd, today or later") String date, @McpParam(description = "Reason for the visit, up to 800 characters") String reason, @McpParam(description = "Vet id from list_vets (optional)") Optional<Long> vetId, ToolCall call) {
        LocalDate day = parseDate(date);
        if (day.isBefore(LocalDate.now())) {
            throw new ToolExecutionException("date must be today or later, got " + day);
        }
        String cleanReason = reason.trim();
        if (cleanReason.isEmpty() || cleanReason.length() > 800) {
            throw new ToolExecutionException("reason must be 1 to 800 characters");
        }
        Duck duck = duckService.findById(duckId).orElseThrow(() -> new ToolExecutionException("There is no duck " + duckId));
        Vet vet = vetId.map(id -> vetService.findById(id).orElseThrow(() -> new ToolExecutionException("There is no vet " + id))).orElse(null);
        if (visitService.findByDuck(duckId).stream().anyMatch(v -> day.equals(v.getVisitDate()))) {
            throw new ToolExecutionException(duck.getName() + " already has a visit on " + day);
        }

        String summary = "Book " + duck.getName() + " (owner " + duck.getOwner().getFullName() + ") on " + day + (vet == null ? "" : " with " + vet.getFullName()) + ": " + cleanReason + "?";
        String payload = duckId + "|" + day + "|" + (vet == null ? "" : vet.getId()) + "|" + cleanReason;
        String context = call.name() + "|" + call.caller().subject();

        Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "confirm");
        if (answer.isEmpty()) {
            if (!call.clientSupportsElicitation(Elicitation.Mode.FORM)) {
                throw McpProtocolException.missingClientCapability("elicitation");
            }
            return ToolResult.ask(InputRequests.of("confirm", confirmation(summary)), states.sign(payload, context));
        }
        if (!payload.equals(states.require(call.requestState(), context))) {
            throw new ToolExecutionException("The booking details changed after the user was asked. Call book_visit again.");
        }
        if (!answer.get().accepted() || !confirmation(summary).problems(answer.get()).isEmpty() || !answer.get().bool("confirm").orElse(false)) {
            return ToolResult.error("Not booked: the user did not confirm.");
        }

        Visit visit = new Visit();
        visit.setVisitDate(day);
        visit.setDescription((vet == null ? "" : "[Vet: " + vet.getFullName() + "] ") + cleanReason);
        visit.setDuck(duck);
        Visit saved = visitService.save(visit);
        return ToolResult.text("Booked visit " + saved.getId() + " for " + duck.getName() + " on " + day + (vet == null ? "" : " with " + vet.getFullName()) + ".");
    }

    private static Elicitation.Form confirmation(String summary) {
        return Elicitation.form(summary).bool("confirm", p -> p.title("Yes, book this visit").required().defaultValue(true));
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new ToolExecutionException("date must look like 2026-12-01, got '" + date + "'");
        }
    }

    private static DuckView view(Duck duck) {
        return new DuckView(duck.getId(), duck.getName(), duck.getType().getName(), duck.getBirthDate().toString());
    }
}
