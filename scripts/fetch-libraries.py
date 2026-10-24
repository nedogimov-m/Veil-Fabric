#!/usr/bin/env python3
"""Fetch exact upstream artifacts into the ignored, optional local Maven cache.

No old Veil, ImGuiMC or Sodium mod is installed in a 26.3 game. Only Molang/GLSL
are platform-independent runtime libraries; ImGuiMC/Sodium are compileOnly APIs.
The original POM metadata and runtime library licenses are retained in the tree.
"""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
cache = root / '.upstream-cache'
cache.mkdir(exist_ok=True)
VEIL_URL = 'https://cdn.modrinth.com/data/3KUWeVhG/versions/jgU9q5Ka/veil-fabric-1.21.1-4.3.2.jar'
VEIL_SHA512 = 'ae90ae78afe4405c8b12bfce1780a8fd11bc3d3a2b2415aada47812686828556e841232c8dc15e2ef9590c25c1ec5e9b77b8fe31cd5247c1c9d9cba93f9ff438'

def fetch(url, path, digest, algorithm='sha256'):
    if path.exists() and hashlib.new(algorithm, path.read_bytes()).hexdigest() == digest:
        return
    data = urllib.request.urlopen(url, timeout=60).read()
    if hashlib.new(algorithm, data).hexdigest() != digest:
        raise SystemExit(f'Checksum mismatch: {url}')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print(f'Fetched and verified {path.relative_to(root)}')

upstream = cache / 'veil-fabric-1.21.1-4.3.2.jar'
fetch(VEIL_URL, upstream, VEIL_SHA512, 'sha512')
embedded = [
    ('gg.moonflower', 'molang-compiler', '3.1.1.19', 'b5d1c376891835ee4c1c1345f29703b247cf0efcaca44b264da422ab602b223b'),
    ('io.github.ocelot', 'glsl-processor', '0.2.3', '5e71812461c168ec677830521d95e6dc92f7faf82fd371c5f5ddf6abc0caacc8'),
]
with zipfile.ZipFile(upstream) as bundle:
    for group, name, version, digest in embedded:
        filename = f'{name}-{version}.jar'
        data = bundle.read('META-INF/jars/' + filename)
        if hashlib.sha256(data).hexdigest() != digest:
            raise SystemExit(f'Embedded library checksum mismatch: {filename}')
        target = root / 'local-maven' / group.replace('.', '/') / name / version / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print(f'Extracted and verified {filename}')

for group, name, version, repository, digest in [
    ('foundry.imguimc', 'imguimc-fabric-1.21.1', '1.1.0', 'https://maven.ryanhcode.dev/releases',
     '3f7f7f084e8ac3ab2d2820a45b7bb276380dc9f8e196d204cf9fec81d8cddcab'),
    ('net.caffeinemc', 'sodium-fabric', '0.8.12-alpha.2+mc1.21.1', 'https://maven.caffeinemc.net/releases',
     'e60f54d197356d98362844f2d054148d25f6401bea35100775fb49bfc1732505'),
]:
    relative = Path(group.replace('.', '/')) / name / version / f'{name}-{version}.jar'
    fetch(repository + '/' + str(relative), root / 'local-maven' / relative, digest)
print('Library cache prepared. The original 1.21.1 Veil jar is only an extraction source, never a 26.3 dependency.')
