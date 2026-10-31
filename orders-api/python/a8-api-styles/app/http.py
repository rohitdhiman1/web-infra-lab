"""Small HTTP helpers FastAPI doesn't give you out of the box: 406, 415, and RFC 9457 problem details."""

from http import HTTPStatus

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

JSON = "application/json"
MERGE_PATCH = "application/merge-patch+json"


PROBLEM = "application/problem+json"
TYPES = "https://api.example.com/problems/"
TITLES = {
    "validation-error": "Your request is not valid",
    "malformed-body": "Request body is not valid JSON",
    "refund-not-found": "Refund not found",
}


class ApiError(Exception):
    """type_ is the last part of the problem type URI, e.g. "order-not-found".
    None means about:blank: a plain HTTP error with no extra meaning."""

    def __init__(self, status: int, type_: str | None, message: str, fields: dict[str, str] | None = None,
                 headers: dict[str, str] | None = None):
        self.status, self.type, self.message = status, type_, message
        self.fields, self.headers = fields or {}, headers or {}


def problem(request: Request, status: int, type_: str | None, detail: str,
            fields: dict[str, str] | None = None, headers: dict[str, str] | None = None) -> JSONResponse:
    """An RFC 9457 problem details response."""
    body: dict = {
        "type": TYPES + type_ if type_ else "about:blank",
        "title": TITLES.get(type_ or "", HTTPStatus(status).phrase),
        "status": status,
        "detail": detail,
        "instance": request.url.path,
    }
    if fields:  # extension member: one entry per invalid field
        body["errors"] = [{"pointer": k, "detail": v} for k, v in fields.items()]
    return JSONResponse(body, status_code=status, headers=headers, media_type=PROBLEM)


async def api_error_handler(request: Request, e: ApiError) -> JSONResponse:
    return problem(request, e.status, e.type, e.message, e.fields, e.headers)


def pointer(loc: tuple) -> str:
    return "#/" + "/".join(str(x) for x in loc[1:])


async def validation_handler(request: Request, e: RequestValidationError) -> JSONResponse:
    """400 if the body isn't JSON (or is missing); 422 if it's JSON of the wrong shape."""
    errors = e.errors()
    if any(err["type"] == "json_invalid" or (err["type"] == "missing" and tuple(err["loc"]) == ("body",))
           for err in errors):
        return problem(request, 400, "malformed-body", "request body is not valid JSON")
    fields = {pointer(tuple(err["loc"])): err["msg"] for err in errors}
    return problem(request, 422, "validation-error", "request body has the wrong shape", fields)


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
        raise ApiError(406, None, "this API only produces application/json", headers={"Accept": JSON})


def require_content_type(request: Request, wanted: str, header: str = "Accept") -> None:
    if media_type(request.headers.get("content-type")) != wanted:
        raise ApiError(415, None, f"send Content-Type: {wanted}", headers={header: wanted})
