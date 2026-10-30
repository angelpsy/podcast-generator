#!/usr/bin/env python3
"""Fail-closed UI checks for the foreground podcast app. See tools/DEVICE_UI.md."""
import argparse
import contextlib
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = 'dev.podcast.siwcspike'
ROOT = Path(__file__).resolve().parents[1]
QUEUE_ACTIONS = ('Продолжить очередь', 'Продолжить выпуск:', 'Приостановить очередь',
                 'Убрать задание:', 'Повторить загрузку очереди', 'Добавить в очередь')


def bounds(value):
    values = list(map(int, re.findall(r'-?\d+', value)))
    if len(values) != 4:
        raise RuntimeError('Invalid bounds')
    return tuple(values)


def has_labels(nodes, labels, absent=()):
    present = {n.get(k, '') for n in nodes for k in ('text', 'content-desc')}
    return all(label in present for label in labels) and not any(label in present for label in absent)


def target(nodes, label, size):
    found = []
    for n in nodes:
        if (n.get('content-desc') or n.get('text')) != label:
            continue
        if n.get('clickable') != 'true' or n.get('enabled') != 'true':
            continue
        x1, y1, x2, y2 = bounds(n['bounds'])
        if 0 <= x1 < x2 <= size[0] and 0 <= y1 < y2 <= size[1]:
            found.append((x1, y1, x2, y2))
    if len(found) != 1:
        raise RuntimeError(f'Expected exactly one visible enabled target, found {len(found)}')
    return found[0]


