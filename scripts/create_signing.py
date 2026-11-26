"""Create a local release key, without placing passwords on command lines or in Git."""
from pathlib import Path
import os
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    folder = ROOT / "signing"
    folder.mkdir(exist_ok=True)
    props = folder / "keystore.properties"
    key = folder / "autocount.jks"
    if props.exists() and key.exists():
        print("Using existing local release signing key.")
        return
    if props.exists() or key.exists():
        raise SystemExit("Incomplete signing files. Restore both signing files before building.")
    password = secrets.token_urlsafe(32)
    environment = os.environ.copy()
    environment["APK_SIGNING_PASSWORD"] = password
    java = Path(environment.get("APK_JAVA_HOME", "C:/devsoft/ideaIC/jbr"))
    subprocess.run([
        str(java / "bin/keytool.exe"), "-genkeypair", "-keystore", str(key),
        "-storetype", "JKS", "-alias", "autocount", "-keyalg", "RSA", "-keysize", "3072",
        "-validity", "10000", "-dname", "CN=APK Autocount Tasks, O=Local Development",
        "-storepass:env", "APK_SIGNING_PASSWORD", "-keypass:env", "APK_SIGNING_PASSWORD",
    ], check=True, env=environment, capture_output=True)
    props.write_text(f"storePassword={password}\nkeyPassword={password}\nkeyAlias=autocount\n", encoding="utf-8")
    print("Created local release signing files in signing/. Keep both files for future updates.")


if __name__ == "__main__":
    main()
