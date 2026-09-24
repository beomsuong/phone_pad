## 서버 실행

```bash
cd pc_server
python server.py                  # 창(PIN·접속 주소·연결 상태 + 시작/정지/종료 버튼) + 트레이 아이콘, 기본 모드
python server.py --no-tray        # 그래픽 UI(창+트레이) 전부 끄고 콘솔 모드 (Ctrl+C로 종료)
python server.py --no-discovery   # UDP 9002 자동 탐색 응답 끄기 (수동 IP 입력만)
python server.py --pin 123456     # 무작위 생성 대신 고정 PIN 사용
python server.py --no-auth        # PIN 인증 완전히 끄기 (개발/디버깅용)
```

실행하면 콘솔(또는 창)에 `[Server] PIN for this session: 483920`처럼 PIN이 뜬다 — 폰 앱 연결 화면에 이 값을 그대로 입력해야 접속된다(서버를 새로 시작할 때마다 PIN이 바뀐다).

## 아래 명령어로 방화벽을 뚫어줘야함

New-NetFirewallRule -DisplayName "Phone Pad (Private)" -Direction Inbound -Program "C:\Python313\python.exe" -Action Allow -Profile Private,Domain -Protocol TCP
New-NetFirewallRule -DisplayName "Phone Pad (Private)" -Direction Inbound -Program "C:\Python313\python.exe" -Action Allow -Profile Private,Domain -Protocol UDP
