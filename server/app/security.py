from __future__ import annotations

import hashlib
import hmac
from secrets import token_hex, token_urlsafe

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from .config import get_settings


def create_token() -> str:
    return token_urlsafe(32)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def secrets_match(candidate: str | None, expected: str | None) -> bool:
    if not candidate or not expected:
        return False
    return hmac.compare_digest(candidate.encode("utf-8"), expected.encode("utf-8"))


def hash_password(password: str) -> str:
    iterations = 210_000
    salt = token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), iterations)
    return f"pbkdf2_sha256${iterations}${salt}${digest.hex()}"


def verify_password(password: str, encoded: str | None) -> bool:
    if not encoded:
        return False
    try:
        algorithm, iterations_text, salt, expected = encoded.split("$", 3)
    except ValueError:
        return False
    if algorithm != "pbkdf2_sha256":
        return False
    iterations = int(iterations_text)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), iterations)
    return hmac.compare_digest(digest.hex(), expected)


class LocalOnlyMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        client_host = request.client.host if request.client else ""
        allowed_hosts = set(settings.allowed_hosts)
        if settings.allow_test_client:
            allowed_hosts.add("testclient")

        if client_host not in allowed_hosts:
            return JSONResponse(
                status_code=403,
                content={"detail": "Local access only. Bind the API to 127.0.0.1 or call from localhost."},
            )

        return await call_next(request)
