from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse

from .config import get_settings
from .database import get_db, init_db, one
from .repository import (
    all_slots_with_location,
    list_rentals,
    rental_detail,
    require_rental,
    require_user,
    row_to_dict,
    slot_with_location,
)
from .schemas import (
    ApiInfo,
    AppHomeOut,
    AuthOut,
    HardwareScanOut,
    HardwareScanRequest,
    LocationOut,
    LoginRequest,
    PaymentChargeRequest,
    PaymentDisabledOut,
    PaymentMethodOut,
    QrTokenOut,
    RechargeRequest,
    RegisterRequest,
    RentQrRequest,
    ReportOut,
    ReportRequest,
    ReturnQrRequest,
    SensorEventOut,
    SensorEventRequest,
    SlotMaintenanceRequest,
    SlotOut,
    SlotReportOut,
    SlotReportRequest,
    UserCreate,
    UserOut,
    WalletOut,
)
from .security import LocalOnlyMiddleware
from .services import (
    authenticate_session,
    create_user,
    disable_slot,
    enable_slot,
    app_home,
    create_payment_charge,
    handle_sensor_event,
    issue_rent_qr,
    issue_return_qr,
    list_location_slots,
    list_locations,
    login_user,
    logout_user,
    payment_methods,
    recharge_wallet,
    register_user,
    report_defect,
    report_slot_issue,
    report_self_damage,
    scan_qr,
    wallet,
)


settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title=settings.app_name, version="0.1.0", lifespan=lifespan)
# app.add_middleware(LocalOnlyMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.cors_origins),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def db():
    conn = get_db()
    try:
        with conn:
            yield conn
    finally:
        conn.close()


def current_user(authorization: str | None = Header(default=None), conn=Depends(db)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="Missing bearer token")
    return authenticate_session(conn, token=token)


@app.get("/", include_in_schema=False)
def root():
    return RedirectResponse(url="/docs")


@app.get("/api", response_model=ApiInfo)
def api_info():
    return {
        "name": settings.app_name,
        "docs": "/docs",
        "health": "/health",
        "local_only": True,
    }


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/auth/register", response_model=AuthOut, status_code=201)
def register_endpoint(payload: RegisterRequest, conn=Depends(db)):
    return register_user(
        conn,
        email=payload.email,
        name=payload.name,
        password=payload.password,
        password_confirm=payload.password_confirm,
    )


@app.post("/api/auth/login", response_model=AuthOut)
def login_endpoint(payload: LoginRequest, conn=Depends(db)):
    return login_user(conn, email=payload.email, password=payload.password, remember_me=payload.remember_me)


@app.post("/api/auth/logout")
def logout_endpoint(authorization: str | None = Header(default=None), conn=Depends(db)):
    token = authorization.removeprefix("Bearer ").strip() if authorization else ""
    if not token:
        return {"ok": True}
    return logout_user(conn, token=token)


@app.get("/api/me", response_model=UserOut)
def get_me(user=Depends(current_user)):
    return row_to_dict(user)


@app.get("/api/me/wallet", response_model=WalletOut)
def get_my_wallet(user=Depends(current_user), conn=Depends(db)):
    return wallet(conn, user_id=user["id"])


@app.post("/api/users", response_model=UserOut, status_code=201)
def create_user_endpoint(payload: UserCreate, conn=Depends(db)):
    return create_user(conn, name=payload.name, phone=payload.phone, email=payload.email)


@app.get("/api/users/{user_id}", response_model=UserOut)
def get_user(user_id: int, conn=Depends(db)):
    return row_to_dict(require_user(conn, user_id))


@app.post("/api/users/{user_id}/wallet/recharge", response_model=WalletOut)
def recharge_wallet_endpoint(user_id: int, payload: RechargeRequest, conn=Depends(db)):
    return recharge_wallet(conn, user_id=user_id, amount=payload.amount, note=payload.note)


@app.get("/api/users/{user_id}/wallet", response_model=WalletOut)
def get_wallet(user_id: int, conn=Depends(db)):
    return wallet(conn, user_id=user_id)


@app.get("/api/app/home", response_model=AppHomeOut)
def get_app_home(user=Depends(current_user), conn=Depends(db)):
    return app_home(conn, user_id=user["id"])


@app.get("/api/payments/methods", response_model=list[PaymentMethodOut])
def get_payment_methods():
    return payment_methods()


@app.post("/api/payments/charge")
def create_payment_charge_endpoint(payload: PaymentChargeRequest, user=Depends(current_user), conn=Depends(db)):
    return create_payment_charge(conn, user_id=user["id"], amount=payload.amount, method=payload.method)


@app.get("/api/payments/status", response_model=PaymentDisabledOut)
def get_payment_status():
    return {
        "enabled": settings.payments_enabled,
        "code": "PAYMENTS_DISABLED" if not settings.payments_enabled else "PAYMENTS_ENABLED",
        "message": "포인트 결제는 아직 열려 있지 않습니다."
        if not settings.payments_enabled
        else "포인트 결제를 사용할 수 있습니다.",
        "methods": payment_methods(),
    }


@app.post("/api/points/recharge")
def create_point_recharge_endpoint(payload: PaymentChargeRequest, user=Depends(current_user), conn=Depends(db)):
    return create_payment_charge(conn, user_id=user["id"], amount=payload.amount, method=payload.method)


@app.get("/api/locations", response_model=list[LocationOut])
def get_locations(conn=Depends(db)):
    return list_locations(conn)


