#!/usr/bin/env python3
"""CPU-compile the migrated real Veil public shader pairs and check their compiled SPIR-V interfaces."""
from pathlib import Path
import hashlib, json, os, shlex, shutil, struct, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
work = root / 'build/native-public-shader-verification'
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
sources = [base / name for name in ['NativeGlslNodeWriter.java', 'NativeGlslTreeWriter.java', 'NativeShaderProcessor.java',
    'NativeShaderIncludes.java', 'NativeManagedUniforms.java']]
sources += [root / 'common/src/main/java/foundry/veil/impl/client/render/dynamicbuffer/DynamicBufferProcessor.java',
    root / 'port-tests/NativePublicShaderAcceptance.java']
result = subprocess.run([str(java / 'javac'), '--release', '25', '-proc:none', '-sourcepath', '', '-cp', classpath,
    '-d', str(classes), *map(str, sources)], capture_output=True, text=True)
(work / 'compile.log').write_text(result.stdout + result.stderr)
if result.returncode:
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
result = subprocess.run([str(java / 'java'), '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join([str(classes), classpath]),
    'NativePublicShaderAcceptance', str(root / 'common/src/main/resources'), str(assets), str(work)], capture_output=True, text=True)
(work / 'runtime.log').write_text(result.stdout + result.stderr)
print(result.stdout + result.stderr, end='')
if result.returncode:
    raise SystemExit(result.returncode)

def interfaces(path):
    """Read actual scalar/vector SPIR-V locations, types, and storage classes (SPIR-V opcodes)."""
    code = path.read_bytes()
    words = struct.unpack('<' + 'I' * (len(code) // 4), code)
    assert words[0] == 0x07230203
    names, locations, types, variables = {}, {}, {}, {}
    pos = 5
    while pos < len(words):
        length, op = words[pos] >> 16, words[pos] & 0xffff
        assert length > 0
        data = words[pos + 1:pos + length]
        if op == 5:  # OpName
            names[data[0]] = struct.pack('<' + 'I' * (len(data) - 1), *data[1:]).split(b'\0')[0].decode()
        elif op == 71 and data[1] == 30:  # OpDecorate Location
            locations[data[0]] = data[2]
        elif op in (21, 22, 23, 32):  # OpTypeInt / Float / Vector / Pointer
            types[data[0]] = (op, data[1:])
        elif op == 59:  # OpVariable
            variables[data[1]] = (data[0], data[2])
        pos += length

    def typename(id):
        op, data = types[id]
        if op == 21:
            return ('i' if data[1] else 'u') + str(data[0])
        if op == 22:
            return 'f' + str(data[0])
        if op == 23:
            return typename(data[0]) + 'x' + str(data[1])
        assert op == 32
        return typename(data[1])

    return {storage: {locations[id]: {'name': names[id], 'type': typename(typeid)}
            for id, (typeid, found) in variables.items() if found == storage and id in locations}
            for storage in (1, 3)}  # Input / Output

programs = ['quasar_particle_additive', 'blit_screen', 'blit_depth', 'core_composite',
    'core_blit_screen_effect', 'debug_array', 'debug_cubemap']
reflected = {}
for name in programs:
    vertex = interfaces(work / (name + '.vsh.spv'))
    fragment = interfaces(work / (name + '.fsh.spv'))
    assert vertex[3] == fragment[1], (name, 'compiled varying interface mismatch', vertex[3], fragment[1])
    expected = {0: {'name': 'fragColor' if name not in ('blit_depth', 'debug_array', 'debug_cubemap')
        else 'OutColor' if name == 'blit_depth' else 'outColor', 'type': 'f32x4'}}
    assert fragment[3] == expected, (name, 'compiled color output mismatch', fragment[3])
    if name == 'quasar_particle_additive':
        assert vertex[1] == {0: {'name': 'Position', 'type': 'f32x3'}, 1: {'name': 'UV0', 'type': 'f32x2'},
            2: {'name': 'Color', 'type': 'f32x4'}, 3: {'name': 'UV2', 'type': 'i32x2'}}, vertex[1]
        assert [value['type'] for _, value in sorted(vertex[3].items())] == ['f32', 'f32', 'f32x2', 'f32x4', 'f32x4']
    else:
        assert vertex[1] == {}, (name, 'fullscreen triangle unexpectedly needs vertex input')
        assert vertex[3] == {0: {'name': 'texCoord', 'type': 'f32x2'}}
    reflected[name] = {'vertex': vertex, 'fragment': fragment}
(work / 'spirv-interfaces.json').write_text(json.dumps(reflected, indent=2) + '\n')
print('PASS compiled SPIR-V interfaces: four authored particle attributes, five matched particle varyings, six input-free fullscreen pairs, and explicit location-0 color outputs')
resource_inputs = [Path(path) for path in (work / 'resource-inputs.txt').read_text().splitlines()]
manifest = {'scope': 'Fourteen actual managed shader stages across seven real JSON-selected public program pairs; genuine Minecraft26.3 includes, real typed native particle pipeline, shared managed numeric/sampler schemas and compiled SPIR-V stage interfaces. CPU shaderc only; no GPU/client launch.',
    'result': 'PASS', 'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'resources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in resource_inputs},
    'actual_jar_inputs': {str(mc): hashlib.sha256(mc.read_bytes()).hexdigest(), str(frozen.relative_to(root)): hashlib.sha256(frozen.read_bytes()).hexdigest()},
    'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(work / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
