from fastapi.testclient import TestClient

from app.database import get_db
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


def test_admin_can_list_search_and_delete_users():
    with TestClient(app) as client:
        admin, admin_headers = register(client, "admin@example.ac.kr")
        with get_db() as conn:
            conn.execute("UPDATE users SET role = 'admin' WHERE id = ?", (admin["id"],))

        created, user_headers = register(client, "admin-target@example.ac.kr")

        assert client.get("/api/admin/users").status_code == 401
        assert client.get("/api/admin/users", headers=user_headers).status_code == 403
        assert client.post("/api/maintenance/slots/1/disable", headers=user_headers).status_code == 403

        disabled = client.post("/api/maintenance/slots/1/disable", headers=admin_headers)
        assert disabled.status_code == 200
        assert disabled.json()["status"] == "disabled"
        assert client.post(
            "/api/maintenance/slots/1/enable",
            json={"umbrella_present": True},
            headers=admin_headers,
        ).status_code == 200

        users = client.get("/api/admin/users", headers=admin_headers)
        assert users.status_code == 200
        assert {user["id"] for user in users.json()} == {admin["id"], created["id"]}
        assert {user["role"] for user in users.json()} == {"admin", "user"}

        search = client.get(
            "/api/admin/users/search",
            params={"email": "admin-target"},
            headers=admin_headers,
        )
        assert search.status_code == 200
        assert [user["email"] for user in search.json()] == [created["email"]]

        deleted = client.delete(f"/api/admin/users/{created['id']}", headers=admin_headers)
        assert deleted.status_code == 204
        assert client.get(f"/api/users/{created['id']}", headers=admin_headers).status_code == 404
