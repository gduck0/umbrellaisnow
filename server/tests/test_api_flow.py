import os
from pathlib import Path

os.environ["UMBRELLA_DATABASE_PATH"] = str(Path(__file__).parent / "test.db")

from fastapi.testclient import TestClient

from app.main import app


def register_user(client, email, name="tester", amount=0):
    auth = client.post(
        "/api/auth/register",
        json={
            "email": email,
            "name": name,
            "password": "1111",
            "password_confirm": "1111",
        },
    ).json()
    headers = {"Authorization": f"Bearer {auth['access_token']}"}
    if amount:
        client.post(
            f"/api/users/{auth['user']['id']}/wallet/recharge",
            json={"amount": amount},
            headers=headers,
        )
    return auth["user"], headers


def test_full_rent_and_return_flow(tmp_path):
    db_path = Path(os.environ["UMBRELLA_DATABASE_PATH"])
    if db_path.exists():
        db_path.unlink()

    with TestClient(app) as client:
        user, headers = register_user(client, "tester1@example.ac.kr", amount=5000)

        qr = client.post("/api/qr/rent", json={"slot_id": 1}, headers=headers).json()
        scan = client.post("/api/hardware/qr/scan", json={"token": qr["token"]}, headers=headers).json()
        assert scan["unlock"] is True
        assert scan["action"] == "rent"

        pickup = client.post("/api/hardware/slots/1/sensor", json={"present": False}, headers=headers).json()
        assert pickup["event"] == "pickup_completed"
        assert pickup["rental"]["status"] == "active"

        return_qr = client.post("/api/qr/return", json={}, headers=headers).json()
        client.post("/api/hardware/qr/scan", json={"token": return_qr["token"]}, headers=headers)
        returned = client.post("/api/hardware/slots/1/sensor", json={"present": True}, headers=headers).json()
        assert returned["event"] == "return_completed"
        assert returned["slot"]["status"] == "available"

        wallet = client.get(f"/api/users/{user['id']}/wallet", headers=headers).json()
        assert wallet["balance"] == 5000


def test_defect_report_penalizes_previous_user(tmp_path):
    db_path = Path(os.environ["UMBRELLA_DATABASE_PATH"])
    if db_path.exists():
        db_path.unlink()

    with TestClient(app) as client:
        user_a, headers_a = register_user(client, "a@example.ac.kr", name="A", amount=5000)
        user_b, headers_b = register_user(client, "b@example.ac.kr", name="B", amount=5000)

        qr_a = client.post("/api/qr/rent", json={"slot_id": 1}, headers=headers_a).json()
        client.post("/api/hardware/qr/scan", json={"token": qr_a["token"]}, headers=headers_a)
        client.post("/api/hardware/slots/1/sensor", json={"present": False}, headers=headers_a)
        return_qr_a = client.post("/api/qr/return", json={}, headers=headers_a).json()
        client.post("/api/hardware/qr/scan", json={"token": return_qr_a["token"]}, headers=headers_a)
        client.post("/api/hardware/slots/1/sensor", json={"present": True}, headers=headers_a)

        qr_b = client.post("/api/qr/rent", json={"slot_id": 1}, headers=headers_b).json()
        scan_b = client.post("/api/hardware/qr/scan", json={"token": qr_b["token"]}, headers=headers_b).json()
        client.post("/api/hardware/slots/1/sensor", json={"present": False}, headers=headers_b)

        report = client.post(
            "/api/reports/defect",
            json={"rental_id": scan_b["rental_id"], "description": "broken"},
            headers=headers_b,
        ).json()
        assert report["slot"]["status"] == "disabled"
        assert report["previous_rental"]["user_id"] == user_a["id"]

        wallet_a = client.get(f"/api/users/{user_a['id']}/wallet", headers=headers_a).json()
        wallet_b = client.get(f"/api/users/{user_b['id']}/wallet", headers=headers_b).json()
        assert wallet_a["balance"] == 2000
        assert wallet_b["balance"] == 5000


