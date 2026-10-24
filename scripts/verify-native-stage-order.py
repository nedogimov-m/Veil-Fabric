#!/usr/bin/env python3
"""Check genuine Fabric event phase ordering without a client/GPU launch."""
from pathlib import Path
import hashlib, json, os, shlex, subprocess

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-stage-order-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
sources = [root / 'common/src/main/java/foundry/veil/api/event/VeilRenderLevelStageEvent.java',
    root / 'fabric/src/main/java/foundry/veil/fabric/event/FabricVeilRenderLevelStageEvent.java',
    root / 'port-tests/NativeStageOrderingAcceptance.java']
args = shlex.split((repo / 'build/porting/suite-diagnostic/javac.args').read_text())
classpath = args[args.index('-classpath') + 1]
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', classpath,
    '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
result = subprocess.run([str(java / 'java'), '-cp', os.pathsep.join([str(classes), classpath]),
    'NativeStageOrderingAcceptance'], text=True, capture_output=True)
(work / 'runtime.log').write_text(result.stdout + result.stderr)
print(result.stdout + result.stderr, end='')
if result.returncode:
    raise SystemExit(result.returncode)
production = [root / 'common/src/main/java/foundry/veil/platform/VeilEventPlatform.java',
    root / 'fabric/src/main/java/foundry/veil/fabric/platform/FabricVeilEventPlatform.java',
    root / 'common/src/main/java/foundry/veil/api/client/render/VeilRenderSystem.java']
manifest = {'scope': 'Actual Fabric phased event invocation only: final listeners registered early still follow every default listener across all eight native stages. VRS final draw/composite behavior requires packaged world integration.',
    'result': 'PASS', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'integration_sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production},
    'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
