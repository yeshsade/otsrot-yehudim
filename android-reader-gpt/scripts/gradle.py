#!/usr/bin/env python3
"""Run the project's Gradle wrapper using the cloud environment's HTTP proxy.

Java does not automatically read HTTPS_PROXY. This helper translates only the
proxy host and port into Java system properties, preserving certificate and
artifact verification. It never logs proxy environment values.
"""

import os
from pathlib import Path
import shlex
import subprocess
import sys
from urllib.parse import urlsplit


def main() -> int:
    project_root = Path(__file__).resolve().parents[1]
    android_root = project_root / "android"
    task_env = os.environ.copy()
    task_env.setdefault("GRADLE_USER_HOME", str(project_root / ".gradle-home"))
    task_env.setdefault("ANDROID_USER_HOME", str(project_root / ".android-home"))

    proxy_url = task_env.get("HTTPS_PROXY") or task_env.get("https_proxy")
    if proxy_url:
        proxy = urlsplit(proxy_url)
        if not proxy.hostname:
            print("HTTPS_PROXY must identify a proxy host.", file=sys.stderr)
            return 2
        proxy_port = proxy.port or (443 if proxy.scheme == "https" else 80)
        proxy_flags = [
            f"-Dhttps.proxyHost={proxy.hostname}",
            f"-Dhttps.proxyPort={proxy_port}",
            f"-Dhttp.proxyHost={proxy.hostname}",
            f"-Dhttp.proxyPort={proxy_port}",
        ]
        task_env["JAVA_OPTS"] = (
            task_env.get("JAVA_OPTS", "") + " " + shlex.join(proxy_flags)
        ).strip()

    if os.name == "nt":
        print("On Windows, open the Android project in Android Studio or use gradlew.bat.", file=sys.stderr)
        return 2

    return subprocess.run(
        [str(android_root / "gradlew"), *sys.argv[1:]],
        cwd=android_root,
        env=task_env,
        check=False,
    ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
