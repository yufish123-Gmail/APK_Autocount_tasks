"""Inspect an APK's packaged Python code, templates, ABI libraries and ELF alignment."""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def elf_alignment(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    alignments = []
    for index in range(count):
        header = offset + size * index
        if struct.unpack_from("<I", data, header)[0] == 1:
            alignments.append(struct.unpack_from("<Q", data, header + 48)[0])
    return min(alignments) if alignments else None


def inspect(path):
    manifest = json.loads((ROOT / "docs/upstream-manifest.json").read_text(encoding="utf-8"))
    template_hashes = {name: value for name, value in manifest["sha256"].items() if name.endswith(".png")}
    found = {}
    code = set()
    native = {}
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        for required in ("AndroidManifest.xml", "classes.dex"):
            assert required in names, required
        for name in names:
            data = None
            if name.endswith(".so"):
                native[name] = elf_alignment(apk.read(name))
            if name.endswith(".imy"):
                data = apk.read(name)
                if not zipfile.is_zipfile(io.BytesIO(data)):
                    continue
                with zipfile.ZipFile(io.BytesIO(data)) as package:
                    for entry in package.namelist():
                        if entry.endswith((".py", ".pyc")):
                            code.add(entry)
                        if entry.startswith("templates/") and entry.endswith(".png"):
                            found[entry] = hashlib.sha256(package.read(entry)).hexdigest()
                        if entry.endswith(".so"):
                            native[name + "/" + entry] = elf_alignment(package.read(entry))
    assert found == template_hashes, "Missing or modified template assets"
    for module in ("mgame_core", "android_runtime"):
        assert any(entry.split(".")[0] == module for entry in code), f"Missing {module}"
    assert any("arm64-v8a" in name for name in native), "Missing ARM64 libraries"
    assert any("x86_64" in name for name in native), "Missing emulator libraries"
    unaligned = {name: alignment for name, alignment in native.items() if alignment and alignment < 16384}
    result = {
        "apk": path.name, "size_bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "verified_templates": len(found), "native_libraries": len(native),
        "libraries_below_16kb_alignment": unaligned,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "android/app/build/outputs/apk/release/app-release.apk"
    inspect(path)
