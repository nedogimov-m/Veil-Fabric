#!/usr/bin/env python3
"""Reproduce native coordinate/cardinal-light records and explicit CPU scissor geometry acceptance."""
from pathlib import Path
import hashlib, json, os, shlex, subprocess

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-client-helpers'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
base = root / 'common/src/main/java/foundry/veil'
sources = [base / 'api/client/render/shader/NativeShaderDefaults.java', base / 'impl/ClientCoordinates.java',
    base / 'api/client/util/ScissorStack.java', root / 'port-tests/NativeClientHelpersAcceptance.java']
args = shlex.split((repo / 'build/porting/suite-diagnostic/javac.args').read_text())
classpath = os.pathsep.join([str(root / 'build/gpu-verification/classes'), args[args.index('-classpath') + 1]])
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', classpath,
    '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
result = subprocess.run([str(java / 'java'), '-cp', os.pathsep.join([str(classes), classpath]),
    'NativeClientHelpersAcceptance'], text=True, capture_output=True)
(work / 'runtime.log').write_text(result.stdout + result.stderr)
print(result.stdout + result.stderr, end='')
if result.returncode:
    raise SystemExit(result.returncode)
manifest = {'scope': 'Actual native CPU command parsing/cardinal-light records plus explicit scissor geometry; GPU scissors separately required.',
    'result': 'PASS', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
