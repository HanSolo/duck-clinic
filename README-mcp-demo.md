# Duck Clinic MCP demo

This demo adds an MCP server to the Jakarta EE Duck Clinic, so an AI client can look up owners, ducks and vets and book a visit. Booking asks the user to confirm first.

- Application: [duck-clinic](https://github.com/ivargrimstad/duck-clinic), a Jakarta EE port of the Spring PetClinic with ducks, on DuckDB.
- Library: `eu.hansolo.mcp:mcp-jakarta:0.9.0` (jakarta-mcp-server, on Maven Central).
- Protocol: MCP 2026-07-28, stateless, Streamable HTTP only. There is no `initialize` handshake and no session.
- Status: unauthenticated and meant for localhost only. Authentication (OAuth) is not part of this demo.

## What the demo shows

1. A normal Jakarta EE application gets MCP tools by adding one dependency and one CDI bean. The library itself needed no change.
2. The tools use the application's existing services (`OwnerService`, `DuckService`, `VetService`, `VisitService`). There is no second data layer.
3. A tool that writes data (`book_visit`) asks the user to confirm through elicitation. Nothing is written until the user accepts.

## Tools

| Tool | What it does | Hints |
| --- | --- | --- |
| `find_owners(lastName?)` | Finds owners with their ducks (ids included). Without a filter, returns the first 25 owners. | read-only |
| `list_vets()` | Lists the vets with their specialties (ids included). | read-only |
| `list_visits(duckId)` | Lists the visits of one duck, newest first. | read-only |
| `book_visit(duckId, date, reason, vetId?)` | Books a visit after the user confirms. `date` is `yyyy-MM-dd`, today or later. | not read-only, not destructive |

How booking behaves:

- A second visit for the same duck on the same day is refused before any question is asked.
- On the first call the server answers with a confirmation form instead of writing. The client shows it and sends the answer back.
- The confirmation is signed over the booking details (duck, date, vet, reason). If the details change between the question and the retry, the booking is refused.
- Declining or dismissing the form writes nothing.
- The clinic has no time slots, and a visit has no vet field. A chosen vet is stored as a `[Vet: …]` prefix in the visit description.

## What was added to the original code

Three files changed, and nothing else.

### 1. `pom.xml`

The library dependency:

```xml
<dependency>
    <groupId>eu.hansolo.mcp</groupId>
    <artifactId>mcp-jakarta</artifactId>
    <version>0.9.0</version>
</dependency>
```

The compiler plugin must keep parameter names, because the library reads tool argument names from the compiled classes. Without this the deployment fails with "parameter names are not available":

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.15.0</version>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

### 2. `src/main/java/dukes/DuckClinicApplication.java`

The MCP endpoint lives in the library JAR, so it has to be registered. Returning a non-empty set from `getClasses()` switches classpath scanning off, so the existing REST resources are listed as well. The application path is `/api`, not the root, because a root-mapped JAX-RS application takes the `*.xhtml` requests away from the Faces servlet and the pages return 404 (see Known issues). The REST resources and the MCP endpoint therefore live under `/duck-clinic/api/`:

```java
@ApplicationPath("/api")
public class DuckClinicApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OwnerResource.class, DuckResource.class, VisitResource.class, VetResource.class, McpResource.class);
    }
}
```

### 3. `src/main/java/dukes/mcp/ClinicTools.java` (new)

An `@ApplicationScoped` CDI bean with the four `@McpTool` methods. It receives the existing services by constructor injection, plus the library's `McpRequestStates` for signing the confirmation. Lists are returned inside wrapper records, and dates are strings, because the library does not convert `java.time` types or return bare lists.

## How to run it

### Requirements

- JDK 21 or newer and Maven (the pom sets `maven.compiler.release` to 21).
- Node.js 20 or newer for the MCP Inspector.
- Optional: the Claude Code CLI as a real client.

### Start the application

```
mvn clean package payara-server:start
```

This runs Payara Server 7.2026.10 (the pom uses `payara-server-maven-plugin`; the older `payara-micro:start` goal no longer exists in this pom). The first start downloads and provisions the server, so it needs the network and takes a while. If port 8080 is already in use, stop the process that holds it (`lsof -nP -iTCP:8080 -sTCP:LISTEN`).

Use `clean`, so classes compiled earlier without `-parameters` do not stay in `target`. In the console, look for `duck-clinic` under `Deployed`. If the "Deployed" list is empty, the first `SEVERE` block says why.

The pages are at `http://localhost:8080/duck-clinic/`, the REST API at `http://localhost:8080/duck-clinic/api/owners` and so on, and the MCP endpoint at `http://localhost:8080/duck-clinic/api/mcp`. The Payara plugin sets the context root to `/duck-clinic`. Opening the MCP URL in a browser returns 405, because the endpoint only answers `POST`.

Open Liberty (`mvn clean package liberty:run`) serves the application at the root, so the endpoint would be `http://localhost:8080/api/mcp`. Liberty was not tested for the MCP endpoint.

### Check the endpoint with curl

```
curl -s http://localhost:8080/duck-clinic/api/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

You should get four tools back.

### Try it with the MCP Inspector

```
npx @modelcontextprotocol/inspector@latest --transport http --server-url http://localhost:8080/duck-clinic/api/mcp --protocol-era modern
```

`--protocol-era modern` is required. The default era opens with the older handshake, and this server answers it with `-32020 Missing MCP-Protocol-Version header`.

1. Call `find_owners` and note a duck id.
2. Call `book_visit` with that `duckId`, a `date` of today or later, and a `reason`. A form with a "Yes, book this visit" checkbox appears.
3. Accept it. The reply says "Booked visit N …". Check with `list_visits`, or in the REST API: `http://localhost:8080/duck-clinic/api/visits?duckId=<id>`.
4. Repeat the booking for another date and decline. Nothing is written.
5. Book the first date again. The server answers "already has a visit on …" without showing a form.

### Try it with Claude Code

```
claude mcp add --scope user --transport http duck-clinic http://localhost:8080/duck-clinic/api/mcp
claude mcp list
```

Use `--scope user`. The default scope ties the server to the folder where you ran the command, and a session in another folder will not see it.

Then start `claude` and ask, for example:

> Use the duck-clinic MCP tools: find the owner Davis and book her duck for a checkup on 2026-11-25.

Claude Code asks permission to use the tools, then shows the booking confirmation before anything is written.

### Reset

The database is recreated each time the application starts. To remove a single test booking while it is running:

```
curl -X DELETE http://localhost:8080/duck-clinic/api/visits/<visitId>
```

## What was tested

- MCP Inspector against Payara Micro 7.2026.9 (with the application at the root, before the `/api` change): all four tools, including booking accepted and declined.
- `tools/list` with curl against Payara Server 7.2026.10 at `/duck-clinic/api/mcp`: four tools with the right hints. The Faces pages and the REST API also work there.
- Claude Code (also before the `/api` change): connects to the unauthenticated endpoint, calls the tools, and shows the confirmation. Booking accepted, declined (nothing written) and a duplicate date (refused) all behaved as described above.
- Not tested: Open Liberty, WildFly, GlassFish, Claude Desktop, authentication, and a booking through a client after the move to `/api` and Payara Server (only `tools/list` was repeated).

## Known issues

- **A root-mapped JAX-RS application breaks the Faces pages.** [Confirmed on Payara Server 7.2026.10] With `@ApplicationPath("")` the pages (`/duck-clinic/`, `/duck-clinic/index.xhtml`) return 404, and without the application class, or with `@ApplicationPath("/api")`, they work. The `/*` mapping of the JAX-RS application beats the `*.xhtml` mapping of the Faces servlet. That is why the application uses `/api`. The original duck-clinic code, which has the root mapping, showed the same 404 on Payara Micro. Why Open Liberty is said to work with it is not known.
- **No unauthenticated hardening.** Run the demo on localhost only.
- **Vets are only recorded as text** in the visit description, because the existing `Visit` entity has no vet field.