def test_screen_based_auth_locations_and_payments_disabled(tmp_path):
    db_path = Path(os.environ["UMBRELLA_DATABASE_PATH"])
    if db_path.exists():
        db_path.unlink()

    with TestClient(app) as client:
        registered = client.post(
            "/api/auth/register",
            json={
                "email": "student@example.ac.kr",
                "name": "길덕영",
                "password": "1111",
                "password_confirm": "1111",
            },
        ).json()
        assert registered["user"]["name"] == "길덕영"
        assert registered["access_token"]
        headers = {"Authorization": f"Bearer {registered['access_token']}"}

        assert client.get("/api/app/home").status_code == 401
        assert client.post("/api/qr/rent", json={"slot_id": 1}).status_code == 401

        me = client.get("/api/me", headers=headers).json()
        assert me["email"] == "student@example.ac.kr"

        logged_in = client.post(
            "/api/auth/login",
            json={"email": "student@example.ac.kr", "password": "1111", "remember_me": True},
        ).json()
        assert logged_in["user"]["id"] == registered["user"]["id"]

        locations = client.get("/api/locations").json()
        assert [location["name"] for location in locations] == ["디지털관 1층"]
        assert locations[0]["total_slots"] == 4

        slots = client.get(f"/api/locations/{locations[0]['id']}/slots").json()
        assert slots["slots"][0]["slot_number"] == 1

        status = client.get("/api/payments/status").json()
        assert status["enabled"] is False
        assert {method["id"] for method in status["methods"]} == {"kakao_pay", "toss_pay"}

        payment = client.post(
            "/api/payments/charge",
            json={"amount": 10000, "method": "toss_pay"},
            headers=headers,
        )
        assert payment.status_code == 503
        assert payment.json()["detail"]["code"] == "PAYMENTS_DISABLED"


def test_slot_report_and_damage_return_flow(tmp_path):
    db_path = Path(os.environ["UMBRELLA_DATABASE_PATH"])
    if db_path.exists():
        db_path.unlink()

    with TestClient(app) as client:
        user, headers = register_user(client, "tester2@example.ac.kr", amount=10000)

        slots = client.get("/api/locations/1/slots").json()["slots"]
        report = client.post(
            f"/api/slots/{slots[2]['id']}/reports",
            json={"reason": "umbrella_damage", "description": "handle broken"},
            headers=headers,
        ).json()
        assert report["slot"]["status"] == "disabled"
        assert report["slot"]["report_reason"] == "umbrella_damage"

        qr = client.post("/api/qr/rent", json={"slot_id": slots[0]["id"]}, headers=headers).json()
        scan = client.post("/api/hardware/qr/scan", json={"token": qr["token"]}, headers=headers).json()
        client.post(
            f"/api/hardware/slots/{slots[0]['id']}/sensor",
            json={"present": False},
            headers=headers,
        )

        home = client.get("/api/app/home", headers=headers).json()
        assert home["point_balance"] == 7000
        assert home["locked_deposit"] == 3000
        assert home["current_rental"]["location_name"] == "디지털관 1층"

        return_qr = client.post(
            "/api/qr/return",
            json={"rental_id": scan["rental_id"], "return_type": "damage_report"},
            headers=headers,
        ).json()
        assert return_qr["return_type"] == "damage_report"
        client.post("/api/hardware/qr/scan", json={"token": return_qr["token"]}, headers=headers)
        returned = client.post(
            f"/api/hardware/slots/{slots[0]['id']}/sensor",
            json={"present": True},
            headers=headers,
        ).json()
        assert returned["event"] == "damage_return_completed"
        assert returned["slot"]["status"] == "disabled"

        home_after = client.get("/api/app/home", headers=headers).json()
        assert home_after["point_balance"] == 7000
        assert home_after["locked_deposit"] == 0
        assert home_after["current_rental"] is None
