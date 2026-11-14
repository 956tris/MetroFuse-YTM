from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parent.parent
source = root / "universal"
output = root / "build/outputs/plugins/youtube-music.mfp"
output.parent.mkdir(parents=True, exist_ok=True)
files = {p.relative_to(source).as_posix(): p.read_bytes() for p in sorted(source.rglob("*")) if p.is_file() and p.name != "manifest.json"}
manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
manifest["integrity"] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
files["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, data in sorted(files.items()):
        entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data)
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
print(output)
