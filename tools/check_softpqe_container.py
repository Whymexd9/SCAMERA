#!/usr/bin/env python3
"""Malformed-container guards for the softpqe reader; optional integration
against locally supplied .vdnn files. See docs/vivo-softpqe-upscale.md.
"""
import argparse
from pathlib import Path
import struct
import tempfile
import unittest
from inspect_softpqe_model import ENVELOPE, Metadata, describe

# Real, verified against the supplied containers (docs/vivo-softpqe-upscale.md).
EXPECTED = {
    'softpqe_y_1x_0_5_0.vdnn': ('model_y_gan_f8_w8a8_sym_ep0399_stack_quant_8w8a32b', [1, 560, 560, 16], [1, 560, 560, 4]),
    'softpqe_y_1x_0_6_0.vdnn': ('sr1x_qat_ep0499_202311251515_quant_8w8a32b', [1, 560, 560, 16], [1, 560, 560, 4]),
    'softpqe_y_1x_0_6_1.vdnn': ('sr1x_qat_20240131_quant_8w8a32b', [1, 560, 560, 16], [1, 560, 560, 4]),
    'softpqe_y_2x_0_1_0.vdnn': ('keta_sr2x_qat_ep0396_202312011751_quant_8w8a32b', [1, 560, 560, 4], [1, 1120, 1120, 1]),
    'softpqe_y_2x_0_2_0.vdnn': ('keta_sr2x_qat_20240314_quant_8w8a32b', [1, 560, 560, 4], [1, 1120, 1120, 1]),
    'softpqe_y_4x_0_1_0.vdnn': ('keta_sr4x_qat_ep0368_202312011729_quant_8w8a32b', [1, 560, 560, 4], [1, 2240, 2240, 1]),
    'softpqe_y_4x_0_2_0.vdnn': ('keta_sr4x_qat_20240310_quant_8w8a32b', [1, 560, 560, 4], [1, 2240, 2240, 1]),
    'softpqe_uv_0_7_0.vdnn': ('uv_0_7_0_a08_quant_8w8a32b', [1, 560, 560, 5], [1, 560, 560, 2]),
    'softpqe_uv_0_8_0.vdnn': ('uv_0_8_0_a08_nr24_qnn_quant_8w8a32b', [1, 560, 560, 5], [1, 560, 560, 2]),
}


class ContainerChecks(unittest.TestCase):
    def test_reject_missing_envelope(self):
        with self.assertRaises(ValueError):
            Metadata(b'not a model')

    def test_reject_inconsistent_metadata_length(self):
        header = ENVELOPE + struct.pack('<QQQ', 100, 999, 0)
        with self.assertRaisesRegex(ValueError, 'inconsistent'):
            Metadata(header)

    def test_reference_bounds(self):
        header = ENVELOPE + struct.pack('<QQQ', 0, 40, 0)
        meta = Metadata(header)
        with self.assertRaises(ValueError):
            meta.number(10 ** 6)

    def test_vector_size_guard(self):
        header = ENVELOPE + struct.pack('<QQQ', 100, 140, 0) + struct.pack('<I', 1000) + b'\x00' * 96
        meta = Metadata(header)
        with self.assertRaisesRegex(ValueError, 'unexpected vector size'):
            meta.vector(40)

    def test_extract_blob_length(self):
        # blob_length = context_offset(local) + declared blob size.
        header = ENVELOPE + struct.pack('<QQQ', 8, 48, 100) + b'\x00' * 8 + b'X' * 100 + b'trailer!'
        meta = Metadata(header)
        self.assertEqual(meta.blob_length, 48 + 100)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ContainerChecks))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.models:
        found = 0
        with tempfile.TemporaryDirectory() as tmp:
            for name, (graph, in_shape, out_shape) in EXPECTED.items():
                path = args.models / name
                if not path.exists():
                    continue
                found += 1
                data = path.read_bytes()
                start = data.find(ENVELOPE)
                assert start >= 0, name
                meta = Metadata(data[start:])
                graphs = meta.graphs()
                assert len(graphs) == 1, name
                g = graphs[0]
                assert g['name'] == graph, (name, g['name'])
                assert len(g['inputs']) == 1 and g['inputs'][0]['shape'] == in_shape, (name, g['inputs'])
                assert len(g['outputs']) == 1 and g['outputs'][0]['shape'] == out_shape, (name, g['outputs'])
                # Extraction must round-trip: the trimmed blob re-parses with its
                # own envelope at offset 0, and nothing beyond blob_length is kept.
                describe(path, extract_dir=Path(tmp))
                extracted = (Path(tmp) / (path.stem + '.bin')).read_bytes()
                assert extracted == data[start:start + meta.blob_length], name
                reparsed = Metadata(extracted)
                assert reparsed.graphs()[0]['name'] == graph, name
                print(name, 'OK:', graph, in_shape, '->', out_shape)
        if found == 0:
            raise ValueError('No known softpqe models found in ' + str(args.models))
        print(f'{found}/{len(EXPECTED)} known models verified')
