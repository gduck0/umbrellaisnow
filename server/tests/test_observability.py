import json
import logging

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.main import app
from app.observability import RequestContextMiddleware, access_logger, unexpected_error_handler


def observability_test_app() -> FastAPI:
    test_app = FastAPI()
    test_app.add_middleware(RequestContextMiddleware)
    test_app.add_exception_handler(Exception, unexpected_error_handler)

    @test_app.get("/ok")
    def ok():
        return {"status": "ok"}

    @test_app.get("/boom")
    def boom():
        raise RuntimeError("internal detail must not reach the client")

    return test_app


def test_request_id_is_returned_and_access_log_is_structured(caplog):
    caplog.set_level(logging.INFO, logger="umbrella.access")
    access_logger.addHandler(caplog.handler)
    try:
        with TestClient(observability_test_app()) as client:
            response = client.get("/ok", headers={"X-Request-ID": "mobile-123"})
    finally:
        access_logger.removeHandler(caplog.handler)

    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == "mobile-123"
    access_event = json.loads(
        next(record.message for record in caplog.records if record.name == "umbrella.access")
    )
    assert access_event["event"] == "request_completed"
    assert access_event["request_id"] == "mobile-123"
    assert access_event["method"] == "GET"
    assert access_event["path"] == "/ok"
    assert access_event["status_code"] == 200
    assert access_event["duration_ms"] >= 0


def test_invalid_request_id_is_replaced():
    with TestClient(observability_test_app()) as client:
        response = client.get("/ok", headers={"X-Request-ID": "bad value"})

    request_id = response.headers["X-Request-ID"]
    assert len(request_id) == 32
    assert request_id.isalnum()


def test_unhandled_error_is_safe_and_traceable():
    with TestClient(observability_test_app(), raise_server_exceptions=False) as client:
        response = client.get("/boom", headers={"X-Request-ID": "failure-123"})

    assert response.status_code == 500
    assert response.headers["X-Request-ID"] == "failure-123"
    assert response.json() == {
        "detail": {
            "code": "INTERNAL_SERVER_ERROR",
            "message": "Unexpected server error",
            "request_id": "failure-123",
        }
    }
    assert "internal detail" not in response.text


def test_readiness_checks_database_and_schema():
    with TestClient(app) as client:
        response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "database": "ok",
        "schema_version": 2,
    }
    assert response.headers["X-Request-ID"]
