# 우산있나우 서버

앱과 라즈베리파이 사이에서 대여 상태, QR 토큰, 슬롯, 지갑 정보를 관리하는 FastAPI 서버.
로컬에서는 SQLite를 사용하고 앱과 하드웨어는 REST API로 서버에 연결된다.

## 역할

- 회원가입과 로그인
- 대여/반납 QR 발급과 검증
- 슬롯 상태와 센서 이벤트 처리
- 보증금 포인트 충전·환불
- 대여 기록과 고장 신고 관리

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
- `security.py` - 비밀번호와 JWT 처리
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
- 건물 목록: http://127.0.0.1:8000/api/locations

## 테스트

```powershell
pip install -r requirements-dev.txt
python -m pytest -q
```

GitHub에 코드를 올리면 같은 테스트가 자동으로 실행된다.

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

## DB

- 로컬 DB: `data/umbrella.db`
- 기본 건물: `디지털관 1층`
- 기본 슬롯: 1번부터 4번까지
- 결제: 카카오페이/토스페이 모두 비활성화

DB는 서버를 처음 실행할 때 자동으로 만들어진다. 실행 중 생성된 DB와 실제 사용자 데이터는 Git에 올리지 않는다.

## API

전체 요청/응답 구조와 앱 흐름은 [API.md](API.md)에 정리되어 있다.

## 저장소에 올리지 않는 파일

- `.env`
- `.venv/`
- `__pycache__/`
- `.pytest_cache/`
- `data/*.db`
- `tests/test.db`
