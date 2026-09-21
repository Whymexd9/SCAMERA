#!/usr/bin/env python3
import argparse
import json
import struct
import tarfile
from pathlib import Path


def block(value, minimum=0):
    if not isinstance(value, dict) or "error" in value:
        raise ValueError("Missing or unreadable memory block")
    data = bytes.fromhex(value["hex"])
    if len(data) != value["size"] or len(data) < minimum:
        raise ValueError("Truncated memory block")
    return data


def words(value, stride=4, offset=0):
    if "error" in value or value.get("stride") != stride:
        raise ValueError("Invalid vector")
    count = value["count"]
    if count < 0 or count > 256:
        raise ValueError("Invalid vector count")
    data = block(value["data"], count * stride) if count else b""
    if len(data) != count * stride:
        raise ValueError("Vector length mismatch")
    return [struct.unpack_from("<I", data, stride*i+offset)[0] for i in range(count)]


def references(value):
    if "error" in value or value.get("stride") != 16:
        raise ValueError("Invalid shared-pointer vector")
    count = value["count"]
    if count < 0 or count > 256:
        raise ValueError("Invalid shared-pointer count")
    data = block(value["data"], count * 16) if count else b""
    if len(data) != count * 16:
        raise ValueError("Shared-pointer length mismatch")
    return [struct.unpack_from("<QQ", data, i*16) for i in range(count)]


def events(path):
    with tarfile.open(path) as archive:
        logs = [m for m in archive.getmembers() if m.isfile() and m.name.endswith("/trace.log")]
        if len(logs) != 1 or logs[0].size > 16*1024*1024:
            raise ValueError("Expected one bounded trace.log")
        lines = archive.extractfile(logs[0]).read().decode().splitlines()
    return [json.loads(line[len("SCAMERA_ZSL "):]) for line in lines if line.startswith("SCAMERA_ZSL ")]


def analyze(trace):
    nice = next((e for e in trace if e["event"] == "nice_enter"), None)
    if nice is None:
        raise ValueError("NICE plan not observed")
    after = next((e for e in trace if e["event"] == "nice_leave" and e["id"] == nice["id"]), None)
    if after is None:
        raise ValueError("NICE plan did not return")
    preview = block(nice["preview"], 0x3e48)
    control = block(after["control"], 0xb34)
    past, future = struct.unpack_from("<II", preview, 0x2d08)
    if past + future == 0 or past + future > 16:
        raise ValueError("Unsupported NICE frame counts")
    alternate = struct.unpack_from("<I", preview, 0x3d8c)[0] != 0
    count, batches = struct.unpack_from("<II", control)
    if count != past + future or batches != count:
        raise ValueError("Query/produced plan count mismatch")
    records = []
    for i in range(count):
        fmt, ev, gain, shutter, direction = struct.unpack_from("<IfffI", control, 0x94+20*i)
        records.append(dict(index=i, format=fmt, ev=ev, gain=gain, shutterNative=shutter, direction=direction))
    queues = []
    for enter in [e for e in trace if e["event"] == "queue_enter"]:
        leave = next((e for e in trace if e["event"] == "queue_leave" and e["id"] == enter["id"]), None)
        before_ids = words(enter["requestedIds"])
        after_ids = words(leave["requestedIds"]) if leave else None
        append_only = after_ids is not None and after_ids[:len(before_ids)] == before_ids
        queues.append(dict(queue=enter["queue"], call=enter["id"], request=enter["request"],
            past=enter["past"], future=enter["future"], catchMode=enter["catchMode"],
            readyIds=words(enter["ready"], 48, 0x24), returned=leave is not None,
            appendedIds=after_ids[len(before_ids):] if append_only else None))
    deliveries = []
    for enter in [e for e in trace if e["event"] == "delivery_enter"]:
        leave = next((e for e in trace if e["event"] == "delivery_leave" and e["id"] == enter["id"]), None)
        item = dict(queue=enter["queue"], call=enter["id"], returned=leave is not None,
                    route=enter.get("route", "combined"),
                    knownQueue=enter.get("knownQueue", any(q["queue"]==enter["queue"] for q in queues)))
        if leave:
            # Vector growth is evidence of delivery, not merely preexisting buffers.
            before_past = references(enter["past"])
            before_future = references(enter["future"])
            after_past = references(leave["past"])
            after_future = references(leave["future"])
            item.update(returnBits=leave["returnBits"],
                pastAdded=len(after_past)-len(before_past), futureAdded=len(after_future)-len(before_future),
                futureAppendValid=after_future[:len(before_future)] == before_future
                    and all(ref[0] != 0 for ref in after_future[len(before_future):]),
                requestIds=words(leave["requestedIds"]))
        deliveries.append(item)
    return dict(version=nice["version"], pid=nice["pid"], query=dict(past=past, future=future,
        alternateExposureMode=alternate), producerCatchMode=struct.unpack_from("<I",control,0xb18)[0],
        frames=records, queues=queues, deliveries=deliveries,
        futureDeliveryObserved=any(d.get("knownQueue") and d.get("returnBits")==1 and d.get("futureAdded",0)>0 and d.get("futureAppendValid") for d in deliveries),
        limitations=["No proof of scene/AE solver parity", "No proof of RAW pixels or NICE model inputs",
                    "Native gain/shutter units are not Camera2 conversions"])


def main():
    parser=argparse.ArgumentParser();parser.add_argument("archive",type=Path)
    args=parser.parse_args()
    print(json.dumps(analyze(events(args.archive)),ensure_ascii=False,indent=2,allow_nan=False))

if __name__ == "__main__": main()
