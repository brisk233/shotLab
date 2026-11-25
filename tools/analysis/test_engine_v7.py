import gzip
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ANALYSIS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = ANALYSIS_DIR.parent.parent
ENGINE = ANALYSIS_DIR / "engine_v7.py"


class EngineV7RegressionTest(unittest.TestCase):

    def run_engine(self, payload, rim, *extra):
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "tracklet.json"
            source.write_text(json.dumps(payload), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ENGINE),
                    str(source),
                    rim,
                    "0.55",
                    *extra,
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )
        self.assertEqual(0, completed.returncode, completed.stderr)
        return json.loads(completed.stdout)

    def test_historical_pc_fixtures_keep_validated_counts(self):
        cases = [
            ("one_score_one_miss.json", 2, 1),
            ("two_score_three_miss.json", 5, 2),
            ("two_score_two_miss.json", 4, 2),
        ]
        for name, attempts, made in cases:
            with self.subTest(fixture=name):
                source = ANALYSIS_DIR / "fixtures" / name
                payload = json.loads(source.read_text(encoding="utf-8"))
                result = self.run_engine(payload, "0.625,0.322,0.663,0.352")

                self.assertEqual(attempts, result["attempts"])
                self.assertEqual(made, result["made"])

    def test_real_device_dynamic_rim_dump_records_one_make(self):
        source = ANALYSIS_DIR / "fixtures" / "dynamic_rim_device_v78.json.gz"
        payload = json.loads(gzip.decompress(source.read_bytes()))

        result = self.run_engine(payload, "0.705,0.41343495,0.75333333,0.43416178")

        self.assertEqual(3, result["attempts"])
        self.assertEqual(1, result["made"])
        self.assertEqual([0.0, 6.0, 12.1], [event["t"] for event in result["events"]])
        self.assertEqual(
            [True, False, False],
            [event["made"] for event in result["events"]],
        )
        self.assertEqual(
            [0.928, 7.188, 13.326],
            [event["resolve"] for event in result["events"]],
        )
        self.assertEqual(["THREE", "THREE", "THREE"], [
            event["type"] for event in result["events"]
        ])

    def test_dynamic_rim_fixture_records_two_makes(self):
        source = PROJECT_ROOT / "datasets" / "derived" / "dynamic_rim_engine_test.json"
        payload = json.loads(source.read_text(encoding="utf-8"))

        result = self.run_engine(payload, "0.25,0.28,0.35,0.32")

        self.assertEqual(2, result["attempts"])
        self.assertEqual(2, result["made"])
        self.assertEqual([0.0, 3.0], [event["t"] for event in result["events"]])
        self.assertEqual([0.367, 3.367], [event["resolve"] for event in result["events"]])

    def test_scene_cut_prevents_cross_view_tracklet_linking(self):
        first_rim = [0.29, 0.28, 0.31, 0.32]
        second_rim = [0.69, 0.28, 0.71, 0.32]
        points = [
            (0.0, 0.30, 0.58, 0),
            (0.05, 0.30, 0.48, 0),
            (0.10, 0.30, 0.36, 0),
            (0.15, 0.70, 0.28, 1),
            (0.20, 0.70, 0.36, 1),
            (0.25, 0.70, 0.48, 1),
            (0.30, 0.70, 0.58, 1),
        ]
        payload = {
            "w": 720,
            "h": 1280,
            "frames": [
                {
                    "t": t,
                    "cands": [[x - 0.01, y - 0.01, x + 0.01, y + 0.01, 0.9]],
                    "person": None,
                    "persons": [],
                    "segment": segment,
                }
                for t, x, y, segment in points
            ],
            "rimTrack": [
                {"tMs": 0, "rim": first_rim, "confidence": 0.95, "segment": 0},
                {"tMs": 100, "rim": first_rim, "confidence": 0.95, "segment": 0},
                {"tMs": 150, "rim": second_rim, "confidence": 0.95, "segment": 1},
                {"tMs": 300, "rim": second_rim, "confidence": 0.95, "segment": 1},
            ],
        }

        result = self.run_engine(payload, "0.29,0.28,0.31,0.32")

        self.assertEqual(0, result["attempts"])
        self.assertEqual([], result["events"])

    def test_shot_type_uses_rim_position_at_release_time(self):
        trajectory = [
            0.60,
            0.52,
            0.45,
            0.39,
            0.34,
            0.30,
            0.27,
            0.29,
            0.32,
            0.38,
            0.46,
            0.55,
        ]
        payload = {
            "w": 720,
            "h": 1280,
            "frames": [
                {
                    "t": index * 0.05,
                    "cands": [[0.59, y - 0.01, 0.61, y + 0.01, 0.9]],
                    "person": None,
                    "persons": [],
                }
                for index, y in enumerate(trajectory)
            ],
            "rimTrack": [
                {"tMs": 0, "rim": [0.025, 0.28, 0.175, 0.32], "confidence": 0.95},
                {"tMs": 100, "rim": [0.191, 0.28, 0.341, 0.32], "confidence": 0.95},
                {"tMs": 200, "rim": [0.358, 0.28, 0.508, 0.32], "confidence": 0.95},
                {"tMs": 250, "rim": [0.525, 0.28, 0.675, 0.32], "confidence": 0.95},
                {"tMs": 500, "rim": [0.525, 0.28, 0.675, 0.32], "confidence": 0.95},
            ],
        }

        result = self.run_engine(payload, "0.525,0.28,0.675,0.32")

        self.assertEqual(1, result["attempts"])
        self.assertEqual("THREE", result["events"][0]["type"])

    def test_rim_at_crossing_time_is_used_even_outside_track_center_window(self):
        y_values = [
            0.65,
            0.55,
            0.47,
            0.40,
            0.34,
            0.29,
            0.25,
            0.22,
            0.20,
            0.18,
            0.17,
            0.16,
            0.17,
            0.18,
            0.20,
            0.23,
            0.26,
            0.30,
            0.34,
            0.38,
            0.42,
            0.46,
            0.50,
            0.55,
            0.60,
        ]
        payload = {
            "w": 720,
            "h": 1280,
            "frames": [
                {
                    "t": index * 0.1,
                    "cands": [[
                        0.15 + 0.325 * index * 0.1 - 0.01,
                        y - 0.01,
                        0.15 + 0.325 * index * 0.1 + 0.01,
                        y + 0.01,
                        0.9,
                    ]],
                    "person": None,
                    "persons": [],
                    "segment": 0,
                }
                for index, y in enumerate(y_values)
            ],
            "rimTrack": [
                {
                    "tMs": 2_400,
                    "rim": [0.74, 0.38, 0.86, 0.42],
                    "confidence": 0.95,
                    "segment": 0,
                },
            ],
        }

        result = self.run_engine(payload, "0.14,0.38,0.26,0.42")

        self.assertEqual(1, result["attempts"])
        self.assertTrue(
            result["events"][0]["made"],
            "crossing-time rim must win over the stale fallback rim",
        )

    def test_fixed_rim_flags_disable_track_self_correction(self):
        path = [
            (0.20, 0.95),
            (0.15, 0.93),
            (0.12, 0.91),
            (0.10, 0.90),
            (0.12, 0.91),
            (0.15, 0.93),
            (0.20, 0.95),
        ]
        frames = []
        for track in range(4):
            for index, (y, x) in enumerate(path):
                frames.append(
                    {
                        "t": track * 2.0 + index * 0.1,
                        "cands": [[x - 0.01, y - 0.01, x + 0.01, y + 0.01, 0.9]],
                        "person": None,
                        "persons": [],
                        "segment": 0,
                    }
                )
        payload = {"w": 720, "h": 1280, "frames": frames, "rimTrack": []}

        result = self.run_engine(
            payload,
            "0.48,0.14,0.52,0.16",
            "false",
            "false",
        )

        self.assertEqual([0.48, 0.14, 0.52, 0.16], result["rim"])
        self.assertTrue(all(not event["made"] for event in result["events"]))

    def test_scene_cut_fallback_rim_ignores_other_camera_segments(self):
        target_rim = [0.69, 0.28, 0.71, 0.32]
        path = [0.60, 0.52, 0.45, 0.39, 0.34, 0.30, 0.27, 0.29, 0.32, 0.38, 0.46, 0.55]
        frames = []

        def add_track(segment, start_s, center_x):
            for index, y in enumerate(path):
                t = start_s + index * 0.05
                frames.append(
                    {
                        "t": t,
                        "cands": [[center_x - 0.01, y - 0.01, center_x + 0.01, y + 0.01, 0.9]],
                        "person": None,
                        "persons": [],
                        "segment": segment,
                    }
                )

        for index in range(3):
            add_track(segment=0, start_s=float(index), center_x=0.20)
        add_track(segment=1, start_s=3.0, center_x=0.70)
        payload = {"w": 720, "h": 1280, "frames": frames, "rimTrack": []}

        result = self.run_engine(payload, "0.69,0.28,0.71,0.32")
        target_events = [event for event in result["events"] if event["t"] >= 3.0]

        self.assertEqual(1, len(target_events))
        self.assertTrue(target_events[0]["made"], target_events[0])


if __name__ == "__main__":
    unittest.main()
