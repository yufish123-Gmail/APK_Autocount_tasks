"""Android adapter for the complete upstream task engine."""
import json
from pathlib import Path
import time as system_time

import cv2
import mgame_core as core
import templates


class SessionEnded(BaseException):
    pass


class RuntimeClock:
    def __init__(self, bridge):
        self.bridge = bridge

    def monotonic(self):
        self.check()
        return system_time.monotonic()

    def check(self):
        if not self.bridge.isActive():
            raise SessionEnded()

    def sleep(self, seconds):
        deadline = system_time.monotonic() + max(0, seconds)
        while system_time.monotonic() < deadline:
            self.check()
            system_time.sleep(min(0.1, max(0, deadline - system_time.monotonic())))
        self.check()


class AndroidPhone:
    def __init__(self, bridge):
        self.bridge = bridge

    def foreground_component(self):
        core.time.check()
        return list(json.loads(str(self.bridge.foreground())))

    def foreground(self):
        return self.foreground_component()[0]

    def require_mgame(self):
        if self.foreground() != core.MGAME:
            raise core.ForegroundChanged("请把美团放在前台。")

    def capture(self, *, calibrated=True):
        core.time.check()
        try:
            image = core.decode(bytes(self.bridge.capture()))
        except Exception as exc:
            raise core.CaptureUnavailable("Android 截图失败：" + str(exc)) from exc
        if calibrated and image.shape[1::-1] != core.SIZE:
            raise core.CaptureUnavailable("请使用 1080×2400 竖屏并核对显示设置。")
        return image

    def frame(self):
        for attempt in range(4):
            self.require_mgame()
            try:
                image = self.capture()
            except core.CaptureUnavailable:
                if attempt == 3:
                    raise
                core.time.sleep(0.4)
                continue
            self.require_mgame()
            return image

    def tap(self, point):
        self.require_mgame()
        self.bridge.tap(int(point[0]), int(point[1]))

    def scroll(self, down):
        self.require_mgame()
        start, end = (1650, 990) if down else (990, 1650)
        self.bridge.swipe(750, start, 750, end, 800)

    def back(self):
        self.require_mgame()
        self.bridge.globalAction("back")

    def home(self, *, recovery=False):
        if not recovery:
            self.require_mgame()
        launcher = str(self.bridge.launcherPackage())
        if not launcher:
            raise core.Stop("无法确认默认桌面。")
        self.bridge.globalAction("home")
        return launcher

    def return_to_mgame(self):
        self.bridge.openMeituan()

    def recover_mgame_main(self, vision, trace):
        self.return_to_mgame()
        deadline = core.time.monotonic() + 20
        for _ in range(8):
            if core.time.monotonic() >= deadline:
                break
            if self.foreground() != core.MGAME:
                core.time.sleep(0.6)
                continue
            image = self.frame()
            state, _ = vision.identify(image)
            if state in {"profile", "coins", "list", "task", "task_unavailable"}:
                trace.record("recovery_page_confirmed", state=state)
                return
            self.back()
            core.time.sleep(0.8)
        raise core.Stop("请手动打开美团「我的」或「加次数」，然后点继续。")

    def restart_mgame(self, vision, trace):
        trace.record("recovery_page_refresh")
        self.recover_mgame_main(vision, trace)
        image = self.frame()
        state, _ = vision.identify(image)
        if state in {"list", "task", "task_unavailable"}:
            self.back()
            core.time.sleep(0.8)
            core.game60_panel(self, vision, trace)

    def wait_for_usb(self, timeout=45):
        raise core.Stop("无障碍连接中断，请重新开启服务并继续。")

    def adb_call(self, *arguments):
        # Task rules keep their upstream call sites. Only these operations are mapped.
        # No shell process, ADB server or elevated privilege is used.
        if arguments[:3] == ("shell", "pm", "list"):
            packages = set(core.APP_JUMP_TARGETS.values()) | {core.DOUYIN_PACKAGE}
            installed = [name for name in packages if self.bridge.installed(name)]
            return "\n".join("package:" + name for name in installed).encode()
        if arguments[:3] == ("shell", "input", "tap") and len(arguments) == 5:
            self.bridge.tap(int(arguments[3]), int(arguments[4]))
            return b""
        if arguments[:3] == ("shell", "input", "keyevent") and arguments[3] in {"4", "KEYCODE_BACK"}:
            self.bridge.globalAction("back")
            return b""
        raise core.Stop("Android 适配器不支持此操作：" + repr(arguments))


