"""Extracts the zoom evidence of the owner's Xiaomi 17 Ultra dumps (2026-10-08) into small text files.

Inputs (one directory): stock_1..8.txt and scamera_1..8.txt (`dumpsys media.camera`), stock_logcat.txt (logcat),
log-2026-10-08.txt (SCAMERA PhotonLog). The raw files are large (6 MB per dump, 22 MB logcat) and are not in git.

    python -I extract_zoom_dumps.py <input dir> <output dir>

Writes: dumps_summary.tsv (one row per dump), camera_static_zoom.txt (vendor zoom characteristics of the HAL cameras),
vendor_tags_zoom.txt (zoom related vendor tag definitions), logcat_excerpt.txt, photonlog_excerpt.txt.
"""
import os
import re
import sys

ZOOM_TAG = re.compile(r"zoom|optical|tele|fov|sat\b|\.sat|satmap|smoothTransition|thirdparty|thirdParty|sessionparams|"
                      r"focalLength35mm|lens\.info|actuator|isZooming|insensor|InSensor", re.I)


def meta_block(lines, i):
    """Key -> value text of a 'Dumping camera metadata array' block that starts at lines[i]."""
    out = {}
    base = len(lines[i]) - len(lines[i].lstrip())
    i += 1
    key = None
    while i < len(lines):
        ln = lines[i]
        if ln.strip():
            if len(ln) - len(ln.lstrip()) <= base:
                break
            m = re.match(r"^\s+([A-Za-z0-9_.]+) \(([0-9a-f]+)\): (\S+)\[(\d+)\]\s*$", ln)
            if m:
                key = m.group(1)
                out[key] = []
            elif key is not None:
                out[key].append(ln.strip())
        i += 1
    return {k: " ".join(v) for k, v in out.items()}, i


def text_of(value):
    """A byte[] value as text (up to the first NUL)."""
    nums = [int(x) & 255 for x in re.findall(r"-?\d+", value or "")]
    return bytes(nums).split(b"\0")[0].decode("latin1")


def parse_dump(path):
    lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
    r = {"clients": [], "devices": {}}
    dev = None
    i = 0
    while i < len(lines):
        ln = lines[i]
        m = re.match(r"^\(Camera ID: (\S+), .*PID: (\d+).*Client Package Name: ([^,]+),", ln)
        if m:
            r["clients"].append("%s/%s/%s" % m.groups())
        m = re.match(r"^== Camera device (\S+) dynamic info: ==", ln)
        if m:
            dev = m.group(1)
        elif ln.startswith("== Camera Provider") or ln.startswith("== Camera HAL device"):
            dev = None
        if dev is not None:
            d = r["devices"].setdefault(dev, {"streams": []})
            s = ln.strip()
            if s.startswith("Client package:"):
                d["package"] = s.split(":", 1)[1].strip()
            elif s.startswith("Client PID:"):
                d["pid"] = s.split(":", 1)[1].strip()
            elif s.startswith("Operation mode"):
                d["opmode"] = s.split(":", 1)[1].strip()
            elif s.startswith("Dims:"):
                d["streams"].append(s.replace("Dims: ", "").split(", dataspace")[0])
            elif s == "Latest received frame:" and "Dumping camera metadata" in lines[i + 1]:
                d["result"], i = meta_block(lines, i + 1)
                continue
            elif s == "Logical request settings:" and "Dumping camera metadata" in lines[i + 1]:
                d["request"], i = meta_block(lines, i + 1)
                continue
        i += 1
    return r


def first(values, key):
    v = values.get(key, "")
    m = re.search(r"\[(.*?)\]", v)
    return m.group(1).strip() if m else ("-" if not v else v)


def af_focal_ratio(result):
    t = text_of(result.get("com.xiaomi.afinfo.exifinfo"))
    m = re.search(r"FocalLengthRatio ([0-9.]+)", t)
    return m.group(1) if m else "-"


COLUMNS = [
    ("client", None), ("streams", None), ("opmode", None),
    ("req zoomRatio", ("request", "android.control.zoomRatio")),
    ("req userZoomRatio", ("request", "com.xiaomi.camera.userZoomRatio.userZoomRatio")),
    ("req current_mode", ("request", "org.codeaurora.qcamera3.sensor_meta_data.current_mode")),
    ("req focalLength", ("request", "android.lens.focalLength")),
    ("req clientName", ("request", "com.xiaomi.sessionparams.clientName")),
    ("res zoomRatio", ("result", "android.control.zoomRatio")),
    ("res focalLength", ("result", "android.lens.focalLength")),
    ("res lens.state", ("result", "android.lens.state")),
    ("res current_mode", ("result", "org.codeaurora.qcamera3.sensor_meta_data.current_mode")),
    ("opt TargetRatio", ("result", "com.xiaomi.optical.zoom.opticalZoomTargetRatio")),
    ("opt CurrentRatio", ("result", "com.xiaomi.optical.zoom.opticalZoomCurrentRatio")),
    ("opt State", ("result", "com.xiaomi.optical.zoom.opticalZoomState")),
    ("opt AFDac", ("result", "com.xiaomi.optical.zoom.opticalZoomCurrentAFDac")),
    ("focalLength35mm", ("result", "com.xiaomi.sensor.info.focalLength35mm")),
    ("isThirdParty", ("result", "xiaomi.thirdparty.isThirdParty")),
    ("AF FocalLengthRatio", None),
]


