import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.database import get_db, init_db
from app.security import hash_password
from app.time_utils import utc_now_iso


TEST_ID = "admin"
TEST_PASSWORD = os.getenv("UMBRELLA_TEST_PASSWORD")
TEST_NAME = "관리자"
TEST_BALANCE = 10000


def main() -> None:
    if not TEST_PASSWORD:
        raise RuntimeError("UMBRELLA_TEST_PASSWORD 환경 변수를 설정하세요.")

    init_db()
    now = utc_now_iso()
    with get_db() as conn:
        existing = conn.execute("SELECT * FROM users WHERE lower(email) = lower(?)", (TEST_ID,)).fetchone()
        if existing is None:
            cursor = conn.execute(
                """
                INSERT INTO users (email, name, phone, password_hash, balance, created_at)
                VALUES (?, ?, NULL, ?, ?, ?)
                """,
                (TEST_ID, TEST_NAME, hash_password(TEST_PASSWORD), TEST_BALANCE, now),
            )
            user_id = cursor.lastrowid
            conn.execute(
                """
                INSERT INTO wallet_transactions (user_id, amount, kind, rental_id, note, created_at)
                VALUES (?, ?, 'test_seed', NULL, 'Test account initial points', ?)
                """,
                (user_id, TEST_BALANCE, now),
            )
            action = "created"
        else:
            user_id = existing["id"]
            conn.execute(
                """
                UPDATE users
                SET name = ?,
                    password_hash = ?,
                    balance = CASE WHEN balance < ? THEN ? ELSE balance END
                WHERE id = ?
                """,
                (TEST_NAME, hash_password(TEST_PASSWORD), TEST_BALANCE, TEST_BALANCE, user_id),
            )
            action = "updated"

        conn.commit()
        user = conn.execute(
            "SELECT id, email, name, balance, created_at FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()

    print({"action": action, "user": dict(user)})


if __name__ == "__main__":
    main()
