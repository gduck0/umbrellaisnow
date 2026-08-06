from __future__ import annotations

import sqlite3

from fastapi import HTTPException

from .config import get_settings
from .database import many, one
from .repository import (
    active_rental_for_user,
    active_rental_detail_for_user,
    add_wallet_transaction,
    all_slots_with_location,
    location_summaries,
    locked_deposit_for_user,
    previous_completed_rental_for_slot,
    rental_detail,
    require_location,
    require_rental,
    require_slot,
    require_user,
    row_to_dict,
    rows_to_dicts,
    slot_with_location,
    slots_for_location,
)
from .security import create_token, hash_password, hash_token, verify_password
from .time_utils import add_hours, add_seconds, parse_iso, utc_now, utc_now_iso


def normalize_email(email: str) -> str:
    return email.strip().lower()


def register_user(
    conn: sqlite3.Connection,
    *,
    email: str,
    name: str,
    password: str,
    password_confirm: str,
) -> dict:
    if password != password_confirm:
        raise HTTPException(status_code=400, detail="Password confirmation does not match")

    email = normalize_email(email)
    try:
        cursor = conn.execute(
            """
            INSERT INTO users (email, name, phone, password_hash, balance, created_at)
            VALUES (?, ?, NULL, ?, 0, ?)
            """,
            (email, name, hash_password(password), utc_now_iso()),
        )
    except sqlite3.IntegrityError as exc:
        raise HTTPException(status_code=409, detail="Email already exists") from exc

    return create_session(conn, user_id=cursor.lastrowid, remember_me=False)


def login_user(conn: sqlite3.Connection, *, email: str, password: str, remember_me: bool) -> dict:
    user = one(conn, "SELECT * FROM users WHERE lower(email) = lower(?)", (normalize_email(email),))
    if user is None or not verify_password(password, user["password_hash"]):
        raise HTTPException(status_code=401, detail="Invalid email or password")
    return create_session(conn, user_id=user["id"], remember_me=remember_me)


def authenticate_session(conn: sqlite3.Connection, *, token: str) -> sqlite3.Row:
    session = one(
        conn,
        """
        SELECT *
        FROM user_sessions
        WHERE token_hash = ?
          AND revoked_at IS NULL
        """,
        (hash_token(token),),
    )
    if session is None:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    if parse_iso(session["expires_at"]) < utc_now():
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    return require_user(conn, session["user_id"])


def create_session(conn: sqlite3.Connection, *, user_id: int, remember_me: bool) -> dict:
    settings = get_settings()
    user = require_user(conn, user_id)
    token = create_token()
    ttl_hours = settings.remember_session_ttl_hours if remember_me else settings.session_ttl_hours
    expires_at = add_hours(ttl_hours)
    conn.execute(
        """
        INSERT INTO user_sessions (user_id, token_hash, expires_at, revoked_at, created_at)
        VALUES (?, ?, ?, NULL, ?)
        """,
        (user_id, hash_token(token), expires_at, utc_now_iso()),
    )
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_at": expires_at,
        "user": row_to_dict(user),
    }


def logout_user(conn: sqlite3.Connection, *, token: str) -> dict:
    conn.execute(
        "UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
        (utc_now_iso(), hash_token(token)),
    )
    return {"ok": True}


def create_user(conn: sqlite3.Connection, *, name: str, phone: str | None, email: str | None = None) -> dict:
    email = normalize_email(email) if email else None
    try:
        cursor = conn.execute(
            "INSERT INTO users (email, name, phone, balance, created_at) VALUES (?, ?, ?, 0, ?)",
            (email, name, phone, utc_now_iso()),
        )
    except sqlite3.IntegrityError as exc:
        raise HTTPException(status_code=409, detail="User already exists") from exc
    return row_to_dict(one(conn, "SELECT * FROM users WHERE id = ?", (cursor.lastrowid,)))


