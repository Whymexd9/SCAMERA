"""Prefetch must preserve every network-input and output byte, including fallback."""
from pathlib import Path
import subprocess
import sys
import tempfile
with tempfile.TemporaryDirectory(prefix='hex-hybrid-') as temp:
    cpu=Path(temp,'cpu');hybrid=Path(temp,'hybrid');cpu.mkdir();hybrid.mkdir()
    subprocess.run([sys.argv[1],str(cpu),str(hybrid),*sys.argv[2:]],check=True)
    assert len(list(cpu.iterdir()))==(4 if len(sys.argv)>2 else 48)
    for p in cpu.iterdir():assert p.read_bytes()==(hybrid/p.name).read_bytes(),p.name
    print('Hybrid prefetch PASS: bitwise input/output equivalence and serial NPU ownership')
