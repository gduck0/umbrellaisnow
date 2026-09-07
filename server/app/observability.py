from __future__ import annotations

import json
import logging
import re
import time
import uuid

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response


REQUEST_ID_HEADER = "X-Request-ID"
_REQUEST_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")


def json_logger(name: str, level: int) -> logging.Logger:
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)
    logger.setLevel(level)
    logger.propagate = False
    return logger


access_logger = json_logger("umbrella.access", logging.INFO)
error_logger = json_logger("umbrella.error", logging.ERROR)


def request_id_for(request: Request) -> str:
    supplied = request.headers.get(REQUEST_ID_HEADER)
    if supplied is not None and _REQUEST_ID_PATTERN.fullmatch(supplied):
        return supplied
    return uuid.uuid4().hex


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        request_id = request_id_for(request)
        request.state.request_id = request_id
        started_at = time.perf_counter()
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
            response.headers[REQUEST_ID_HEADER] = request_id
            return response
        finally:
            access_logger.info(
                json.dumps(
                    {
                        "event": "request_completed",
                        "request_id": request_id,
                        "method": request.method,
                        "path": request.url.path,
                        "status_code": status_code,
                        "duration_ms": round((time.perf_counter() - started_at) * 1000, 2),
                    },
                    separators=(",", ":"),
                )
            )


async def unexpected_error_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", uuid.uuid4().hex)
    error_logger.error(
        json.dumps(
            {
                "event": "unhandled_exception",
                "request_id": request_id,
                "method": request.method,
                "path": request.url.path,
                "exception_type": type(exc).__name__,
            },
            separators=(",", ":"),
        ),
        exc_info=(type(exc), exc, exc.__traceback__),
    )
    return JSONResponse(
        status_code=500,
        content={
            "detail": {
                "code": "INTERNAL_SERVER_ERROR",
                "message": "Unexpected server error",
                "request_id": request_id,
            }
        },
        headers={REQUEST_ID_HEADER: request_id},
    )
