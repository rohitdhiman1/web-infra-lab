# web-infra-lab

Runnable reference labs for the **web-infra-guide** documentation site. The guide explains each
module and gives its lab spec and snippets; this repo holds complete projects that pass that spec.

## Layout

```
orders-api/
  java/<module>/     Spring Boot 4 on Java 25 (Maven Wrapper included)
  python/<module>/   FastAPI on Python 3.14 (managed with uv)
```

Each module folder is a complete, independent snapshot. Later modules build on earlier ones, so
diffing two folders shows exactly what a module adds.

| Module | Folder | What it adds |
|---|---|---|
| A0 Foundations | `a0-first-api` | POST/GET on an in-memory orders collection; 201 + Location; 404 |
| A1 HTTP semantics | `a1-http-semantics` | PUT, PATCH (JSON Merge Patch), DELETE, HEAD; 400/405/406/409/415/422 done deliberately |
| A2 Errors and contracts | `a2-errors-contracts` | RFC 9457 problem details for every error (framework errors included); OpenAPI document |
| A3 Reliability | `a3-reliability` | Postgres; payments with `Idempotency-Key`; slow fake gateway; a retrying client with backoff + jitter |
| A4 Concurrency and caching | `a4-concurrency-caching` | ETags from a version column; `If-None-Match` → 304; `If-Match` → 412; 428 without it; atomic version check |
| A5 Collections | `a5-collections` | `GET /orders` with cursor (keyset) pagination, offset for comparison, status filter, `Link` headers |
| A6 Evolving an API | `a6-evolving` | `/v1` and `/v2` over one store; `Deprecation`, `Sunset` and `Link` headers on v1; an additive field (in memory) |
| A7 Long-running work | `a7-async-webhooks` | `202` + status resource; signed webhooks (Standard Webhooks) retried with backoff (in memory) |

From A3 on, labs need Postgres: `docker compose up -d` in the module folder (port 55432, throwaway
credentials `lab`/`lab`). The Java and Python labs share the schema and the port, so run one at a time;
their tests truncate the tables before each test. From A4 on, each module uses its own Postgres schema
(`a4`, `a5`, …) so table shapes never clash.

Modules focus on their own topic: A3 keeps only create/read for orders plus payments, and A4/A5 build on
the order resource without payments. Earlier behaviour lives in earlier folders.

## Running a lab

Java (needs JDK 25 as `JAVA_HOME`):

```bash
cd orders-api/java/a1-http-semantics
./mvnw test                 # runs the spec as HTTP tests against a real server
./mvnw spring-boot:run      # serves on http://localhost:8080
```

Python (needs [uv](https://docs.astral.sh/uv/)):

```bash
cd orders-api/python/a1-http-semantics
uv run pytest               # the same spec, case for case
uv run uvicorn app.main:app --port 8000
```

Both languages implement the same spec and their tests mirror each other.
