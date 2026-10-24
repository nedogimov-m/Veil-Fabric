#!/usr/bin/env python3
"""Focused native timestamp profiler and actual diagnostic pass lifecycle Vulkan acceptance.
Uses the already compiled genuine GPU boundary; does not rerun prior GPU groups
or compile/package a complete Veil distribution.
"""
import argparse, hashlib, json, os, subprocess, zipfile, shutil
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client', action='store_true')
parser.add_argument('--checks', default='native-profiler', choices=['native-profiler'], help='Run only the new shell/capture boundary checks')
args = parser.parse_args()
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home')) / 'bin'
caches = Path.home() / '.gradle/caches'
modules = caches / 'modules-2/files-2.1'
loom = caches / 'fabric-loom'
work = root / 'build/native-profiler-verification'
work.mkdir(parents=True, exist_ok=True)
classes = work / 'classes'
classes.mkdir(exist_ok=True)
def artifact(group, name, version):
    return next((modules / group / name / version).glob('*/' + name + '-' + version + '.jar'))
mc = loom / 'minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
aw = work / 'native-rendertype.accesswidener'
aw.write_text('accessWidener v2 named\naccessible method net/minecraft/client/renderer/rendertype/RenderType <init> (Ljava/lang/String;Lnet/minecraft/client/renderer/rendertype/RenderSetup;)V\n')
patched = work / 'minecraft-native-rendertype-access.jar'
key = hashlib.sha256(aw.read_bytes() + str(mc.stat().st_mtime_ns).encode()).hexdigest()
keyfile = work / 'native-access.key'
if not patched.exists() or not keyfile.exists() or keyfile.read_text() != key:
    driver = work / 'ApplyRenderTypeAccess.java'
    driver.write_text('''
import java.nio.file.*; import java.util.jar.*;
import net.fabricmc.classtweaker.api.*; import org.objectweb.asm.*;
public class ApplyRenderTypeAccess {
 public static void main(String[] args) throws Exception {
  var tweaker=ClassTweaker.newInstance();
  ClassTweakerReader.create(tweaker).read(Files.readAllBytes(Path.of(args[1])), "named");
  try(var source=new JarFile(args[0]); var output=new JarOutputStream(Files.newOutputStream(Path.of(args[2])))) {
   var entries=source.entries();
   while(entries.hasMoreElements()) {
    var entry=entries.nextElement(); var bytes=source.getInputStream(entry).readAllBytes();
    if(entry.getName().endsWith(".class")) {
     String owner=entry.getName().substring(0,entry.getName().length()-6);
     if(tweaker.getTargets().contains(owner)||tweaker.getTargets().contains(owner.replace('/','.'))) {
      var writer=new ClassWriter(0); new ClassReader(bytes).accept(tweaker.createClassVisitor(Opcodes.ASM9,writer,
       (name,generated)->{throw new IllegalStateException("Unexpected generated class "+name);}),0); bytes=writer.toByteArray();
     }
    }
    output.putNextEntry(new JarEntry(entry.getName())); output.write(bytes); output.closeEntry();
   }
  }
 }
}''')
    tweak = [artifact('net.fabricmc', 'class-tweaker', '0.3.0')]
    tweak += list((modules / 'org.ow2.asm').glob('*/9.10.1/*/*.jar'))
    cp = os.pathsep.join(map(str, tweak))
    subprocess.run([str(java/'javac'), '--release', '25', '-proc:none', '-cp', cp, '-d', str(work), str(driver)], check=True)
    subprocess.run([str(java/'java'), '-cp', str(work)+os.pathsep+cp, 'ApplyRenderTypeAccess', str(mc), str(aw), str(patched)], check=True)
    keyfile.write_text(key)
manifest = json.loads((loom/'26.3/mojang_minecraft_info.json').read_text())
classpath = [mc]
for library in manifest['libraries']:
    group, name, version, *_ = library['name'].split(':')
    paths = list((modules/group/name/version).glob('*/'+Path(library['downloads']['artifact']['path']).name))
    if paths: classpath.append(paths[0])
fabric = artifact('net.fabricmc.fabric-api', 'fabric-api', '0.163.0+26.3')
api = work/'fabric-api'; api.mkdir(exist_ok=True)
with zipfile.ZipFile(fabric) as bundle:
    for entry in bundle.namelist():
        if entry.startswith('META-INF/jars/') and entry.endswith('.jar'):
            destination = api/Path(entry).name
            if not destination.exists(): destination.write_bytes(bundle.read(entry))
            classpath.append(destination)
classpath += [artifact('net.fabricmc','fabric-loader','0.19.5'), artifact('org.jetbrains','annotations','26.0.2'),
              root/'local-maven/gg/moonflower/molang-compiler/3.1.1.19/molang-compiler-3.1.1.19.jar',
              root/'local-maven/io/github/ocelot/glsl-processor/0.2.3/glsl-processor-0.2.3.jar']
for name in ['asm','asm-analysis','asm-commons','asm-tree','asm-util']:
    classpath.append(artifact('org.ow2.asm', name, '9.10.1'))
classpath += [artifact('net.fabricmc','sponge-mixin','0.17.4+mixin.0.8.7'), artifact('io.github.llamalad7','mixinextras-fabric','0.5.5')]
gpu = work/'frozen-native-classes'
if gpu.exists(): shutil.rmtree(gpu)
shutil.copytree(root/'build/gpu-verification/classes',gpu)
if not (gpu/'foundry/veil/api/client/render/gpu/GpuShaderProgram$Lease.class').is_file():
    raise SystemExit('First compile the genuine GPU boundary with native program leases; no old API jar or substitute is accepted.')
