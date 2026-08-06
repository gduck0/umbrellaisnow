# 우산있나우 백엔드 실행 안내

이 폴더만 Windows로 옮겨도 실행할 수 있습니다. 별도 DB 서버는 필요 없으며,
서버를 처음 실행하면 로컬 SQLite 파일인 `data/umbrella.db`가 자동으로 생성됩니다.

## Windows 실행

PowerShell:

```powershell
cd "C:\경로\umbrella-server"

py -3 -m venv .venv
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1

pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

CMD:

```bat
cd "C:\경로\umbrella-server"

py -3 -m venv .venv
.venv\Scripts\activate.bat

pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

실행 후 확인:

- Swagger: http://127.0.0.1:8000/docs
- Health: http://127.0.0.1:8000/health
- 건물 목록: http://127.0.0.1:8000/api/locations

## 테스트 계정

테스트 계정 비밀번호는 저장소에 포함하지 않습니다. 계정을 만들기 전에 현재
PowerShell 세션에 비밀번호를 환경 변수로 설정하세요.

```powershell
$env:UMBRELLA_TEST_PASSWORD = "직접 정한 임시 비밀번호"
python scripts\seed_test_account.py
```

로그인 ID는 `admin`이며, 환경 변수로 지정한 비밀번호를 사용합니다.

로그인 API:

```http
POST /api/auth/login
Content-Type: application/json
```

```json
{
  "email": "admin",
  "password": "<환경 변수로 지정한 값>",
  "remember_me": true
}
```

응답의 `access_token`을 앱에서 저장하고, 이후 사용자 API에 붙이면 됩니다.

```http
Authorization: Bearer <access_token>
```

## DB

- 로컬 DB 파일: `data/umbrella.db`
- 기본 건물: `디지털관 1층`
- 기본 슬롯: 1번부터 4번까지
- 결제: 카카오페이/토스페이 모두 비활성화

DB 파일은 실행 환경마다 생성되며 Git에는 포함하지 않습니다. 기존 DB가 필요하면
저장소 외부의 안전한 위치에 별도로 백업하세요.

## 압축할 때 포함/제외

포함해야 하는 것:

- `app/`
- `scripts/`
- `requirements.txt`
- `API.md`
- `README.md`

제외해야 하는 것:

- `.env`
- `.venv/`
- `__pycache__/`
- `.pytest_cache/`
- `data/*.db`
- `tests/test.db`
