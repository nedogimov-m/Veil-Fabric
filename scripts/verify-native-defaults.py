#!/usr/bin/env python3
"""Verify only native authored time/shade/normal defaults, with immutable view and actual per-draw owners."""
from pathlib import Path
import argparse, hashlib, json, os, shlex, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', action='store_true')
options = parser.parse_args()
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-defaults-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
native = root / 'build/rendertype-verification'
gpu = root / 'build/gpu-verification'
base = root / 'common/src/main/java/foundry/veil'
sources = [base / name for name in ['api/client/render/shader/NativeShaderDefaults.java', 'impl/client/render/shader/processor/NativeShaderDefaultsProcessor.java', 'impl/client/render/shader/processor/NativeSodiumShaderProcessor.java', 'impl/client/render/shader/processor/NativeShaderProcessor.java', 'api/client/render/shader/NativePipelineBridge.java', 'impl/client/render/shader/injection/NativeShaderInjectionFrames.java']]
sources.append(root / 'port-tests/NativeShaderDefaultsAcceptance.java')
sources += [base / ('impl/client/render/shader/processor/' + name) for name in ['NativeGlslNodeWriter.java', 'NativeGlslTreeWriter.java', 'NativeShaderIncludes.java']]
args = shlex.split((repo / 'build/porting/suite-diagnostic/javac.args').read_text())
boundaries = (gpu / 'classes', native / 'classes')
compile_cp = os.pathsep.join([*map(str, boundaries), args[args.index('-classpath') + 1]])
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', compile_cp, '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
print('Genuine native default production owners plus new fixture javac:', result.returncode, flush=True)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
resources = root / 'port-tests/resources'
test_resources = [resources / 'assets/veil_test/shaders/native_defaults.vsh', resources / 'assets/veil_test/shaders/native_defaults.fsh']
manifest = {'scope': 'Actual native automatic authored defaults only: typed generated UBOs/view lifetime, real native DynamicTransforms normal expression and ordinary owned numeric preservation. No complete Veil startup claim.', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, *test_resources, Path(__file__).resolve()]}, 'boundaries': {str(folder): {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*.class'))} for folder in boundaries}}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
if not options.client:
    raise SystemExit(0)
jar = work / 'native-defaults-acceptance.jar'
with zipfile.ZipFile(native / 'native-rendertype-acceptance.jar') as existing, zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    for item in existing.infolist():
        if item.filename == 'fabric.mod.json' or item.filename.endswith('.class'):
            continue
        target.writestr(item, existing.read(item))
    target.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'veil_native_defaults_acceptance', 'version': '1.0.0', 'environment': 'client', 'name': 'Veil actual native automatic defaults acceptance (test only)', 'entrypoints': {'client': ['NativeShaderDefaultsAcceptance']}, 'mixins': ['native-defaults.mixins.json'], 'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}))
    target.writestr('native-defaults.mixins.json', json.dumps({'required': True, 'minVersion': '0.8', 'package': 'foundry.veil.mixin.shader', 'compatibilityLevel': 'JAVA_25', 'client': ['client.NativePipelineCacheMixin', 'client.NativeFrontendRenderPassMixin', 'client.NativeShaderEncoderMixin'], 'injectors': {'defaultRequire': 1}}))
    for p in test_resources:
        target.write(p, str(p.relative_to(resources)))
    seen = set()
    for folder in (classes, *boundaries):
        for p in folder.rglob('*.class'):
            name = str(p.relative_to(folder))
            if name not in seen:
                target.write(p, name)
                seen.add(name)
launcher = shlex.split((native / 'client.args').read_text())
index = launcher.index('-classpath') + 1
launcher[index] = os.pathsep.join(str(jar) if entry == str(native / 'native-rendertype-acceptance.jar') else entry for entry in launcher[index].split(os.pathsep))
launcher = [entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')]
run = work / 'run'
run.mkdir(exist_ok=True)
launcher[launcher.index('--gameDir') + 1] = str(run)
report = work / 'runtime.json'
report.unlink(missing_ok=True)
launcher.insert(0, '-Dveil.defaults.report=' + str(report))
argfile = work / 'client.args'
argfile.write_text('\n'.join('"' + entry.replace('\\', '\\\\').replace('"', '\\"') + '"' for entry in launcher))
print('Launching only new authored automatic native default checks on actual Vulkan.', flush=True)
result = subprocess.run([str(java / 'java'), '@' + str(argfile)], cwd=run, timeout=180)
if result.returncode:
    raise SystemExit(result.returncode)
if not report.is_file():
    raise SystemExit('Native default fixture produced no report')
data = json.loads(report.read_text())
print('New native defaults result:', data)
if data.get('status') != 'passed':
    raise SystemExit(1)
