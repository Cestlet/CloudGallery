"""Read-only snapshots and volume input for the mock integration test device."""
import json
import sqlite3
import subprocess
import tempfile
import time
from pathlib import Path


def tasks():
    base = Path('app/build/tmp'); base.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='quick-snapshot-', dir=base))
    for suffix in ['', '-wal']:
        result = subprocess.run(['adb', 'exec-out', 'run-as', 'com.cloudgallery.app.mock', 'cat',
                                 'databases/quick_import.db' + suffix], capture_output=True)
        if result.returncode == 0:
            (directory / ('quick.db' + suffix)).write_bytes(result.stdout)
    with sqlite3.connect(directory / 'quick.db') as database:
        database.row_factory = sqlite3.Row
        return [dict(row) for row in database.execute('SELECT id,page,total,status,detail,destination,saveLocal,localUri,extension,length(link) AS linkLength FROM tasks ORDER BY created DESC LIMIT 20')]


def chord():
    try:
        subprocess.check_call(['adb','shell','sendevent /dev/input/event3 1 115 1\nsendevent /dev/input/event3 0 0 0\nsendevent /dev/input/event3 1 114 1\nsendevent /dev/input/event3 0 0 0'])
        time.sleep(.15)
    finally:
        subprocess.check_call(['adb','shell','sendevent /dev/input/event3 1 114 0\nsendevent /dev/input/event3 1 115 0\nsendevent /dev/input/event3 0 0 0'])


if __name__ == '__main__':
    print(json.dumps(tasks()[:3], ensure_ascii=False, indent=2))
