#!/usr/bin/env python3
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path
import re
import struct

import moderngl
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
VS = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'


class GoogleOpenCL:
    def __init__(self, source_dir):
        self.lib = C.WinDLL('OpenCL.dll') if hasattr(C, 'WinDLL') else C.CDLL('libOpenCL.so.1')
        P, U, Z = C.c_void_p, C.c_uint, C.c_size_t
        self.functions = {}
        signatures = {
            'clGetPlatformIDs': (C.c_int, [U, P, P]),
            'clGetDeviceIDs': (C.c_int, [P, C.c_ulonglong, U, P, P]),
            'clGetDeviceInfo': (C.c_int, [P, U, Z, P, P]),
            'clCreateContext': (P, [P, U, P, P, P, P]),
            'clCreateCommandQueue': (P, [P, P, C.c_ulonglong, P]),
            'clCreateProgramWithSource': (P, [P, U, P, P, P]),
            'clBuildProgram': (C.c_int, [P, U, P, C.c_char_p, P, P]),
            'clGetProgramBuildInfo': (C.c_int, [P, P, U, Z, P, P]),
            'clCreateImage2D': (P, [P, C.c_ulonglong, P, Z, Z, Z, P, P]),
            'clCreateKernel': (P, [P, C.c_char_p, P]),
            'clSetKernelArg': (C.c_int, [P, U, Z, P]),
            'clEnqueueWriteImage': (C.c_int, [P, P, U, P, P, Z, Z, P, U, P, P]),
            'clEnqueueReadImage': (C.c_int, [P, P, U, P, P, Z, Z, P, U, P, P]),
            'clEnqueueNDRangeKernel': (C.c_int, [P, P, U, P, P, P, U, P, P]),
            'clFinish': (C.c_int, [P]),
            'clReleaseMemObject': (C.c_int, [P]),
            'clReleaseKernel': (C.c_int, [P]),
            'clReleaseProgram': (C.c_int, [P]),
            'clReleaseCommandQueue': (C.c_int, [P]),
            'clReleaseContext': (C.c_int, [P]),
        }
        for name, (result, arguments) in signatures.items():
            fn = getattr(self.lib, name)
            fn.restype, fn.argtypes = result, arguments
            self.functions[name] = fn
        count = U()
        self.check(self.call('clGetPlatformIDs', 0, None, C.byref(count)))
        platforms = (P * count.value)()
        self.check(self.call('clGetPlatformIDs', count, platforms, None))
        devices = []
        for platform in platforms:
            n = U()
            if self.call('clGetDeviceIDs', platform, 4, 0, None, C.byref(n)) == 0 and n.value:
                ids = (P * n.value)()
                self.check(self.call('clGetDeviceIDs', platform, 4, n, ids, None))
                devices.extend(ids)
        if not devices:
            raise RuntimeError('No OpenCL GPU')
        self.device = devices[0]
        name = C.create_string_buffer(1024)
        self.check(self.call('clGetDeviceInfo', self.device, 0x102B, len(name), name, None))
        self.name = name.value.decode()
        err = C.c_int()
        device_array = (P * 1)(self.device)
        self.context = self.call('clCreateContext', None, 1, device_array, None, None, C.byref(err))
        self.check(err.value)
        self.queue = self.call('clCreateCommandQueue', self.context, self.device, 0, C.byref(err))
        self.check(err.value)
        common = (source_dir / 'original/finish/common.cl').read_text(encoding='utf-8')
        kernel = (source_dir / 'original/finish/demosaic.cl').read_text(encoding='utf-8')
        text = kernel.replace('#include "common.cl"', common).encode()
        source = C.c_char_p(text)
        length = Z(len(text))
        self.program = self.call('clCreateProgramWithSource', self.context, 1, C.byref(source), C.byref(length), C.byref(err))
        self.check(err.value)
        result = self.call('clBuildProgram', self.program, 1, device_array, b'-cl-std=CL2.0', None, None)
        if result:
            log = C.create_string_buffer(32768)
            self.call('clGetProgramBuildInfo', self.program, self.device, 0x1183, len(log), log, None)
            raise RuntimeError(log.value.decode(errors='replace'))
        self.source_sha256 = hashlib.sha256(text).hexdigest()

    def call(self, name, *args):
        return self.functions[name](*args)

    @staticmethod
    def check(result):
        if result:
            raise RuntimeError(f'OpenCL error {result}')

    def run(self, raw, sharpness=1.0):
        h, w = raw.shape
        packed = np.ascontiguousarray(raw.reshape(h // 2, 2, w // 2, 2).transpose(0, 2, 1, 3).reshape(h // 2, w // 2, 4))
        fmt = (C.c_uint * 2)(0x10B5, 0x10DE)  # CL_RGBA, CL_FLOAT
        origin = (C.c_size_t * 3)(0, 0, 0)
        region = (C.c_size_t * 3)(w // 2, h // 2, 1)
        global_size = (C.c_size_t * 2)(w // 2, h // 2)
        err = C.c_int()
        images = []
        kernels = []
        try:
            for _ in range(7):
                images.append(self.call('clCreateImage2D', self.context, 1, fmt, w // 2, h // 2, 0, None, C.byref(err)))
                self.check(err.value)
            self.check(self.call('clEnqueueWriteImage', self.queue, images[0], 1, origin, region, 0, 0, packed.ctypes.data, 0, None, None))
            commands = [
                ('BlurDiagonalGreen', [images[0], C.c_float(sharpness), images[1]]),
                ('ComputeBlurHV', [images[1], images[2], images[3]]),
                ('DemosaicGreen', [images[1], images[2], images[3], images[4]]),
                ('DemosaicRedBlue', [images[1], images[4], images[5], images[6]]),
            ]
            for name, args in commands:
                kernel = self.call('clCreateKernel', self.program, name.encode(), C.byref(err))
                self.check(err.value)
                kernels.append(kernel)
                for i, value in enumerate(args):
                    value = C.c_void_p(value) if isinstance(value, int) else value
                    self.check(self.call('clSetKernelArg', kernel, i, C.sizeof(value), C.byref(value)))
                self.check(self.call('clEnqueueNDRangeKernel', self.queue, kernel, 2, None, global_size, None, 0, None, None))
            output = np.empty((h, w, 3), np.float32)
            for channel, index in enumerate([5, 4, 6]):
                self.check(self.call('clEnqueueReadImage', self.queue, images[index], 1, origin, region, 0, 0, packed.ctypes.data, 0, None, None))
                output[..., channel] = packed.reshape(h // 2, w // 2, 2, 2).transpose(0, 2, 1, 3).reshape(h, w)
            return output
        finally:
            for kernel in kernels:
                self.call('clReleaseKernel', kernel)
            for image in images:
                self.call('clReleaseMemObject', image)

    def close(self):
        self.call('clReleaseProgram', self.program)
        self.call('clReleaseCommandQueue', self.queue)
        self.call('clReleaseContext', self.context)


class OurDemosaic:
    def __init__(self):
        self.context = moderngl.create_standalone_context(require=430)
        source = (ROOT / 'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf-8')
        start = source.index('bool bayerDemosaic(')
        end, depth = source.index('{', start) + 1, 1
        while depth:
            depth += (source[end] == '{') - (source[end] == '}')
            end += 1
        self.function_source = source[start:end]
        wrapper = '''#version 310 es
precision highp float;
precision highp int;
uniform sampler2D Raw;
uniform vec2 baseNoise;
uniform float bdAliasU;
uniform vec4 bdU;
uniform float clipLevel;
const ivec4 phaseColor=ivec4(0,1,1,2);
const vec4 fParam[1]=vec4[](vec4(1.0));
out vec4 Output;
float rawSite(int f,int x,int y,out uint flags){
 ivec2 size=textureSize(Raw,0);
 if(x<0)x=-x;if(y<0)y=-y;
 if(x>=size.x)x=2*(size.x-1)-x;if(y>=size.y)y=2*(size.y-1)-y;
 flags=0u;return clamp(texelFetch(Raw,ivec2(x,y),0).r,-0.25,1.0);
}
'''
        self.program = self.context.program(vertex_shader=VS, fragment_shader=wrapper + self.function_source + '''
void main(){vec3 rgb;float detail;bool ok=bayerDemosaic(gl_FragCoord.xy-0.5,rgb,detail);Output=vec4(ok?rgb:vec3(1.0),ok?1.0:0.0);}
''')

    def run(self, raw, noise, anisotropy=1.0):
        h, w = raw.shape
        ctx = self.context
        tex = ctx.texture((w, h), 1, np.ascontiguousarray(raw, np.float32).tobytes(), dtype='f4')
        out = ctx.texture((w, h), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        vao = ctx.vertex_array(self.program, [])
        try:
            tex.use(0)
            self.program['Raw'] = 0
            self.program['baseNoise'] = tuple(noise)
            self.program['bdAliasU'] = 1.0
            self.program['bdU'] = (1.0, anisotropy, 1.3, 2.5)
            self.program['clipLevel'] = 0.98
            fb.use()
            ctx.viewport = (0, 0, w, h)
            vao.render(vertices=3)
            result = np.frombuffer(out.read(), np.float32).reshape(h, w, 4).copy()
            return result[..., :3], result[..., 3] > 0.5
        finally:
            vao.release(); fb.release(); out.release(); tex.release()


def load_base(path):
    with path.open('rb') as f:
        header = f.read(128)
        words = struct.unpack('<32I', header)
        if words[0] != 0x3143484e or words[1] not in (10, 11, 12) or words[14] not in (0, 1):
            raise ValueError('Expected a plain Bayer NCH burst')
        w, h, cfa, count = words[2:6]
        rows = [struct.unpack('<IfIfffII', f.read(32)) for _ in range(count)]
        base = rows[0]
        offset = base[7] * 4096 if words[1] == 12 else 128 + 32 * count
        f.seek(offset)
        payload = f.read(w * h * 2)
        if len(payload) != w * h * 2:
            raise ValueError('Truncated frame')
        raw = np.frombuffer(payload, '<u2').reshape(h, w).astype(np.float32)
    white, *black = struct.unpack_from('<5f', header, 24)
    for y in range(2):
        for x in range(2):
            bl = black[2 * y + x]
            raw[y::2, x::2] = (raw[y::2, x::2] - bl) / (white - bl)
    if cfa & 1:
        raw = raw[:, ::-1]
    if cfa & 2:
        raw = raw[::-1]
    metadata = json.loads(path.with_suffix('.json').read_text(encoding='utf-8'))
    return np.ascontiguousarray(raw), base, metadata, cfa, hashlib.sha256(payload).hexdigest()


def restore(image, cfa):
    if cfa & 2:
        image = image[::-1]
    if cfa & 1:
        image = image[:, ::-1]
    return np.rot90(image, -1)


def display(rgb, matrix):
    linear = np.maximum(0, rgb @ matrix.T) * 4
    linear /= 1 + linear
    return np.uint8(np.clip(np.power(linear, 1 / 2.2), 0, 1) * 255 + 0.5)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--google-source', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('bursts', nargs='+', type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    google, ours = GoogleOpenCL(args.google_source), OurDemosaic()
    try:
        chart = np.empty((64, 64), np.float32)
        chart[0::2, 0::2] = .2; chart[0::2, 1::2] = .4
        chart[1::2, 0::2] = .4; chart[1::2, 1::2] = .6
        for name, image in [('our', ours.run(chart, (.0003, .000001))[0]), ('google', google.run(chart))]:
            error = float(np.max(np.abs(image[8:-8, 8:-8] - [.2, .4, .6])))
            if error > 1e-4:
                raise AssertionError(f'{name}: CFA/calibration chart error {error}')
        report = {'opencl_gpu': google.name, 'opengl_gpu': ours.context.info['GL_RENDERER'],
                  'google_source_sha256': google.source_sha256,
                  'our_function_sha256': hashlib.sha256(ours.function_source.encode()).hexdigest(),
                  'limits': 'Single base-frame reconstruction only; equal matrix, gain 4, Reinhard and gamma; no denoise or sharpening. No sharp ground truth for the hand.', 'shots': []}
        for path in args.bursts:
            raw, base, meta, cfa, raw_hash = load_base(path)
            neutral = np.array(meta['params']['whitePoint'], np.float32)
            wb = 1 / neutral
            gains = np.array([wb[0], wb[1], wb[1], wb[2]], np.float32)
            balanced = raw.copy()
            for y in range(2):
                for x in range(2):
                    balanced[y::2, x::2] *= gains[2 * y + x]
            current, valid = ours.run(raw, base[3:5], .6)
            sharp, _ = ours.run(raw, base[3:5], 1.0)
            variants = {'our_current': current * wb, 'our_motion_sharp': sharp * wb,
                        'google_wb_after': google.run(raw) * wb, 'google_wb_before': google.run(balanced)}
            matrix = np.array(meta['params']['resultCst'], np.float32).reshape(3, 3)
            shot = {'burst': str(path), 'base_raw_sha256': raw_hash, 'base_order_ms': base[5], 'noise': base[3:5],
                    'valid_share': float(valid.mean()), 'variants': list(variants), 'crop_metrics': {}}
            previews = {}
            for name, rgb in variants.items():
                previews[name] = Image.fromarray(display(restore(rgb, cfa), matrix))
                previews[name].save(args.out / f'{path.stem}-{name}.png')
            w, h = next(iter(previews.values())).size
            boxes = {'hand': (int(w * .15), int(h * .24), int(w * .65), int(h * .58)),
                     'cloth': (int(w * .42), int(h * .72), int(w * .82), int(h * .94)),
                     'wall': (int(w * .78), int(h * .35), int(w * .94), int(h * .51))}
            for region, box in boxes.items():
                size = (box[2] - box[0], box[3] - box[1])
                sheet = Image.new('RGB', (size[0] * len(previews), size[1] + 30), 'white')
                draw = ImageDraw.Draw(sheet)
                for i, (name, image) in enumerate(previews.items()):
                    sheet.paste(image.crop(box), (i * size[0], 30))
                    draw.text((i * size[0] + 8, 8), name, fill='black')
                sheet.save(args.out / f'{path.stem}-{region}-comparison.jpg', quality=97, subsampling=0)
                shot['crop_metrics'][region] = {'box': box}
            report['shots'].append(shot)
            print(path.name, 'complete', flush=True)
        (args.out / 'comparison.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    finally:
        google.close()


if __name__ == '__main__':
    main()
