import json
import socket
import threading

from input_controller import InputController

HOST = "0.0.0.0"
TCP_PORT = 9000


def handle_client(conn: socket.socket, addr, controller: InputController):
    print(f"[+] Connected: {addr}")
    buffer = ""
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buffer += data.decode("utf-8")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                    controller.handle_event(event)
                except json.JSONDecodeError:
                    print(f"[!] Invalid JSON: {line!r}")
    except Exception as e:
        print(f"[!] Error from {addr}: {e}")
    finally:
        conn.close()
        print(f"[-] Disconnected: {addr}")


def main():
    controller = InputController()
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((HOST, TCP_PORT))
        srv.listen()
        print(f"Phone Pad Server listening on TCP port {TCP_PORT} ...")
        while True:
            conn, addr = srv.accept()
            t = threading.Thread(target=handle_client, args=(conn, addr, controller), daemon=True)
            t.start()


if __name__ == "__main__":
    main()
