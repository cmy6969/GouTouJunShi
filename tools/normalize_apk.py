"""
Normalises the entry names of an APK written by aapt2 on Windows.

aapt2 joins the ``-A <assets>`` directory with the native separator, so on Windows every asset is
stored as ``assets/js\\probe.js``.  Android's ``ZipFile.getEntry`` matches literally and therefore
never finds ``assets/js/probe.js`` (the AssetManager path happens to work, the ZipFile path does
not).  Rewriting the names is cheaper and safer than teaching every reader about both separators.

Usage: python normalize_apk.py <in.apk> <out.apk>
"""
import shutil
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
changed = 0
with zipfile.ZipFile(src, "r") as zin:
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            # ZipInfo.filename is already normalised to '/', the raw name lives in orig_filename.
            raw = getattr(info, "orig_filename", info.filename)
            name = raw.replace("\\", "/")
            if name != raw:
                changed += 1
            clone = zipfile.ZipInfo(name, date_time=info.date_time)
            clone.compress_type = info.compress_type
            clone.external_attr = info.external_attr
            clone.internal_attr = info.internal_attr
            clone.create_system = info.create_system
            zout.writestr(clone, zin.read(info))
print("normalized entries: %d" % changed)
if changed == 0:
    shutil.copyfile(src, dst)