def summary(indir, outdir):
    rows = ["dump\t" + "\t".join(c for c, _ in COLUMNS)]
    names = ["stock_%d.txt" % k for k in range(1, 9)] + ["scamera_%d.txt" % k for k in range(1, 9)]
    for name in names:
        path = os.path.join(indir, name)
        if not os.path.isfile(path):
            continue
        r = parse_dump(path)
        open_devs = [k for k, v in r["devices"].items() if "package" in v]
        d = r["devices"][open_devs[0]] if open_devs else {}
        cells = []
        for col, src in COLUMNS:
            if col == "client":
                cells.append(";".join(r["clients"]) or "-")
            elif col == "streams":
                cells.append(" + ".join(d.get("streams", [])) or "-")
            elif col == "opmode":
                cells.append(d.get("opmode", "-"))
            elif col == "AF FocalLengthRatio":
                cells.append(af_focal_ratio(d.get("result", {})))
            elif col == "req clientName":
                v = d.get("request", {}).get(src[1])
                cells.append(text_of(v) if v else "-")
            else:
                cells.append(first(d.get(src[0], {}), src[1]))
        rows.append(name + "\t" + "\t".join(cells))
    open(os.path.join(outdir, "dumps_summary.tsv"), "w", encoding="utf-8").write("\n".join(rows) + "\n")
    return names


def static_info(indir, outdir):
    lines = open(os.path.join(indir, "stock_1.txt"), encoding="utf-8", errors="replace").read().split("\n")
    out = []
    cam = None
    keep = re.compile(r"smoothTransition|optical|zoomRatioRange|availableFocalLengths|logicalMultiCamera\.physicalIds|"
                      r"smartFOV|com\.xiaomi\.scaler|satZoomRatioRange|physicalCameraIds|satmap\.|supportedfeatures\.insensorzoom|"
                      r"zoomRatios\.focalLength35mm|lens\.info\.ViewAngle|teleFallback|sessionparams\.(operation|clientName)|"
                      r"isZoomRatioSupported|macro_zoom_feature|sensor\.info\.physicalSize", re.I)
    i = 0
    while i < len(lines):
        ln = lines[i]
        m = re.match(r"^== Camera HAL device device@1\.1/vendor_qti/(\d+) \(v1\.3\) static information: ==", ln)
        if m:
            cam = m.group(1)
            out.append("")
            out.append("== HAL camera %s ==" % cam)
        elif ln.startswith("== Camera HAL device") or ln.startswith("== Vendor tags"):
            cam = None
        elif cam is not None:
            m = re.match(r"^\s+([A-Za-z0-9_.]+) \(([0-9a-f]+)\): (\S+)\[(\d+)\]\s*$", ln)
            if m and keep.search(m.group(1)):
                vals = []
                j = i + 1
                while j < len(lines) and lines[j].strip().startswith("["):
                    vals.append(lines[j].strip())
                    j += 1
                v = " ".join(vals)
                if m.group(3) == "byte" and m.group(1).endswith(("clientName", "physicalIds")):
                    v += "  (text %r)" % text_of(v).replace("\0", ",") if not m.group(1).endswith("physicalIds") else \
                        "  (ids %s)" % ",".join(chr(int(x)) if 32 <= int(x) < 127 else str(x) for x in re.findall(r"\d+", v))
                out.append("  %s = %s" % (m.group(1), v[:400]))
        i += 1
    open(os.path.join(outdir, "camera_static_zoom.txt"), "w", encoding="utf-8").write(
        "Vendor zoom characteristics per HAL camera (from stock_1.txt; identical in every dump).\n" + "\n".join(out) + "\n")


def vendor_tags(indir, outdir):
    out = []
    for ln in open(os.path.join(indir, "stock_1.txt"), encoding="utf-8", errors="replace"):
        if "defined in section" in ln and ZOOM_TAG.search(ln):
            out.append(ln.strip())
    open(os.path.join(outdir, "vendor_tags_zoom.txt"), "w", encoding="utf-8").write("\n".join(out) + "\n")


def logcat(indir, outdir):
    keep = re.compile(r"am_proc_start|wm_set_resumed_activity|CameraService.*(connect|isconnect)|MI-CAM-ZOOM|camxzoom|"
                      r"OpticalRatio|tele_i_zoom|INSENSORZOOM|xmDevCheckAppMatch|isThirdParty|thirdparty|Camera3-Device|"
                      r"SSRU-CpuResourceTracker.*com\.android\.camera")
    seen = {}
    out = []
    for ln in open(os.path.join(indir, "stock_logcat.txt"), encoding="utf-8", errors="replace"):
        if not keep.search(ln):
            continue
        sig = re.sub(r"[0-9]+", "N", ln[19:])
        seen[sig] = seen.get(sig, 0) + 1
        if seen[sig] <= 2:
            out.append(ln.rstrip())
    out.append("")
    out.append("Repeated patterns (count):")
    for sig, n in sorted(seen.items(), key=lambda kv: -kv[1]):
        if n > 2:
            out.append("%6d  %s" % (n, sig.strip()[:200]))
    open(os.path.join(outdir, "logcat_excerpt.txt"), "w", encoding="utf-8").write("\n".join(out) + "\n")


def photonlog(indir, outdir):
    keep = re.compile(r"XiaomiTeleZoom|VendorTagUtils|ID:\d+ deviceID|restartCamera|onAuxButtonClicked|CameraIDs")
    out = [ln.rstrip() for ln in open(os.path.join(indir, "log-2026-10-08.txt"), encoding="utf-8", errors="replace")
           if ln.startswith("2026-10-08 10:0") and keep.search(ln)]
    open(os.path.join(outdir, "photonlog_excerpt.txt"), "w", encoding="utf-8").write("\n".join(out) + "\n")


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    summary(src, dst)
    static_info(src, dst)
    vendor_tags(src, dst)
    logcat(src, dst)
    photonlog(src, dst)
    print("written to", dst)