src = root/'common/src/main/java'
sources = [src/name for name in ['foundry/veil/api/client/render/profiler/VeilRenderProfiler.java','foundry/veil/api/client/render/profiler/RenderProfilerCounter.java','foundry/veil/impl/client/render/profiler/VeilRenderProfilerImpl.java','foundry/veil/api/client/render/ext/VeilDebug.java','foundry/veil/mixin/debug/client/NativeDiagnosticsEncoderMixin.java','foundry/veil/mixin/debug/client/NativeDiagnosticsPassMixin.java']]
production = len(sources)
sources += [root/'port-tests/NativeProfilerAcceptance.java']
compile_cp = os.pathsep.join(map(str,[patched,gpu,*classpath[1:]]))
result = subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-classpath',compile_cp,'-d',str(classes),*map(str,sources)], capture_output=True, text=True)
(work/'compile.log').write_text(result.stdout+result.stderr)
print(f'Native timestamp profiler/pass lifecycle boundary: {production} production classes + 1 explicit test entrypoint; javac exit {result.returncode}',flush=True)
if result.returncode:
    print(result.stdout+result.stderr); raise SystemExit(result.returncode)
(work/'inputs.json').write_text(json.dumps({'sources':{str(path.relative_to(root)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},'scope':'Real native timestamp queries and device period, exact pass-open/close mixins, inclusive CPU-scope replay across passes, nonblocking results, explicit failure-scope cleanup and runtime disposal; full Veil facade not loaded','gpu_boundary_inputs_sha256':hashlib.sha256((root/'build/gpu-verification/inputs.json').read_bytes()).hexdigest(),'native_classes':{str(path.relative_to(gpu)):hashlib.sha256(path.read_bytes()).hexdigest() for path in gpu.rglob('*.class')}},indent=2)+'\n')
if not args.client: raise SystemExit(0)
jarpath = work/'native-rendertype-acceptance.jar'
metadata = {'schemaVersion':1,'id':'veil_native_profiler_acceptance','version':'1.0.0','environment':'client',
            'name':'Veil native timestamp profiler acceptance (test only)', 'entrypoints':{'client':['foundry.veil.porttest.NativeProfilerAcceptance']},
            'mixins':['native-rendertype-test.mixins.json'],'accessWidener':'native-rendertype.accesswidener',
            'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}
with zipfile.ZipFile(jarpath,'w',zipfile.ZIP_DEFLATED) as jar:
    jar.writestr('fabric.mod.json',json.dumps(metadata))
    jar.writestr('native-rendertype.accesswidener',aw.read_text().replace('v2 named','v2 official'))
    jar.writestr('native-rendertype-test.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'foundry.veil.mixin.debug','compatibilityLevel':'JAVA_25','client':['client.NativeDiagnosticsEncoderMixin','client.NativeDiagnosticsPassMixin'],'injectors':{'defaultRequire':1}}))
    seen=set()
    for folder in [classes,gpu]:
        for path in folder.rglob('*.class'):
            name=str(path.relative_to(folder))
            if name not in seen: jar.write(path,name);seen.add(name)
    service='META-INF/services/foundry.veil.platform.registry.RegistrationProvider$Factory'
    jar.write(root/'fabric/src/main/resources'/service,service)
    for folder,prefix in [(root/'common/src/main/resources/assets/veil/shaders','assets/veil/shaders'),(root/'common/src/main/resources/assets/veil/pinwheel/shaders/program','assets/veil/pinwheel/shaders/program'),(root/'common/src/main/resources/assets/veil/pinwheel/rendertypes','assets/veil/pinwheel/rendertypes'),(root/'port-tests/resources','')]:
        for path in folder.rglob('*'):
            if path.is_file(): jar.write(path,(prefix+'/' if prefix else '')+str(path.relative_to(folder)))
classpath += [fabric,jarpath]
run=work/'run'; run.mkdir(exist_ok=True)
asset_index='26.3-'+manifest['assetIndex']['id']
if not (loom/'assets/indexes'/(asset_index+'.json')).is_file(): asset_index=manifest['assetIndex']['id']
report=work/'runtime.json';report.unlink(missing_ok=True)
java_args=['-XstartOnFirstThread','-Xmx2G','-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace=official','-Dfabric.defaultMixinRemapType=static','-Dfabric.gameJarPath='+str(mc),'-Dveil.profiler.report='+str(report),'-Dveil.rendertype.selection='+args.checks,'-classpath',os.pathsep.join(map(str,classpath)),'net.fabricmc.loader.impl.launch.knot.KnotClient','--version','26.3','--username','NativeRenderTypeTest','--accessToken','0','--gameDir',str(run),'--assetsDir',str(loom/'assets'),'--assetIndex',asset_index,'--graphicsBackend','vulkan','--width','480','--height','320']
argfile=work/'client.args';argfile.write_text('\n'.join('"'+arg.replace('\\','\\\\').replace('"','\\"')+'"' for arg in java_args))
print('Launching native timestamp profiler and actual diagnostic pass hooks on Vulkan; full Veil is not loaded.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode: raise SystemExit(result.returncode)
if not report.is_file(): raise SystemExit('Native render-type acceptance produced no report')
result=json.loads(report.read_text())
selection_key = '-'.join(sorted(set(args.checks.split(','))))
(work/(selection_key+'-runtime.json')).write_text(json.dumps(result,indent=2)+'\n')
(work/(selection_key+'-inputs.json')).write_bytes((work/'inputs.json').read_bytes())
if result.get('status')!='passed': raise SystemExit('Native profiler acceptance failed: '+str(result))
print('Native timestamp profiler/pass lifecycle acceptance passed:',json.dumps(result))
