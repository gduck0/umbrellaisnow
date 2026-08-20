from fastapi.testclient import TestClient

from app.main import app


def test_admin_can_list_search_and_delete_users():
    with TestClient(app) as client:
        created = client.post(
            "/api/users",
            json={"email": "admin-target@example.ac.kr", "name": "admin target"},
        ).json()

        users = client.get("/api/admin/users")
        assert users.status_code == 200
        assert [user["id"] for user in users.json()] == [created["id"]]

        search = client.get("/api/admin/users/search", params={"email": "admin-target"})
        assert search.status_code == 200
        assert [user["email"] for user in search.json()] == [created["email"]]

        deleted = client.delete(f"/api/admin/users/{created['id']}")
        assert deleted.status_code == 204
        assert client.get(f"/api/users/{created['id']}").status_code == 404
