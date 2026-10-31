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

From A3 on, labs need Postgres: `docker compose up -d` in the module folder (port 55432, throwaway
credentials `lab`/`lab`). The Java and Python labs share the schema and the port, so run one at a time;
their tests truncate the tables before each test.

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
