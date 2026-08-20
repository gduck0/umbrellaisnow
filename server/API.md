# API Reference

Base URL: `http://127.0.0.1:8000`

개발 기본값에서는 Android 앱과 로컬 시뮬레이터 연결을 허용합니다. 외부 공개 배포 전에는 아래 인증 경계를 적용하고, 필요하면 `UMBRELLA_LOCAL_ONLY=true`로 허용된 로컬 호스트 요청만 받습니다.

## 화면 기준 앱 흐름

1. 회원가입: `POST /api/auth/register`
2. 로그인: `POST /api/auth/login`
3. 앱은 응답의 `access_token`을 저장
4. 이후 앱 API는 `Authorization: Bearer {access_token}` 헤더 첨부
5. 메인 화면: `GET /api/app/home`
6. 포인트 충전 화면: `GET /api/payments/status`
7. 건물 선택: `GET /api/locations`
8. 슬롯 선택: `GET /api/locations/{location_id}/slots`
9. 대여 QR 생성: `POST /api/qr/rent`
10. 반납 QR 생성: `POST /api/qr/return`

현재 카카오페이/토스페이 결제는 닫혀 있습니다. 결제 시도 API는 `503 PAYMENTS_DISABLED`를 반환합니다.

## 인증

로그인/회원가입 응답의 `access_token`을 앱에 저장한 뒤, 앱 사용자 API에는 아래 헤더를 붙입니다.

```http
Authorization: Bearer <access_token>
```

토큰은 서버 DB에는 원문이 아니라 SHA-256 해시로 저장됩니다. 로그아웃하면 해당 세션의 `revoked_at`이 기록되어 더 이상 사용할 수 없습니다.

- 사용자·지갑·대여 상세 API는 본인 또는 `admin` 역할만 접근할 수 있습니다.
- 관리자·슬롯 점검 API는 `admin` 역할의 Bearer 토큰이 필요합니다.
- 하드웨어 API는 `X-Hardware-Key`를 지원합니다. 실제 장비를 연결할 때 `UMBRELLA_HARDWARE_API_KEY`를 설정하고 `UMBRELLA_ALLOW_USER_HARDWARE_SIMULATION=false`로 사용자 시뮬레이션을 끕니다.

## 상태 값

슬롯 상태:

- `available`: 우산이 꽂혀 있고 대여 가능
- `occupied`: 대여 중이거나 반납 대기 중
- `disabled`: 점검 중 / 사용 불가

대여 상태:

- `pending_pickup`: 대여 QR 스캔 완료, IR 인출 감지 대기
- `active`: 대여 중
- `pending_return`: 반납 QR 스캔 완료, IR 삽입 감지 대기
- `completed`: 정상 반납 완료
- `defect_reported`: 이전 사용자 훼손 의심 신고로 종료
- `self_damage_reported`: 본인 훼손 자진신고로 종료

## 사용자/지갑

### 회원가입

`POST /api/auth/register`

```json
{
  "email": "student@example.ac.kr",
  "name": "길덕영",
  "password": "1111",
  "password_confirm": "1111"
}
```

응답에는 앱이 보관할 수 있는 로컬 액세스 토큰과 사용자 정보가 포함됩니다.

### 로그인

`POST /api/auth/login`

```json
{
  "email": "student@example.ac.kr",
  "password": "1111",
  "remember_me": true
}
```

### 메인 화면

`GET /api/app/home`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

반환 필드:

- `point_balance`: 사용 가능한 포인트
- `locked_deposit`: 대여 중 보관된 보증금
- `deposit_amount`: 대여 시 필요한 보증금
- `current_rental`: 현재 대여 정보, 없으면 `null`

### 사용자 생성

`POST /api/users`

```json
{
  "name": "홍길동",
  "phone": "010-0000-0000"
}
```

### 개발용 지갑 충전

`POST /api/users/{user_id}/wallet/recharge`

```json
{
  "amount": 10000,
  "note": "테스트 충전"
}
```

이 API는 외부 결제 없이 로컬 개발 테스트용으로 포인트를 넣는 용도입니다.
본인 또는 `admin` 역할의 Bearer 토큰이 필요하며 실제 결제와 연결되지 않습니다.

### 결제 상태

`GET /api/payments/status`

카카오페이/토스페이는 현재 비활성화되어 있으며, 앱에서는 충전 버튼을 막거나 준비 중 메시지를 보여주면 됩니다.

### 실제 결제 시도

`POST /api/payments/charge`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

```json
{
  "amount": 10000,
  "method": "toss_pay"
}
```

