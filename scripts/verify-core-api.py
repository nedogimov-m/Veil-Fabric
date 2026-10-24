#!/usr/bin/env python3
"""Explicit diagnostic for the migrated Veil core API; never creates a mod jar.

The normal Gradle build still compiles every shared/Fabric source file and fails
until the renderer migration is finished. This script compiles the real selected
production classes against the exact cached 26.3 Minecraft/Fabric artifacts, then
runs behavioral checks. It needs a prior Loom configuration to cache dependencies.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
cache = gradle_home / 'caches/modules-2/files-2.1'
java_home = Path(os.environ['JAVA_HOME'])
output = root / 'build/core-api-verification'
classes = output / 'classes'
classes.mkdir(parents=True, exist_ok=True)

def artifact(group, name, version):
    filename = f'{name}-{version}.jar'
    paths = list((cache / group / name / version).glob(f'*/{filename}'))
    if not paths:
        raise SystemExit(f'Missing cached dependency: {group}:{name}:{version}; configure Loom first.')
    return paths[0]

minecraft = gradle_home / 'caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
manifest = json.loads((gradle_home / 'caches/fabric-loom/26.3/mojang_minecraft_info.json').read_text())
classpath = [minecraft]
for library in manifest['libraries']:
    group, name, version, *_ = library['name'].split(':')
    filename = Path(library['downloads']['artifact']['path']).name
    files = list((cache / group / name / version).glob(f'*/{filename}'))
    if files:
        classpath.append(files[0])
classpath += [artifact('net.fabricmc', 'fabric-loader', '0.19.5'),
              artifact('org.jetbrains', 'annotations', '26.0.2'),
              artifact('net.fabricmc', 'sponge-mixin', '0.17.3+mixin.0.8.7')]
# Use precisely the modules included by Fabric API 0.163.0+26.3, not whichever
# older/newer API happens to appear first in the machine's Gradle cache.
fabric_dir = output / 'fabric-api'
fabric_dir.mkdir(exist_ok=True)
with zipfile.ZipFile(artifact('net.fabricmc.fabric-api', 'fabric-api', '0.163.0+26.3')) as bundle:
    for name in bundle.namelist():
        if name.startswith('META-INF/jars/') and name.endswith('.jar'):
            path = fabric_dir / Path(name).name
            path.write_bytes(bundle.read(name))
            classpath.append(path)

common = root / 'common/src/main/java'
fabric = root / 'fabric/src/main/java'
sources = [common / p for p in [
    'foundry/veil/api/network/VeilPacketManager.java',
    'foundry/veil/api/network/handler/PacketContext.java',
    'foundry/veil/api/network/handler/ClientPacketContext.java',
    'foundry/veil/api/network/handler/ServerPacketContext.java',
    'foundry/veil/impl/network/ClientPacketSink.java',
    'foundry/veil/api/CodecReloadListener.java',
    'foundry/veil/api/client/color/Color.java',
    'foundry/veil/api/client/color/Colorc.java',
    'foundry/veil/api/client/render/MatrixStack.java',
    'foundry/veil/platform/registry/RegistrationProvider.java',
    'foundry/veil/platform/registry/RegistryObject.java',
    'foundry/veil/mixin/network/NetworkMinecraftServerMixin.java',
    'foundry/veil/mixin/network/NetworkServerLevelMixin.java',
    'foundry/veil/mixin/network/NetworkServerPlayerMixin.java',
    'foundry/veil/mixin/pipeline/client/PipelinePoseStackMixin.java',
]] + [fabric / p for p in [
    'foundry/veil/fabric/network/FabricVeilPacketManager.java',
    'foundry/veil/fabric/network/FabricClientPacketContext.java',
    'foundry/veil/fabric/network/FabricServerPacketContext.java',
    'foundry/veil/fabric/platform/FabricVeilPacketManagerFactory.java',
    'foundry/veil/fabric/platform/FabricRegistrationFactory.java',
]]
cp = os.pathsep.join(map(str, classpath))
args = ['--release', '25', '-proc:none', '-implicit:none', '-classpath', cp,
        '-d', str(classes), *map(str, sources), str(root / 'port-tests/CoreApiSmoke.java')]
(output / 'javac.args').write_text('\n'.join('"' + x.replace('\\', '\\\\').replace('"', '\\"') + '"' for x in args))
print(f'Compiling {len(sources)} real core API/provider/mixin sources against Minecraft 26.3.', flush=True)
result = subprocess.run([str(java_home / 'bin/javac'), '@' + str(output / 'javac.args')])
if result.returncode:
    raise SystemExit(result.returncode)
# Only the two real platform factories used by these behavioral checks are loaded.
service_dir = classes / 'META-INF/services'
service_dir.mkdir(parents=True, exist_ok=True)
for interface in ['foundry.veil.api.network.VeilPacketManager$Factory',
                  'foundry.veil.platform.registry.RegistrationProvider$Factory']:
    (service_dir / interface).write_bytes((root / 'fabric/src/main/resources/META-INF/services' / interface).read_bytes())
result = subprocess.run([str(java_home / 'bin/java'), '-ea', '-classpath', str(classes) + os.pathsep + cp, 'CoreApiSmoke', *sys.argv[1:]])
if result.returncode:
    raise SystemExit(result.returncode)
print('Core API verification passed. Full renderer/mod build remains incomplete; no jar was produced.')
