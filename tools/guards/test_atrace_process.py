"""atrace 进程归属自测：目标进程的起点与热点不混入其他进程线程。"""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "perf/perf-atrace-split.py"
TRACE = """other-200 ( 200) [000] .... 1.000000: tracing_mark_write: B|200|activityStart
other-200 ( 200) [000] .... 1.010000: tracing_mark_write: E
app-100 ( 100) [000] .... 2.000000: tracing_mark_write: B|100|activityStart
app-100 ( 100) [000] .... 2.010000: tracing_mark_write: E
app-100 ( 100) [000] .... 2.020000: tracing_mark_write: B|100|bindApplication
app-100 ( 100) [000] .... 2.100000: tracing_mark_write: E
worker-101 ( 100) [000] .... 2.020000: tracing_mark_write: B|100|TargetWork
worker-101 ( 100) [000] .... 2.100000: tracing_mark_write: E
otherWorker-201 ( 200) [000] .... 2.020000: tracing_mark_write: B|200|OtherWork
otherWorker-201 ( 200) [000] .... 2.100000: tracing_mark_write: E
unknown-301 ( -----) [000] .... 2.020000: tracing_mark_write: B||UnknownWork
unknown-301 ( -----) [000] .... 2.100000: tracing_mark_write: E
"""


class AtraceProcessTest(unittest.TestCase):
    def output(self, trace):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.txt"
            path.write_text(trace, encoding="utf-8")
            process = subprocess.run([sys.executable, str(SCRIPT), str(path), "--pid", "100"], capture_output=True, text=True)
            self.assertEqual(0, process.returncode, process.stderr)
            return process.stdout

    def test_其他进程热点不输出到应用线程(self):
        output = self.output(TRACE)
        self.assertIn("TargetWork", output)
        self.assertNotIn("OtherWork", output)
        self.assertNotIn("UnknownWork", output)

    def test_启动起点只取目标进程(self):
        output = self.output(TRACE)
        self.assertIn("首个 slice 在 0.0 ms", output)

    def test_B进程编号补全缺失的tgid(self):
        output = self.output(TRACE.replace("worker-101 ( 100)", "worker-101 ( -----)"))
        self.assertIn("TargetWork", output)
        self.assertNotIn("OtherWork", output)


if __name__ == "__main__":
    unittest.main()
