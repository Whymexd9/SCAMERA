#!/usr/bin/env python3
"""Restore the hash-pinned private model bundle without putting binaries in git.
Bootstrap via the encrypted archive key or a private HTTPS URL in Actions
secrets, then reuse a hash-verified Actions artifact. Never include download URLs or credentials in output.
"""
import argparse
import hashlib
import io
import json
import os
import subprocess
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from package_vivo_neural import pinned_assets

# v2 (4 October 2026): v1 plus the CRE motion runtime and the Quad/VSR contexts, which v1 lacked, so the Actions APK
# shipped without them while local builds had them. All 28 entries are required.
SHA256 = 'b7698172a5e4d76b0b57b357073e6c8aeb97157c9cae950e54ac683b8db82774'
NAME = 'SCAMERA-neural-assets-v2'
ARCHIVE = 'scamera-neural-assets-v2.zip'
LIMIT = 128 * 1024 * 1024
# Entries of bundle v2 that are verified but no longer packaged (the vivo VSR upscale was removed in the settings cleanup;
# the encrypted bundle and its secret stay unchanged).
BUNDLE_ONLY = {'hexquad': {
    'vsr1x-v79.bin': 'db7eec6b89c9040e05be84bfacebb9d7dacb725817998047f7c51e199027b72c',
    'vsr2x-v79.bin': 'd392f3c23181bd792f766ab21b7635f966edbc188ff323d166ed6859a395d118',
    'vsr4x-v79.bin': 'cc6d236a3f6da5214c687d93e16f35715fbeffd36c52eef417ad68f2a0dcb83e'}}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def download(url, headers=None, redirect=False):
    if urllib.parse.urlsplit(url).scheme != 'https':
        raise ValueError('Asset download must use HTTPS')
    request = urllib.request.Request(url, headers={'User-Agent': 'SCAMERA-CI', **(headers or {})})
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=90) as response:
            data = response.read(LIMIT + 1)
    except urllib.error.HTTPError as error:
        if redirect and error.code in (301, 302, 303, 307, 308):
            # Signed storage redirects must never receive the GitHub token.
            return download(error.headers['Location'])
        raise RuntimeError('Asset service returned HTTP ' + str(error.code)) from None
    except Exception:
        raise RuntimeError('Asset download failed (URL omitted)') from None
    if len(data) > LIMIT:
        raise ValueError('Asset download exceeded size limit')
    return data

def decrypt_bundle(manifest, parts, key):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    for part, descriptor in zip(parts, manifest['parts']):
        if len(part) != descriptor['bytes'] or hashlib.sha256(part).hexdigest() != descriptor['sha256']:
            raise ValueError('Encrypted asset part checksum mismatch')
    ciphertext = b''.join(parts)
    if hashlib.sha256(ciphertext).hexdigest() != manifest['ciphertext_sha256']:
        raise ValueError('Encrypted bundle checksum mismatch')
    try:
        plaintext = AESGCM(bytes.fromhex(key)).decrypt(ciphertext[:12], ciphertext[12:],
                                                       manifest['associated_data'].encode())
    except Exception:
        raise ValueError('Encrypted bundle authentication failed') from None
    if hashlib.sha256(plaintext).hexdigest() != SHA256:
        raise ValueError('Decrypted bundle checksum mismatch')
    return plaintext

def encrypted_seed(key):
    manifest = json.loads(Path(__file__).with_name('neural-assets-encrypted.json').read_text())
    commit = manifest['commit']
    # checkout@v4 with fetch-depth: 0 already fetched the seed branch. Read
    # pinned Git objects locally instead of requesting 28 raw-content URLs.
    present = subprocess.run(['git', 'cat-file', '-e', commit + '^{commit}'],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if present.returncode:
        subprocess.run(['git', 'fetch', '--depth=1', 'origin', commit], check=True)
    parts = [subprocess.check_output(['git', 'show', commit + ':' + p['name']])
             for p in manifest['parts']]
    return decrypt_bundle(manifest, parts, key)

def restore():
    key = os.environ.get('SCAMERA_NEURAL_ASSETS_KEY_V2', '')
    if key:
        return encrypted_seed(key)
    seed = os.environ.get('SCAMERA_NEURAL_ASSETS_URL', '')
    repository = os.environ['GITHUB_REPOSITORY']
    token = os.environ['GH_TOKEN']
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json'}
    api = 'https://api.github.com/repos/' + repository + '/actions/artifacts'
    result = json.loads(download(api + '?name=' + NAME + '&per_page=100', headers))
    for artifact in sorted(result['artifacts'], key=lambda a: a['id'], reverse=True):
        if artifact['name'] != NAME or artifact['expired']:
            continue
        wrapper = download(api + '/' + str(artifact['id']) + '/zip', headers, redirect=True)
        with zipfile.ZipFile(io.BytesIO(wrapper)) as archive:
            info = archive.getinfo(ARCHIVE)
            if info.file_size > LIMIT:
                raise ValueError('Asset artifact exceeded size limit')
            return archive.read(info)
    if seed:
        return download(seed, redirect=True)
    raise RuntimeError('Private model bundle is not provisioned. Configure the encrypted '
                       'bundle key SCAMERA_NEURAL_ASSETS_KEY_V2 or bootstrap URL '
                       'SCAMERA_NEURAL_ASSETS_URL. No incomplete APK will be published.')

def unpack(data, output):
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError('Private bundle SHA256 mismatch')
    manifests = {'bundle': pinned_assets(),
                 'hexquad': {**pinned_assets('HEX_FILES', 6), **pinned_assets('QUAD_FILES', 3), **BUNDLE_ONLY['hexquad']},
                 'nice': {**pinned_assets('NICE_FILES', 6), **pinned_assets('NICE_TONE_FILES', 5)}}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        expected = {group + '/' + name for group, manifest in manifests.items() for name in manifest}
        if len(archive.namelist()) != len(expected) or set(archive.namelist()) != expected:
            raise ValueError('Unexpected private bundle entries')
        for group, manifest in manifests.items():
            for name, digest in manifest.items():
                contents = archive.read(group + '/' + name)
                if hashlib.sha256(contents).hexdigest() != digest:
                    raise ValueError('Private asset SHA256 mismatch: ' + name)
                target = output / group / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contents)
    (output / ARCHIVE).write_bytes(data)
    print('Verified all ' + str(sum(len(m) for m in manifests.values())) + ' model/runtime assets; bundle SHA256=' + SHA256)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--archive', type=Path, help='Validate a local bundle without network access')
    args = parser.parse_args()
    try:
        unpack(args.archive.read_bytes() if args.archive else restore(), args.output)
    except Exception as error:
        raise SystemExit(str(error))