def configure(bridge):
    core.ROOT = Path(str(bridge.storagePath()))
    core.ROOT.mkdir(parents=True, exist_ok=True)
    core.ASSETS = Path(templates.__file__).parent
    core.time = RuntimeClock(bridge)

    def read_ocr(trace, image, filename, *, layout=False):
        core.time.check()
        ok, encoded = cv2.imencode(".png", image)
        if not ok:
            raise core.CountUnreadable("无法编码 OCR 图像。")
        if bridge.saveScreenshots():
            core.save_image(trace.directory / filename, image)
        try:
            return str(bridge.ocr(encoded.tobytes(), layout))
        except Exception as exc:
            raise core.CountUnreadable("中文识别失败：" + str(exc)) from exc

    core.read_ocr = read_ocr


def run(bridge, task, foreground_games=False):
    configure(bridge)
    trace = None
    try:
        class Trace(core.Trace):
            def record(self, event, **fields):
                super().record(event, **fields)
                bridge.report(json.dumps({"event": event, **fields}, ensure_ascii=False))

            def snapshot(self, phone, state, **kwargs):
                if bridge.saveScreenshots():
                    super().snapshot(phone, state, **kwargs)

        trace = Trace("android")
        phone = AndroidPhone(bridge)
        vision = core.Vision()
        if task == "selftest":
            capture = phone.capture()
            if len(vision.templates) != 78:
                raise core.Stop("模板文件数量不符。")
            result = str(bridge.ocr(bytes(bridge.testImage()), False))
            compact = "".join(result.split())
            if "123" not in compact or "任务" not in compact:
                raise core.Stop("OCR 自检未读出测试文字与数字：" + result)
            trace.record("self_test_passed", templates=len(vision.templates), ocr=result,
                         width=capture.shape[1], height=capture.shape[0], opencv=cv2.__version__)
            return json.dumps({"status": "checked", "message": "自检通过：截图、中文 OCR、OpenCV 和 78 张模板已加载。"}, ensure_ascii=False)
        if task == "preview":
            image = phone.frame()
            state, _ = vision.identify(image)
            trace.record("preview", state=state, package=phone.foreground())
            title = core.app_jump_title(image, vision)
            if title:
                trace.record("app_jump_progress", completed=core.app_jump_progress(image, title, trace, 0), total=8)
            if state in {"task", "task_unavailable"}:
                done, total = core.read_progress(image, vision, trace, "preview")
                trace.record("progress", completed=done, total=total)
            return json.dumps({"status": "checked", "message": "识别完成，详情见运行记录。"}, ensure_ascii=False)
        if task == "center":
            core.open_game_center(phone, trace)
        else:
            visions = [core.Vision(item) for item in core.TASKS if task in {"all", item.key}]
            core.run_stages(phone, trace, task, visions, set(), foreground_games=bool(foreground_games))
        trace.record("session_finished")
        return json.dumps({"status": "finished", "message": "本次处理结束，请按运行记录核对各项进度。"}, ensure_ascii=False)
    except SessionEnded:
        if trace:
            trace.record("session_interrupted")
        return json.dumps({"status": "paused", "message": "任务已停止。继续时重新核对手机进度。"}, ensure_ascii=False)
    except Exception as exc:
        if trace:
            trace.record("stopped", reason=str(exc))
        return json.dumps({"status": "attention", "message": str(exc)}, ensure_ascii=False)
