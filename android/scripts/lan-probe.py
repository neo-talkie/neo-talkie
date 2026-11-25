#!/usr/bin/env python3
"""Explicit LAN-only device diagnostic. No microphone, credentials or cloud services.

Example: python3 android/scripts/lan-probe.py --serial YOUR_SERIAL --host YOUR_COMPUTER_IP
Run before/after moving the device; keep the same Wi-Fi/AP and test parameters.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
from adb_path import default_adb
import shlex
import subprocess
import threading
import time
import uuid


def private_ipv4(value):
    address = ipaddress.ip_address(value)
    if address.version != 4 or not address.is_private or address.is_loopback or address.is_unspecified:
        raise argparse.ArgumentTypeError('Use a private LAN IPv4 address')
    return str(address)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Wi-Fi ADB IPv4:port')
    parser.add_argument('--host', required=True, type=private_ipv4, help='This Mac LAN IPv4')
    parser.add_argument('--timeout', type=int, default=20)
    parser.add_argument('--adb', default=default_adb())
    args = parser.parse_args()
    if not 5 <= args.timeout <= 30:
        parser.error('--timeout must be between 5 and 30 seconds')
    device = private_ipv4(args.serial.split(':')[0])
    adb = [args.adb, '-s', args.serial]
    payload = bytes(range(256)) * 512
    server_records = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def allowed(self):
            if self.client_address[0] != device or self.path != '/probe':
                self.send_error(403)
                return False
            self.connection.settimeout(args.timeout + 2)
            return True

        def respond(self, body):
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.allowed():
                try:
                    self.respond(payload)
                except OSError:
                    pass

        def do_POST(self):
            if not self.allowed():
                return
            if self.headers.get('Content-Length') != str(len(payload)):
                self.send_error(400)
                return
            began, received = time.monotonic(), 0
            try:
                while received < len(payload):
                    block = self.rfile.read(min(16384, len(payload) - received))
                    if not block:
                        break
                    received += len(block)
                if received == len(payload):
                    self.respond(b'OK')
                else:
                    self.send_error(400)
            except OSError:
                pass
            finally:
                server_records.append({'uploadBytesReceived': received,
                                       'uploadReadSeconds': round(time.monotonic() - began, 4)})

    server = ThreadingHTTPServer((args.host, 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f'http://{args.host}:{server.server_port}/probe'
    remote = f'/data/local/tmp/neotalkie-lan-probe-{uuid.uuid4().hex}.bin'

    def shell(command, timeout=10):
        return subprocess.run([*adb, 'shell', command], capture_output=True,
                              text=True, timeout=timeout)

    def transfer(upload=False):
        fmt = ('\n{"connectSeconds":%{time_connect},"totalSeconds":%{time_total},'
               '"downloadBytes":%{size_download},"uploadBytes":%{size_upload},'
               '"downloadBytesPerSecond":%{speed_download},'
               '"uploadBytesPerSecond":%{speed_upload},"httpCode":"%{http_code}"}')
        command = ['curl', '--max-time', str(args.timeout), '-sS', '-o', '/dev/null', '-w', fmt]
        if upload:
            # Exclude the old curl/HTTP 1.0 Expect:100-continue delay from bandwidth.
            command += ['-H', 'Expect:', '--data-binary', '@' + remote]
        # Legacy Android shell doesn't propagate the child exit code through adb.
        script = shlex.join(command + [url]) + '; probe_status=$?; echo; echo __NEOTALKIE_EXIT:$probe_status'
        result = shell(script, args.timeout + 10)
        lines = result.stdout.strip().splitlines()
        report = next((json.loads(line) for line in lines if line.startswith('{')), {})
        code = next((int(line.split(':')[1]) for line in lines if line.startswith('__NEOTALKIE_EXIT:')), -1)
        report['exitCode'] = code
        count = 'uploadBytes' if upload else 'downloadBytes'
        report['completed'] = report.get('httpCode') == '200' and code == 0 and report.get(count) == len(payload)
        return report

    try:
        report = {'bytesPerTransfer': len(payload), 'download': transfer()}
        result = shell(f'dd if=/dev/zero of={remote} bs=1024 count=128')
        if result.returncode:
            raise RuntimeError('Unable to create temporary zero-filled probe payload')
        report['upload'] = transfer(True)
        report['server'] = server_records
        print(json.dumps(report, ensure_ascii=False, indent=2))
    finally:
        try:
            shell('rm -f ' + remote)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    main()
