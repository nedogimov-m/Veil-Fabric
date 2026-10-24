#!/usr/bin/env python3
"""Run focused genuine Sodium0.9.2 native provider, packed normals and visibility/reload ownership."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
import shutil

ROOT = Path(__file__).resolve().parent.parent
VEIL = ROOT
CACHE = Path.home() / '.gradle/caches'
MODULES = CACHE / 'modules-2/files-2.1'
LOOM = CACHE / 'fabric-loom'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--scope', choices=['sodium'], default='sodium')
selection = parser.parse_args().scope
WORK = ROOT / ('build/native-sodium-verification')
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
native_live = VEIL / 'build/gpu-verification/classes'
native = WORK / 'frozen-native-classes'
if native.exists(): shutil.rmtree(native)
shutil.copytree(native_live, native)
if not (native / 'foundry/veil/api/client/render/gpu/GpuShaderProgram.class').is_file():
    raise SystemExit('Compile the genuine Veil native GPU boundary first.')
src = ROOT / 'common/src/main/java/foundry/veil'
fabric_src = ROOT / 'fabric/src/main/java/foundry/veil/fabric'
sources = [src / name for name in ['api/compat/SodiumCompat.java','platform/VeilPlatform.java','ext/sodium/ChunkVertexEncoderVertexExtension.java','ext/NativePerspectiveLevelExtension.java','api/client/render/gpu/NativeLevelPerspective.java','api/client/render/shader/NativeShaderDefaults.java','impl/client/render/shader/processor/NativeShaderDefaultsProcessor.java','impl/client/render/shader/processor/NativeSodiumShaderProcessor.java','impl/client/render/shader/processor/NativeShaderProcessor.java']]
sources += [src / ('impl/client/render/shader/processor/' + name) for name in ['NativeGlslNodeWriter.java', 'NativeGlslTreeWriter.java', 'NativeShaderIncludes.java']]
sources += [fabric_src / name for name in ['platform/FabricVeilPlatform.java','compat/sodium/VeilChunkVertex.java','compat/sodium/VeilFabricSodiumCompat.java','mixin/compat/sodium/NativeSodiumPipelinesAccessor.java','mixin/compat/sodium/NativeSodiumStorageAccessor.java','mixin/compat/sodium/NativeSodiumPerspectiveGateMixin.java','mixin/compat/sodium/RenderSectionManagerAccessor.java','mixin/compat/sodium/SodiumWorldRendererAccessor.java','mixin/compat/sodium/BlockRendererMixin.java','mixin/compat/sodium/DefaultFluidRendererMixin.java','mixin/compat/sodium/ChunkMeshFormatsMixin.java','mixin/compat/sodium/ChunkVertexConsumerMixin.java','mixin/compat/sodium/ChunkVertexEncoderVertexMixin.java']]
sources += [ROOT / 'port-tests/NativeSodiumAcceptance.java']
sodium = artifact('maven.modrinth','sodium','mc26.3-0.9.2-fabric')
cp.append(sodium)
cp.append(artifact('net.fabricmc.fabric-api', 'fabric-client-gametest-api-v1', '6.0.9+4be74c3f5d'))
cp += [artifact('net.fabricmc', 'sponge-mixin', '0.17.4+mixin.0.8.7'), artifact('io.github.llamalad7', 'mixinextras-fabric', '0.5.5')]
subprocess.run([str(JAVA / 'javac'), '--release', '25', '-proc:none', '-implicit:none', '-cp',
                os.pathsep.join(map(str, [native, *cp])), '-d', str(CLASSES), *map(str, sources)], check=True)
jar = WORK / 'veil-native-sodium-acceptance.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as output:
    output.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': 'veil_native_sodium_acceptance',
        'version': '1.0.0', 'name': 'Veil genuine native Sodium acceptance (test only)', 'environment': 'client',
        'entrypoints': {'fabric-client-gametest': ['foundry.veil.porttest.NativeSodiumAcceptance']},
        'mixins': ['veil-native-sodium.mixins.json'],
        'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}))
    output.writestr('veil-native-sodium.mixins.json', json.dumps({'required': True, 'minVersion': '0.8', 'package': 'foundry.veil.fabric.mixin.compat.sodium', 'compatibilityLevel': 'JAVA_25', 'client': ['NativeSodiumPipelinesAccessor','NativeSodiumStorageAccessor','NativeSodiumPerspectiveGateMixin','RenderSectionManagerAccessor','SodiumWorldRendererAccessor','BlockRendererMixin','DefaultFluidRendererMixin','ChunkMeshFormatsMixin','ChunkVertexConsumerMixin','ChunkVertexEncoderVertexMixin'], 'injectors': {'defaultRequire':1}}))
    service='META-INF/services/foundry.veil.platform.VeilPlatform'
    output.write(ROOT / 'fabric/src/main/resources' / service, service)
    service='META-INF/services/foundry.veil.api.compat.SodiumCompat'
    output.write(ROOT / 'fabric/src/main/resources' / service, service)
    shaders = ROOT / 'common/src/main/resources/assets/veil/shaders'
    for path in shaders.rglob('*'):
        if path.is_file(): output.write(path, 'assets/veil/shaders/' + str(path.relative_to(shaders)))
    assets = ROOT / 'port-tests/resources'
    for path in assets.rglob('*'):
        if path.is_file() and not path.name.startswith('gpu_inherited'): output.write(path, str(path.relative_to(assets)))
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
args = ['-Dfabric.client.gametest=true', '-Dfabric.client.gametest.modid=veil_native_sodium_acceptance', '-Dfabric.client.gametest.testModResourcesPath=' + str(ROOT / 'port-tests/resources'), '-XstartOnFirstThread', '-Xmx2G', '-Dfabric.development=true',
        '-Dfabric.defaultModDistributionNamespace=official', '-Dfabric.defaultMixinRemapType=static',
        '-Dfabric.gameJarPath=' + str(mc), '-Dveil.sodium.report=' + str(report), '-Dveil.sodium.scope=' + selection,
        '-classpath', os.pathsep.join(map(str, cp)), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
        '--version', '26.3', '--username', 'VeilFramesTest', '--accessToken', '0', '--gameDir', str(run),
        '--assetsDir', str(LOOM / 'assets'), '--assetIndex', asset_index, '--graphicsBackend', 'vulkan', '--width', '480', '--height', '320']
argfile = WORK / 'client.args'
argfile.write_text('\n'.join('"' + arg.replace('\\', '\\\\').replace('"', '\\"') + '"' for arg in args))
print('Launching actual native Sodium0.9.2 provider/normal/reload checks ' + selection + ' Vulkan scope; full Veil is not loaded.', flush=True)
subprocess.run([str(JAVA / 'java'), '@' + str(argfile)], cwd=run, timeout=180, check=True)
result = json.loads(report.read_text())
if result.get('status') != 'passed': raise SystemExit('Native Sodium GPU check failed: ' + str(result))
inputs = sources + [Path(__file__).resolve()] + list((ROOT / 'port-tests/resources/assets/veil/shaders/core').glob('gpu_sodium_normal.*'))
record = {'check': 'native-sodium-gpu-check', 'minecraft': '26.3', 'scope': 'genuine Sodium0.9.2 provider and primary world, exact original compact payload plus real signed-normalized normal GPU pixels, native pipeline inventory, renderLists/DeferredTaskList identity scopes, real resource reload and unavailable alternate-world backend gating before mutation; test mode ' + selection + '; not full Veil startup',
          'result': 'PASS', 'runtime': result,
          'inputs': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
          'native_classes': {str(p.relative_to(native)): hashlib.sha256(p.read_bytes()).hexdigest() for p in native.rglob('*.class')},
          'classpath': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in cp}}
(WORK / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
print('Actual native Sodium GPU check passed:', json.dumps(result), flush=True)
