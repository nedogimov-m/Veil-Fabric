#!/usr/bin/env python3
"""Verify the real native registration callbacks and accepted profiler debug mirror only."""
from pathlib import Path
import argparse, hashlib, json, os, shlex, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', action='store_true')
options = parser.parse_args()
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-client-submissions-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
previous = root / 'build/native-skeletal-verification'
boundary = previous / 'veil-native-skeletal-acceptance.jar'
base = root / 'common/src/main/java/foundry/veil'
fabric = root / 'fabric/src/main/java/foundry/veil/fabric'
sources = [base / name for name in ['api/event/VeilRegisterFixedBuffersEvent.java',
    'api/event/VeilRegisterBlockLayersEvent.java', 'api/event/VeilRenderLevelStageEvent.java',
    'api/client/render/ext/VeilDebug.java', 'mixin/debug/client/DebugActiveProfilerMixin.java']]
sources += [fabric / name for name in ['FabricRenderTypeStageHandler.java',
    'event/FabricVeilRegisterFixedBuffersEvent.java', 'event/FabricVeilRegisterBlockLayersEvent.java',
    'event/FabricVeilRenderLevelStageEvent.java']]
sources.append(root / 'port-tests/GpuClientSubmissionsAcceptance.java')
args = shlex.split((repo / 'build/porting/suite-diagnostic/javac.args').read_text())
launcher = shlex.split((previous / 'client.args').read_text())
compile_cp = os.pathsep.join([str(boundary), args[args.index('-classpath') + 1], launcher[launcher.index('-classpath') + 1]])
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', compile_cp,
    '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
print('Nine actual native registration/debug production owners plus new fixture javac:', result.returncode, flush=True)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
manifest = {'scope': 'Actual Fabric native fixed/block submission registration callbacks and accepted ActiveProfiler debug mirror. Reuses the genuine previously verified skeletal/native boundary; no full Veil startup.',
    'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, Path(__file__).resolve()]},
    'boundary': {str(boundary.relative_to(root)): hashlib.sha256(boundary.read_bytes()).hexdigest()}}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
if not options.client:
    raise SystemExit(0)
jar = work / 'native-client-submissions-acceptance.jar'
with zipfile.ZipFile(boundary) as existing, zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    overridden = {str(p.relative_to(classes)) for p in classes.rglob('*.class')}
    for item in existing.infolist():
        if item.filename == 'fabric.mod.json' or item.filename in overridden:
            continue
        target.writestr(item, existing.read(item))
    target.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'veil_native_client_submissions_acceptance', 'version': '1.0.0',
        'environment': 'client', 'name': 'Veil actual native registration acceptance (test only)',
        'entrypoints': {'fabric-client-gametest': ['foundry.veil.porttest.GpuClientSubmissionsAcceptance']},
        'mixins': ['native-client-submissions.mixins.json'], 'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}))
    target.writestr('native-client-submissions.mixins.json', json.dumps({'required': True, 'minVersion': '0.8', 'package': 'foundry.veil.mixin.debug.client',
        'compatibilityLevel': 'JAVA_25', 'client': ['DebugActiveProfilerMixin'], 'injectors': {'defaultRequire': 1}}))
    for path in classes.rglob('*.class'):
        target.write(path, str(path.relative_to(classes)))
index = launcher.index('-classpath') + 1
launcher[index] = os.pathsep.join(str(jar) if entry == str(boundary) else entry for entry in launcher[index].split(os.pathsep))
launcher = [entry for entry in launcher if not entry.startswith('-Dveil.skeletal.')]
launcher = ['-Dfabric.client.gametest.modid=veil_native_client_submissions_acceptance' if entry.startswith('-Dfabric.client.gametest.modid=') else entry for entry in launcher]
run = work / 'run'
run.mkdir(exist_ok=True)
launcher[launcher.index('--gameDir') + 1] = str(run)
report = work / 'runtime.json'
report.unlink(missing_ok=True)
launcher.insert(0, '-Dveil.submissions.report=' + str(report))
argfile = work / 'client.args'
argfile.write_text('\n'.join('"' + entry.replace('\\', '\\\\').replace('"', '\\"') + '"' for entry in launcher))
print('Launching actual native world registration and profiler-mirror checks on Vulkan.', flush=True)
result = subprocess.run([str(java / 'java'), '@' + str(argfile)], cwd=run, timeout=180)
if result.returncode:
    raise SystemExit(result.returncode)
if not report.is_file():
    raise SystemExit('Native client registration fixture produced no report')
data = json.loads(report.read_text())
print('New native client registration result:', data)
if data.get('status') != 'passed':
    raise SystemExit(1)
manifest['result'] = 'PASS'
manifest['runtime'] = data
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
