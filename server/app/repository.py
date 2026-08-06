from __future__ import annotations

import sqlite3
from typing import Any

from fastapi import HTTPException

from .database import many, one
from .time_utils import utc_now_iso


def row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    if row is None:
        return None
    result = dict(row)
    if "umbrella_present" in result:
        result["umbrella_present"] = bool(result["umbrella_present"])
    return result


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [row_to_dict(row) for row in rows if row is not None]


def require_user(conn: sqlite3.Connection, user_id: int) -> sqlite3.Row:
    user = one(conn, "SELECT * FROM users WHERE id = ?", (user_id,))
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    return user


def require_slot(conn: sqlite3.Connection, slot_id: int) -> sqlite3.Row:
    slot = one(conn, "SELECT * FROM slots WHERE id = ?", (slot_id,))
    if slot is None:
        raise HTTPException(status_code=404, detail="Slot not found")
    return slot


def require_location(conn: sqlite3.Connection, location_id: int) -> sqlite3.Row:
    location = one(conn, "SELECT * FROM locations WHERE id = ?", (location_id,))
    if location is None:
        raise HTTPException(status_code=404, detail="Location not found")
    return location


def require_rental(conn: sqlite3.Connection, rental_id: int) -> sqlite3.Row:
    rental = one(conn, "SELECT * FROM rentals WHERE id = ?", (rental_id,))
    if rental is None:
        raise HTTPException(status_code=404, detail="Rental not found")
    return rental


def slot_with_location(conn: sqlite3.Connection, slot_id: int) -> dict[str, Any]:
    row = one(
        conn,
        """
        SELECT
            s.id,
            s.location_id,
            s.slot_number,
            s.status,
            s.umbrella_present,
            s.current_rental_id,
            s.report_reason,
            s.updated_at,
            l.name AS location_name
        FROM slots s
        LEFT JOIN locations l ON l.id = s.location_id
        WHERE s.id = ?
        """,
        (slot_id,),
    )
    if row is None:
        raise HTTPException(status_code=404, detail="Slot not found")
    return row_to_dict(row)


def all_slots_with_location(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    return rows_to_dicts(
        many(
            conn,
            """
            SELECT
                s.id,
                s.location_id,
                s.slot_number,
                s.status,
                s.umbrella_present,
                s.current_rental_id,
                s.report_reason,
                s.updated_at,
                l.name AS location_name
            FROM slots s
            LEFT JOIN locations l ON l.id = s.location_id
            ORDER BY l.display_order, s.slot_number
            """,
        )
    )


def slots_for_location(conn: sqlite3.Connection, location_id: int) -> list[dict[str, Any]]:
    require_location(conn, location_id)
    return rows_to_dicts(
        many(
            conn,
            """
            SELECT
                s.id,
                s.location_id,
                s.slot_number,
                s.status,
                s.umbrella_present,
                s.current_rental_id,
                s.report_reason,
                s.updated_at,
                l.name AS location_name
            FROM slots s
            JOIN locations l ON l.id = s.location_id
            WHERE s.location_id = ?
            ORDER BY s.slot_number
            """,
            (location_id,),
        )
    )


def location_summaries(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = many(
        conn,
        """
        SELECT
            l.id,
            l.name,
            l.display_order,
            COUNT(s.id) AS total_slots,
            SUM(CASE WHEN s.status = 'available' AND s.umbrella_present = 1 THEN 1 ELSE 0 END) AS available_count,
            SUM(CASE WHEN s.status = 'disabled' THEN 1 ELSE 0 END) AS disabled_count,
            l.updated_at
        FROM locations l
        LEFT JOIN slots s ON s.location_id = l.id
        GROUP BY l.id
        ORDER BY l.display_order
        """,
    )
    result = []
    for row in rows:
        item = row_to_dict(row)
        item["total_slots"] = item["total_slots"] or 0
        item["available_count"] = item["available_count"] or 0
        item["disabled_count"] = item["disabled_count"] or 0
        item["rentable"] = item["available_count"] > 0
        result.append(item)
    return result


def add_wallet_transaction(
    conn: sqlite3.Connection,
    *,
    user_id: int,
    amount: int,
    kind: str,
    rental_id: int | None = None,
    note: str | None = None,
) -> sqlite3.Row:
    now = utc_now_iso()
    conn.execute("UPDATE users SET balance = balance + ? WHERE id = ?", (amount, user_id))
    cursor = conn.execute(
        """
        INSERT INTO wallet_transactions (user_id, amount, kind, rental_id, note, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (user_id, amount, kind, rental_id, note, now),
    )
    return one(conn, "SELECT * FROM wallet_transactions WHERE id = ?", (cursor.lastrowid,))


def active_rental_for_user(conn: sqlite3.Connection, user_id: int) -> sqlite3.Row | None:
    return one(
        conn,
        """
        SELECT *
        FROM rentals
        WHERE user_id = ?
          AND status IN ('pending_pickup', 'active', 'pending_return')
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (user_id,),
    )


def locked_deposit_for_user(conn: sqlite3.Connection, user_id: int) -> int:
    row = one(
        conn,
        """
        SELECT COALESCE(SUM(deposit_amount), 0) AS amount
        FROM rentals
        WHERE user_id = ?
          AND deposit_charged_at IS NOT NULL
          AND deposit_refunded_at IS NULL
          AND status IN ('pending_pickup', 'active', 'pending_return')
        """,
        (user_id,),
    )
    return int(row["amount"]) if row is not None else 0


def rental_detail(conn: sqlite3.Connection, rental_id: int) -> dict[str, Any]:
    row = one(
        conn,
        """
        SELECT
            r.*,
            s.location_id,
            s.slot_number,
            l.name AS location_name
        FROM rentals r
        JOIN slots s ON s.id = r.slot_id
        LEFT JOIN locations l ON l.id = s.location_id
        WHERE r.id = ?
        """,
        (rental_id,),
    )
    if row is None:
        raise HTTPException(status_code=404, detail="Rental not found")
    return row_to_dict(row)


def active_rental_detail_for_user(conn: sqlite3.Connection, user_id: int) -> dict[str, Any] | None:
    rental = active_rental_for_user(conn, user_id)
    if rental is None:
        return None
    return rental_detail(conn, rental["id"])


def latest_rental_for_slot(conn: sqlite3.Connection, slot_id: int) -> sqlite3.Row | None:
    return one(
        conn,
        """
        SELECT *
        FROM rentals
        WHERE slot_id = ?
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (slot_id,),
    )


def previous_completed_rental_for_slot(
    conn: sqlite3.Connection,
    *,
    slot_id: int,
    before_rental_id: int,
) -> sqlite3.Row | None:
    require_rental(conn, before_rental_id)
    return one(
        conn,
        """
        SELECT *
        FROM rentals
        WHERE slot_id = ?
          AND id < ?
          AND status IN ('completed', 'defect_reported', 'self_damage_reported')
        ORDER BY returned_at DESC, created_at DESC
        LIMIT 1
        """,
        (slot_id, before_rental_id),
    )


def list_rentals(conn: sqlite3.Connection, user_id: int | None = None) -> list[dict[str, Any]]:
    if user_id is None:
        return rows_to_dicts(many(conn, "SELECT * FROM rentals ORDER BY created_at DESC"))
    return rows_to_dicts(
        many(conn, "SELECT * FROM rentals WHERE user_id = ? ORDER BY created_at DESC", (user_id,))
    )
