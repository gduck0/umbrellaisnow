from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


SlotStatus = Literal["available", "occupied", "disabled"]
RentalStatus = Literal[
    "pending_pickup",
    "active",
    "pending_return",
    "completed",
    "defect_reported",
    "self_damage_reported",
]
QrAction = Literal["rent", "return"]
ReturnType = Literal["normal", "damage_report"]
SlotReportReason = Literal["umbrella_damage", "umbrella_missing", "other"]


class RegisterRequest(BaseModel):
    email: str = Field(min_length=3, max_length=120)
    name: str = Field(min_length=1, max_length=80)
    password: str = Field(min_length=4, max_length=128)
    password_confirm: str = Field(min_length=4, max_length=128)


class LoginRequest(BaseModel):
    email: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=1, max_length=128)
    remember_me: bool = False


class UserCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    phone: str | None = Field(default=None, max_length=30)
    email: str | None = Field(default=None, max_length=120)


class UserOut(BaseModel):
    id: int
    email: str | None = None
    name: str
    phone: str | None
    balance: int
    created_at: str


class AuthOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: str
    user: UserOut


class RechargeRequest(BaseModel):
    # [수정] gt=0 → ne=0 으로 변경: 음수(환불)도 허용, 0만 불가
    amount: int = Field(ne=0, le=1_000_000, ge=-1_000_000)
    note: str | None = Field(default=None, max_length=200)


class WalletOut(BaseModel):
    user_id: int
    balance: int
    transactions: list[dict]


class SlotOut(BaseModel):
    id: int
    location_id: int | None = None
    location_name: str | None = None
    slot_number: int | None = None
    status: SlotStatus
    umbrella_present: bool
    current_rental_id: int | None
    report_reason: str | None = None
    updated_at: str


class LocationOut(BaseModel):
    id: int
    name: str
    display_order: int
    total_slots: int
    available_count: int
    disabled_count: int
    rentable: bool
    updated_at: str


class SlotMaintenanceRequest(BaseModel):
    umbrella_present: bool = True


class RentQrRequest(BaseModel):
    user_id: int | None = None
    slot_id: int


class ReturnQrRequest(BaseModel):
    user_id: int | None = None
    rental_id: int | None = None
    return_type: ReturnType = "normal"


class QrTokenOut(BaseModel):
    action: QrAction
    token: str
    expires_at: str
    slot_id: int
    location_id: int | None = None
    location_name: str | None = None
    slot_number: int | None = None
    rental_id: int | None = None
    return_type: ReturnType | None = None
    ttl_seconds: int


class HardwareScanRequest(BaseModel):
    token: str
    device_id: str | None = Field(default=None, max_length=80)


class HardwareScanOut(BaseModel):
    unlock: bool
    action: QrAction
    slot_id: int
    location_id: int | None = None
    location_name: str | None = None
    slot_number: int | None = None
    rental_id: int
    return_type: ReturnType | None = None
    unlock_seconds: int
    message: str


class SensorEventRequest(BaseModel):
    present: bool
    device_id: str | None = Field(default=None, max_length=80)


class SensorEventOut(BaseModel):
    slot: SlotOut
    rental: dict | None
    event: str


class ReportRequest(BaseModel):
    user_id: int | None = None
    rental_id: int | None = None
    description: str | None = Field(default=None, max_length=500)


class ReportOut(BaseModel):
    report: dict
    rental: dict
    slot: SlotOut
    previous_rental: dict | None = None


class SlotReportRequest(BaseModel):
    user_id: int | None = None
    reason: SlotReportReason
    description: str | None = Field(default=None, max_length=500)


class SlotReportOut(BaseModel):
    report: dict
    slot: SlotOut
    message: str


class PaymentMethodOut(BaseModel):
    id: str
    name: str
    enabled: bool
    reason: str | None = None


class PaymentChargeRequest(BaseModel):
    user_id: int | None = None
    amount: int = Field(gt=0, le=1_000_000)
    method: Literal["kakao_pay", "toss_pay"]


class PaymentDisabledOut(BaseModel):
    enabled: bool
    code: str
    message: str
    methods: list[PaymentMethodOut]


class AppHomeOut(BaseModel):
    user: UserOut
    point_balance: int
    locked_deposit: int
    deposit_amount: int
    current_rental: dict | None = None


class ApiInfo(BaseModel):
    name: str
    docs: str
    health: str
    local_only: bool