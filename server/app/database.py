from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any, Iterable

from .config import get_settings
from .time_utils import utc_now_iso


SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT UNIQUE,
    name TEXT NOT NULL,
    phone TEXT UNIQUE,
    password_hash TEXT,
    role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    balance INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS locations (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    display_order INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS slots (
    id INTEGER PRIMARY KEY,
    location_id INTEGER REFERENCES locations(id),
    slot_number INTEGER,
    status TEXT NOT NULL CHECK (status IN ('available', 'occupied', 'disabled')),
    umbrella_present INTEGER NOT NULL DEFAULT 1,
    current_rental_id INTEGER,
    report_reason TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS rentals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    slot_id INTEGER NOT NULL REFERENCES slots(id),
    status TEXT NOT NULL CHECK (
        status IN (
            'pending_pickup',
            'active',
            'pending_return',
            'completed',
            'defect_reported',
            'self_damage_reported'
        )
    ),
    deposit_amount INTEGER NOT NULL,
    deposit_charged_at TEXT,
    deposit_refunded_at TEXT,
    started_at TEXT,
    due_at TEXT,
    returned_at TEXT,
    return_type TEXT NOT NULL DEFAULT 'normal',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS qr_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash TEXT NOT NULL UNIQUE,
    action TEXT NOT NULL CHECK (action IN ('rent', 'return')),
    user_id INTEGER NOT NULL REFERENCES users(id),
    slot_id INTEGER NOT NULL REFERENCES slots(id),
    rental_id INTEGER REFERENCES rentals(id),
    return_type TEXT,
    expires_at TEXT NOT NULL,
    used_at TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TEXT NOT NULL,
    revoked_at TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wallet_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    amount INTEGER NOT NULL,
    kind TEXT NOT NULL,
    rental_id INTEGER REFERENCES rentals(id),
    note TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rental_id INTEGER NOT NULL REFERENCES rentals(id),
    reporter_user_id INTEGER NOT NULL REFERENCES users(id),
    slot_id INTEGER NOT NULL REFERENCES slots(id),
    type TEXT NOT NULL CHECK (type IN ('defect', 'self_damage')),
    previous_rental_id INTEGER REFERENCES rentals(id),
    previous_user_id INTEGER REFERENCES users(id),
    description TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS slot_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slot_id INTEGER NOT NULL REFERENCES slots(id),
    reporter_user_id INTEGER REFERENCES users(id),
    reason TEXT NOT NULL,
    description TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rentals_user_status ON rentals(user_id, status);
CREATE INDEX IF NOT EXISTS idx_rentals_slot_created ON rentals(slot_id, created_at);
CREATE INDEX IF NOT EXISTS idx_qr_tokens_hash ON qr_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_user ON wallet_transactions(user_id, created_at);
"""

DEFAULT_LOCATIONS = (
    "디지털관 1층",
)


def get_db() -> sqlite3.Connection:
    settings = get_settings()
    db_path = settings.database_path
    if not db_path.is_absolute():
        db_path = Path.cwd() / db_path
    db_path.parent.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db() -> None:
    settings = get_settings()
    with get_db() as conn:
        conn.executescript(SCHEMA)
        migrate_db(conn)
        seed_locations_and_slots(conn, settings.slot_count)


def migrate_db(conn: sqlite3.Connection) -> None:
    add_column_if_missing(conn, "users", "email", "TEXT")
    add_column_if_missing(conn, "users", "password_hash", "TEXT")
    add_column_if_missing(
        conn,
        "users",
        "role",
        "TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin'))",
    )
    add_column_if_missing(conn, "slots", "location_id", "INTEGER REFERENCES locations(id)")
    add_column_if_missing(conn, "slots", "slot_number", "INTEGER")
    add_column_if_missing(conn, "slots", "report_reason", "TEXT")
    add_column_if_missing(conn, "rentals", "due_at", "TEXT")
    add_column_if_missing(conn, "rentals", "return_type", "TEXT NOT NULL DEFAULT 'normal'")
    add_column_if_missing(conn, "qr_tokens", "return_type", "TEXT")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL")
    conn.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_slots_location_number ON slots(location_id, slot_number)
        WHERE location_id IS NOT NULL AND slot_number IS NOT NULL
        """
    )


def add_column_if_missing(conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})")}
    if column not in columns:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


