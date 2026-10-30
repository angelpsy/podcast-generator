import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('device_ui', Path(__file__).with_name('device-ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


def node(label, rect='[30,300][700,430]', clickable=True):
    return {'text': label, 'bounds': rect, 'clickable': str(clickable).lower(), 'enabled': 'true'}


class FakeDevice:
    def __init__(self, frames):
        self.frames = list(frames); self.taps = []

    def snapshot(self):
        return self.frames.pop(0) if len(self.frames) > 1 else self.frames[0]

    def size(self):
        return 1080, 2410

    def tap(self, rect):
        self.taps.append(rect)


class UiSafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.directory = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_check(self, device, steps):
        ui.Check(device, self.directory, timeout=0).run(steps)

    def test_queue_control_needs_explicit_intent(self):
        frame = [node('Queue', clickable=False), node('Продолжить очередь')]
        device = FakeDevice([frame])
        with self.assertRaisesRegex(RuntimeError, 'explicit'):
            self.run_check(device, [{'action': 'tap', 'target': 'Продолжить очередь', 'before': ['Queue'], 'expect': ['Running']}])
        self.assertEqual([], device.taps)

    def test_moving_target_is_never_tapped(self):
        device = FakeDevice([[node('Screen', clickable=False), node('Open')], [node('Screen', clickable=False), node('Open', '[30,600][700,730]')]])
        with self.assertRaisesRegex(RuntimeError, 'moved'):
            self.run_check(device, [{'action': 'tap', 'target': 'Open', 'before': ['Screen'], 'expect': ['Next']}])
        self.assertEqual([], device.taps)

    def test_failed_postcondition_aborts_the_rest_of_the_scenario(self):
        frame = [node('Screen', clickable=False), node('Open')]
        device = FakeDevice([frame])
        step = {'action': 'tap', 'target': 'Open', 'before': ['Screen'], 'expect': ['Next']}
        with self.assertRaisesRegex(RuntimeError, 'not reached'):
            self.run_check(device, [step, step])
        self.assertEqual(1, len(device.taps))
        records = [json.loads(x) for x in (self.directory / 'actions.jsonl').read_text().splitlines()]
        self.assertEqual('failed', records[-1]['result'])
        self.assertEqual(0, records[-1]['step'])

    def test_success_requires_a_fresh_matching_screen_and_records_coordinates(self):
        before = [node('Queue', clickable=False), node('Продолжить очередь')]
        device = FakeDevice([before, before, [node('Running', clickable=False)]])
        self.run_check(device, [{'action': 'tap', 'target': 'Продолжить очередь', 'before': ['Queue'], 'expect': ['Running'], 'queue_command': True}])
        records = [json.loads(x) for x in (self.directory / 'actions.jsonl').read_text().splitlines()]
        self.assertEqual('verified', records[-1]['result'])
        self.assertEqual([30, 300, 700, 430], records[0]['bounds'])
        self.assertTrue(records[0]['queueCommand']); self.assertIn('time', records[0])
        self.assertTrue((self.directory / records[-1]['snapshot']).exists())

    def test_missing_screen_expectations_abort_before_any_input(self):
        device = FakeDevice([[node('Open')]])
        with self.assertRaisesRegex(RuntimeError, 'requires'):
            self.run_check(device, [{'action': 'tap', 'target': 'Open'}])
        self.assertEqual([], device.taps)

    def test_ambiguous_disabled_and_offscreen_targets_are_rejected(self):
        for nodes in [[node('Open'), node('Open')], [node('Open', '[-30,10][700,430]')], [dict(node('Open'), enabled='false')]]:
            with self.assertRaises(RuntimeError):
                ui.target(nodes, 'Open', (1080, 2410))
        self.assertEqual((-30, 10, 700, 430), ui.bounds('[-30,10][700,430]'))

    def test_parallel_ui_invocation_cannot_get_the_lock(self):
        lock = self.directory / 'device.lock'
        with ui.exclusive(lock):
            with self.assertRaisesRegex(RuntimeError, 'lock'):
                with ui.exclusive(lock):
                    self.fail('Second UI runner acquired the lock')

    def test_expected_header_is_not_success_while_the_error_is_still_present(self):
        device = FakeDevice([[node('Queue'), node('Storage error')]])
        with self.assertRaisesRegex(RuntimeError, 'not reached'):
            self.run_check(device, [{'action': 'snapshot', 'expect': ['Queue'], 'expect_absent': ['Storage error']}])


if __name__ == '__main__':
    unittest.main()
