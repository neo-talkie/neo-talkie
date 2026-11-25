#!/usr/bin/env python3
"""Download public runtime dependencies, verifying pinned SHA-256. Not a test."""
import hashlib, urllib.request
from build import LIBS, HASHES
urls = [
 'https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/3.12.13/okhttp-3.12.13.jar',
 'https://repo.maven.apache.org/maven2/com/squareup/okio/okio/1.15.0/okio-1.15.0.jar']
for path, digest, url in zip(LIBS, HASHES, urls):
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest: continue
    data = urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != digest: raise SystemExit('Checksum mismatch: ' + path.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
print('Verified public Android dependencies. See docs/getting-started.md for SDK/JDK setup.')