@contextlib.contextmanager
def exclusive(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as e:
            raise RuntimeError('Another UI check owns the device lock; no action sent') from e
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


class Device:
    def __init__(self, adb, serial=None):
        self.prefix = [adb] + (['-s', serial] if serial else ['-d'])

    def call(self, *args):
        return subprocess.check_output(self.prefix + list(args), timeout=35)

    def foreground(self):
        lines = self.call('shell', 'dumpsys', 'window').decode().splitlines()
        if not any('mCurrentFocus=' in line and PACKAGE + '/' in line for line in lines):
            raise RuntimeError('Podcast app is not foreground; no UI captured or action sent')

    def snapshot(self):
        self.foreground()
        remote = '/data/local/tmp/podcast-ui-' + uuid.uuid4().hex + '.xml'
        try:
            self.call('shell', 'uiautomator', 'dump', remote)
            raw = self.call('exec-out', 'cat', remote)
            self.foreground()
            return [dict(n.attrib) for n in ET.fromstring(raw).iter('node') if n.get('package') == PACKAGE]
        finally:
            self.call('shell', 'rm', '-f', remote)

    def size(self):
        raw = self.call('shell', 'wm', 'size').decode()
        values = re.findall(r'(\d+)x(\d+)', raw)
        if not values:
            raise RuntimeError('Unknown display size')
        return tuple(map(int, values[-1]))

    def tap(self, rect):
        self.foreground()
        x1, y1, x2, y2 = rect
        self.call('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

    def scroll(self, nodes, direction):
        rects = [bounds(n['bounds']) for n in nodes if n.get('class', '').endswith('ScrollView') and n.get('scrollable') == 'true']
        if len(rects) != 1:
            raise RuntimeError('Expected one scrollable app surface')
        x1, y1, x2, y2 = rects[0]
        x = (x1 + x2) // 2; top = y1 + (y2 - y1) // 4; bottom = y1 + 3 * (y2 - y1) // 4
        self.foreground()
        self.call('shell', 'input', 'swipe', str(x), str(bottom if direction == 'down' else top), str(x), str(top if direction == 'down' else bottom), '350')


class Check:
    def __init__(self, device, directory, timeout=12):
        self.device = device; self.directory = directory; self.timeout = timeout
        directory.mkdir(parents=True, exist_ok=True)
        self.run_id = uuid.uuid4().hex
        self.step_index = -1; self.last_capture = None

    def audit(self, **fields):
        record = {'time': dt.datetime.now(dt.timezone.utc).isoformat(), 'runId': self.run_id, 'step': self.step_index, 'snapshot': self.last_capture, **fields}
        with (self.directory / 'actions.jsonl').open('a') as out:
            out.write(json.dumps(record, ensure_ascii=False) + '\n')

    def capture(self):
        nodes = self.device.snapshot()
        safe = [{k: re.sub(r'\S+@\S+', '[email]', v) if k in ('text', 'content-desc') else v for k, v in n.items()} for n in nodes]
        self.last_capture = uuid.uuid4().hex + '.json'
        (self.directory / self.last_capture).write_text(json.dumps(safe, ensure_ascii=False, indent=2))
        return nodes

    def expect(self, labels, absent=()):
        end = time.monotonic() + self.timeout
        while True:
            nodes = self.capture()
            if has_labels(nodes, labels, absent):
                return nodes
            if time.monotonic() >= end:
                raise RuntimeError('Expected app state not reached')
            time.sleep(0.2)

    def step(self, step):
        action = step['action']
        if action == 'snapshot':
            nodes = self.expect(step.get('expect', []), step.get('expect_absent', [])); self.audit(action=action, result='verified')
            return nodes
        before = step.get('before'); expected = step.get('expect')
        if not before or not expected:
            raise RuntimeError('Every action requires before and expect labels')
        nodes = self.expect(before, step.get('before_absent', []))
        if action == 'tap':
            label = step['target']
            if label.startswith(QUEUE_ACTIONS) and step.get('queue_command') is not True:
                raise RuntimeError('Queue command requires explicit queue_command=true')
            rect = target(nodes, label, self.device.size())
            # Revalidate after a separate fresh capture; never trust a previous screen's rectangle.
            latest = self.capture()
            if not has_labels(latest, before, step.get('before_absent', [])) or target(latest, label, self.device.size()) != rect:
                raise RuntimeError('Target moved or screen changed; no tap sent')
            self.audit(action=action, target=label, bounds=rect, before=before, beforeAbsent=step.get('before_absent', []), queueCommand=step.get('queue_command') is True, result='sending')
            self.device.tap(rect)
        elif action == 'back':
            self.device.foreground(); self.audit(action=action, result='sending')
            self.device.call('shell', 'input', 'keyevent', '4')
        elif action == 'scroll':
            direction = step.get('direction')
            if direction not in ('up', 'down'):
                raise RuntimeError('Invalid scroll direction')
            for _ in range(min(10, max(1, step.get('max_swipes', 1)))):
                if has_labels(nodes, expected, step.get('expect_absent', [])):
                    break
                self.audit(action=action, direction=direction, result='sending')
                self.device.scroll(nodes, direction); nodes = self.capture()
        else:
            raise RuntimeError('Unsupported UI action')
        self.expect(expected, step.get('expect_absent', []))
        self.audit(action=action, result='verified', expect=expected, expectAbsent=step.get('expect_absent', []))

    def run(self, steps):
        for index, step in enumerate(steps):
            self.step_index = index
            try:
                self.step(step)
            except Exception as error:
                self.audit(step=index, action=step.get('action'), result='failed', error=type(error).__name__)
                raise  # Never execute the next step after a failure.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenario', type=Path, help='JSON file containing a steps array')
    parser.add_argument('--audit-dir', type=Path, required=True)
    parser.add_argument('--adb', default=os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')) + '/platform-tools/adb')
    parser.add_argument('--serial')
    args = parser.parse_args()
    with exclusive(ROOT / '.tooling/device-ui.lock'):
        Check(Device(args.adb, args.serial), args.audit_dir).run(json.loads(args.scenario.read_text())['steps'])
    print('UI scenario verified; audit: ' + str(args.audit_dir / 'actions.jsonl'))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('UI check stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