현재 응답:

```json
{
  "detail": {
    "code": "PAYMENTS_DISABLED",
    "message": "포인트 결제는 아직 열려 있지 않습니다."
  }
}
```

### 지갑 조회

앱용:

`GET /api/me/wallet`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

개발/관리용:

`GET /api/users/{user_id}/wallet`

## 슬롯

### 건물 목록

`GET /api/locations`

기본 건물:

- 디지털관 1층

각 항목은 `total_slots`, `available_count`, `disabled_count`, `rentable`을 포함합니다.

### 건물별 슬롯 목록

`GET /api/locations/{location_id}/slots`

각 슬롯은 `slot_number`, `status`, `umbrella_present`, `report_reason`을 포함합니다.

### 슬롯 목록

`GET /api/slots`

### 대여 전 슬롯 신고

`POST /api/slots/{slot_id}/reports`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

```json
{
  "reason": "umbrella_damage",
  "description": "손잡이 파손"
}
```

`reason` 값:

- `umbrella_damage`: 우산 고장
- `umbrella_missing`: 우산 없음 / 분실 의심
- `other`: 기타 신고

처리 후 해당 슬롯은 `disabled`로 바뀝니다.

### 슬롯 점검 처리

`POST /api/maintenance/slots/{slot_id}/disable`

`admin` 역할의 Bearer 토큰이 필요합니다.

### 슬롯 재활성화

`POST /api/maintenance/slots/{slot_id}/enable`

`admin` 역할의 Bearer 토큰이 필요합니다.

```json
{
  "umbrella_present": true
}
```

## 대여

### 앱: 대여 QR 발급

`POST /api/qr/rent`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

```json
{
  "slot_id": 1
}
```

응답의 `token`을 앱에서 QR로 렌더링하면 됩니다. 토큰은 60초 동안 1회만 유효합니다.

### 하드웨어: QR 스캔

`POST /api/hardware/qr/scan`

운영 장비 헤더:

```http
X-Hardware-Key: <device-secret>
```

```json
{
  "token": "qr-token-from-app",
  "device_id": "raspberry-pi-1"
}
```

대여 QR이면 이 시점에 보증금 3,000원이 차감되고 잠금 해제 응답이 내려갑니다.

### 하드웨어: IR 인출 감지

`POST /api/hardware/slots/1/sensor`

운영 장비는 같은 `X-Hardware-Key` 헤더를 사용합니다.

```json
{
  "present": false,
  "device_id": "raspberry-pi-1"
}
```

`pending_pickup` 대여가 있으면 `active`로 전환됩니다.

## 반납

### 앱: 반납 QR 발급

`POST /api/qr/return`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

```json
{
  "rental_id": 1,
  "return_type": "normal"
}
```

`rental_id`를 생략하면 해당 사용자의 진행 중인 대여를 자동으로 찾습니다.

`return_type` 값:

- `normal`: 정상 반납, IR 삽입 감지 후 보증금 환불
- `damage_report`: 고장 신고 반납, IR 삽입 감지 후 보증금 환불 없음 + 슬롯 점검 처리

### 하드웨어: 반납 QR 스캔

`POST /api/hardware/qr/scan`

```json
{
  "token": "return-qr-token",
  "device_id": "raspberry-pi-1"
}
```

### 하드웨어: IR 삽입 감지

`POST /api/hardware/slots/1/sensor`

```json
{
  "present": true,
  "device_id": "raspberry-pi-1"
}
```

`pending_return` 대여가 있으면 반납 방식에 따라 처리됩니다.

- `normal`: `completed` 전환, 보증금 3,000원 환불
- `damage_report`: `self_damage_reported` 전환, 보증금 환불 없음, 슬롯 `disabled`

## 신고

### 우산 불량 신고

`POST /api/reports/defect`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

```json
{
  "rental_id": 2,
  "description": "대여 직후 손잡이 파손 확인"
}
```

처리:

- 현재 사용자 보증금 환불
- 같은 슬롯의 직전 반납자에게 3,000원 패널티 차감
- 슬롯 `disabled` 전환
- 현재 대여 `defect_reported` 종료

### 훼손 자진신고

`POST /api/reports/self-damage`

필수 헤더:

```http
Authorization: Bearer <access_token>
```

```json
{
  "rental_id": 1,
  "description": "사용 중 우산살 파손"
}
```

처리:

- 신고자 보증금 환불 없음
- 슬롯 `disabled` 전환
- 현재 대여 `self_damage_reported` 종료
