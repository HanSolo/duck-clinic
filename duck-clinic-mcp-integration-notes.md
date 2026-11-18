# Adding jakarta-mcp-server to duck-clinic: integration notes

Date: 2026-11-11

Context: `jakarta-mcp-server` (groupId `eu.hansolo.mcp`, version `0.9.0` in the poms) was added to duck-clinic (https://github.com/ivargrimstad/duck-clinic, a Jakarta EE port of Spring PetClinic with ducks, on DuckDB) to expose clinic data and booking as MCP tools. It was tested with MCP Inspector and Claude Code on Payara Micro 7. Tags: [Certain] hard evidence, [Likely] strong inference, [Guessing] filling gaps.

## Verdict

Adding the library was easy once, and the one hard stop was the missing `-parameters` compiler flag. Every other obstacle came from the test setup, not the library. The first deployment failed, and because the whole application fails with it, every URL returned a plain 404 until the Payara log was read.

- [Certain] No change to the library's public API was needed. The three read tools, the booking tool and the confirmation step all worked with the API as it stands.
- [Certain] Library-caused friction: one deployment failure (`-parameters`), plus README and version inconsistencies that did not block anything.
- [Likely] The author of the library did this test, which makes it easier than it would be for an outside developer. These notes say how it went for someone who knows the project, not how it would go for a first-time user.

## What was done

Three files changed in duck-clinic, and nothing in the library. Result: four MCP tools on `/duck-clinic/mcp`, unauthenticated, working in MCP Inspector and in Claude Code.

- `pom.xml`: dependency `eu.hansolo.mcp:mcp-jakarta:0.9.0`, plus `<parameters>true</parameters>` on the compiler plugin.
- `DuckClinicApplication.java`: lists the four existing REST resources and `McpResource` explicitly.
- `dukes/mcp/ClinicTools.java` (new): `find_owners`, `list_vets`, `list_visits` (all read-only) and `book_visit` (not read-only, not destructive), which writes through the existing `VisitService` after a confirmation form.

Tested: booking accepted, booking declined (nothing written), and a second booking for the same duck and day (refused before any form).

## Effort by step

Five of nine steps worked first time. Four needed a second try, and two of those were my own mistakes (the URL and the curl command).

| Step | Result | Friction |
| --- | --- | --- |
| Install the library locally | Worked | The parent pom must be installed too (`-pl .,mcp-jakarta`). The version is `0.9.0`, not a snapshot, which contradicted the handoff. |
| Add the dependency | Worked | None. The local pom already had release 25. |
| Write the tools bean | Compiled, as far as the build output showed | No `LocalDate` support, no `List` return type, and `Elicitation.form` refuses a form with no fields, so each needed a workaround. |
| Register the endpoint | Worked | `getClasses()` must list the app's own REST resources, or scanning is switched off. |
| Deploy on Payara | Failed | Missing `-parameters`: the whole application failed to deploy, and every URL returned 404. |
| Open the app | Confused | I gave Liberty URLs for a Payara run. Payara's context root is `/duck-clinic`. |
| `tools/list` with curl | Worked | My first curl lacked the `Mcp-Method` header and the `_meta` fields. |
| MCP Inspector | Worked | Needs `--protocol-era modern`, which the README states. |
| Claude Code | Worked after two detours | Login had expired, and `claude mcp add` defaulted to local scope, so a session in another folder did not see the server. |

## What went smoothly

The core idea of the library held up: a CDI bean with annotated methods became working MCP tools with almost no ceremony.

- [Certain] Tool declaration needed only `@McpTool` and `@McpParam`. Input and output schemas were generated from the method signatures and records.
- [Certain] The hints came out right in `tools/list`: `readOnlyHint` true on the three read tools, and `readOnlyHint` and `destructiveHint` false on `book_visit`.
- [Certain] The booking used duck-clinic's existing `VisitService`, so no second data layer was needed.
- [Certain] The stateless endpoint connected to Claude Code with no handshake and no authentication.
- [Certain] The multi round-trip confirmation worked in a real client in all three cases: accept, decline and duplicate date. The signed request state held through the retry.
- [Certain] The one deployment error named the class and method and said what to do.

## Where it was harder than it should be

The missing `-parameters` step cost the most time. Ranked by cost to a new user:

1. **`-parameters` is not in the adding-the-library steps.** The only mention is under "Writing tools", as "the parent POM does", which does not apply to an app with its own parent. The deployment then fails as a whole and every URL returns 404 until the server log is read.
2. **`Elicitation.form` cannot express a plain confirmation.** It throws when a form has no fields, while `close_ticket` in the example builds raw JSON with an empty `properties` object. I added a checkbox to get around it.
3. **Types and returns that are not covered.** `java.time` types are not converted (dates are strings with no `format: date` in the schema), and `List` is not a return type, so each list needs a wrapper record.
4. **Version statements disagree.** The poms say `0.9.0`, the README calls 0.9.0 released and shows `0.7.0` in its dependency example, and the handoff said 0.8.0 is on Central.
5. **Registering the endpoint.** The README says to add `McpResource` to the `Application`. It does not say that a non-empty `getClasses()` switches scanning off, so the app's own REST resources must be listed too.
6. **The curl examples cover only `tools/call`.** Listing tools needs the `Mcp-Method` header and `_meta` fields that are not shown for that method.

**Problems I caused or that are not the library's:**

- I gave Liberty URLs (`/owners`, `/mcp`) for a Payara run, which uses `/duck-clinic`. This cost one round trip.
- My first curl command lacked the headers and `_meta` fields.
- Claude Code's login had expired, and `claude mcp add` defaulted to local scope, so a session in another folder did not see the server. Neither is a library issue.

## Caveats

This was one small app on one server with one real client, so it shows the path works but not that it is easy for everyone.

- The test was run by the library's author, who knows the conventions. A developer new to the project would likely hit more friction.
- Only Payara Micro was used. Open Liberty and WildFly were not tried with duck-clinic, and duck-clinic's pom names both.
- Only Claude Code was tested as a real client. Claude Desktop was not tried and may need an `https` URL.
- Authentication, clustering and load were out of scope. The endpoint was open on localhost.
- Four small tools is a light test. Tasks, streaming, resources and prompts were not used.
- I could not compile or run anything myself. The build output and logs came from the user's machine, so the first successful compile is inferred from the absence of a reported compile error.

## Next steps for the 1.0.0 review

The fixes that cost users the most time need no API change, so they can be done before the freeze.

1. **README only:** add `-parameters` to the adding-the-library steps, fix the version statements, note that a bad tool declaration fails the whole deployment, say that `getClasses()` must list existing resources, and add `tools/list` to the curl examples.
2. **Decide before the freeze:** whether `Elicitation.form` should allow a form with no fields, and which public classes in `eu.hansolo.mcp.server` are internal.
3. **Example fix:** bind `close_ticket`'s retry to the confirmed arguments with `McpRequestStates`, as `book_visit` does.
4. **More real-host testing:** run duck-clinic on Open Liberty and WildFly, and try Claude Desktop.
5. **Outsider test:** have someone who did not write the library repeat these steps from the README alone and note where they get stuck.

## Update 2026-11-18: later findings

- [Certain] The duck-clinic Faces pages returned 404 on Payara. With `@ApplicationPath("")` they fail and without the application class, or with `@ApplicationPath("/api")`, they work, tested on Payara Server 7.2026.10. The root-mapped JAX-RS application takes `*.xhtml` requests away from the Faces servlet. The tests above were done before this change, so the REST API and the MCP endpoint were at `/duck-clinic/...`. They are now at `/duck-clinic/api/...`.
- [Certain] The author's newer pom runs Payara Server through `payara-server-maven-plugin` (`mvn clean package payara-server:start`) instead of Payara Micro. After the move to `/api`, `tools/list` was repeated with curl against `/duck-clinic/api/mcp` and returned the four tools with the right hints. A booking through a client was not repeated after the move.
- New gap for the library README: an application with a UI has to give JAX-RS a sub-path, or the pages return 404. The README should say so, and say what a sub-path does to the endpoint and to the OAuth discovery locations.
