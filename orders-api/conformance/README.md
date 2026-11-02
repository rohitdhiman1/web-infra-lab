# Capstone conformance suite

Black-box tests for the A11 capstone spec. They speak only HTTP, so they run against any
implementation, in any language:

```bash
BASE_URL=http://localhost:8080 uv run pytest -q
```

Every request is authenticated, as in A10. Tests get tokens from the implementation's `POST /token`
endpoint (a stand-in for an identity provider) as one of four users: `alice` and `bob` (customers),
`sam` (support, may read any order) and `ana` (admin). The warehouse service uses the API key
`wh_test_4f9c2a7d1e8b6035`, a throwaway lab value. Each test uses a fresh token, which is also a
fresh rate-limit bucket, so the suite doesn't trip the limiter except in the test that means to. The suite doesn't clear the database; it only relies on orders it creates itself.
