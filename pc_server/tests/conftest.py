import os
import sys

# pc_server/ 를 import 경로에 추가 (server.py / input_controller.py 는 패키지가 아닌 평면 모듈)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
