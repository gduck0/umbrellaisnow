# 우산있나우

QR 코드로 캠퍼스 우산을 대여/반납하는 무인 서비스. 앱, 서버, 라즈베리파이가 REST API로 연동된다.

## 구조

```
Android App  <-->  FastAPI Server  <-->  Raspberry Pi
```

앱과 하드웨어는 서로 직접 통신하지 않고 항상 서버를 거친다.

대여 흐름:
1. 앱이 슬롯을 선택하면 서버가 60초짜리 일회용 QR 토큰을 발급한다
2. 라즈베리파이 카메라가 QR을 스캔해서 서버에 검증을 요청한다
3. 검증되면 솔레노이드가 열린다
4. 우산을 빼면 IR 센서가 감지해서 서버로 전송한다
5. 서버가 대여 상태를 바꾸고, 앱이 그걸 확인해서 화면을 넘긴다

반납도 같은 흐름이고 센서 값만 반대다.

## 기술 스택

- App: Kotlin, Retrofit2, ZXing
- Server: Python, FastAPI, SQLite, JWT
- Hardware: Raspberry Pi, picamera2, pyzbar, gpiod

## 폴더

```
umbrella-now/
├── app/        Android 앱
├── server/     FastAPI 서버
└── hardware/   라즈베리파이 코드
```

app 쪽 주요 파일:
- `MainActivity.kt` - 홈 화면
- `QrActivity.kt` - QR 생성, 대여/반납 처리, 폴링
- `StatusActivity.kt` - 대여 현황, 반납 기한 카운트다운
- `AdminActivity.kt` - 관리자 페이지 (슬롯 점검, 사용자 관리)
- `AppSession.kt` - 로그인 세션, 대여 상태 전역 관리
- `UmbrellaApiService.kt` / `UmbrellaDto.kt` - API 정의

server 쪽 주요 파일:
- `main.py` - 라우트
- `services.py` - 비즈니스 로직
- `repository.py` - DB 쿼리

## 기능

대여/반납, 보증금 포인트 충전·환불, 대여 기록 조회, 슬롯 고장 신고, 대여 직후 5분 내 불량 신고(직전 사용자에게 패널티), 앱 내 관리자 페이지.

## 겪었던 문제

**서버 응답 구조 문제**
대여 상세 API가 slot 정보를 중첩 객체로 안 주고 평탄화해서 같은 레벨로 내려준다. 처음엔 중첩 구조로 가정하고 파싱해서 위치 정보가 계속 null이었다. DTO를 응답 구조에 맞게 다시 짜서 해결했다.

**반납 QR 409 에러**
반납 시도가 중간에 끊기면 다음 시도에서 409가 났다. rental_id를 안 보내고 재요청하면 서버가 현재 진행 중인 대여를 알아서 찾으니까, 409 받으면 그 방식으로 한 번 더 시도하게 했다.

**QR 토큰 길이**
서버 토큰은 `secrets.token_urlsafe(32)`로 만드는데 이게 32바이트를 Base64URL로 인코딩한 거라 실제 문자열은 43자다. 하드웨어 쪽에서 32자로 체크하고 있어서 정상 토큰을 다 걸러내고 있었다. 43으로 고쳐서 해결했다.

## 실행

서버:
```
cd server
pip install -r requirements.txt
uvicorn app.main:app --reload
```
`/docs`에서 API 확인 가능.

앱: `AppSession.kt`의 `BASE_URL`을 서버 주소로 바꾸고 빌드.

## 팀

- 앱:
- 서버:
- 하드웨어:

## 남은 것

다중 거점 지원, 실제 결제 연동, 푸시 알림, PostgreSQL 전환.
