"""Bundle runtime license metadata and original archive notices without a settings entry.

Run gradlew :app:exportRuntimeArtifacts, then python tools/package-licenses.py.
Canonical Apache-2.0 and MPL-2.0 texts live alongside the generated assets.
"""
from pathlib import Path
import io
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets/third_party"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def licenses(pom, cache_root, depth=0):
    root = ET.parse(pom).getroot()
    found = [f"{node.findtext('m:name', '', NS)} — {node.findtext('m:url', '', NS)}"
             for node in root.findall("m:licenses/m:license", NS)]
    if found or depth > 4:
        return found
    parent = root.find("m:parent", NS)
    if parent is not None:
        parts = [parent.findtext(f"m:{key}", "", NS) for key in ("groupId", "artifactId", "version")]
        for candidate in (cache_root.joinpath(*parts)).glob("*/*.pom"):
            found = licenses(candidate, cache_root, depth + 1)
            if found:
                return found
    return []


def archive_notices(data, prefix=""):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            basename = name.rsplit("/", 1)[-1].upper()
            if not name.endswith("/") and ("LICENSE" in basename or "NOTICE" in basename):
                yield prefix + name, archive.read(name).decode("utf-8", errors="replace")
            elif name == "classes.jar":
                yield from archive_notices(archive.read(name), prefix + "classes.jar!")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for required in ("Apache-2.0.txt", "MPL-2.0.txt"):
        if not (OUTPUT / required).exists():
            raise RuntimeError(f"Missing canonical license: {required}")
    components = ["PixNexus Gallary runtime third-party components", "", "License copies: Apache-2.0.txt and MPL-2.0.txt", ""]
    notices = ["Original third-party notices and license texts from distributed archives.", ""]
    rows = (ROOT / "app/build/tmp/runtime-artifacts.tsv").read_text(encoding="utf-8").splitlines()
    for row in rows:
        coordinate, filename = row.split("\t")
        artifact = Path(filename)
        version_dir = artifact.parent.parent
        cache_root = version_dir.parents[2]
        declared = []
        for pom in version_dir.glob("*/*.pom"):
            declared = licenses(pom, cache_root)
            if declared:
                break
        if not declared:
            raise RuntimeError(f"No license metadata resolved for {coordinate}")
        components.extend([coordinate, *declared, ""])
        archives = [artifact, *version_dir.glob("*/*-sources.jar")]
        seen = set()
        for archive in archives:
            for name, text in archive_notices(archive.read_bytes()):
                if text in seen:
                    continue
                seen.add(text)
                notices.extend([f"===== {coordinate} / {name} =====", text, ""])
    (OUTPUT / "COMPONENTS.txt").write_text("\n".join(components), encoding="utf-8")
    (OUTPUT / "NOTICES.txt").write_text("\n".join(notices), encoding="utf-8")
    print(f"Bundled license metadata for {len(rows)} runtime artifacts and their original notices.")


if __name__ == "__main__":
    main()
