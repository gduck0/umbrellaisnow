# ☂️ 우산있나우

QR 코드 기반 무인 스마트 우산 대여/반납 서비스입니다. 모바일 앱, 중앙 서버, IoT 하드웨어(라즈베리파이)가 실시간으로 통신하여 관리자 개입 없이 우산을 대여하고 반납할 수 있습니다.

캡스톤 디자인 프로젝트로, 캠퍼스 내 우천 시 우산이 필요한 학생들에게 무인 대여 서비스를 제공하는 것을 목표로 합니다.

## 시스템 구조

```
[Android App] ──REST API──> [FastAPI Server] <──REST API── [Raspberry Pi]
   Kotlin                      Python / SQLite              QR 스캔 / 솔레노이드 / IR 센서
```

세 컴포넌트가 직접 통신하지 않고 모두 서버를 거쳐서만 데이터를 주고받습니다. 앱이 QR 생성을 요청하면 서버가 일회용 토큰을 발급하고, 라즈베리파이가 그 토큰을 스캔해서 서버에 검증을 요청하는 구조입니다.

### 대여 흐름

1. 앱에서 슬롯을 선택하면 서버가 60초간 유효한 일회용 QR 토큰을 발급
2. 라즈베리파이 카메라가 QR을 스캔해서 서버에 검증 요청
3. 서버가 토큰을 확인하고 잠금 해제 신호 응답
4. 솔레노이드가 열리고 사용자가 우산을 인출
5. IR 센서가 우산 제거를 감지해서 서버에 전송
6. 서버가 대여 상태를 갱신하고, 앱이 이를 감지해 화면을 전환

반납도 같은 흐름으로 동작하며, 센서 신호의 방향만 반대입니다.

## 기술 스택

**Android App**
- Kotlin
- Retrofit2 + OkHttp (REST API 통신)
- ZXing (QR 코드 생성)
- Material Components

**Server**
- Python / FastAPI
- SQLite
- JWT 기반 인증
- Railway (배포)

**Hardware**
- Raspberry Pi 4
- Picamera2 + pyzbar (QR 스캔)
- gpiod (GPIO 제어)
- MOSFET + 솔레노이드 락
- IR Break Beam 센서

## 주요 기능

### 사용자
- 회원가입 / 로그인 / 토큰 기반 자동 로그인
- 건물·슬롯별 실시간 우산 재고 조회
- QR 코드 기반 대여/반납
- 보증금 포인트 충전·환불
- 대여 기록 조회
- 반납 기한 실시간 카운트다운
- 슬롯 고장 신고
- 대여 직후 5분 내 불량 우산 신고 (직전 반납자에게 패널티 부과)

### 관리자 (앱 내 숨겨진 페이지)
- 슬롯 점검 상태 해제/설정
- 사용자 조회 및 검색
- 사용자 포인트 직접 조정
- 회원 삭제

### 하드웨어 연동
- QR 토큰 검증 API
- IR 센서 이벤트 수신 API
- 솔레노이드 제어를 위한 슬롯 매핑

## 프로젝트 구조

```
umbrella-app/
├── app/src/main/
│   ├── java/com/example/umbrella/
│   │   ├── MainActivity.kt           # 홈 화면
│   │   ├── LoginActivity.kt          # 로그인
│   │   ├── SignupActivity.kt         # 회원가입
│   │   ├── BuildingListActivity.kt   # 건물 목록
│   │   ├── SelectActivity.kt         # 슬롯 선택
│   │   ├── QrActivity.kt             # QR 대여/반납
│   │   ├── StatusActivity.kt         # 대여 현황
│   │   ├── PaymentActivity.kt        # 포인트 충전/환불
│   │   ├── RentalHistoryActivity.kt  # 대여 기록
│   │   ├── AdminActivity.kt          # 관리자 페이지
│   │   ├── BaseActivity.kt           # 공통 베이스 (상태바 인셋 처리)
│   │   ├── AppSession.kt             # 전역 세션 상태
│   │   ├── RetrofitClient.kt         # API 클라이언트
│   │   ├── UmbrellaApiService.kt     # API 엔드포인트 정의
│   │   ├── UmbrellaDto.kt            # 요청/응답 DTO
│   │   └── DateUtils.kt              # 날짜 변환 유틸
│   └── res/layout/                   # 화면별 레이아웃 XML
│
umbrella-server/
├── app/
│   ├── main.py          # API 라우트
│   ├── services.py      # 비즈니스 로직
│   ├── repository.py    # DB 쿼리
│   ├── schemas.py        # Pydantic 모델
│   ├── database.py      # DB 초기화/스키마
│   ├── security.py      # 인증/토큰
│   └── config.py        # 환경설정
│
umbrella-hardware/
└── main.py              # 라즈베리파이 제어 코드
```

## 개발 중 해결한 문제

**서버-앱 데이터 구조 불일치**
서버의 대여 상세 API가 slot 정보를 중첩 객체가 아닌 평탄화된(flat) 구조로 반환하는데, 초기 앱 코드는 중첩 객체를 가정해서 위치 정보가 항상 null로 표시되는 문제가 있었습니다. DTO를 서버 응답 구조에 맞게 재정의해서 해결했습니다.

**반납 QR 생성 시 409 충돌**
이전 반납 시도가 완전히 처리되지 않은 상태에서 재시도하면 서버가 409를 반환하는 문제가 있었습니다. `rental_id`를 명시하지 않고 재요청하면 서버가 활성 대여를 자동으로 찾아 처리하도록 재시도 로직을 추가했습니다.

**QR 토큰 길이 불일치**
서버는 `secrets.token_urlsafe(32)`로 토큰을 생성하는데, 이는 32바이트를 Base64URL로 인코딩한 결과라 실제 문자열 길이는 43자입니다. 하드웨어 측에서 토큰 길이를 32자로 잘못 가정해서 정상 토큰을 무효 처리하는 문제가 있었고, 길이 기준을 43으로 수정해 해결했습니다.

## 실행 방법

### 서버
```bash
cd umbrella-server
pip install -r requirements.txt
uvicorn app.main:app --reload
```
실행 후 `http://localhost:8000/docs`에서 Swagger UI로 API를 직접 테스트할 수 있습니다.

### 앱
1. Android Studio에서 `umbrella-app` 폴더 열기
2. `AppSession.kt`의 `BASE_URL`을 서버 주소로 변경
3. 빌드 후 실행

## 팀 구성

| 역할 | 담당 |
|---|---|
| Android 앱 개발 | - |
| 서버 백엔드 개발 | - |
| 하드웨어(IoT) 설계 및 제어 | - |

## 향후 개선 사항

- 다중 거점 지원 (현재 단일 위치 4개 슬롯만 서버 연동)
- 실제 결제 수단(카카오페이 등) 연동
- 푸시 알림 (반납 기한, 패널티 부과 등)
- SQLite → PostgreSQL 마이그레이션
