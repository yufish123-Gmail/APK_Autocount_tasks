import json
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "android/app/src/main/python"))
import android_runtime as runtime
import mgame_core as core


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.bridge = MagicMock()
        self.bridge.isActive.return_value = True
        self.phone = runtime.AndroidPhone(self.bridge)
        self.clock = runtime.RuntimeClock(self.bridge)

    def test_cancel_interrupts_long_game_wait(self):
        self.bridge.isActive.return_value = False
        before = time.monotonic()
        with self.assertRaises(runtime.SessionEnded):
            self.clock.sleep(62)
        self.assertLess(time.monotonic() - before, 0.2)

    def test_capture_retry_preserves_foreground_guard(self):
        self.bridge.foreground.return_value = json.dumps([core.MGAME, core.GAME_ACTIVITY])
        with patch.object(core, "time", self.clock), patch.object(self.phone, "capture", side_effect=[
            core.CaptureUnavailable("transient"), "image"
        ]), patch.object(self.clock, "sleep"):
            self.assertEqual(self.phone.frame(), "image")

    def test_no_tap_when_other_app_is_foreground(self):
        self.bridge.foreground.return_value = json.dumps(["com.android.settings", "Settings"])
        with patch.object(core, "time", self.clock):
            with self.assertRaises(core.ForegroundChanged):
                self.phone.tap((400, 800))
        self.bridge.tap.assert_not_called()

    def test_shell_commands_are_not_executed(self):
        with self.assertRaises(core.Stop):
            self.phone.adb_call("shell", "am", "force-stop", core.MGAME)
        self.bridge.assert_not_called()

    def test_known_package_query_maps_to_native_install_check(self):
        self.bridge.installed.side_effect = lambda name: name == "com.baidu.BaiduMap"
        self.assertEqual(self.phone.adb_call("shell", "pm", "list", "packages"),
                         b"package:com.baidu.BaiduMap")

    def test_recovery_uses_page_state_instead_of_force_stop(self):
        self.bridge.foreground.return_value = json.dumps([core.MGAME, "UnknownActivity"])
        vision = MagicMock()
        vision.identify.return_value = ("list", None)
        with patch.object(core, "time", self.clock), patch.object(self.phone, "frame", return_value="image"), \
                patch.object(self.clock, "sleep"), patch.object(core, "game60_panel") as reopen:
            self.phone.restart_mgame(vision, MagicMock())
        self.bridge.openMeituan.assert_called_once()
        self.bridge.globalAction.assert_called_once_with("back")
        reopen.assert_called_once()

    def test_capture_rejects_unconfigured_geometry(self):
        import numpy as np
        with patch.object(core, "time", self.clock), patch.object(core, "decode", return_value=np.zeros((1200, 540, 3))):
            with self.assertRaises(core.CaptureUnavailable):
                self.phone.capture()


if __name__ == "__main__":
    unittest.main()
