"""Research public XHS share pages; no cookies, private app data, or signature generation.

Copy a note link in XHS, then run:
  python tools/xhs-source-probe.py --adb-clipboard --page 2 --expected-count 7
Outputs stay in app/build/tmp/xhs-source (not release assets).
The ADB clipboard option is for this Android 9 test device, not a production API.
"""
import argparse
import io
import json
import re
import struct
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

USER_AGENT = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36')
PAGE_HOSTS = {'www.xiaohongshu.com', 'xiaohongshu.com', 'xhslink.com', 'xhslink.cn'}


def allowed(url, image=False):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ''
    if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.port not in (None, 80, 443):
        raise ValueError('Unsupported URL')
    valid = (host == 'ci.xiaohongshu.com' or host == 'xhscdn.com' or host.endswith('.xhscdn.com')) if image else host in PAGE_HOSTS
    if not valid:
        raise ValueError('Unexpected source host: ' + host)
    # Retain the supplied path/query exactly; do not strip CDN transforms/signatures.
    return urllib.parse.urlunsplit(('https', host, parsed.path, parsed.query, ''))


class CheckedRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, image):
        super().__init__()
        self.image = image

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return super().redirect_request(req, fp, code, msg, headers, allowed(newurl, self.image))


def fetch(url, image=False):
    request = urllib.request.Request(allowed(url, image), headers={
        'User-Agent': USER_AGENT, 'Referer': 'https://www.xiaohongshu.com/'})
    limit = (64 if image else 2) * 1024 * 1024
    with urllib.request.build_opener(CheckedRedirects(image)).open(request, timeout=20) as response:
        body = response.read(limit + 1)
        if len(body) > limit:
            raise ValueError('Response exceeds research download limit')
        return response.url, body


def copied_link():
    parcel = subprocess.check_output(['adb', 'shell', 'service', 'call', 'clipboard', '3',
                                      's16', 'com.android.shell']).decode()
    words = []
    for line in parcel.splitlines():
        match = re.match(r'\s*0x[0-9a-f]+: ((?:[0-9a-f]{8}\s*)+)', line)
        if match:
            words.extend(match.group(1).split())
    value = b''.join(struct.pack('<I', int(word, 16)) for word in words).decode('utf-16le', errors='ignore')
    match = re.search(r'https?://(?:xhslink\.(?:com|cn)|(?:www\.)?xiaohongshu\.com)/[^\s\x00]+', value)
    if not match:
        raise ValueError('Copy an XHS note link first')
    return match.group(0)


def note_from_html(html, final_url):
    path = urllib.parse.urlsplit(final_url).path
    match = re.fullmatch(r'/(?:discovery/item|explore)/([0-9a-f]{24})/?', path)
    if not match:
        raise ValueError('Not a note page (login/challenge/other page)')
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, flags=re.S)
    state = next((script for script in scripts if script.strip().startswith('window.__SETUP_SERVER_STATE__=')), None)
    if state is None:
        raise ValueError('Public page does not expose supported note data')
    # Parse JSON only, never execute downloaded JavaScript.
    data = json.loads(state.strip().split('=', 1)[1].rstrip(';'))
    note = data['LAUNCHER_SSR_STORE_PAGE_DATA']['noteData']
    if note.get('noteId') != match.group(1):
        raise ValueError('Page data belongs to a different note')
    if note.get('type') != 'normal' or not note.get('imageList'):
        raise ValueError('Not a supported image note')
    return note


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--url')
    source.add_argument('--adb-clipboard', action='store_true')
    parser.add_argument('--page', type=int, default=1)
    parser.add_argument('--expected-count', type=int)
    parser.add_argument('--source-file', action='store_true', help='Research the public ci CDN fileId URL, without requesting format conversion')
    parser.add_argument('--output', type=Path, default=Path('app/build/tmp/xhs-source/probe'))
    args = parser.parse_args()
    url, body = fetch(args.url or copied_link())
    note = note_from_html(body.decode('utf-8'), url)
    images = note['imageList']
    if args.expected_count is not None and args.expected_count != len(images):
        raise ValueError('App image count and public page image count differ')
    if not 1 <= args.page <= len(images):
        raise ValueError('Current page is outside the image list')
    image = images[args.page - 1]
    image_url = image.get('url')
    if args.source_file:
        file_id = image.get('fileId', '')
        if not re.fullmatch(r'[a-zA-Z0-9_/-]+', file_id) or '..' in file_id:
            raise ValueError('Unsupported file identifier')
        image_url = 'https://ci.xiaohongshu.com/' + file_id
    if not image_url:
        raise ValueError('Selected image has no public URL')
    final_image_url, content = fetch(image_url, image=True)
    from PIL import Image
    with Image.open(io.BytesIO(content)) as decoded:
        decoded.verify()
    with Image.open(io.BytesIO(content)) as decoded:
        size, image_format = decoded.size, decoded.format
    report = {
        'noteId': note['noteId'], 'count': len(images), 'selectedPage': args.page,
        'fileId': image.get('fileId'), 'declaredSize': [image.get('width'), image.get('height')],
        'decodedSize': list(size), 'format': image_format, 'bytes': len(content),
        'imageHost': urllib.parse.urlsplit(final_image_url).hostname,
        'scenes': [item.get('imageScene') for item in image.get('infoList', [])],
        'otherImageCount': len(images) - 1,
        'sourceMode': 'public-file-candidate' if args.source_file else 'page-provided-image',
        'declaredDimensionsMatch': list(size) == [image.get('width'), image.get('height')],
        'originalQualityVerified': False,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / ('image.' + image_format.lower())).write_bytes(content)
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, urllib.error.URLError, json.JSONDecodeError) as error:
        # URL errors may contain signed URLs; do not print those URLs.
        print('Probe failed: ' + (type(error).__name__ if isinstance(error, urllib.error.URLError) else str(error)))
        raise SystemExit(1)
