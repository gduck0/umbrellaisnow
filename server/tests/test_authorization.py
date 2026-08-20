import sqlite3

from fastapi.testclient import TestClient

from app.config import get_settings
from app.database import init_db
from app.main import app


def register(client, email):
    auth = client.post(
        "/api/auth/register",
        json={
            "email": email,
            "name": email.split("@", 1)[0],
            "password": "1111",
            "password_confirm": "1111",
        },
    ).json()
    return auth["user"], {"Authorization": f"Bearer {auth['access_token']}"}


def test_user_resources_are_limited_to_the_owner():
    with TestClient(app) as client:
        user_a, headers_a = register(client, "owner-a@example.ac.kr")
        user_b, headers_b = register(client, "owner-b@example.ac.kr")

        assert client.get("/api").json()["local_only"] is False
        assert client.get(f"/api/users/{user_a['id']}/wallet").status_code == 401
        assert client.get(f"/api/users/{user_a['id']}/wallet", headers=headers_a).status_code == 200
        assert client.get(f"/api/users/{user_b['id']}/wallet", headers=headers_a).status_code == 403
        assert client.get("/api/rentals", params={"user_id": user_b["id"]}, headers=headers_a).status_code == 403
        assert client.get("/api/rentals", headers=headers_b).json() == []
        assert client.post(
            f"/api/users/{user_a['id']}/wallet/recharge",
            json={"amount": 0},
            headers=headers_a,
        ).status_code == 422


def test_existing_users_receive_the_default_role():
    database_path = get_settings().database_path
    with sqlite3.connect(database_path) as conn:
        conn.execute(
            """
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE,
                name TEXT NOT NULL,
                phone TEXT UNIQUE,
                password_hash TEXT,
                balance INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            "INSERT INTO users (email, name, balance, created_at) VALUES (?, ?, 0, ?)",
            ("legacy@example.ac.kr", "legacy", "2026-08-20T00:00:00+00:00"),
        )

    init_db()

    with sqlite3.connect(database_path) as conn:
        role = conn.execute("SELECT role FROM users WHERE email = ?", ("legacy@example.ac.kr",)).fetchone()[0]
    assert role == "user"


def test_hardware_key_can_replace_user_simulation(monkeypatch):
    monkeypatch.setenv("UMBRELLA_ALLOW_USER_HARDWARE_SIMULATION", "false")
    monkeypatch.setenv("UMBRELLA_HARDWARE_API_KEY", "test-hardware-key")
    get_settings.cache_clear()

    with TestClient(app) as client:
        user, headers = register(client, "hardware-owner@example.ac.kr")
        client.post(
            f"/api/users/{user['id']}/wallet/recharge",
            json={"amount": 5000},
            headers=headers,
        )
        qr = client.post("/api/qr/rent", json={"slot_id": 1}, headers=headers).json()

        assert client.post("/api/hardware/qr/scan", json={"token": qr["token"]}).status_code == 401
        assert client.post(
            "/api/hardware/qr/scan",
            json={"token": qr["token"]},
            headers=headers,
        ).status_code == 401

        scanned = client.post(
            "/api/hardware/qr/scan",
            json={"token": qr["token"]},
            headers={"X-Hardware-Key": "test-hardware-key"},
        )
        assert scanned.status_code == 200
        assert scanned.json()["unlock"] is True
