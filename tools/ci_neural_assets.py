#!/usr/bin/env python3
"""Restore the hash-pinned private model bundle without putting binaries in git.
Bootstrap via SCAMERA_NEURAL_ASSETS_URL (an Actions secret), then reuse an
Actions artifact. Never include download URLs or credentials in output.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from package_vivo_neural import pinned_assets

SHA256 = '7a2c0d642f3fce5dd20f4f5c0bb94cbd9f6216cae7fd504cb380f94b85c54587'
NAME = 'SCAMERA-neural-assets-v1'
ARCHIVE = 'scamera-neural-assets-v1.zip'
LIMIT = 128 * 1024 * 1024

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

def restore():
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
    raise RuntimeError('Private model bundle is not provisioned. Set Actions secret '
                       'SCAMERA_NEURAL_ASSETS_URL to an HTTPS download of the pinned bundle '
                       'and re-run this job. No incomplete APK will be published.')

def unpack(data, output):
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError('Private bundle SHA256 mismatch')
    manifests = {'bundle': pinned_assets(), 'hexquad': pinned_assets('HEX_FILES', 6),
                 'nice': {**pinned_assets('NICE_FILES', 1), **pinned_assets('NICE_TONE_FILES', 5)}}
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
    print('Verified all 17 model/runtime assets; bundle SHA256=' + SHA256)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--archive', type=Path, help='Validate a local bundle without network access')
    args = parser.parse_args()
    try:
        unpack(args.archive.read_bytes() if args.archive else restore(), args.output)
    except Exception as error:
        raise SystemExit(str(error))