def seed_locations_and_slots(conn: sqlite3.Connection, slots_per_location: int) -> None:
    now = utc_now_iso()
    for index, name in enumerate(DEFAULT_LOCATIONS, 1):
        conn.execute(
            """
            INSERT OR IGNORE INTO locations (id, name, display_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (index, name, index, now, now),
        )
        conn.execute(
            "UPDATE locations SET display_order = ?, updated_at = ? WHERE name = ?",
            (index, now, name),
        )

    prune_removed_default_locations(conn)

    for slot_id in range(1, slots_per_location + 1):
        conn.execute(
            """
            UPDATE slots
            SET location_id = COALESCE(location_id, 1),
                slot_number = COALESCE(slot_number, id)
            WHERE id = ? AND (location_id IS NULL OR slot_number IS NULL)
            """,
            (slot_id,),
        )

    for location_id in range(1, len(DEFAULT_LOCATIONS) + 1):
        for slot_number in range(1, slots_per_location + 1):
            slot_id = ((location_id - 1) * slots_per_location) + slot_number
            existing = conn.execute(
                "SELECT id FROM slots WHERE location_id = ? AND slot_number = ?",
                (location_id, slot_number),
            ).fetchone()
            if existing is not None:
                continue
            conn.execute(
                """
                INSERT OR IGNORE INTO slots (
                    id, location_id, slot_number, status, umbrella_present,
                    current_rental_id, report_reason, updated_at
                )
                VALUES (?, ?, ?, 'available', 1, NULL, NULL, ?)
                """,
                (slot_id, location_id, slot_number, now),
            )


def prune_removed_default_locations(conn: sqlite3.Connection) -> None:
    if not DEFAULT_LOCATIONS:
        return

    placeholders = ", ".join("?" for _ in DEFAULT_LOCATIONS)
    removed_locations = conn.execute(
        f"SELECT id FROM locations WHERE name NOT IN ({placeholders})",
        tuple(DEFAULT_LOCATIONS),
    ).fetchall()

    for location in removed_locations:
        location_id = location["id"]
        related_count = conn.execute(
            """
            SELECT
                (SELECT COUNT(*)
                 FROM rentals r
                 JOIN slots s ON s.id = r.slot_id
                 WHERE s.location_id = ?)
              + (SELECT COUNT(*)
                 FROM qr_tokens q
                 JOIN slots s ON s.id = q.slot_id
                 WHERE s.location_id = ?)
              + (SELECT COUNT(*)
                 FROM reports rp
                 JOIN slots s ON s.id = rp.slot_id
                 WHERE s.location_id = ?)
              + (SELECT COUNT(*)
                 FROM slot_reports sr
                 JOIN slots s ON s.id = sr.slot_id
                 WHERE s.location_id = ?) AS count
            """,
            (location_id, location_id, location_id, location_id),
        ).fetchone()["count"]
        if related_count == 0:
            conn.execute("DELETE FROM slots WHERE location_id = ?", (location_id,))
            conn.execute("DELETE FROM locations WHERE id = ?", (location_id,))


def seed_slots(conn: sqlite3.Connection, count: int) -> None:
    now = utc_now_iso()
    for slot_id in range(1, count + 1):
        conn.execute(
            """
            INSERT OR IGNORE INTO slots (
                id, location_id, slot_number, status, umbrella_present,
                current_rental_id, report_reason, updated_at
            )
            VALUES (?, 1, ?, 'available', 1, NULL, NULL, ?)
            """,
            (slot_id, slot_id, now),
        )


def one(conn: sqlite3.Connection, query: str, params: Iterable[Any] = ()) -> sqlite3.Row | None:
    return conn.execute(query, tuple(params)).fetchone()


def many(conn: sqlite3.Connection, query: str, params: Iterable[Any] = ()) -> list[sqlite3.Row]:
    return conn.execute(query, tuple(params)).fetchall()
