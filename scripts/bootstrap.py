"""Download local build tools. All writable state stays under this repository."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import urllib.request
import zipfile
import time

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tooling"


def fetch(name, url):
    target = TOOLS / name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return target
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "APK-Autocount-build"})
            with urllib.request.urlopen(request, timeout=90) as response, target.with_suffix(".part").open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            target.with_suffix(".part").replace(target)
            print(f"Downloaded {name}", flush=True)
            return target
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)


def install(item):
    name, url, folder = item
    archive = fetch(name, url)
    destination = TOOLS / folder
    if not (destination / ".extracted").exists():
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as source:
            source.extractall(destination)
        (destination / ".extracted").write_text("ok")
    return destination


if __name__ == "__main__":
    items = [
        ("gradle-8.13-bin.zip", "https://downloads.gradle.org/distributions/gradle-8.13-bin.zip", "gradle"),
        ("commandlinetools.zip", "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip", "sdk/cmdline-tools/latest"),
        ("python-3.10.11.nupkg", "https://www.nuget.org/api/v2/package/python/3.10.11", "python-full"),
    ]
    with ThreadPoolExecutor(max_workers=3) as pool:
        for directory in pool.map(install, items):
            print(directory, flush=True)
