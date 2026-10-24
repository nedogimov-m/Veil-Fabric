#!/usr/bin/env python3
"""Run focused real native OIT property accumulation/resolve or automatic integration checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VEIL = ROOT
CACHE = Path.home() / '.gradle/caches'
MODULES = CACHE / 'modules-2/files-2.1'
LOOM = CACHE / 'fabric-loom'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--scope', choices=['component', 'integration'], default='component')
selection = parser.parse_args().scope
WORK = ROOT / ('build/oit-' + selection + '-verification')
CLASSES = WORK / 'classes'
CLASSES.mkdir(parents=True, exist_ok=True)
JAVA = Path(os.environ['JAVA_HOME']) / 'bin'

def artifact(group, name, version):
    return next((MODULES / group / name / version).glob('*/' + name + '-' + version + '.jar'))

metadata = json.loads((LOOM / '26.3/mojang_minecraft_info.json').read_text())
mc = LOOM / 'minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
cp = [mc]
for library in metadata['libraries']:
    group, name, version, *_ = library['name'].split(':')
    matches = list((MODULES / group / name / version).glob('*/' + Path(library['downloads']['artifact']['path']).name))
    cp.extend(matches[:1])
fabric = artifact('net.fabricmc.fabric-api', 'fabric-api', '0.163.0+26.3')
cp += [artifact('net.fabricmc', 'fabric-loader', '0.19.5'), artifact('org.jetbrains', 'annotations', '26.0.2'),
       VEIL / 'local-maven/gg/moonflower/molang-compiler/3.1.1.19/molang-compiler-3.1.1.19.jar',
       VEIL / 'local-maven/io/github/ocelot/glsl-processor/0.2.3/glsl-processor-0.2.3.jar']
modules = WORK / 'fabric-api'
modules.mkdir(exist_ok=True)
with zipfile.ZipFile(fabric) as archive:
    for entry in archive.namelist():
        if entry.startswith('META-INF/jars/') and entry.endswith('.jar'):
            path = modules / Path(entry).name
            if not path.exists(): path.write_bytes(archive.read(entry))
            cp.append(path)
native = VEIL / 'build/gpu-verification/classes'
if not (native / 'foundry/veil/api/client/render/gpu/GpuShaderProgram.class').is_file():
    raise SystemExit('Compile the genuine Veil native GPU boundary first.')
sources = [ROOT / 'common/src/main/java' / name for name in [
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicOit.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicOitProcessor.java',
]] + [ROOT / 'port-tests/GpuDynamicOitAcceptance.java']
subprocess.run([str(JAVA / 'javac'), '--release', '25', '-proc:none', '-implicit:none', '-cp',
                os.pathsep.join(map(str, [native, *cp])), '-d', str(CLASSES), *map(str, sources)], check=True)
jar = WORK / 'veil-oit-acceptance.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as output:
    output.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'veil_oit_acceptance',
        'version': '1.0.0', 'name': 'Veil native OIT acceptance (test only)', 'environment': 'client',
        'entrypoints': {'client': ['GpuDynamicOitAcceptance']},
        **({'mixins': ['veil-oit-bridge-test.mixins.json', 'veil-oit-world-test.mixins.json']} if selection == 'integration' else {}),
        'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}))
    if selection == 'integration':
        output.writestr('veil-oit-bridge-test.mixins.json', json.dumps({'required': True, 'minVersion': '0.8', 'package': 'foundry.veil.mixin.shader.client',
            'compatibilityLevel': 'JAVA_25', 'client': ['NativePipelineCacheMixin', 'NativeShaderManagerMixin', 'NativeFrontendRenderPassMixin'], 'injectors': {'defaultRequire': 1}}))
        output.writestr('veil-oit-world-test.mixins.json', json.dumps({'required': True, 'minVersion': '0.8', 'package': 'foundry.veil.mixin.dynamicbuffer.client',
            'compatibilityLevel': 'JAVA_25', 'client': ['NativeDynamicEncoderMixin', 'NativeDynamicPassMixin', 'NativeDynamicLevelRendererMixin', 'NativeDynamicOitProviderMixin'], 'injectors': {'defaultRequire': 1}}))
    for directory in [ROOT / 'common/src/main/resources/assets/veil/shaders', ROOT / 'port-tests/resources/assets/veil/shaders']:
        for path in directory.rglob('*'):
            if path.is_file(): output.write(path, 'assets/veil/shaders/' + str(path.relative_to(directory)))
    seen = set()
    for directory in [CLASSES, native]:
        for path in directory.rglob('*.class'):
            name = str(path.relative_to(directory))
            if name not in seen: output.write(path, name); seen.add(name)
    output.write(ROOT / 'LICENSE', 'LICENSE_Veil')
for name in ['asm', 'asm-analysis', 'asm-commons', 'asm-tree', 'asm-util']:
    cp.append(artifact('org.ow2.asm', name, '9.10.1'))
cp += [artifact('net.fabricmc', 'sponge-mixin', '0.17.4+mixin.0.8.7'),
       artifact('io.github.llamalad7', 'mixinextras-fabric', '0.5.5'), fabric, jar]
run = WORK / 'run'
run.mkdir(exist_ok=True)
report = WORK / 'runtime.json'
report.unlink(missing_ok=True)
asset_index = '26.3-' + metadata['assetIndex']['id']
if not (LOOM / 'assets/indexes' / (asset_index + '.json')).is_file(): asset_index = metadata['assetIndex']['id']
args = ['-XstartOnFirstThread', '-Xmx2G', '-Dfabric.development=true',
        '-Dfabric.defaultModDistributionNamespace=official', '-Dfabric.defaultMixinRemapType=static',
        '-Dfabric.gameJarPath=' + str(mc), '-Dveil.oit.report=' + str(report), '-Dveil.oit.scope=' + selection,
        '-classpath', os.pathsep.join(map(str, cp)), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
        '--version', '26.3', '--username', 'VeilOitTest', '--accessToken', '0', '--gameDir', str(run),
        '--assetsDir', str(LOOM / 'assets'), '--assetIndex', asset_index, '--graphicsBackend', 'vulkan', '--width', '480', '--height', '320']
argfile = WORK / 'client.args'
argfile.write_text('\n'.join('"' + arg.replace('\\', '\\\\').replace('"', '\\"') + '"' for arg in args))
print('Launching focused real native OIT ' + selection + ' Vulkan scope; full Veil is not loaded.', flush=True)
subprocess.run([str(JAVA / 'java'), '@' + str(argfile)], cwd=run, timeout=180, check=True)
result = json.loads(report.read_text())
if result.get('status') != 'passed': raise SystemExit('Native OIT GPU check failed: ' + str(result))
inputs = sources + [Path(__file__).resolve()] + list((ROOT / 'port-tests/resources/assets/veil/shaders').glob('core/gpu_oit.*')) + list((ROOT / 'common/src/main/resources/assets/veil/shaders').glob('core/dynamic_oit_composite.*'))
record = {'check': 'native-oit-gpu-check', 'minecraft': '26.3', 'scope': 'genuine native OIT algorithms, vec4 property accumulation/coverage and resolve; test mode ' + selection + '; not full Veil startup',
          'result': 'PASS', 'runtime': result,
          'inputs': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
          'native_classes': {str(p.relative_to(native)): hashlib.sha256(p.read_bytes()).hexdigest() for p in native.rglob('*.class')},
          'classpath': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in cp}}
(WORK / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
print('Actual native OIT GPU check passed:', json.dumps(result), flush=True)
