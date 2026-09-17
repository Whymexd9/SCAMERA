#!/usr/bin/env python3
"""Malformed-container guards; optional integration against locally supplied VDNN."""
import argparse
from pathlib import Path
import struct
import unittest
from inspect_vivo_hexquad import FlatBuffer, inspect


class ContainerChecks(unittest.TestCase):
    def test_reject_unpinned(self):
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            inspect(b'not a model')

    def test_pointer_bounds(self):
        for data in (b'', b'\x01', struct.pack('<I', 0), struct.pack('<I', 0xffffffff)):
            with self.assertRaises(ValueError):
                FlatBuffer(data).pointer(0)

    def test_vector_bounds(self):
        f = FlatBuffer(struct.pack('<II', 4, 0xffffffff))
        with self.assertRaises(ValueError):
            f.vector(0)

    def test_string_termination(self):
        f = FlatBuffer(struct.pack('<II', 4, 3) + b'rawX')
        with self.assertRaisesRegex(ValueError, 'Unterminated'):
            f.string(0)

    def test_table_bounds_and_missing_fields(self):
        # Vtable at 0, object at 8; field zero at object+4.
        f = FlatBuffer(struct.pack('<HHHHii', 6, 8, 4, 0, 8, 123))
        self.assertEqual(f.field(8, 0), 12)
        with self.assertRaises(ValueError):
            f.field(8, 1)
        with self.assertRaises(ValueError):
            f.field(100, 0)
        corrupt = bytearray(f.data)
        struct.pack_into('<H', corrupt, 4, 7)
        with self.assertRaises(ValueError):
            FlatBuffer(corrupt).field(8, 0)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ContainerChecks))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.models:
        paths = sorted(args.models.glob('nice_ldr_hp9_general_*.vdnn'))
        if len(paths) != 3:
            raise ValueError('Expected all three supplied HP9 models')
        for path in paths:
            report, context = inspect(path.read_bytes())
            assert not report['capture_ready']
            assert report['context']['size'] == len(context)
            print(report['model'], report['input']['shape'], '->', report['output']['shape'])
