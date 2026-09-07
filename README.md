# 우산있나우

QR 코드로 캠퍼스 우산을 대여·반납하는 무인 서비스입니다. Android 앱, FastAPI 서버, Raspberry Pi 기반 우산 보관 장치가 REST API로 연동됩니다.

## 프로젝트 정보

- 기간: 2026.03.02 ~ 2026.06.09
- 형태: 3인 팀 캡스톤디자인 / 졸업작품
- 주요 구성: Android · FastAPI · SQLite · Raspberry Pi

## 구조

```text
Android App  <-->  FastAPI Server  <-->  Raspberry Pi / Umbrella Station
```

앱과 하드웨어는 서로 직접 통신하지 않고 항상 서버를 거칩니다.

대여 흐름:
1. 앱이 슬롯을 선택하면 서버가 짧은 유효시간의 일회용 QR 토큰을 발급합니다.
2. Raspberry Pi 카메라가 QR을 스캔해 서버에 검증을 요청합니다.
3. 검증되면 대상 슬롯의 잠금장치가 열립니다.
4. 우산 제거를 IR 센서가 감지해 서버로 전달합니다.
5. 서버가 대여 상태를 변경하고 앱이 갱신된 상태를 확인합니다.

반납도 같은 흐름으로 진행되며 센서 상태가 반대로 처리됩니다.

> Raspberry Pi 제어 프로그램은 팀원이 담당했으며 본 저장소에는 포함하지 않았습니다.

## 담당 역할

### 본인 — Android App / FastAPI Server

**Android**
- Android 앱 개발
- 회원가입·로그인, 대여·반납 등 주요 사용자 흐름 구현
- FastAPI 서버 API 연동

**Backend**
- FastAPI 서버 핵심 기능 개발 및 개선
- SQLite 기반 데이터 저장 및 상태 관리
- 회원가입·로그인 및 세션 토큰 기반 인증 처리
- 우산 대여·반납 상태 처리
- QR 토큰 발급·검증
- 슬롯 상태 및 센서 이벤트 API 구현
- Android 앱 ↔ 서버 API 연동
- 서버 ↔ Raspberry Pi 통신 API 연동
- API 테스트, 오류 수정 및 디버깅
- GitHub Actions 기반 서버 테스트 자동화

## 팀 구성

- 본인: Android 앱 및 FastAPI 서버
- 팀원 1: 우산 기판 제작, Raspberry Pi 및 하드웨어 연결
- 팀원 2: Raspberry Pi 제어 프로그램, QR 카메라·IR 센서·솔레노이드 제어

## 기술 스택

- App: Kotlin, Retrofit2, ZXing
- Server: Python, FastAPI, SQLite, 세션 토큰 인증
- Hardware: Raspberry Pi, Picamera2, pyzbar, gpiod

## 폴더

```text
umbrellaisnow/
├── app/        Android 앱
├── server/     FastAPI 서버
├── .github/    GitHub Actions
└── README.md
```

app 쪽 주요 파일:
- `MainActivity.kt` - 홈 화면
- `QrActivity.kt` - QR 생성, 대여/반납 처리, 폴링
- `StatusActivity.kt` - 대여 현황, 반납 기한 카운트다운
- `AdminActivity.kt` - 관리자 페이지
- `AppSession.kt` - 로그인 세션, 대여 상태 관리
- `UmbrellaApiService.kt` / `UmbrellaDto.kt` - API 정의

server 쪽 주요 파일:
- `main.py` - API 라우트와 앱 설정
- `services.py` - 대여·반납 등 비즈니스 로직
- `repository.py` - DB 조회와 저장
- `schemas.py` - 요청·응답 구조
- `security.py` - 비밀번호 해시와 세션 토큰 처리
- `database.py` - SQLite 연결과 초기화

## 주요 기능

- 회원가입·로그인
- 대여·반납 상태 관리
- QR 토큰 발급 및 검증
- 슬롯 상태 및 센서 이벤트 처리
- 보증금 포인트 처리
- 대여 기록 조회
- 고장 신고 및 관리자 기능
- 요청 추적과 일관된 오류 응답
- DB 스키마 버전 관리와 감사 이력

## 테스트와 CI

서버는 pytest 기반 테스트를 사용합니다.

```bash
cd server
pip install -r requirements-dev.txt
python -m pytest -q
```

GitHub Actions에서 Ubuntu와 Windows 환경으로 서버 테스트를 자동 실행하며, 현재 공개 저장소의 최신 CI에서 **16개 테스트가 통과**했습니다.

## 겪었던 문제

**서버 응답 구조 문제**  
대여 상세 API 응답 구조와 앱 DTO 구조가 달라 위치 정보가 정상적으로 파싱되지 않았습니다. 실제 응답 구조에 맞춰 DTO를 수정해 해결했습니다.

**반납 QR 409 에러**  
반납 시도가 중간에 끊긴 뒤 재시도할 때 충돌 응답이 발생하는 경우가 있었습니다. 진행 중인 대여를 다시 조회해 재시도할 수 있도록 앱과 서버의 처리 흐름을 조정했습니다.

**QR 토큰 길이 불일치**  
서버의 `secrets.token_urlsafe(32)` 결과를 하드웨어에서 32자로 가정해 정상 QR이 차단되는 문제가 있었습니다. Base64URL 인코딩 후 실제 토큰 길이에 맞게 검증 조건을 수정했습니다.

## 실행

서버:

```bash
cd server
pip install -r requirements.txt
python -m uvicorn app.main:app --reload
```

실행 후 `http://127.0.0.1:8000/docs`에서 API 문서를 확인할 수 있습니다.

Android 앱은 서버 주소를 설정한 뒤 빌드하여 사용합니다.

## 향후 개선

- 다중 거점 지원
- 실제 결제 연동
- 푸시 알림
- 운영 환경용 DB 전환
