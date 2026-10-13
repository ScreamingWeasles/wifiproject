"""Opt-in UDP echo server for Screaming Weasels v0.7.2.
Run only on a network you control: python udp_echo_server.py --bind 0.0.0.0 --port 50000
Stop with Ctrl+C. Windows Firewall may need an inbound UDP rule for port 50000.
"""
import argparse
import socket

parser = argparse.ArgumentParser()
parser.add_argument('--bind', default='127.0.0.1', help='Use 0.0.0.0 to accept LAN packets')
parser.add_argument('--port', type=int, default=50000)
args = parser.parse_args()
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind((args.bind, args.port))
    print(f'Consenting UDP echo server listening at {args.bind}:{args.port} (Ctrl+C to stop)', flush=True)
    try:
        while True:
            packet, client = sock.recvfrom(2048)
            sock.sendto(packet, client)
    except KeyboardInterrupt:
        print('Stopped.')
