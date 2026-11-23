# Agent-to-agent: referral from the DuckClinic to physiotherapy, with DuckInsurance

Three applications, three agents, one referral id (REF-0001) carried through every call. Each agent has its own policy file in plain language (what the model is told) and hard limits in code (what the model cannot override).

| | DuckClinic | Duck Physical Therapy Clinic | DuckInsurance |
|---|---|---|---|
| Stack, port | Jakarta EE 11, Payara 7, 8080 (`/duck-clinic`) | Quarkus 3, 8082 | Jakarta EE 11, Payara 7, 8081 |
| Agent | `dukes.agent.ReferralAgent` (`@Agent`) | `agent.TherapyAgent` (plain class, same steps) | `agent.PreauthAgent` (`@Agent`) next to `ClaimFraudAgent` |
| Policy file | `policies/clinic-agent-policy.md` | `policies/therapy-agent-policy.md` | `policies/insurer-agent-policy.md` |
| Limits in code | `ClinicPolicy`: owner consent, visit within 90 days, no open referral, 1 to 6 sessions | `TherapyAgent`: at most 6 sessions, working days 08:00-18:00, 2 days apart, book only what the insurer approved | `PreauthPolicy`: PHYSIO policy, 14 days waiting period, referral document, 6 sessions, 75 EUR per session, remaining coverage |
| Tools others call (MCP) | `get_referral_document` (asks back: confirm release), `report_referral_status` | `propose_treatment_plan`, `get_plan_status`, `complete_appointment` | `request_preauthorization` (asks back: referral document), `submit_provider_claim`, `get_preauthorization` |
| Conversation log | `GET /duck-clinic/api/agent/log` | `GET /api/agent/log` | in process, merged by `GET /api/agent/timeline` |

## The story (Sir Quacksalot, owner Anna Keller)

1. Start: `POST /duck-clinic/api/referrals` (or the button on the Agents page). Clinic agent: reads the visits of the last 90 days, asks the model, writes the referral, calls `propose_treatment_plan` at the therapy clinic. Answer RECEIVED, the rest is asynchronous.
2. Therapy agent: finds the duck, model picks treatment, therapist, sessions; code finds free slots; asks the insurer `request_preauthorization` **without** the referral document (minimum sharing).
3. Insurance agent: the rule "referral document required" fires. The tool does not end the call with an error, it answers `input_required`: a form with the text field `referralDocument` and a signed `requestState`. The therapy agent's MCP client answers the question in code and repeats the call.
4. To answer, the therapy agent calls the clinic's `get_referral_document`, which also answers `input_required`: "do you confirm the release?" (a checkbox). The therapy agent confirms because it holds the referral and the insurer asked, and gets the document. Two nested multi round-trip requests, no session on any server.
5. Insurance agent: APPROVED, PARTIAL or DENIED. The model decides the number of sessions inside the limits the code computed.
6. Therapy agent books the approved sessions and tells the clinic agent (`report_referral_status` SCHEDULED, or NOT_COVERED).
7. After each session (`POST /api/agent/complete-next` or the button): `submit_provider_claim`. The insurer files a claim and starts its existing fraud agent, which asks the two clinics whether the visit happened.

## Run it

1. `llm.env` in each project (copy `llm.env.example`): the same model everywhere, default `qwen3.8:27b` on the local Ollama. For Claude, use option B of `llm.env.example` in all three projects: `LLM_PROVIDER=anthropic`, `LLM_BASE_URL=https://api.anthropic.com`, `LLM_MODEL` and `LLM_API_KEY`.
2. Start all three: `docker compose up --build` in each folder. The insurer and the DuckClinic take the MCP client `eu.hansolo.mcp:mcp-jakarta-client:0.3.0` from Maven Central. Without Docker: `mvn clean package payara-server:start`.
3. Sign in to DuckInsurance as `adjuster/adjuster`, open **Agents**.

Settings: `DUCKCLINIC_REST_URL`, `PHYSIOCLINIC_REST_URL`, `AGENT_SECRET` (insurer, compose.yaml); `THERAPY_MCP_URL` (clinic); `INSURER_MCP_URL`, `DUCKCLINIC_MCP_URL`, `AGENT_SECRET`, `LLM_*` (therapy clinic).

## Known limits

- The conversation logs, referrals, plans and pre-authorizations live in memory. A restart of one application loses its part. Visits, appointments and claims are in the databases and stay.
- The shared key between the applications is owner last name plus duck name (their ids differ). Two owners with the same last name and a duck of the same name would be confused.
- The insurer's tools accept a shared secret (`clinic-agent:SECRET`, `therapy-agent:SECRET`). The clinics' MCP endpoints have no authentication. Demo only.
- Multi round-trip requests (`input_required`) are used for the two questions above. A caller that declares no elicitation capability gets the status `NEEDS_INFO` from the insurer instead and may call again with the argument `referralDocument`; the clinic refuses such a caller. Long-running work (planning) uses a status tool plus a callback, not MCP Tasks.
- The answers to the questions are given by code (an input handler in the agent's MCP client), not by a person.
- The therapy clinic is Quarkus, which has no runtime for the draft Jakarta Agentic AI API. Its agent has the same steps as plain methods and its own small client (Ollama's native chat API, or the Anthropic Messages API with `LLM_PROVIDER=anthropic`).
- A completed session may be marked before its start time (demo shortcut).
- Nothing in this change was compiled against the real libraries or run against a real model. See HANDOVER-agents.md.
