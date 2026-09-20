#!/usr/bin/env python3
"""Remove automatic SELinux policy rewriting from the pinned diagnostic helper.

Only frida-inject's startup tail-call changes. No Vivo/system binary is changed.
Reproduce from the official upstream .xz; reject all other versions.
"""
import hashlib,lzma,struct,sys
from pathlib import Path
ARCHIVE_SHA='a72de74276d914f6769b8b85f8dd287cbafa4527c42ae1c8dd87b0d23d261391'
ORIGINAL_SHA='d8ce6fe18db97594d1c7a43be4f8fb405fc76f6a01470df34504bca6461328d0'
PATCH_OFFSET=0x7d8dec

def prepare(archive,destination):
    archive=Path(archive).read_bytes()
    if hashlib.sha256(archive).hexdigest()!=ARCHIVE_SHA: raise ValueError('Wrong upstream archive')
    original=lzma.decompress(archive)
    if hashlib.sha256(original).hexdigest()!=ORIGINAL_SHA: raise ValueError('Wrong injector')
    # Environment.init: save FP/LR; w0=0; BL frida_init_with_runtime;
    # restore FP/LR; B frida_selinux_patch_policy. Replace the final B with RET.
    expected=struct.pack('<I',0x14000000|((0x32d99d0-PATCH_OFFSET)//4))
    if original[PATCH_OFFSET:PATCH_OFFSET+4]!=expected: raise ValueError('Unexpected callsite')
    patched=bytearray(original);patched[PATCH_OFFSET:PATCH_OFFSET+4]=bytes.fromhex('c0035fd6')
    assert patched[:PATCH_OFFSET]==original[:PATCH_OFFSET]
    assert patched[PATCH_OFFSET+4:]==original[PATCH_OFFSET+4:]
    Path(destination).write_bytes(patched);Path(destination).chmod(0o700)
    print(hashlib.sha256(patched).hexdigest())
if __name__=='__main__':prepare(sys.argv[1],sys.argv[2])
