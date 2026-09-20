#!/usr/bin/env python3
"""Extract only hash-pinned tone contexts from privately supplied VDNN files."""
import argparse
import hashlib
import json
from pathlib import Path
from inspect_vivo_nice import inspect


def extract(source, destination):
    rows=json.loads((Path(__file__).resolve().parents[1]/"docs/vivo-nice-tone-contracts.json").read_text())
    verified=[]
    for row in rows:
        candidates=list(source.rglob(row["source"]))
        matches=[p for p in candidates if hashlib.sha256(p.read_bytes()).hexdigest()==row["source_sha256"]]
        if not matches:raise ValueError("Missing pinned source: "+row["source"])
        metadata,context=inspect(matches[0],allow_multiple_inputs=True)
        if metadata["graphs"]!=row["graphs"] or len(context)!=row["size"] or hashlib.sha256(context).hexdigest()!=row["sha256"]:
            raise ValueError("Tone context contract mismatch: "+row["source"])
        verified.append((row["file"],context))
    destination.mkdir(parents=True,exist_ok=False)
    for name,context in verified:(destination/name).write_bytes(context)
    print("Verified and extracted",len(verified),"tone contexts; no device inference")

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("source",type=Path);p.add_argument("destination",type=Path)
    a=p.parse_args();extract(a.source,a.destination)
