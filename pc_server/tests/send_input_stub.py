"""테스트용 `SendInput` 스텁 (테스트 헬퍼 - 수집 대상 아님).

실제 `SendInput` 은 **입력 큐에 넣은 이벤트 수**를 반환한다. `input_controller`
가 이 반환값을 검사하게 된 뒤로는 `MagicMock` 의 기본 반환값(= `MagicMock`)이
곧 "하나도 주입되지 않았다"로 읽히므로, 모든 patch 지점이 이 모듈의 스텁으로
실제 계약을 흉내내야 한다.

사용:
    with patch_send_input() as mock_send:      # 성공(요청 개수 그대로 주입)
    with patch_send_input(injected_none) as m: # 실패(입력 데스크톱 차단)
"""
from unittest.mock import patch

SEND_INPUT_TARGET = "input_controller.ctypes.windll.user32.SendInput"


def injected_all(count, inputs, size):
    """성공 경로: 요청한 개수를 전부 주입했다고 보고한다."""
    return count


def injected_none(count, inputs, size):
    """실패 경로: 하나도 주입하지 못했다(잠금 화면 / UAC 보안 데스크톱 등)."""
    return 0


def injected_partial(n):
    """부분 실패 경로: `n` 개만 주입했다고 보고하는 스텁을 만든다."""
    def _stub(count, inputs, size):
        return min(n, count)
    return _stub


def patch_send_input(side_effect=injected_all):
    """`ctypes.windll.user32.SendInput` 을 계약대로 동작하는 mock 으로 교체한다.

    patch 경로는 기존과 동일(`input_controller.ctypes.windll.user32.SendInput`).
    """
    return patch(SEND_INPUT_TARGET, side_effect=side_effect)