def recharge_wallet(conn: sqlite3.Connection, *, user_id: int, amount: int, note: str | None) -> dict:
    require_user(conn, user_id)
    add_wallet_transaction(conn, user_id=user_id, amount=amount, kind="recharge", note=note)
    user = require_user(conn, user_id)
    transactions = rows_to_dicts(
        many(
            conn,
            """
            SELECT *
            FROM wallet_transactions
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT 20
            """,
            (user_id,),
        )
    )
    return {"user_id": user_id, "balance": user["balance"], "transactions": transactions}


def wallet(conn: sqlite3.Connection, *, user_id: int) -> dict:
    user = require_user(conn, user_id)
    transactions = rows_to_dicts(
        many(
            conn,
            """
            SELECT *
            FROM wallet_transactions
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT 50
            """,
            (user_id,),
        )
    )
    return {"user_id": user_id, "balance": user["balance"], "transactions": transactions}


def app_home(conn: sqlite3.Connection, *, user_id: int) -> dict:
    user = require_user(conn, user_id)
    return {
        "user": row_to_dict(user),
        "point_balance": user["balance"],
        "locked_deposit": locked_deposit_for_user(conn, user_id),
        "deposit_amount": get_settings().deposit_amount,
        "current_rental": active_rental_detail_for_user(conn, user_id),
    }


def payment_methods() -> list[dict]:
    disabled_reason = "결제 연동은 아직 비활성화되어 있습니다."
    return [
        {"id": "kakao_pay", "name": "카카오페이", "enabled": False, "reason": disabled_reason},
        {"id": "toss_pay", "name": "토스페이", "enabled": False, "reason": disabled_reason},
    ]


def create_payment_charge(conn: sqlite3.Connection, *, user_id: int, amount: int, method: str) -> dict:
    require_user(conn, user_id)
    settings = get_settings()
    if not settings.payments_enabled:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "PAYMENTS_DISABLED",
                "message": "포인트 결제는 아직 열려 있지 않습니다.",
                "methods": payment_methods(),
            },
        )
    add_wallet_transaction(conn, user_id=user_id, amount=amount, kind=f"payment_{method}", note="Point recharge")
    return wallet(conn, user_id=user_id)


def list_locations(conn: sqlite3.Connection) -> list[dict]:
    return location_summaries(conn)


def list_location_slots(conn: sqlite3.Connection, *, location_id: int) -> dict:
    location = require_location(conn, location_id)
    return {"location": row_to_dict(location), "slots": slots_for_location(conn, location_id)}


