from datetime import datetime, timedelta, timezone


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def utc_now_iso() -> str:
    return utc_now().isoformat()


def add_seconds(seconds: int) -> str:
    return (utc_now() + timedelta(seconds=seconds)).isoformat()


def add_hours(hours: int) -> str:
    return (utc_now() + timedelta(hours=hours)).isoformat()


def parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value)
