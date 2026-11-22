"""Build and run device-free checks and schedule previews with Python 3.9+ and JDK 17."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
JSON_URL = "https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar"
JSON_SHA256 = "3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796"


def json_library(build, local):
    path = local or build / "json-20250517.jar"
    if local or path.exists():
        data = path.read_bytes()
    else:
        with urlopen(JSON_URL, timeout=30) as response:
            data = response.read(1024 * 1024)
    if hashlib.sha256(data).hexdigest() != JSON_SHA256:
        raise ValueError("org.json dependency checksum mismatch")
    if local is None and not path.exists():
        path.write_bytes(data)
    return path.resolve()


def run(args):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, help="JDK directory, defaults to JAVA_HOME or PATH")
    parser.add_argument("--json-jar", type=Path, help="Use a local copy of the pinned org.json JAR")
    parser.add_argument("--android-jar", type=Path, help="Also compile the Android adapter with SDK 33+")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check")
    plan = commands.add_parser("plan")
    plan.add_argument("course", type=Path)
    plan.add_argument("monday")
    plan.add_argument("--slots", type=int, required=True)
    plan.add_argument("--backup-minutes", type=int, default=0)
    options = parser.parse_args(args)
    jdk = options.jdk or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)

    def tool(name):
        return str(jdk / "bin" / (name + (".exe" if os.name == "nt" else ""))) if jdk else name

    build = ROOT / ".build"
    classes = build / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    library = json_library(build, options.json_jar)
    sources = sorted((ROOT / "src").rglob("*.java"))
    if options.command == "check":
        sources.extend(sorted((ROOT / "tests").glob("*.java")))
    subprocess.run([tool("javac"), "--release", "17", "-encoding", "UTF-8", "-cp", str(library),
                    "-d", str(classes), *map(str, sources)], check=True)
    cp = os.pathsep.join(map(str, (classes, library)))

    def java(entry, *params, capture=False):
        return subprocess.run([tool("java"), "-cp", cp, "cn.local.garminassistant." + entry, *map(str, params)],
                              check=True, text=True, encoding="utf-8", capture_output=capture)

    if options.android_jar:
        android_out = build / "android"
        android_out.mkdir(exist_ok=True)
        android_sources = sorted((ROOT / "android").rglob("*.java"))
        # Match an Android build that uses Android's own org.json.
        android_core = [p for p in sources if p.is_relative_to(ROOT / "src") and p.name != "PlanCli.java"]
        subprocess.run([tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-cp", str(options.android_jar), "-d", str(android_out),
                        *map(str, android_core + android_sources)], check=True)

    if options.command == "plan":
        java("PlanCli", options.course, options.monday, options.slots, options.backup_minutes)
        return
    sys.path.insert(0, str(ROOT / "tests"))
    from fit_fixture import crc, records, uint

    def message(local, number, fields, values):
        definition = bytes([0x40 | local, 0, 0]) + struct.pack("<H", number) + bytes([len(fields)])
        return definition + b"".join(map(bytes, fields)) + b"".join(bytes([local]) + v for v in values)

    identity = message(0, 0, [(0,1,0), (1,2,132), (2,2,132), (3,4,140)],
                       [struct.pack("<BHHI", 2, 1, 3992, 123456789)])
    alarms = message(1, 222, [(254,2,132), (0,2,132), (1,4,140), (2,1,0), (90,1,13)],
                     [struct.pack("<HHIBB", i, 400 + i * 11, 127, int(i in (1, 5, 7)), i) for i in range(10)])
    body = identity + alarms
    header = struct.pack("<BBHI4s", 14, 32, 22100, len(body), b".FIT")
    content = header + struct.pack("<H", crc(header)) + body
    fixture = build / "synthetic.fit"
    fixture.write_bytes(content + struct.pack("<H", crc(content)))
    delta = build / "alarm-delta.fit"
    java("nativealarm.FitFixtureTest", fixture, delta)
    java("nativealarm.NativeAlarmProtocolTests", delta)
    java("nativealarm.FitDownload")
    parsed = records(delta.read_bytes())
    assert [r["number"] for r in parsed].count(0) == 1
    assert all(r["number"] in (0, 222) for r in parsed)
    written, enabled = set(), {}
    for alarm in (r for r in parsed if r["number"] == 222):
        written.add(uint(alarm, 254, 2, 132))
        if uint(alarm, 2, 1, 0):
            enabled[uint(alarm, 0, 2, 132)] = uint(alarm, 1, 4, 140)
            assert uint(alarm, 90, 1, 13) == 5
    assert written == {0, 2, 4, 5, 8} and enabled == {431: 3, 439: 3, 530: 124, 538: 124}
    print("PASS independent FIT decode: protected slots excluded and alert bytes copied")
    preview = json.loads(java("PlanCli", ROOT / "examples/course.json", "2030-09-02", 8, 8, capture=True).stdout)
    assert ([row["primary"] for row in preview["days"]] == ["08:35", "08:20", "08:05", "08:35", "08:20", "08:20", "08:20"]
            and preview["days"][0]["backup"] == "08:43" and preview["slotsNeeded"] == 6)
    second = json.loads(java("PlanCli", ROOT / "examples/course.json", "2030-09-09", 8, 0, capture=True).stdout)
    assert second["days"][0]["primary"] == "08:35" and "backup" not in second["days"][0]
    print("PASS whole-week preview, date override, makeup course and optional backup")


if __name__ == "__main__":
    run(sys.argv[1:])
