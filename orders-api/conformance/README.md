# Capstone conformance suite

Black-box tests for the A10 capstone spec. They speak only HTTP, so they run against any
implementation, in any language:

```bash
BASE_URL=http://localhost:8080 uv run pytest -q
```

Each test uses its own `X-API-Key`, so the suite doesn't trip the rate limiter except in the test that
means to. The suite doesn't clear the database; it only relies on orders it creates itself.
