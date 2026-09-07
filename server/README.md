# 우산있나우 서버

앱과 라즈베리파이 사이에서 대여 상태, QR 토큰, 슬롯, 지갑 정보를 관리하는 FastAPI 서버.
로컬에서는 SQLite를 사용하고 앱과 하드웨어는 REST API로 서버에 연결된다.

## 역할

- 회원가입과 로그인
- 대여/반납 QR 발급과 검증
- 슬롯 상태와 센서 이벤트 처리
- 보증금 포인트 충전·환불
- 대여 기록과 고장 신고 관리
- 중복 QR·센서 요청의 원자적 상태 전이

## 폴더

```
server/
├── app/          API와 서버 로직
├── data/         로컬 DB 생성 위치
├── scripts/      테스트 계정 생성 스크립트
├── tests/        API 테스트
├── API.md        API 상세 문서
└── requirements.txt
```

app 쪽 주요 파일:
- `main.py` - 라우트와 앱 설정
- `services.py` - 대여/반납 등 비즈니스 로직
- `repository.py` - DB 조회와 저장
- `schemas.py` - 요청/응답 구조
- `security.py` - 비밀번호 해시와 불투명 세션 토큰 처리
- `database.py` - SQLite 연결과 초기 데이터

## 실행

Windows PowerShell:

```powershell
cd server
py -3 -m venv .venv
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1

pip install -r requirements.txt
python -m uvicorn app.main:app --reload
```

실행 후 확인:

- Swagger: http://127.0.0.1:8000/docs
- Health: http://127.0.0.1:8000/health
- Readiness: http://127.0.0.1:8000/health/ready
- 건물 목록: http://127.0.0.1:8000/api/locations

## 운영 확인

모든 응답에는 `X-Request-ID`가 포함된다. 앱이나 장비가 올바른 요청 ID를 보내면 그대로
사용하고, 없거나 형식이 잘못되면 서버가 새 값을 만든다. 접근 로그는 요청 본문이나 인증
값을 제외하고 요청 ID, 메서드, 경로, 상태 코드, 처리 시간만 JSON으로 기록한다.

- `GET /health`: 프로세스가 요청을 받을 수 있는지 확인
- `GET /health/ready`: DB 연결과 현재 스키마 버전을 확인

예상하지 못한 서버 오류는 내부 예외 내용을 노출하지 않고 오류 코드와 요청 ID가 있는
일정한 JSON 응답을 반환한다. 서버 로그의 같은 요청 ID로 원인을 추적할 수 있다.

## 테스트

```powershell
pip install -r requirements-dev.txt
python -m pytest -q
```

GitHub에 코드를 올리면 Ubuntu와 Windows에서 같은 테스트가 자동으로 실행된다.

## 테스트 계정

테스트 계정 비밀번호는 저장소에 넣지 않고 환경 변수로 전달한다.

```powershell
$env:UMBRELLA_TEST_PASSWORD = "직접 정한 임시 비밀번호"
python scripts\seed_test_account.py
```

로그인 ID는 `admin`이고 로그인 성공 후 받은 `access_token`을 사용자 API에 붙여서 사용한다.

```http
Authorization: Bearer <access_token>
```

이 계정은 DB의 `admin` 역할로 저장되어 관리자·슬롯 점검 API에 사용할 수 있다. 실제 하드웨어를 연결할 때는 `UMBRELLA_HARDWARE_API_KEY`를 설정하고 `UMBRELLA_ALLOW_USER_HARDWARE_SIMULATION=false`로 사용자 시뮬레이션을 끈다.

## DB

- 로컬 DB: `data/umbrella.db`
- 기본 건물: `디지털관 1층`
- 기본 슬롯: 1번부터 4번까지
- 결제: 카카오페이/토스페이 모두 비활성화

DB는 서버를 처음 실행할 때 자동으로 만들어진다. 실행 중 생성된 DB와 실제 사용자 데이터는 Git에 올리지 않는다.

### 버전 마이그레이션과 감사 이력

서버를 시작하면 적용한 DB 변경의 버전, 이름, 시각을 `schema_migrations`에 기록한다. 현재
서버보다 최신인 DB는 실행을 거부하며, 각 마이그레이션은 SQLite savepoint 안에서 처리해
일부만 적용된 상태가 남지 않게 한다.

QR 승인, 대여·반납 센서 처리, 신고, 유지보수, 사용자 삭제는 `audit_events`에 추가된다.
DB 트리거가 이 테이블의 수정과 삭제를 막는다. 토큰, 비밀번호, 하드웨어 키, 신고 설명은
감사 이력에 저장하지 않는다.

관리자는 `GET /api/admin/audit-events`에서 최신 이력을 조회할 수 있다. `action`,
`resource_type`, `before_id`, `limit` 필터를 지원한다.

## API

전체 요청/응답 구조와 앱 흐름은 [API.md](API.md)에 정리되어 있다.

## 저장소에 올리지 않는 파일

- `.env`
- `.venv/`
- `__pycache__/`
- `.pytest_cache/`
- `data/*.db`
- `tests/test.db`
