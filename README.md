# Phone Pad

Android 폰을 Windows PC의 무선 트랙패드로 쓰는 앱 + 서버.

같은 Wi-Fi에 연결된 Android 폰이 커서 이동·클릭·스크롤·드래그·가상 데스크톱 전환 같은 제스처를 인식해 PC로 보내고, PC의 Python 서버가 그걸 실제 마우스/키보드 입력으로 바꿔 준다.

## 기능

- **1손가락**: 이동, 탭(좌클릭), 더블탭(더블클릭), 탭홀드+드래그
- **2손가락**: 탭(우클릭), 상하좌우 드래그(스크롤)
- **3손가락**: 좌우 스와이프로 가상 데스크톱 전환(Ctrl+Win+←/→)
- **서버 자동 탐색**: 같은 Wi-Fi에서 "서버 찾기"로 PC를 찾아 IP를 자동으로 채움(수동 입력도 계속 가능)
- **PIN 인증**: 서버를 켤 때마다 무작위 6자리 PIN이 생성되어 화면(콘솔/트레이/창)에 표시 — 폰 앱에 그대로 입력해야 연결됨
- **단일 클라이언트**: 새 기기가 접속하면 이전 연결은 자동으로 끊김
- **자동 재연결**, 감도 설정 화면, 서버 시스템 트레이 아이콘 + GUI 창

## 구조

```
phone_pad_app/   Android 앱 (Kotlin + Jetpack Compose, Clean Architecture)
pc_server/       Windows Python 서버 (표준 라이브러리 socket + ctypes SendInput)
```

통신은 TCP 9000(이벤트 전송 + PIN 인증 핸드셰이크), UDP 9001(커서 이동 전용, 지연에 민감해 별도 채널), UDP 9002(서버 자동 탐색) 세 포트를 쓴다.

## 실행

### PC 서버

```bash
cd pc_server
pip install -r requirements.txt   # 트레이 아이콘용 (선택 — 없어도 창 모드로 동작)
python server.py                  # 창(PIN·접속 주소·연결 상태 + 시작/정지/종료 버튼) + 트레이 아이콘, 기본 모드
```

실행하면 창(또는 콘솔)에 `[Server] PIN for this session: 483920`처럼 PIN이 뜬다 — 폰 앱 연결 화면에 이 값을 그대로 입력해야 접속된다(서버를 새로 시작할 때마다 PIN이 바뀐다).

그 외 옵션:

```bash
python server.py --no-tray        # 그래픽 UI(창+트레이) 전부 끄고 콘솔 모드 (Ctrl+C로 종료)
python server.py --no-discovery   # UDP 9002 자동 탐색 응답 끄기 (수동 IP 입력만)
python server.py --pin 123456     # 무작위 생성 대신 고정 PIN 사용
python server.py --no-auth        # PIN 인증 완전히 끄기 (개발/디버깅용)
```

단일 실행 파일로 빌드하려면 `./build_exe.ps1` → `dist/PhonePadServer.exe`.

### Android 앱

Android Studio에서 `phone_pad_app/` 열고 빌드 후 기기에 설치. minSdk 26(Android 8.0) 이상.

### 방화벽 (Windows)

서버와 폰이 같은 Wi-Fi인데도 연결이 안 되면, Windows 방화벽이 현재 네트워크 프로필(주로 Private)에서 python.exe의 인바운드 연결을 막고 있을 수 있다. 관리자 권한 PowerShell에서:

```powershell
New-NetFirewallRule -DisplayName "Phone Pad (Private)" -Direction Inbound -Program "C:\Python313\python.exe" -Action Allow -Profile Private,Domain -Protocol TCP
New-NetFirewallRule -DisplayName "Phone Pad (Private)" -Direction Inbound -Program "C:\Python313\python.exe" -Action Allow -Profile Private,Domain -Protocol UDP
```

(python 경로는 환경에 맞게 바꿀 것 — `where.exe python`으로 확인)

## 개발 문서

프로젝트 구조·통신 프로토콜 상세 스펙·제스처 판정 로직·로드맵·코딩 컨벤션은 [`AGENTS.md`](AGENTS.md) 참조.
