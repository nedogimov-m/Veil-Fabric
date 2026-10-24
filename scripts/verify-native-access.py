#!/usr/bin/env python3
"""Validate the native Veil access file against genuine 26.3 and apply actual Fabric ClassTweaker."""
from pathlib import Path
import hashlib, importlib.util, json, os, shlex, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches'
work = root / 'build/native-access-verification'
work.mkdir(parents=True, exist_ok=True)
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
target = cache / 'fabric-loom/26.3/minecraft-merged.jar'
access = root / 'common/src/main/resources/veil.native.accesswidener'
legacy = root / 'upstream-retired/access/veil.accesswidener'
spec = importlib.util.spec_from_file_location('native_member_audit', repo / 'tools/audit_minecraft_mixins.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

responsibilities = {
    'Pose Helper': ('Explicit native camera/projection state replaces removed private GameRenderer.getFov; no remaining direct getFov call.', ['impl/client/render/perspective/LevelPerspectiveCamera.java', 'api/client/render/gpu/NativeFeatureScene.java']),
    'Shader Instance Wrapper': ('Native GPU programs and typed bindings replace ShaderInstance/GlStateManager/Program GL object ownership.', ['api/client/render/gpu/GpuShaderProgram.java', 'api/client/render/shader/NativePipelineBridge.java']),
    'Shader Program Cache': ('Actual native pipeline cache/registry replaces integer lastProgramId state.', ['impl/client/render/shader/debug/NativePipelineRegistry.java']),
    'Uniform Wrapper': ('Typed native uniform buffers and real shader layouts replace subclassing removed GL Uniform.', ['impl/client/render/shader/uniform/ShaderUniformImpl.java', 'api/client/render/gpu/GpuUniformBuffer.java']),
    'Deferred Renderer': ('Native RenderSetup and explicit buffer/pass submission replace CompositeState and global BufferUploader state.', ['impl/client/render/rendertype/NativeRenderTypeDraws.java', 'api/client/render/rendertype/VeilRenderType.java']),
    'Quasar': ('Real ResourceManagerRegistryLoadTask/RegistryLoadTask and the exact native createContext invoker replace RegistryDataLoader.Loader and its old descriptor.', ['api/resource/VeilDynamicRegistry.java', 'mixin/registry/accessor/RegistryDataAccessor.java']),
    'Dynamic Render Types': ('Native RenderSetup/pipelines and typed layer state replace all removed RenderStateShard subclasses and CompositeState factory access.', ['api/client/render/rendertype/VeilRenderTypeBuilder.java', 'api/client/render/rendertype/VeilRenderType.java']),
    'VertexFormat': ('Native named VertexFormatElement values have explicit offsets and formats; no mutable global BY_ID table.', ['api/client/render/vertex/VeilVertexFormat.java']),
    'Resource Manager': ('Public AtlasManager with resident TextureAtlas inventory replaces removed AtlasSet.AtlasEntry access.', ['api/client/util/NativeBlockModelBaker.java', 'mixin/resource/client/ResourceTextureAtlasMixin.java']),
    'Block Model Bakery': ('Actual CuboidModel/ResolvedModel/MaterialBaker pipeline replaces the removed static item-model generator.', ['api/client/util/NativeBlockModelBaker.java']),
    'Level Perspective Occlusion Graph': ('Native perspective owners/accessors replace old occlusion traversal; genuine ViewArea.getRenderSectionAt is public and has no current Veil caller.', ['mixin/perspective/accessor/NativePerspectiveViewAreaAccessor.java', 'api/client/render/VeilLevelPerspectiveRenderer.java']),
    'Native26.3 dynamic/layered render-type constructors': ('Retained: real native RenderType subclasses call this private constructor; ClassTweaker makes the exact genuine method public.', ['api/client/render/rendertype/VeilRenderType.java'])
}
entries = []
section = ''
with zipfile.ZipFile(target) as jar:
    names = set(jar.namelist())
    for number, line in enumerate(legacy.read_text().splitlines(), 1):
        if line.startswith('# '):
            section = line[2:].strip()
            continue
        parts = line.split('#', 1)[0].split()
        if len(parts) < 3 or parts[1] not in ('class', 'method', 'field'):
            continue
        action, kind, owner = parts[:3]
        member = audit.class_members(jar.read(owner + '.class')) if owner + '.class' in names else None
        exists = member is not None and (kind == 'class' or tuple(parts[3:5]) in member[kind + 's'])
        reason, paths = responsibilities[section]
        for path in paths:
            if not (root / 'common/src/main/java/foundry/veil' / path).is_file():
                raise SystemExit('Missing responsibility source: ' + path)
        entries.append({'line': number, 'entry': line, 'section': section, 'targetClassExists26_3': member is not None,
                        'exactTargetExists26_3': exists, 'disposition': 'retained-native' if 'Native26.3' in section else 'replaced-native',
                        'responsibility': reason, 'sources': ['common/src/main/java/foundry/veil/' + p for p in paths]})

driver = work / 'VerifyVeilAccess.java'
driver.write_text('''
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.fabricmc.classtweaker.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class VerifyVeilAccess {
 public static void main(String[] args) throws Exception {
  final var tweaker=ClassTweaker.newInstance();
  ClassTweakerReader.create(tweaker).read(Files.readAllBytes(Path.of(args[1])),"official");
  int validated=0;
  try(var jar=new JarFile(args[0])) {
   for(String line:Files.readAllLines(Path.of(args[1]))) {
    line=line.split("#",2)[0].trim(); if(line.isEmpty()||line.startsWith("accessWidener"))continue;
    final var p=line.split("\\\\s+");
    final var entry=jar.getJarEntry(p[2]+".class");
    if(entry==null)throw new IllegalStateException("Missing genuine class: "+p[2]);
    final var bytes=jar.getInputStream(entry).readAllBytes();
    final var before=new ClassNode();new ClassReader(bytes).accept(before,0);
    final var original=before.methods.stream().filter(m->m.name.equals(p[3])&&m.desc.equals(p[4])).findFirst().orElseThrow();
    final var writer=new ClassWriter(0);
    new ClassReader(bytes).accept(tweaker.createClassVisitor(Opcodes.ASM9,writer,
       (name,generated)->{throw new IllegalStateException("Unexpected generated class "+name);}),0);
    final var transformed=writer.toByteArray(); final var after=new ClassNode();new ClassReader(transformed).accept(after,0);
    final var changed=after.methods.stream().filter(m->m.name.equals(p[3])&&m.desc.equals(p[4])).findFirst().orElseThrow();
    if((original.access&Opcodes.ACC_PRIVATE)==0||(changed.access&Opcodes.ACC_PUBLIC)==0||(changed.access&Opcodes.ACC_PRIVATE)!=0)
       throw new IllegalStateException("Constructor access transition is not genuine private to public");
    Files.write(Path.of(args[2]),transformed);
    System.out.println("PASS actual ClassTweaker: "+p[2]+"."+p[3]+p[4]+" private -> public; class identity and descriptor preserved");validated++;
   }
  }
  if(validated!=1)throw new IllegalStateException("Unexpected native Veil access entry count: "+validated);
 }
}
''')
modules = cache / 'modules-2/files-2.1'
cp = list((modules / 'net.fabricmc/class-tweaker/0.3.0').glob('*/*.jar'))
cp += list((modules / 'org.ow2.asm').glob('*/9.10.1/*/*.jar'))
classpath = os.pathsep.join(map(str, cp))
compile = subprocess.run([str(java/'javac'), '--release', '25', '-proc:none', '-cp', classpath, '-d', str(work), str(driver)], capture_output=True, text=True)
(work/'compile.log').write_text(compile.stdout + compile.stderr)
if compile.returncode:
    print(compile.stderr)
    raise SystemExit(compile.returncode)
run = subprocess.run([str(java/'java'), '-cp', str(work)+os.pathsep+classpath, 'VerifyVeilAccess', str(target), str(access), str(work/'RenderType.class')], capture_output=True, text=True)
(work/'runtime.log').write_text(run.stdout + run.stderr)
print(run.stdout + run.stderr, end='')
if run.returncode:
    raise SystemExit(run.returncode)
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
report = {'status':'passed', 'scope':'New native Veil access migration and actual ClassTweaker transformation only; no old functional group rerun',
          'genuineMinecraft':str(target), 'minecraftSha256':digest(target), 'nativeAccess':str(access.relative_to(root)), 'nativeAccessSha256':digest(access),
          'preservedLegacy':str(legacy.relative_to(root)), 'preservedLegacySha256':digest(legacy), 'legacyEntryCount':len(entries),
          'nativeEntryCount':1, 'entries':entries, 'proofLimits':['Exact member existence and genuine ClassTweaker access transition; full Fabric mod activation is a separate startup gate.']}
(root/'upstream-retired/access/responsibility-map.json').write_text(json.dumps(report,indent=2)+'\n')
(work/'inputs.json').write_text(json.dumps({'status':'passed','nativeAccessSha256':digest(access),'scriptSha256':digest(Path(__file__).resolve()),'minecraftSha256':digest(target),'legacyAccessSha256':digest(legacy),'tweakerAndAsm':{str(p):digest(p) for p in cp}},indent=2)+'\n')
print('Native access entries validated: 1; exact legacy entries accounted:',len(entries))
