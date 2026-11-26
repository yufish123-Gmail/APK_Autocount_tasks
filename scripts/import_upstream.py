"""Import a pinned read-only Git snapshot, without changing the source repository."""
from pathlib import Path
import ast
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "Mgame-auto-count-tasks"
COMMIT = "d352788a8018635857e485f22c4d198f7e942a95"
PYTHON = ROOT / "android/app/src/main/python"


def read(name):
    return subprocess.run(["git", "-C", str(SOURCE), "show", f"{COMMIT}:{name}"],
                          capture_output=True, check=True).stdout


def main():
    PYTHON.mkdir(parents=True, exist_ok=True)
    original = read("coin_helper.py").decode("utf-8")
    lines = original.splitlines(keepends=True)
    tree = ast.parse(original)
    removed = {"run_command", "find_adb", "main"}
    for node in reversed(tree.body):
        if (isinstance(node, ast.FunctionDef) and node.name in removed
                or isinstance(node, ast.ClassDef) and node.name == "Phone"
                or isinstance(node, ast.If) and "__name__" in ast.get_source_segment(original, node.test)):
            del lines[node.lineno - 1:node.end_lineno]
    core = "".join(lines).replace("import msvcrt\n", "")
    core = core.replace("import argparse\n", "").replace("import subprocess\n", "")
    core = core.replace("import shutil\n", "").replace("import os\n", "")
    # Native Android bindings are installed explicitly by android_runtime.configure.
    start = core.index("def read_ocr(")
    end = core.index("\ndef read_progress(", start)
    core = core[:start] + 'def read_ocr(trace, image, filename, *, layout=False):\n    raise RuntimeError("Android OCR bridge is not configured")\n\n' + core[end:]
    (PYTHON / "mgame_core.py").write_text(
        "# Adapted from Mgame-auto-count-tasks at " + COMMIT + "\n" + core.rstrip() + "\n", encoding="utf-8")
    names = subprocess.run(["git", "-C", str(SOURCE), "ls-tree", "-r", "--name-only", COMMIT],
                           capture_output=True, text=True, check=True).stdout.splitlines()
    manifest = {}
    for name in names:
        if name.startswith("templates/") and name.endswith(".png"):
            data = read(name)
            target = PYTHON / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            manifest[name] = hashlib.sha256(data).hexdigest()
    manifest["coin_helper.py"] = hashlib.sha256(original.encode()).hexdigest()
    (ROOT / "docs/upstream-manifest.json").write_text(
        json.dumps({"repository": "https://github.com/yufish123-Gmail/Mgame-auto-count-tasks",
                    "commit": COMMIT, "sha256": manifest}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Preserve rule tests, excluding desktop-only USB/ADB implementation tests.
    tests = read("tests/test_automation.py").decode("utf-8").replace("import coin_helper as helper", "import mgame_core as helper")
    excluded = {"test_usb_wait_checks_the_original_phone_and_times_out",
                "test_adb_missing_device_is_recognized_as_usb_loss",
                "test_rotated_live_capture_retries_before_matching",
                "test_game_still_foreground_after_main_recovery_gets_one_restart"}
    test_lines = tests.splitlines(keepends=True)
    test_tree = ast.parse(tests)
    for cls in test_tree.body:
        if isinstance(cls, ast.ClassDef):
            for node in reversed(cls.body):
                if isinstance(node, ast.FunctionDef) and node.name in excluded:
                    del test_lines[node.lineno - 1:node.end_lineno]
    target = ROOT / "tests/test_upstream_rules.py"
    target.parent.mkdir(exist_ok=True)
    target.write_text("".join(test_lines), encoding="utf-8")
    print(f"Imported {len(manifest) - 1} templates and task engine at {COMMIT}")


if __name__ == "__main__":
    main()
