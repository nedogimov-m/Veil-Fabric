#!/usr/bin/env python3
"""Compile only the new real GPU component boundary; optionally run a distinct
Fabric client acceptance mod against the cached genuine Minecraft 26.3 client.
This does not compile/package the full Veil mod, nor rerun the Create baseline.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', action='store_true')
parser.add_argument('--api', action='store_true', help='Run only the CPU shader/inspector API semantics after compilation')
parser.add_argument('--checks', default='attachments,copies,blit,resize,borrow,definitions,uniforms,manager,post,blocks,blocksnapshots,fields,program,snapshots,depth,leases,reload,meshes,processors,debug,bridge', help='Only selected affected GPU checks')
args = parser.parse_args()
java = Path(os.environ['JAVA_HOME']) / 'bin'
gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
cache = gradle_home / 'caches/modules-2/files-2.1'
loom = gradle_home / 'caches/fabric-loom'
output = root / 'build/gpu-verification'
classes = output / 'classes'
classes.mkdir(parents=True, exist_ok=True)

def artifact(group, name, version):
    files = list((cache / group / name / version).glob(f'*/{name}-{version}.jar'))
    if not files:
        raise SystemExit(f'First configure Loom: missing {group}:{name}:{version}')
    return files[0]

manifest = json.loads((loom / '26.3/mojang_minecraft_info.json').read_text())
mc = loom / 'minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
classpath = [mc]
for lib in manifest['libraries']:
    group, name, version, *_ = lib['name'].split(':')
    files = list((cache / group / name / version).glob('*/' + Path(lib['downloads']['artifact']['path']).name))
    if files: classpath.append(files[0])
loader = artifact('net.fabricmc', 'fabric-loader', '0.19.5')
fabric = artifact('net.fabricmc.fabric-api', 'fabric-api', '0.163.0+26.3')
classpath += [loader, artifact('org.jetbrains', 'annotations', '26.0.2'),
              root / 'local-maven/gg/moonflower/molang-compiler/3.1.1.19/molang-compiler-3.1.1.19.jar',
              root / 'local-maven/io/github/ocelot/glsl-processor/0.2.3/glsl-processor-0.2.3.jar']
module_dir = output / 'fabric-api'
module_dir.mkdir(exist_ok=True)
with zipfile.ZipFile(fabric) as bundle:
    for name in bundle.namelist():
        if name.startswith('META-INF/jars/') and name.endswith('.jar'):
            path = module_dir / Path(name).name
            path.write_bytes(bundle.read(name))
            classpath.append(path)
client_tests = artifact('net.fabricmc.fabric-api', 'fabric-client-gametest-api-v1', '6.0.9+4be74c3f5d')
classpath.append(client_tests)
# Only the platform-independent binding API is used to compile the real inspector contracts.
# The old Minecraft ImGuiMC integration/backend is never installed into the 26.3 client.
imgui_bundle = root / 'local-maven/foundry/imguimc/imguimc-fabric-1.21.1/1.1.0/imguimc-fabric-1.21.1-1.1.0.jar'
imgui_binding = output / 'imgui-java-binding-1.90.0.jar'
with zipfile.ZipFile(imgui_bundle) as bundle:
    imgui_binding.write_bytes(bundle.read('META-INF/jars/imgui-java-binding-1.90.0.jar'))
classpath.append(imgui_binding)
classpath.append(artifact('net.fabricmc', 'sponge-mixin', '0.17.4+mixin.0.8.7'))
classpath.append(artifact('io.github.llamalad7', 'mixinextras-fabric', '0.5.5'))
cp = os.pathsep.join(map(str, classpath))
sources = sorted((root / 'common/src/main/java/foundry/veil/api/client/render/gpu').glob('*.java'))
sources += [root / 'common/src/main/java' / name for name in [
    'foundry/veil/api/client/render/shader/program/ShaderBlendMode.java',
    'foundry/veil/api/client/render/shader/program/ShaderUniformCache.java',
    'foundry/veil/api/client/render/shader/program/ShaderProgram.java',
    'foundry/veil/api/client/render/shader/program/UniformAccess.java',
    'foundry/veil/api/client/render/shader/program/TextureUniformAccess.java',
    'foundry/veil/impl/client/render/shader/program/ShaderProgramImpl.java',
    'foundry/veil/api/client/render/shader/program/ProgramDefinition.java',
    'foundry/veil/api/client/render/shader/ShaderManager.java',
    'foundry/veil/api/client/render/shader/ShaderSourceSet.java',
    'foundry/veil/api/client/render/shader/ShaderPreDefinitions.java',
    'foundry/veil/api/client/render/shader/ShaderFeature.java',
    'foundry/veil/api/client/render/shader/NativePipelineBridge.java',
    'foundry/veil/api/client/render/shader/NativeShaderBlocks.java',
    'foundry/veil/api/client/render/shader/NativeShaderDefaults.java',
    'foundry/veil/ext/RenderTargetExtension.java',
    'foundry/veil/ext/NativePerspectiveLevelExtension.java',
    'foundry/veil/impl/client/render/perspective/LevelPerspectiveCamera.java',
    'foundry/veil/impl/client/render/perspective/VeilSectionOcclusionGraph.java',
    'foundry/veil/mixin/perspective/accessor/NativePerspectiveCameraAccessor.java',
    'foundry/veil/mixin/perspective/accessor/NativePerspectiveExtractorInvoker.java',
    'foundry/veil/mixin/perspective/accessor/NativePerspectiveViewAreaAccessor.java',
    'foundry/veil/mixin/perspective/accessor/NativePerspectiveBlockEntityAccessor.java',
    'foundry/veil/mixin/perspective/accessor/NativePerspectiveTerrainAccessor.java',
    'foundry/veil/api/client/render/rendertype/NativeRenderTypeOutput.java',
    'foundry/veil/api/client/render/shader/processor/ShaderBufferProcessor.java',
    'foundry/veil/api/client/render/shader/processor/ShaderPreProcessor.java',
    'foundry/veil/api/client/render/shader/processor/ShaderImporter.java',
    'foundry/veil/api/client/render/shader/processor/ShaderMultiProcessor.java',
    'foundry/veil/api/client/render/shader/processor/ShaderVersionProcessor.java',
    'foundry/veil/api/client/render/shader/processor/ShaderImportProcessor.java',
    'foundry/veil/api/event/VeilAddShaderPreProcessorsEvent.java',
    'foundry/veil/impl/client/render/shader/ShaderImporterImpl.java',
    'foundry/veil/impl/client/render/shader/processor/ShaderProcessorList.java',
    'foundry/veil/impl/client/render/shader/processor/NativeShaderProcessor.java',
    'foundry/veil/impl/client/render/shader/processor/NativeSodiumShaderProcessor.java',
    'foundry/veil/impl/client/render/shader/processor/NativeManagedUniforms.java',
    'foundry/veil/impl/client/render/shader/processor/NativeShaderDefaultsProcessor.java',
    'foundry/veil/impl/client/render/shader/processor/NativeShaderIncludes.java',
    'foundry/veil/impl/client/render/shader/processor/NativeGlslNodeWriter.java',
    'foundry/veil/impl/client/render/shader/processor/NativeGlslTreeWriter.java',
    'foundry/veil/api/client/editor/Inspector.java',
    'foundry/veil/api/client/editor/InspectorState.java',
    'foundry/veil/api/client/editor/SingleWindowInspector.java',
    'foundry/veil/impl/client/editor/ShaderInspector.java',
    'foundry/veil/impl/client/render/shader/debug/NativePipelineRegistry.java',
    'foundry/veil/mixin/debug/accessor/DebugPipelineCacheAccessor.java',
    'foundry/veil/mixin/debug/client/DebugPipelineCacheMixin.java',
    'foundry/veil/mixin/shader/client/NativePipelineCacheMixin.java',
    'foundry/veil/mixin/shader/client/NativeShaderManagerMixin.java',
    'foundry/veil/mixin/shader/client/NativeFrontendRenderPassMixin.java',
    'foundry/veil/mixin/shader/client/NativeShaderEncoderMixin.java',
    'foundry/veil/api/client/render/shader/processor/ShaderInjectProcessor.java',
    'foundry/veil/api/client/render/shader/texture/ShaderTextureSource.java',
    'foundry/veil/api/client/render/shader/texture/LocationSource.java',
    'foundry/veil/api/client/render/shader/texture/FramebufferSource.java',
    'foundry/veil/api/client/render/framebuffer/FramebufferDefinition.java',
    'foundry/veil/api/client/render/framebuffer/FramebufferAttachmentDefinition.java',
    'foundry/veil/api/client/render/framebuffer/FramebufferManager.java',
    'foundry/veil/api/client/render/texture/TextureFilter.java',
    'foundry/veil/api/util/EnumCodec.java',
    'foundry/veil/api/molang/VeilMolang.java',
    'foundry/veil/api/molang/MolangExpressionCodec.java',
    'foundry/veil/api/CodecReloadListener.java',
    'foundry/veil/api/client/color/Color.java',
    'foundry/veil/api/client/color/Colorc.java',
]]
sources += [root / 'common/src/main/java/foundry/veil/api/event/VeilRenderLevelStageEvent.java',
            root / 'fabric/src/main/java/foundry/veil/fabric/event/FabricVeilRenderLevelStageEvent.java',
            root / 'fabric/src/main/java/foundry/veil/fabric/FabricRenderTypeStageHandler.java']
sources += sorted((root / 'common/src/main/java/foundry/veil/api/client/render/post').rglob('*.java'))
sources += [root / 'common/src/main/java' / name for name in [
    'foundry/veil/api/client/render/dynamicbuffer/DynamicBufferType.java',
    'foundry/veil/impl/client/render/dynamicbuffer/DynamicBufferProcessor.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicBufferTarget.java',
    'foundry/veil/impl/client/render/dynamicbuffer/DynamicBufferManager.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicBuffers.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicOit.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicOitProcessor.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicCoverage.java',
    'foundry/veil/impl/client/render/dynamicbuffer/NativeDynamicCoverageProcessor.java',
    'foundry/veil/mixin/dynamicbuffer/client/NativeDynamicOitProviderMixin.java',
    'foundry/veil/api/client/render/dynamicbuffer/DynamicBuffersChange.java',
    'foundry/veil/mixin/dynamicbuffer/client/NativeDynamicEncoderMixin.java',
    'foundry/veil/mixin/dynamicbuffer/client/NativeDynamicPassMixin.java',
    'foundry/veil/mixin/dynamicbuffer/client/NativeDynamicLevelRendererMixin.java',
    'foundry/veil/mixin/scene/client/NativeFeatureDispatcherInvoker.java',
    'foundry/veil/mixin/scene/client/NativeFeatureLightmapMixin.java',
    'foundry/veil/impl/client/render/dynamicbuffer/VanillaShaderCompiler.java',
    'foundry/veil/api/client/render/framebuffer/VeilFramebuffers.java',
    'foundry/veil/api/client/registry/PostPipelineStageRegistry.java',
    'foundry/veil/impl/client/render/pipeline/PostPipelineContext.java',
    'foundry/veil/impl/client/render/pipeline/VeilShaderBlockState.java',
    'foundry/veil/impl/client/render/pipeline/VeilShaderBufferCache.java',
    'foundry/veil/platform/registry/RegistrationProvider.java',
    'foundry/veil/platform/registry/RegistryObject.java',
]]
sources += sorted((root / 'common/src/main/java/foundry/veil/impl/client/render/shader/injection').rglob('*.java'))
sources += [root / 'fabric/src/main/java/foundry/veil/fabric/platform/FabricRegistrationFactory.java']
sources += sorted((root / 'common/src/main/java/foundry/veil/api/client/render/shader/block').glob('*.java'))
sources += sorted((root / 'common/src/main/java/foundry/veil/impl/client/render/shader/block').glob('*.java'))
sources += sorted((root / 'common/src/main/java/foundry/veil/api/client/render/shader/uniform').glob('*.java'))
sources += sorted((root / 'common/src/main/java/foundry/veil/impl/client/render/shader/uniform').glob('*.java'))
sources += [root / 'common/src/main/java/foundry/veil/api/client/render/VeilShaderBufferLayout.java',
            root / 'common/src/main/java/foundry/veil/api/client/render/MatrixStack.java',
            root / 'common/src/main/java/foundry/veil/api/client/necromancer/Skeleton.java',
            root / 'common/src/main/java/foundry/veil/api/client/necromancer/Bone.java',
            root / 'common/src/main/java/foundry/veil/api/client/necromancer/render/Skin.java',
            root / 'common/src/main/java/foundry/veil/api/client/necromancer/render/NecromancerRenderer.java',
            root / 'common/src/main/java/foundry/veil/impl/client/necromancer/render/NecromancerRenderDispatcher.java',
            root / 'common/src/main/java/foundry/veil/impl/client/render/LayoutSerializer.java',
            root / 'common/src/main/java/foundry/veil/api/client/render/vertex/VertexArray.java',
            root / 'common/src/main/java/foundry/veil/api/client/render/vertex/VertexArrayBuilder.java']
sources += [root / 'port-tests/GpuClientAcceptance.java', root / 'port-tests/GpuLevelStageAcceptance.java', root / 'port-tests/GpuPostBootstrap.java', root / 'port-tests/GpuMeshAcceptance.java', root / 'port-tests/ShaderApiAcceptance.java', root / 'port-tests/GpuWorldBridgeAcceptance.java', root / 'port-tests/GpuDynamicBufferAcceptance.java', root / 'port-tests/GpuDynamicVariantAcceptance.java', root / 'port-tests/GpuDynamicWorldAcceptance.java', root / 'port-tests/GpuDynamicWorldSceneAcceptance.java', root / 'port-tests/GpuManagedReplayAcceptance.java', root / 'port-tests/GpuDynamicCoverageAcceptance.java', root / 'port-tests/GpuSourceBlocksAcceptance.java', root / 'port-tests/GpuManagedUniformAcceptance.java', root / 'port-tests/GpuManagedSamplerAcceptance.java']
production_count = sum('/port-tests/' not in str(path) for path in sources)
print(f'Compiling {production_count} genuine GPU production classes and {len(sources) - production_count} explicit test entrypoints.', flush=True)
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-implicit:none',
    '-classpath', cp, '-d', str(classes), *map(str, sources)])
if result.returncode: raise SystemExit(result.returncode)
record = {'minecraft': '26.3', 'fabric_api': '0.163.0+26.3',
          'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
          'resources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in (root / 'common/src/main/resources/assets/veil').rglob('*')
                        if p.is_file() and ('/shaders/core/gpu_' in str(p) or '/shaders/include/gpu_' in str(p)
                                           or '/shaders/core/dynamic_' in str(p) or '/pinwheel/framebuffers/' in str(p) or '/pinwheel/post/' in str(p) or p.name.startswith('gpu_'))},
          'test_resources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in (root / 'port-tests/resources').rglob('*') if p.is_file()}}
(output / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
if args.api:
    subprocess.run([str(java / 'java'), '-classpath', str(classes) + os.pathsep + cp,
                    'ShaderApiAcceptance', str(root / 'port-tests/resources')], check=True)
    (output / 'api-inputs.json').write_text((output / 'inputs.json').read_text())
if not args.client:
    print('GPU boundary compile passed. No GPU runtime claim; full Veil remains a separate failing build.')
    raise SystemExit(0)

if args.checks not in ('stages', 'dynamicworldscene'):
    classpath.remove(client_tests)
# This is explicitly a test mod, with no Veil mod ID or production entrypoints.
test_jar = output / 'veil-gpu-acceptance.jar'
metadata = {'schemaVersion': 1, 'id': 'veil_gpu_acceptance', 'version': '1.0.0',
            'name': 'Veil GPU port acceptance (test only)', 'environment': 'client',
            'entrypoints': {'fabric-client-gametest': ['GpuDynamicWorldSceneAcceptance' if args.checks == 'dynamicworldscene' else 'GpuLevelStageAcceptance']} if args.checks in ('stages', 'dynamicworldscene') else {'main': ['GpuPostBootstrap'], 'client': ['GpuClientAcceptance']},
            'depends': {'minecraft': '26.3', 'fabricloader': '>=0.19.5', 'fabric-api': '*'}}
if 'debug' in args.checks.split(','): metadata['mixins'] = ['veil-gpu-debug-test.mixins.json']
if any(check in args.checks.split(',') for check in ('bridge', 'dynamicvariants', 'dynamiccache', 'dynamicworld', 'dynamicworldscene', 'managedreplay', 'classiccoverage', 'sourceblocks', 'manageduniforms', 'manageduniformreload', 'managedsamplers')): metadata.setdefault('mixins', []).append('veil-gpu-bridge-test.mixins.json')
if any(check in args.checks.split(',') for check in ('dynamicworld', 'dynamicworldscene', 'managedreplay', 'classiccoverage', 'sourceblocks', 'manageduniforms', 'manageduniformreload', 'managedsamplers')): metadata.setdefault('mixins', []).append('veil-gpu-dynamic-world-test.mixins.json')
with zipfile.ZipFile(test_jar, 'w', zipfile.ZIP_DEFLATED) as jar:
    jar.writestr('fabric.mod.json', json.dumps(metadata))
    if 'debug' in args.checks.split(','):
        jar.writestr('veil-gpu-debug-test.mixins.json', json.dumps({'required': True, 'minVersion': '0.8',
            'package': 'foundry.veil.mixin.debug', 'compatibilityLevel': 'JAVA_25',
            'client': ['accessor.DebugPipelineCacheAccessor', 'client.DebugPipelineCacheMixin'], 'injectors': {'defaultRequire': 1}}))
    if any(check in args.checks.split(',') for check in ('bridge', 'dynamicvariants', 'dynamiccache', 'dynamicworld', 'dynamicworldscene', 'managedreplay', 'classiccoverage', 'sourceblocks', 'manageduniforms', 'manageduniformreload', 'managedsamplers')):
        jar.writestr('veil-gpu-bridge-test.mixins.json', json.dumps({'required': True, 'minVersion': '0.8',
            'package': 'foundry.veil.mixin.shader.client', 'compatibilityLevel': 'JAVA_25',
            'client': ['NativePipelineCacheMixin', 'NativeShaderManagerMixin', 'NativeFrontendRenderPassMixin', 'NativeShaderEncoderMixin'], 'injectors': {'defaultRequire': 1}}))
    if any(check in args.checks.split(',') for check in ('dynamicworld', 'dynamicworldscene', 'managedreplay', 'classiccoverage', 'sourceblocks', 'manageduniforms', 'manageduniformreload', 'managedsamplers')):
        jar.writestr('veil-gpu-dynamic-world-test.mixins.json', json.dumps({'required': True, 'minVersion': '0.8',
            'package': 'foundry.veil.mixin.dynamicbuffer.client', 'compatibilityLevel': 'JAVA_25',
            'client': ['NativeDynamicEncoderMixin', 'NativeDynamicPassMixin', 'NativeDynamicLevelRendererMixin', 'NativeDynamicOitProviderMixin'], 'injectors': {'defaultRequire': 1}}))
    for path in classes.rglob('*.class'): jar.write(path, path.relative_to(classes))
    service = 'META-INF/services/foundry.veil.platform.registry.RegistrationProvider$Factory'
    jar.write(root / 'fabric/src/main/resources' / service, service)
    shader_dir = root / 'common/src/main/resources/assets/veil/shaders'
    for path in shader_dir.rglob('*'):
        if path.is_file(): jar.write(path, 'assets/veil/shaders/' + str(path.relative_to(shader_dir)))
    for path in (root / 'common/src/main/resources/assets/veil/pinwheel/framebuffers').glob('*.json'):
        jar.write(path, 'assets/veil/pinwheel/framebuffers/' + path.name)
    for path in (root / 'common/src/main/resources/assets/veil/pinwheel/shaders/program').glob('gpu_*.json'):
        jar.write(path, 'assets/veil/pinwheel/shaders/program/' + path.name)
    test_resources = root / 'port-tests/resources'
    for path in test_resources.rglob('*'):
        if path.is_file(): jar.write(path, path.relative_to(test_resources))
    jar.write(root / 'LICENSE', 'LICENSE_Veil')

# Use the correct current Java launcher dependencies, not the stale absolute
# paths in an old sibling project's saved launch.cfg. No sibling files change.
for name in ['asm', 'asm-analysis', 'asm-commons', 'asm-tree', 'asm-util']:
    classpath.append(artifact('org.ow2.asm', name, '9.10.1'))
classpath += [artifact('net.fabricmc', 'sponge-mixin', '0.17.4+mixin.0.8.7'),
              artifact('io.github.llamalad7', 'mixinextras-fabric', '0.5.5'), fabric, test_jar]
run = output / 'run'
run.mkdir(exist_ok=True)
asset_index = '26.3-' + manifest['assetIndex']['id']
if not (loom / 'assets/indexes' / (asset_index + '.json')).is_file():
    asset_index = manifest['assetIndex']['id']
java_args = ['-XstartOnFirstThread', '-Xmx2G', '-Dfabric.development=true',
    '-Dfabric.defaultModDistributionNamespace=official', '-Dfabric.defaultMixinRemapType=static',
    '-Dfabric.gameJarPath=' + str(mc), '-Dveil.gpu.acceptance.selection=' + args.checks, '-Dveil.gpu.acceptance.report=' + str(output / 'runtime.json'),
    '-classpath', os.pathsep.join(map(str, classpath)), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
    '--version', '26.3', '--username', 'VeilGpuTest', '--accessToken', '0',
    '--gameDir', str(run), '--assetsDir', str(loom / 'assets'), '--assetIndex', asset_index,
    '--graphicsBackend', 'vulkan', '--width', '480', '--height', '320']
if args.checks in ('stages', 'dynamicworldscene'):
    java_args = ['-Dfabric.client.gametest=true', '-Dfabric.client.gametest.modid=veil_gpu_acceptance',
                 '-Dfabric.client.gametest.testModResourcesPath=' + str(root / 'port-tests/resources'), *java_args]
(output / 'client.args').write_text('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in java_args))
(output / 'runtime.json').unlink(missing_ok=True)
print('Launching the real 26.3 Vulkan client for GPU readback only; Create baseline is not loaded.', flush=True)
result = subprocess.run([str(java / 'java'), '@' + str(output / 'client.args')], cwd=run, timeout=180)
if result.returncode: raise SystemExit(result.returncode)
report = json.loads((output / 'runtime.json').read_text())
evidence_name = args.checks.replace(',', '_')
(output / (evidence_name + '-runtime.json')).write_text(json.dumps(report, indent=2) + '\n')
(output / (evidence_name + '-inputs.json')).write_text((output / 'inputs.json').read_text())
if report.get('status') != 'passed': raise SystemExit('GPU acceptance failed: ' + str(report))
print('Real GPU acceptance passed:', json.dumps(report))
