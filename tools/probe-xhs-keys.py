"""Check the mock diagnostic service using this emulator's volume input device.

Does not enable accessibility or require root. The service must be enabled by the
user and an XHS image page must be foreground. No picture save/upload occurs.
"""
import json
import re
import subprocess
import time


def adb(*args):
    return subprocess.check_output(['adb', 'shell', *args], stderr=subprocess.STDOUT).decode('utf-8')


def report():
    return json.loads(adb('run-as', 'com.cloudgallery.app.mock', 'cat', 'files/xhs-source-probe.json'))


def main():
    devices = adb('getevent', '-pl')
    blocks = re.split(r'add device \d+: ', devices)[1:]
    path = next((block.splitlines()[0] for block in blocks
                 if 'KEY_VOLUMEUP' in block and 'KEY_VOLUMEDOWN' in block and 'name:     "gpio"' in block), None)
    if not path or not re.fullmatch(r'/dev/input/event\d+', path):
        raise RuntimeError('No supported emulator volume input device')
    def event(code, value):
        adb(f'sendevent {path} 1 {code} {value}\nsendevent {path} 0 0 0')
    def trial(first, second, gap, hold, expected):
        before = report()['chords']
        try:
            event(first, 1); time.sleep(gap); event(second, 1); time.sleep(hold)
        finally:
            # Always release both keys, including after a failed driver write.
            event(114, 0); event(115, 0)
        time.sleep(.3)
        after = report()
        delta = after['chords'] - before
        if delta != expected:
            raise RuntimeError(f'Unexpected chord delta: {delta}, expected {expected}')
        print(json.dumps({'gap': gap, 'hold': hold, 'newChords': delta,
                          'currentPage': after['images']}, ensure_ascii=False))
    trial(115, 114, .06, .1, 1)
    trial(114, 115, .06, .65, 1)  # Hold repeats must not retrigger.
    trial(115, 114, .4, .1, 0)    # Outside the combination window.


if __name__ == '__main__':
    main()
