#!/usr/bin/env python3
"""Compile real clouds/flat/OIT shader pairs on CPU, including generic AST precedence round trips."""
from pathlib import Path
import hashlib, json, os, shlex, shutil, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-cloud-shader-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
args = shlex.split((repo / 'build/porting/packaged-smoke/veil/client/launch.args').read_text())
cp = args[args.index('-classpath') + 1].split(os.pathsep)
production = root / 'build/libs/veil-fabric-4.3.2-port.1+mc26.3.jar'
frozen = work / 'actual-production-veil.jar'
shutil.copy2(production, frozen)
cp = [str(frozen) if entry == str(production) else entry for entry in cp]
classpath = os.pathsep.join(cp)
mc = Path.home() / '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
assets = work / 'actual-minecraft-resources'
with zipfile.ZipFile(mc) as archive:
    for item in archive.infolist():
        if item.filename.startswith('assets/minecraft/shaders/') and not item.is_dir():
            path = assets / item.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(item))
base = root / 'common/src/main/java/foundry/veil/impl/client/render/shader/processor'
sources = [base / name for name in ['NativeGlslNodeWriter.java', 'NativeGlslTreeWriter.java', 'NativeShaderProcessor.java', 'NativeShaderIncludes.java']]
sources += [root / 'common/src/main/java/foundry/veil/impl/client/render/dynamicbuffer/DynamicBufferProcessor.java', root / 'port-tests/NativeCloudShaderAcceptance.java']
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', classpath,
    '-d', str(classes), *map(str, sources)], capture_output=True, text=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
result = subprocess.run([str(java / 'java'), '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join([str(classes), classpath]),
    'NativeCloudShaderAcceptance', str(assets), str(work)], capture_output=True, text=True)
(work / 'runtime.log').write_text(result.stdout + result.stderr)
print(result.stdout + result.stderr, end='')
if result.returncode:
    raise SystemExit(result.returncode)
manifest = {'scope': 'Five generic expression AST round trips and actual CPU shaderc compilation of both original Minecraft26.3 stages for all eight genuine native clouds/flat/OIT pipeline definitions/defines. No GPU/client launch.',
    'result': 'PASS', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'actual_jar_inputs': {str(mc): hashlib.sha256(mc.read_bytes()).hexdigest(), str(frozen.relative_to(root)): hashlib.sha256(frozen.read_bytes()).hexdigest()},
    'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
