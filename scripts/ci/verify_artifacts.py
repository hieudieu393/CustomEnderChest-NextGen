"""Fail closed for empty/skipped test suites and incorrectly packaged candidates."""
import hashlib
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


def verify(root: Path) -> None:
    reports = sorted((root / "build/test-results/test").glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No JUnit reports: NO-SOURCE is not a successful acceptance run")
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = set()
    for report in reports:
        suite = ET.parse(report).getroot()
        suites.add(suite.attrib.get("name", ""))
        for key in totals:
            totals[key] += int(suite.attrib.get(key, "0"))
    required = {
        "org.maiminhdung.customenderchest.data.ItemSerializerSafetyTest",
        "org.maiminhdung.customenderchest.data.EnderChestManagerSafetyTest",
        "org.maiminhdung.customenderchest.storage.H2StorageSafetyTest",
    }
    if not required.issubset(suites):
        raise ValueError(f"Missing safety suites: {required - suites}")
    if totals["tests"] < 26 or any(totals[k] for k in ("failures", "errors", "skipped")):
        raise ValueError(f"Acceptance gate rejected test results: {totals}")

    build_script = (root / "build.gradle").read_text(encoding="utf-8")
    match = re.search(r"(?m)^version\s*=\s*'([^']+)'", build_script)
    if not match:
        raise ValueError("Cannot determine project version")
    version = match.group(1)
    jar = root / "build/libs" / f"CustomEnderChest-NextGen-{version}.jar"
    with zipfile.ZipFile(jar) as archive:
        if archive.testzip() is not None:
            raise ValueError("JAR failed CRC verification")
        plugin_yaml = archive.read("plugin.yml").decode("utf-8")
        plugin_version = re.search(r"(?m)^version:\s*['\"]?([^\s'\"]+)", plugin_yaml)
        if not plugin_version or plugin_version.group(1) != version:
            raise ValueError("plugin.yml version does not match the Gradle artifact version")
        names = set(archive.namelist())
        required_classes = {
            "org/maiminhdung/customenderchest/EnderChest.class",
            "org/maiminhdung/customenderchest/lib/h2/Driver.class",
            "org/maiminhdung/customenderchest/lib/hikari/HikariDataSource.class",
        }
        if not required_classes.issubset(names):
            raise ValueError(f"Missing shaded classes: {required_classes - names}")
        if any(n.startswith(("org/junit/", "org/mockito/", "org/bukkit/")) for n in names):
            raise ValueError("Server or test dependencies leaked into the plugin JAR")
    checksum = hashlib.sha256(jar.read_bytes()).hexdigest()
    (jar.parent / "SHA256SUMS").write_text(f"{checksum}  {jar.name}\n", encoding="utf-8")
    print(f"Verified {totals['tests']} tests and {jar.name}; candidate still needs live-server acceptance")


if __name__ == "__main__":
    try:
        verify(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, KeyError) as error:
        print(f"::error::{error}", file=sys.stderr)
        raise SystemExit(1)
