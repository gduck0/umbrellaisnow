import sqlite3

import pytest

from app.database import MIGRATIONS, SCHEMA, migrate_db
from app.repository import record_audit_event


def migrated_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    migrate_db(conn)
    return conn


def test_migrations_are_versioned_and_idempotent():
    with migrated_connection() as conn:
        expected = [(version, name) for version, name, _ in MIGRATIONS]
        applied = [
            (row["version"], row["name"])
            for row in conn.execute(
                "SELECT version, name FROM schema_migrations ORDER BY version"
            )
        ]
        assert applied == expected

        migrate_db(conn)
        count = conn.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0]
        assert count == len(expected)


def test_newer_database_version_is_rejected():
    with migrated_connection() as conn:
        conn.execute(
            "INSERT INTO schema_migrations (version, name, applied_at) VALUES (999, 'future', '2026-08-30T00:00:00+00:00')"
        )

        with pytest.raises(RuntimeError, match="newer than this server"):
            migrate_db(conn)


def test_audit_events_are_append_only():
    with migrated_connection() as conn:
        event = record_audit_event(
            conn,
            action="slot.disabled",
            actor_type="admin",
            actor_id=1,
            resource_type="slot",
            resource_id=2,
            details={"reason": "maintenance"},
        )
        assert event["actor_id"] == "1"
        assert event["details"] == {"reason": "maintenance"}

        with pytest.raises(sqlite3.IntegrityError, match="immutable"):
            conn.execute("UPDATE audit_events SET action = 'changed' WHERE id = ?", (event["id"],))
        with pytest.raises(sqlite3.IntegrityError, match="immutable"):
            conn.execute("DELETE FROM audit_events WHERE id = ?", (event["id"],))