@app.get("/api/locations/{location_id}/slots")
def get_location_slots(location_id: int, conn=Depends(db)):
    return list_location_slots(conn, location_id=location_id)


@app.get("/api/slots", response_model=list[SlotOut])
def get_slots(conn=Depends(db)):
    return all_slots_with_location(conn)


@app.get("/api/slots/{slot_id}", response_model=SlotOut)
def get_slot(slot_id: int, conn=Depends(db)):
    return slot_with_location(conn, slot_id)


@app.post("/api/qr/rent", response_model=QrTokenOut, status_code=201)
def create_rent_qr(payload: RentQrRequest, user=Depends(current_user), conn=Depends(db)):
    return issue_rent_qr(conn, user_id=user["id"], slot_id=payload.slot_id)


@app.post("/api/qr/return", response_model=QrTokenOut, status_code=201)
def create_return_qr(payload: ReturnQrRequest, user=Depends(current_user), conn=Depends(db)):
    return issue_return_qr(
        conn,
        user_id=user["id"],
        rental_id=payload.rental_id,
        return_type=payload.return_type,
    )


@app.post("/api/hardware/qr/scan", response_model=HardwareScanOut)
def hardware_scan_qr(payload: HardwareScanRequest, conn=Depends(db)):
    return scan_qr(conn, token=payload.token)


@app.post("/api/hardware/slots/{slot_id}/sensor", response_model=SensorEventOut)
def hardware_sensor_event(slot_id: int, payload: SensorEventRequest, conn=Depends(db)):
    return handle_sensor_event(conn, slot_id=slot_id, present=payload.present)


@app.get("/api/rentals")
def get_rentals(user_id: int | None = Query(default=None), conn=Depends(db)):
    return list_rentals(conn, user_id=user_id)


@app.get("/api/rentals/active")
def get_active_rental(user_id: int = Query(...), conn=Depends(db)):
    rental = one(
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
    return rental_detail(conn, rental["id"]) if rental is not None else None


@app.get("/api/me/rentals/active")
def get_my_active_rental(user=Depends(current_user), conn=Depends(db)):
    rental = one(
        conn,
        """
        SELECT *
        FROM rentals
        WHERE user_id = ?
          AND status IN ('pending_pickup', 'active', 'pending_return')
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (user["id"],),
    )
    return rental_detail(conn, rental["id"]) if rental is not None else None


@app.get("/api/rentals/{rental_id}")
def get_rental(rental_id: int, conn=Depends(db)):
    require_rental(conn, rental_id)
    return rental_detail(conn, rental_id)


@app.post("/api/reports/defect", response_model=ReportOut, status_code=201)
def report_defect_endpoint(payload: ReportRequest, user=Depends(current_user), conn=Depends(db)):
    return report_defect(
        conn,
        user_id=user["id"],
        rental_id=payload.rental_id,
        description=payload.description,
    )


@app.post("/api/reports/self-damage", response_model=ReportOut, status_code=201)
def report_self_damage_endpoint(payload: ReportRequest, user=Depends(current_user), conn=Depends(db)):
    return report_self_damage(
        conn,
        user_id=user["id"],
        rental_id=payload.rental_id,
        description=payload.description,
    )


@app.post("/api/slots/{slot_id}/reports", response_model=SlotReportOut, status_code=201)
def report_slot_issue_endpoint(slot_id: int, payload: SlotReportRequest, user=Depends(current_user), conn=Depends(db)):
    return report_slot_issue(
        conn,
        slot_id=slot_id,
        user_id=user["id"],
        reason=payload.reason,
        description=payload.description,
    )


@app.post("/api/maintenance/slots/{slot_id}/enable", response_model=SlotOut)
def enable_slot_endpoint(slot_id: int, payload: SlotMaintenanceRequest, conn=Depends(db)):
    return enable_slot(conn, slot_id=slot_id, umbrella_present=payload.umbrella_present)


@app.post("/api/maintenance/slots/{slot_id}/disable", response_model=SlotOut)
def disable_slot_endpoint(slot_id: int, conn=Depends(db)):
    return disable_slot(conn, slot_id=slot_id)


# ── 관리자 API ───────────────────────────────────────────────
# 별도 인증 없이 앱 내부에서만 접근 (관리자 비밀번호는 앱에서 검증)
# 실제 서비스라면 admin 전용 토큰 인증이 필요함

@app.get("/api/admin/users")
def admin_get_all_users(conn=Depends(db)):
    users = many(conn, "SELECT id, email, name, phone, balance, created_at FROM users ORDER BY created_at DESC")
    return rows_to_dicts(users)


@app.get("/api/admin/users/search")
def admin_search_user(email: str = Query(...), conn=Depends(db)):
    users = many(
        conn,
        "SELECT id, email, name, phone, balance, created_at FROM users WHERE email LIKE ?",
        (f"%{email}%",),
    )
    return rows_to_dicts(users)


@app.delete("/api/admin/users/{user_id}", status_code=204)
def admin_delete_user(user_id: int, conn=Depends(db)):
    require_user(conn, user_id)
    # 진행 중 대여가 있으면 삭제 불가
    active = one(
        conn,
        """SELECT id FROM rentals WHERE user_id = ?
           AND status IN ('pending_pickup', 'active', 'pending_return')""",
        (user_id,),
    )
    if active:
        raise HTTPException(status_code=409, detail="진행 중인 대여가 있어 삭제할 수 없습니다.")
    conn.execute("DELETE FROM wallet_transactions WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM user_sessions WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM slot_reports WHERE reporter_user_id = ?", (user_id,))
    conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
    return None


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=True)