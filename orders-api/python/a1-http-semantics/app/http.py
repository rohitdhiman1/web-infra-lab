"""Small HTTP helpers FastAPI doesn't give you out of the box: 406, 415, and one error shape."""

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

JSON = "application/json"
MERGE_PATCH = "application/merge-patch+json"


class ApiError(Exception):
    def __init__(self, status: int, message: str, fields: dict[str, str] | None = None,
                 headers: dict[str, str] | None = None):
        self.status, self.message, self.fields, self.headers = status, message, fields or {}, headers or {}


def error_body(message: str, fields: dict[str, str] | None = None) -> dict:
    body: dict = {"error": message}
    if fields:
        body["fields"] = fields
    return body


async def api_error_handler(_: Request, e: ApiError) -> JSONResponse:
    return JSONResponse(error_body(e.message, e.fields), status_code=e.status, headers=e.headers)


async def validation_handler(_: Request, e: RequestValidationError) -> JSONResponse:
    """400 if the body isn't JSON (or is missing); 422 if it's JSON of the wrong shape."""
    errors = e.errors()
    if any(err["type"] == "json_invalid" or (err["type"] == "missing" and tuple(err["loc"]) == ("body",))
           for err in errors):
        return JSONResponse(error_body("request body is not valid JSON"), status_code=400)
    fields = {".".join(str(x) for x in err["loc"][1:]) or "body": err["msg"] for err in errors}
    return JSONResponse(error_body("request body has the wrong shape", fields), status_code=422)


def media_type(value: str | None) -> str:
    return (value or "").split(";")[0].strip().lower()


def accepts_json(accept: str | None) -> bool:
    """True if the Accept header allows application/json (a missing header allows anything)."""
    if not accept:
        return True
    for part in accept.split(","):
        kind, *params = [x.strip() for x in part.split(";")]
        q = next((p.split("=", 1)[1] for p in params if p.replace(" ", "").startswith("q=")), "1")
        try:
            if float(q) == 0:
                continue
        except ValueError:
            continue
        if kind.lower() in ("*/*", "application/*", JSON):
            return True
    return False


def require_accept_json(request: Request) -> None:
    if not accepts_json(request.headers.get("accept")):
        raise ApiError(406, "this API only produces application/json", headers={"Accept": JSON})


def require_content_type(request: Request, wanted: str, header: str = "Accept") -> None:
    if media_type(request.headers.get("content-type")) != wanted:
        raise ApiError(415, f"send Content-Type: {wanted}", headers={header: wanted})
