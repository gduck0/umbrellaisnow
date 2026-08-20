from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="UMBRELLA_", env_file=".env", extra="ignore")

    app_name: str = "Smart Umbrella Sharing Backend"
    host: str = "127.0.0.1"
    port: int = 8000
    database_path: Path = Field(default=Path("data/umbrella.db"))
    deposit_amount: int = 3000
    qr_ttl_seconds: int = 60
    slot_count: int = 4
    unlock_seconds: int = 5
    rental_hours: int = 24
    payments_enabled: bool = False
    session_ttl_hours: int = 24
    remember_session_ttl_hours: int = 24 * 30
    hardware_api_key: str | None = None
    allow_user_hardware_simulation: bool = True

    local_only: bool = False
    allow_test_client: bool = True
    allowed_hosts: tuple[str, ...] = ("127.0.0.1", "::1", "localhost")
    cors_origins: tuple[str, ...] = (
        "http://127.0.0.1:3000",
        "http://localhost:3000",
        "http://127.0.0.1:5173",
        "http://localhost:5173",
        "http://127.0.0.1:8000",
        "http://localhost:8000",
        "http://192.168.0.6:8000",

    )

    @property
    def database_url(self) -> str:
        return str(self.database_path)


@lru_cache
def get_settings() -> Settings:
    return Settings()
