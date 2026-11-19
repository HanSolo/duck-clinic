# Running duck-clinic in Docker

Needs Docker with Compose. Run these in the project root (next to `pom.xml`).

```
docker compose up --build
```

The first build downloads Maven dependencies and the Payara image, so it takes a few minutes. Wait for the log line saying the application is deployed.

| What | URL |
| --- | --- |
| Web UI | http://localhost:8080/duck-clinic/ |
| REST API | http://localhost:8080/duck-clinic/api/owners |
| MCP endpoint (POST only) | http://localhost:8080/duck-clinic/api/mcp |

Register it in Claude Code:

```
claude mcp add --scope user --transport http duck-clinic http://localhost:8080/duck-clinic/api/mcp
```

Stop with `docker compose down`.

## Notes

- **Data is reset on every start.** `schema.sql` drops and recreates the tables and the seed data is loaded again, so the DuckDB file in the container (`/tmp/duck_clinic.db`) needs no volume. Bookings made through MCP are lost when the container restarts.
- **Localhost only.** The compose file binds port 8080 to `127.0.0.1` because the MCP endpoint is unauthenticated. Do not change it to `0.0.0.0:8080` on a shared network.
- **Payara version.** The default is `7.2026.10`. To use another one, change `PAYARA_VERSION` in `docker-compose.yml`, or run `docker compose build --build-arg PAYARA_VERSION=7.2026.11`. Tags with a `-jdk25` suffix use JDK 25. The plain tags are, as far as I know, the JDK 21 images; check with `docker run --rm payara/server-full:7.2026.10 java -version` if it matters. The project compiles for release 21, so either works.
- **Changed code:** run `docker compose up --build` again.
- The H2 warnings at startup described in `README-mcp-demo.md` also appear in the container log and are harmless.
- **JDK vendor.** The build stage uses the official `azul-zulu:21` image. The runtime stage uses the Payara image, whose JDK vendor I could not confirm for 7.2026.10. Check with `docker run --rm --entrypoint java payara/server-full:7.2026.10 -version`; the output names Zulu or Temurin.
