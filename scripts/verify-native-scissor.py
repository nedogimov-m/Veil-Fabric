#!/usr/bin/env python3
"""Verify only only explicit native pass scissor pixels."""
from pathlib import Path
import argparse, hashlib, json, os, shlex, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', action='store_true')
options = parser.parse_args()
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-scissor-verification'
classes = work / 'classes'
classes.mkdir(parents=True, exist_ok=True)
native = root / 'build/rendertype-verification'
gpu = root / 'build/gpu-verification'
base = root / 'common/src/main/java/foundry/veil'
sources = [base / 'api/client/util/ScissorStack.java', root / 'port-tests/NativeScissorAcceptance.java']
args = shlex.split((repo / 'build/porting/suite-diagnostic/javac.args').read_text())
boundaries = (gpu / 'classes', native / 'classes')
compile_cp = os.pathsep.join([*map(str, boundaries), args[args.index('-classpath') + 1]])
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', compile_cp, '-d', str(classes), *map(str, sources)], text=True, capture_output=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
print('Genuine explicit native scissor helper plus new fixture javac:', result.returncode, flush=True)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
resources = root / 'port-tests/resources'
test_resources = [resources / 'assets/veil_test/shaders/native_scissor.vsh', resources / 'assets/veil_test/shaders/native_scissor.fsh']
manifest = {'scope': 'Exact explicit native RenderPass scissors only; GUI coordinate scaling, nested/intersection empty regions and restoration. No complete port startup.', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, *test_resources, Path(__file__).resolve()]}, 'boundaries': {str(folder): {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*.class'))} for folder in boundaries}}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
if not options.client:
    raise SystemExit(0)
jar = work / 'native-scissor-acceptance.jar'
with zipfile.ZipFile(native / 'native-rendertype-acceptance.jar') as existing, zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    for item in existing.infolist():
        if item.filename == 'fabric.mod.json' or item.filename.endswith('.class'):
            continue
        target.writestr(item, existing.read(item))
    target.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'veil_native_scissor_acceptance', 'version': '1.0.0', 'environment': 'client', 'name': 'Veil actual explicit native scissor acceptance (test only)', 'entrypoints': {'client': ['NativeScissorAcceptance']}, 'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}))
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
launcher.insert(0, '-Dveil.scissor.report=' + str(report))
argfile = work / 'client.args'
argfile.write_text('\n'.join('"' + entry.replace('\\', '\\\\').replace('"', '\\"') + '"' for entry in launcher))
print('Launching only new explicit native scissor exact-pixel checks on actual Vulkan.', flush=True)
result = subprocess.run([str(java / 'java'), '@' + str(argfile)], cwd=run, timeout=180)
if result.returncode:
    raise SystemExit(result.returncode)
if not report.is_file():
    raise SystemExit('Native scissor fixture produced no report')
data = json.loads(report.read_text())
print('New native scissor result:', data)
if data.get('status') != 'passed':
    raise SystemExit(1)
