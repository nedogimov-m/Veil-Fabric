#!/usr/bin/env python3
"""Check actual Sodium26.3 source/include processing through the genuine native shader core."""
from pathlib import Path
import hashlib, json, os, shlex, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-sodium-shader-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
sources = [root / 'common/src/main/java/foundry/veil/impl/client/render/shader/processor' / name
    for name in ['NativeSodiumShaderProcessor.java', 'NativeShaderProcessor.java', 'NativeShaderIncludes.java', 'NativeGlslNodeWriter.java', 'NativeGlslTreeWriter.java']]
sources += [root / 'common/src/main/java/foundry/veil/impl/client/render/dynamicbuffer/DynamicBufferProcessor.java',
    root / 'port-tests/NativeSodiumShaderAcceptance.java']
args = shlex.split((repo / 'build/porting/suite-diagnostic/javac.args').read_text())
classpath = os.pathsep.join([str(root / 'build/native-defaults-verification/classes'), str(root / 'build/gpu-verification/classes'),
    args[args.index('-classpath') + 1]])
sodium = next(Path(entry) for entry in args[args.index('-classpath') + 1].split(os.pathsep)
    if Path(entry).name == 'sodium-mc26.3-0.9.2-fabric.jar')
assets = work / 'actual-sodium-resources'
with zipfile.ZipFile(sodium) as archive:
    for item in archive.infolist():
        if item.filename.startswith('assets/sodium/shaders/') and not item.is_dir():
            path = assets / item.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(item))
mc = Path.home() / '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
with zipfile.ZipFile(mc) as archive:
    for item in archive.infolist():
        if item.filename.startswith('assets/minecraft/shaders/include/') and not item.is_dir():
            path = assets / item.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(item))
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', classpath,
    '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
result = subprocess.run([str(java / 'java'), '-cp', os.pathsep.join([str(classes), classpath]),
    'NativeSodiumShaderAcceptance', str(assets), str(work)], text=True, capture_output=True)
(work / 'runtime.log').write_text(result.stdout + result.stderr)
print(result.stdout + result.stderr, end='')
if result.returncode:
    raise SystemExit(result.returncode)
manifest = {'scope': 'Exact genuine Sodium0.9.2+mc26.3 source/include pair through native processor registration and real shared dynamic AST outputs. CPU contract/source proof; no native optional Sodium MRT runtime certification.',
    'result': 'PASS', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'sodium_jar': {str(sodium): hashlib.sha256(sodium.read_bytes()).hexdigest()},
    'minecraft_jar': {str(mc): hashlib.sha256(mc.read_bytes()).hexdigest()},
    'processed': {name: hashlib.sha256((work / name).read_bytes()).hexdigest() for name in ['processed.vsh', 'processed.fsh']},
    'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
