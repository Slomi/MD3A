"""Copies an APK and adds files to it, keeping each existing entry's compression (resources.arsc stays stored)."""
import sys
import zipfile

src, dst, *adds = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename), compress_type=item.compress_type)
    for spec in adds:
        path, name = spec.split("=", 1)
        zout.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
