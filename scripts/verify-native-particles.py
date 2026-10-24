#!/usr/bin/env python3
"""Compile/run only the four new genuine native particle contracts."""
from pathlib import Path
import argparse,hashlib,json,os,shlex,subprocess,zipfile
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client',action='store_true');parser.add_argument('--cases',default='payload,particles,additive,trails');options=parser.parse_args()
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
work=root/'build/native-particles';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
native=root/'build/rendertype-verification';gpu=root/'build/gpu-verification'
launcher=shlex.split((native/'client.args').read_text());index=launcher.index('-classpath')+1;classpath=launcher[index].split(os.pathsep)
base=root/'common/src/main/java/foundry/veil';sources=[base/name for name in ['api/client/render/gpu/NativeParticlePass.java','api/client/render/gpu/NativeVertexSnapshot.java','api/client/render/VertexSource.java','api/client/render/CachedBufferSource.java','api/client/render/gpu/NativeFeatureScene.java','mixin/scene/client/NativeFeatureDispatcherInvoker.java','mixin/scene/client/NativeFeatureLightmapMixin.java']]+[root/'port-tests/NativeParticleAcceptance.java']
args=shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text());compile_cp=os.pathsep.join([str(gpu/'classes'),str(native/'classes'),args[args.index('-classpath')+1]])
result=subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-cp',compile_cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True)
(work/'compile.log').write_text(result.stdout+result.stderr);print('Seven actual native particle/capture/scene production sources + new fixture javac:',result.returncode,flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
resources=root/'common/src/main/resources';test_resources=root/'port-tests/resources'
resource_names=['assets/veil/pinwheel/shaders/program/quasar/particle.'+ext for ext in ['json','vsh','fsh']]+['veil.scene.mixins.json']
test_names=[]
manifest={'scope':'New native Quasar original particle shader/instance data, camera/billboard geometry, UV+lightmap+alpha+depth and detached procedural native feature capture. Full Quasar lifecycle/global Veil bootstrap remains separate','sources':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,Path(__file__).resolve(),*[resources/name for name in resource_names],*[test_resources/name for name in test_names]]},'boundaries':{str(folder):{str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*.class'))} for folder in (gpu/'classes',native/'classes')}}
(work/'inputs.json').write_text(json.dumps(manifest,indent=2)+'\n')
if not options.client:raise SystemExit(0)
jar=work/'native-particles-acceptance.jar'
with zipfile.ZipFile(native/'native-rendertype-acceptance.jar') as existing,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 for item in existing.infolist():
  if item.filename=='fabric.mod.json' or item.filename.endswith('.class') or item.filename in resource_names or item.filename in test_names:continue
  target.writestr(item,existing.read(item))
 target.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'veil_native_particles_acceptance','version':'1.0.0','environment':'client','name':'Veil native particles acceptance (test only)','entrypoints':{'client':['NativeParticleAcceptance']},'mixins':['veil.scene.mixins.json'],'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}))
 for name in resource_names:target.write(resources/name,name)
 for name in test_names:target.write(test_resources/name,name)
 seen=set()
 for folder in (classes,gpu/'classes',native/'classes'):
  for p in folder.rglob('*.class'):
   name=str(p.relative_to(folder))
   if name not in seen:target.write(p,name);seen.add(name)
launcher[index]=os.pathsep.join([str(jar) if entry==str(native/'native-rendertype-acceptance.jar') else entry for entry in classpath]);run=work/'run';run.mkdir(exist_ok=True);launcher[launcher.index('--gameDir')+1]=str(run)
launcher=[entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')];report=work/'runtime.json';report.unlink(missing_ok=True);launcher.insert(0,'-Dveil.particles.report='+str(report));launcher.insert(0,'-Dveil.particles.cases='+options.cases)
argfile=work/'client.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in launcher))
print('Launching only selected new native particle shader/geometry contracts on actual Vulkan.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode:raise SystemExit(result.returncode)
if not report.is_file():raise SystemExit('Native particle fixture produced no report')
data=json.loads(report.read_text());print('New native particle result:',data)
if data.get('status')!='passed':raise SystemExit(1)
