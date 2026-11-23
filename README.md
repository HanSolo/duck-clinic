# Jakarta EE Duck Clinic


## Run the Jakarta EE Duck Clinic

### Run with Open Liberty

```
mvn clean package liberty:run
```

### Run with WildFly

```
mvn clean package wildfly:run -Pwildfly
```

### Run with Payara

```
mvn clean package payara-server:start
```

The application is then available at http://localhost:8080/duck-clinic/.

### Run with Docker

Needs Docker with Compose. The `Dockerfile` builds the WAR and runs it on Payara Server.

```
docker compose up --build
```

Open http://localhost:8080/duck-clinic/ once the log says the application is deployed. Stop with `docker compose down`.

The data is reset at every start, and port 8080 is bound to `127.0.0.1` only. See `README-docker.md` for details, including how to change the Payara version.


## Agent-to-agent demo

This application has an agent that takes part in a referral across DuckClinic, the Duck Physical Therapy Clinic and DuckInsurance. The design, tools, policies and limits are in `README-agents.md` of the DuckInsurance project (copied here as `README-agents.md`). Model settings: copy `llm.env.example` to `llm.env`. It holds three options: a local Ollama (default), Claude through the Anthropic API (`LLM_PROVIDER=anthropic`) and any service in the OpenAI Chat Completions format (`LLM_PROVIDER=openai`: OpenAI, Groq, OpenRouter, Mistral, ...), each with `LLM_BASE_URL`, `LLM_MODEL` and `LLM_API_KEY`. The agent still injects `LargeLanguageModel`; the bean comes from the application (`agent/ConfiguredLlm`), because Payara 7.2026.10 has no OpenAI-format backend, and while it exists Payara's default model and `payara.agentic.llm.*` are not used.
