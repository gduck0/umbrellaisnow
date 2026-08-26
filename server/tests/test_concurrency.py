from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from threading import Barrier

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.database import get_db
from app.main import app
from app.services import handle_sensor_event, scan_qr


def register_user(client, email, amount):
    auth = client.post(
        "/api/auth/register",
        json={
            "email": email,
            "name": "concurrency tester",
            "password": "1111",
            "password_confirm": "1111",
        },
    ).json()
    headers = {"Authorization": f"Bearer {auth['access_token']}"}
    client.post(
        f"/api/users/{auth['user']['id']}/wallet/recharge",
        json={"amount": amount},
        headers=headers,
    )
    return auth["user"], headers


def run_concurrently(operation):
    barrier = Barrier(2)

    def worker():
        with closing(get_db()) as conn:
            barrier.wait(timeout=5)
            try:
                with conn:
                    return {"status": 200, "body": operation(conn)}
            except HTTPException as exc:
                return {"status": exc.status_code, "detail": exc.detail}

    with ThreadPoolExecutor(max_workers=2) as executor:
        return list(executor.map(lambda _: worker(), range(2)))


def test_qr_and_sensor_events_apply_side_effects_once():
    with TestClient(app) as client:
        user, headers = register_user(client, "concurrent@example.ac.kr", amount=5000)

        superseded = client.post("/api/qr/rent", json={"slot_id": 1}, headers=headers).json()
        current = client.post("/api/qr/rent", json={"slot_id": 1}, headers=headers).json()
        old_scan = client.post(
            "/api/hardware/qr/scan",
            json={"token": superseded["token"]},
            headers=headers,
        )
        assert old_scan.status_code == 409
        assert old_scan.json()["detail"] == "QR token superseded"

        scans = run_concurrently(lambda conn: scan_qr(conn, token=current["token"]))
        assert sorted(result["status"] for result in scans) == [200, 409]
        assert sum(result.get("body", {}).get("action") == "rent" for result in scans) == 1

        pickups = run_concurrently(lambda conn: handle_sensor_event(conn, slot_id=1, present=False))
        assert sorted(result["body"]["event"] for result in pickups) == ["pickup_completed", "sensor_updated"]

        superseded_return = client.post("/api/qr/return", json={}, headers=headers).json()
        return_qr = client.post("/api/qr/return", json={}, headers=headers).json()
        old_return_scan = client.post(
            "/api/hardware/qr/scan",
            json={"token": superseded_return["token"]},
            headers=headers,
        )
        assert old_return_scan.status_code == 409
        assert old_return_scan.json()["detail"] == "QR token superseded"

        scanned_return = client.post(
            "/api/hardware/qr/scan",
            json={"token": return_qr["token"]},
            headers=headers,
        )
        assert scanned_return.status_code == 200

        returns = run_concurrently(lambda conn: handle_sensor_event(conn, slot_id=1, present=True))
        assert sorted(result["body"]["event"] for result in returns) == ["return_completed", "sensor_updated"]

        with closing(get_db()) as conn:
            rental = conn.execute("SELECT * FROM rentals WHERE user_id = ?", (user["id"],)).fetchone()
            wallet = conn.execute("SELECT balance FROM users WHERE id = ?", (user["id"],)).fetchone()
            transactions = conn.execute(
                """
                SELECT kind, COUNT(*) AS count
                FROM wallet_transactions
                WHERE user_id = ? AND kind IN ('deposit_charge', 'deposit_refund')
                GROUP BY kind
                """,
                (user["id"],),
            ).fetchall()

        assert rental["status"] == "completed"
        assert wallet["balance"] == 5000
        assert {row["kind"]: row["count"] for row in transactions} == {
            "deposit_charge": 1,
            "deposit_refund": 1,
        }