def report_slot_issue(
    conn: sqlite3.Connection,
    *,
    slot_id: int,
    user_id: int | None,
    reason: str,
    description: str | None,
) -> dict:
    slot = require_slot(conn, slot_id)
    if user_id is not None:
        require_user(conn, user_id)
    if slot["status"] == "occupied":
        raise HTTPException(status_code=409, detail="Cannot report a slot currently in use")

    now = utc_now_iso()
    cursor = conn.execute(
        """
        INSERT INTO slot_reports (slot_id, reporter_user_id, reason, description, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (slot_id, user_id, reason, description, now),
    )
    conn.execute(
        """
        UPDATE slots
        SET status = 'disabled',
            report_reason = ?,
            current_rental_id = NULL,
            updated_at = ?
        WHERE id = ?
        """,
        (reason, now, slot_id),
    )
    return {
        "report": row_to_dict(one(conn, "SELECT * FROM slot_reports WHERE id = ?", (cursor.lastrowid,))),
        "slot": slot_with_location(conn, slot_id),
        "message": "신고가 접수되었습니다. 해당 슬롯은 점검 상태로 변경됩니다.",
    }


def issue_rent_qr(conn: sqlite3.Connection, *, user_id: int, slot_id: int) -> dict:
    settings = get_settings()
    user = require_user(conn, user_id)
    slot = require_slot(conn, slot_id)

    if user["balance"] < settings.deposit_amount:
        raise HTTPException(status_code=400, detail="Insufficient balance for deposit")
    if slot["status"] != "available" or not bool(slot["umbrella_present"]):
        raise HTTPException(status_code=409, detail="Slot is not available for rent")
    if active_rental_for_user(conn, user_id) is not None:
        raise HTTPException(status_code=409, detail="User already has an active rental")

    token = create_token()
    expires_at = add_seconds(settings.qr_ttl_seconds)
    conn.execute(
        """
        INSERT INTO qr_tokens (token_hash, action, user_id, slot_id, rental_id, expires_at, created_at)
        VALUES (?, 'rent', ?, ?, NULL, ?, ?)
        """,
        (hash_token(token), user_id, slot_id, expires_at, utc_now_iso()),
    )
    slot_meta = slot_with_location(conn, slot_id)
    return {
        "action": "rent",
        "token": token,
        "expires_at": expires_at,
        "slot_id": slot_id,
        "location_id": slot_meta["location_id"],
        "location_name": slot_meta["location_name"],
        "slot_number": slot_meta["slot_number"],
        "rental_id": None,
        "return_type": None,
        "ttl_seconds": settings.qr_ttl_seconds,
    }


def issue_return_qr(conn: sqlite3.Connection, *, user_id: int, rental_id: int | None, return_type: str = "normal") -> dict:
    settings = get_settings()
    require_user(conn, user_id)

    rental = require_rental(conn, rental_id) if rental_id is not None else active_rental_for_user(conn, user_id)
    if rental is None or rental["user_id"] != user_id:
        raise HTTPException(status_code=404, detail="Active rental not found")
    if rental["status"] != "active":
        raise HTTPException(status_code=409, detail="Rental is not ready for return")
    if return_type not in ("normal", "damage_report"):
        raise HTTPException(status_code=400, detail="Unsupported return type")

    token = create_token()
    expires_at = add_seconds(settings.qr_ttl_seconds)
    conn.execute(
        """
        INSERT INTO qr_tokens (token_hash, action, user_id, slot_id, rental_id, return_type, expires_at, created_at)
        VALUES (?, 'return', ?, ?, ?, ?, ?, ?)
        """,
        (hash_token(token), user_id, rental["slot_id"], rental["id"], return_type, expires_at, utc_now_iso()),
    )
    slot_meta = slot_with_location(conn, rental["slot_id"])
    return {
        "action": "return",
        "token": token,
        "expires_at": expires_at,
        "slot_id": rental["slot_id"],
        "location_id": slot_meta["location_id"],
        "location_name": slot_meta["location_name"],
        "slot_number": slot_meta["slot_number"],
        "rental_id": rental["id"],
        "return_type": return_type,
        "ttl_seconds": settings.qr_ttl_seconds,
    }


def scan_qr(conn: sqlite3.Connection, *, token: str) -> dict:
    settings = get_settings()
    token_hash = hash_token(token)
    token_row = one(conn, "SELECT * FROM qr_tokens WHERE token_hash = ?", (token_hash,))
    if token_row is None:
        raise HTTPException(status_code=404, detail="QR token not found")
    if token_row["used_at"] is not None:
        raise HTTPException(status_code=409, detail="QR token already used")
    if parse_iso(token_row["expires_at"]) < utc_now():
        raise HTTPException(status_code=410, detail="QR token expired")

    if token_row["action"] == "rent":
        result = _scan_rent_qr(conn, token_row)
    elif token_row["action"] == "return":
        result = _scan_return_qr(conn, token_row)
    else:
        raise HTTPException(status_code=400, detail="Unsupported QR action")

    conn.execute("UPDATE qr_tokens SET used_at = ? WHERE id = ?", (utc_now_iso(), token_row["id"]))
    return result


def _scan_rent_qr(conn: sqlite3.Connection, token_row: sqlite3.Row) -> dict:
    settings = get_settings()
    user = require_user(conn, token_row["user_id"])
    slot = require_slot(conn, token_row["slot_id"])

    if user["balance"] < settings.deposit_amount:
        raise HTTPException(status_code=400, detail="Insufficient balance for deposit")
    if slot["status"] != "available" or not bool(slot["umbrella_present"]):
        raise HTTPException(status_code=409, detail="Slot is not available for rent")
    if active_rental_for_user(conn, user["id"]) is not None:
        raise HTTPException(status_code=409, detail="User already has an active rental")

    now = utc_now_iso()
    cursor = conn.execute(
        """
        INSERT INTO rentals (
            user_id, slot_id, status, deposit_amount, deposit_charged_at,
            deposit_refunded_at, started_at, due_at, returned_at, return_type,
            created_at, updated_at
        )
        VALUES (?, ?, 'pending_pickup', ?, ?, NULL, NULL, NULL, NULL, 'normal', ?, ?)
        """,
        (user["id"], slot["id"], settings.deposit_amount, now, now, now),
    )
    rental_id = cursor.lastrowid
    add_wallet_transaction(
        conn,
        user_id=user["id"],
        amount=-settings.deposit_amount,
        kind="deposit_charge",
        rental_id=rental_id,
        note="Rental deposit charged on QR scan",
    )
    conn.execute(
        "UPDATE slots SET status = 'occupied', current_rental_id = ?, updated_at = ? WHERE id = ?",
        (rental_id, now, slot["id"]),
    )

    slot_meta = slot_with_location(conn, slot["id"])
    return {
        "unlock": True,
        "action": "rent",
        "slot_id": slot["id"],
        "location_id": slot_meta["location_id"],
        "location_name": slot_meta["location_name"],
        "slot_number": slot_meta["slot_number"],
        "rental_id": rental_id,
        "return_type": None,
        "unlock_seconds": settings.unlock_seconds,
        "message": "Rent QR accepted. Wait for IR sensor pickup event.",
    }


def _scan_return_qr(conn: sqlite3.Connection, token_row: sqlite3.Row) -> dict:
    settings = get_settings()
    rental = require_rental(conn, token_row["rental_id"])
    slot = require_slot(conn, rental["slot_id"])

    if rental["status"] != "active":
        raise HTTPException(status_code=409, detail="Rental is not active")
    if slot["status"] == "disabled":
        raise HTTPException(status_code=409, detail="Slot is disabled")

    now = utc_now_iso()
    return_type = token_row["return_type"] or "normal"
    conn.execute(
        "UPDATE rentals SET status = 'pending_return', return_type = ?, updated_at = ? WHERE id = ?",
        (return_type, now, rental["id"]),
    )
    conn.execute(
        "UPDATE slots SET status = 'occupied', current_rental_id = ?, updated_at = ? WHERE id = ?",
        (rental["id"], now, slot["id"]),
    )

    slot_meta = slot_with_location(conn, slot["id"])
    return {
        "unlock": True,
        "action": "return",
        "slot_id": slot["id"],
        "location_id": slot_meta["location_id"],
        "location_name": slot_meta["location_name"],
        "slot_number": slot_meta["slot_number"],
        "rental_id": rental["id"],
        "return_type": return_type,
        "unlock_seconds": settings.unlock_seconds,
        "message": "Return QR accepted. Wait for IR sensor insertion event.",
    }


def handle_sensor_event(conn: sqlite3.Connection, *, slot_id: int, present: bool) -> dict:
    slot = require_slot(conn, slot_id)
    now = utc_now_iso()
    rental = None
    event = "sensor_updated"

    if slot["current_rental_id"] is not None:
        rental = require_rental(conn, slot["current_rental_id"])

    if not present and rental is not None and rental["status"] == "pending_pickup":
        due_at = add_hours(get_settings().rental_hours)
        conn.execute(
            """
            UPDATE rentals
            SET status = 'active', started_at = ?, due_at = ?, updated_at = ?
            WHERE id = ?
            """,
            (now, due_at, now, rental["id"]),
        )
        conn.execute(
            """
            UPDATE slots
            SET status = 'occupied', umbrella_present = 0, updated_at = ?
            WHERE id = ?
            """,
            (now, slot_id),
        )
        rental = require_rental(conn, rental["id"])
        event = "pickup_completed"
    elif present and rental is not None and rental["status"] == "pending_return":
        if (rental["return_type"] or "normal") == "damage_report":
            conn.execute(
                """
                UPDATE rentals
                SET status = 'self_damage_reported', returned_at = ?, updated_at = ?
                WHERE id = ?
                """,
                (now, now, rental["id"]),
            )
            conn.execute(
                """
                UPDATE slots
                SET status = 'disabled',
                    umbrella_present = 1,
                    current_rental_id = NULL,
                    report_reason = 'umbrella_damage',
                    updated_at = ?
                WHERE id = ?
                """,
                (now, slot_id),
            )
            _insert_report(
                conn,
                rental_id=rental["id"],
                reporter_user_id=rental["user_id"],
                slot_id=slot_id,
                report_type="self_damage",
                previous_rental_id=None,
                previous_user_id=None,
                description="Damage reported during return",
            )
            rental = require_rental(conn, rental["id"])
            event = "damage_return_completed"
        else:
            _refund_deposit_if_needed(conn, rental)
            conn.execute(
                """
                UPDATE rentals
                SET status = 'completed', returned_at = ?, updated_at = ?
                WHERE id = ?
                """,
                (now, now, rental["id"]),
            )
            conn.execute(
                """
                UPDATE slots
                SET status = 'available',
                    umbrella_present = 1,
                    current_rental_id = NULL,
                    report_reason = NULL,
                    updated_at = ?
                WHERE id = ?
                """,
                (now, slot_id),
            )
            rental = require_rental(conn, rental["id"])
            event = "return_completed"
    else:
        status_clause = "" if slot["status"] == "disabled" else ", status = status"
        conn.execute(
            f"UPDATE slots SET umbrella_present = ?, updated_at = ?{status_clause} WHERE id = ?",
            (int(present), now, slot_id),
        )

    return {
        "slot": slot_with_location(conn, slot_id),
        "rental": rental_detail(conn, rental["id"]) if rental is not None else None,
        "event": event,
    }


def report_defect(conn: sqlite3.Connection, *, user_id: int, rental_id: int | None, description: str | None) -> dict:
    require_user(conn, user_id)
    rental = require_rental(conn, rental_id) if rental_id is not None else active_rental_for_user(conn, user_id)
    if rental is None or rental["user_id"] != user_id:
        raise HTTPException(status_code=404, detail="Active rental not found")
    if rental["status"] not in ("pending_pickup", "active", "pending_return"):
        raise HTTPException(status_code=409, detail="Rental cannot be reported")

    previous = previous_completed_rental_for_slot(conn, slot_id=rental["slot_id"], before_rental_id=rental["id"])
    _refund_deposit_if_needed(conn, rental)
    if previous is not None:
        add_wallet_transaction(
            conn,
            user_id=previous["user_id"],
            amount=-rental["deposit_amount"],
            kind="defect_penalty",
            rental_id=previous["id"],
            note=f"Penalty for defect reported on slot {rental['slot_id']}",
        )

    now = utc_now_iso()
    conn.execute(
        """
        UPDATE rentals
        SET status = 'defect_reported', returned_at = ?, updated_at = ?
        WHERE id = ?
        """,
        (now, now, rental["id"]),
    )
    conn.execute(
        """
        UPDATE slots
        SET status = 'disabled',
            umbrella_present = 0,
            current_rental_id = NULL,
            report_reason = 'defect',
            updated_at = ?
        WHERE id = ?
        """,
        (now, rental["slot_id"]),
    )
    report_id = _insert_report(
        conn,
        rental_id=rental["id"],
        reporter_user_id=user_id,
        slot_id=rental["slot_id"],
        report_type="defect",
        previous_rental_id=previous["id"] if previous is not None else None,
        previous_user_id=previous["user_id"] if previous is not None else None,
        description=description,
    )

    return {
        "report": row_to_dict(one(conn, "SELECT * FROM reports WHERE id = ?", (report_id,))),
        "rental": row_to_dict(require_rental(conn, rental["id"])),
        "slot": slot_with_location(conn, rental["slot_id"]),
        "previous_rental": row_to_dict(previous),
    }


def report_self_damage(
    conn: sqlite3.Connection,
    *,
    user_id: int,
    rental_id: int | None,
    description: str | None,
) -> dict:
    require_user(conn, user_id)
    rental = require_rental(conn, rental_id) if rental_id is not None else active_rental_for_user(conn, user_id)
    if rental is None or rental["user_id"] != user_id:
        raise HTTPException(status_code=404, detail="Active rental not found")
    if rental["status"] not in ("pending_pickup", "active", "pending_return"):
        raise HTTPException(status_code=409, detail="Rental cannot be reported")

    now = utc_now_iso()
    conn.execute(
        """
        UPDATE rentals
        SET status = 'self_damage_reported', returned_at = ?, updated_at = ?
        WHERE id = ?
        """,
        (now, now, rental["id"]),
    )
    conn.execute(
        """
        UPDATE slots
        SET status = 'disabled',
            umbrella_present = 0,
            current_rental_id = NULL,
            report_reason = 'self_damage',
            updated_at = ?
        WHERE id = ?
        """,
        (now, rental["slot_id"]),
    )
    report_id = _insert_report(
        conn,
        rental_id=rental["id"],
        reporter_user_id=user_id,
        slot_id=rental["slot_id"],
        report_type="self_damage",
        previous_rental_id=None,
        previous_user_id=None,
        description=description,
    )

    return {
        "report": row_to_dict(one(conn, "SELECT * FROM reports WHERE id = ?", (report_id,))),
        "rental": row_to_dict(require_rental(conn, rental["id"])),
        "slot": slot_with_location(conn, rental["slot_id"]),
        "previous_rental": None,
    }


def enable_slot(conn: sqlite3.Connection, *, slot_id: int, umbrella_present: bool) -> dict:
    require_slot(conn, slot_id)
    now = utc_now_iso()
    conn.execute(
        """
        UPDATE slots
        SET status = ?, umbrella_present = ?, current_rental_id = NULL, report_reason = NULL, updated_at = ?
        WHERE id = ?
        """,
        ("available" if umbrella_present else "occupied", int(umbrella_present), now, slot_id),
    )
    return slot_with_location(conn, slot_id)


def disable_slot(conn: sqlite3.Connection, *, slot_id: int) -> dict:
    require_slot(conn, slot_id)
    now = utc_now_iso()
    conn.execute(
        "UPDATE slots SET status = 'disabled', current_rental_id = NULL, report_reason = 'maintenance', updated_at = ? WHERE id = ?",
        (now, slot_id),
    )
    return slot_with_location(conn, slot_id)


def _refund_deposit_if_needed(conn: sqlite3.Connection, rental: sqlite3.Row) -> None:
    if rental["deposit_refunded_at"] is not None:
        return
    now = utc_now_iso()
    add_wallet_transaction(
        conn,
        user_id=rental["user_id"],
        amount=rental["deposit_amount"],
        kind="deposit_refund",
        rental_id=rental["id"],
        note="Rental deposit refunded",
    )
    conn.execute("UPDATE rentals SET deposit_refunded_at = ?, updated_at = ? WHERE id = ?", (now, now, rental["id"]))


def _insert_report(
    conn: sqlite3.Connection,
    *,
    rental_id: int,
    reporter_user_id: int,
    slot_id: int,
    report_type: str,
    previous_rental_id: int | None,
    previous_user_id: int | None,
    description: str | None,
) -> int:
    cursor = conn.execute(
        """
        INSERT INTO reports (
            rental_id, reporter_user_id, slot_id, type,
            previous_rental_id, previous_user_id, description, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            rental_id,
            reporter_user_id,
            slot_id,
            report_type,
            previous_rental_id,
            previous_user_id,
            description,
            utc_now_iso(),
        ),
    )
    return int(cursor.lastrowid)
