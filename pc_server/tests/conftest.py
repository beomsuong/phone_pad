import os
import sys

import pytest

# pc_server/ 를 import 경로에 추가 (server.py / input_controller.py 는 패키지가 아닌 평면 모듈)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import single_instance  # noqa: E402  (위 sys.path 조작 이후에만 import 가능)


@pytest.fixture(autouse=True)
def isolated_single_instance_name(monkeypatch):
    """테스트가 실제 서버의 mutex 이름(`Local\\PhonePadServer`)을 건드리지 않게 한다.

    `server.main()` 을 호출하는 기존 테스트들은 진짜 가드를 그대로 통과하는데,
    이름이 실제 서버와 같으면 **트레이에 서버가 떠 있는 동안 pytest 가 깨진다**
    (main() 이 종료 코드 2를 반환하므로). 가드 자체는 죽이지 않고 이름만 이
    pytest 프로세스 전용으로 바꾼다 - 실제 CreateMutexW 경로는 그대로 실행된다.

    `single_instance._HELD` 덕분에 같은 프로세스 안의 두 번째 호출은 멱등이라,
    `main()` 을 여러 번 부르는 테스트들도 영향을 받지 않는다.
    """
    unique = "Local\\PhonePadServer-pytest-%d" % os.getpid()
    monkeypatch.setattr(single_instance, "MUTEX_NAME", unique)
    yield
    single_instance.release(unique)
