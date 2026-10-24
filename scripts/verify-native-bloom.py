#!/usr/bin/env python3
"""Compile native Bloom/output queue and run only the new original Bloom pipeline acceptance."""
from pathlib import Path
import argparse,hashlib,json,os,shlex,subprocess,zipfile
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client',action='store_true');options=parser.parse_args()
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
work=root/'build/native-bloom';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
native=root/'build/rendertype-verification';gpu=root/'build/gpu-verification'
launcher=shlex.split((native/'client.args').read_text());index=launcher.index('-classpath')+1;classpath=launcher[index].split(os.pathsep)
base=root/'common/src/main/java/foundry/veil'
sources=[base/name for name in ['api/client/render/gpu/GpuFramebuffer.java','api/client/render/gpu/NativeBloomTarget.java','api/client/render/rendertype/NativeRenderTypeOutput.java','impl/client/render/rendertype/NativeRenderTypeDraws.java']]+[root/'port-tests/NativeBloomAcceptance.java']
args=shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text());compile_cp=os.pathsep.join([str(gpu/'classes'),str(native/'classes'),args[args.index('-classpath')+1]])
result=subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-cp',compile_cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True)
(work/'compile.log').write_text(result.stdout+result.stderr);print('Four genuine native Bloom/output production sources + new fixture javac:',result.returncode,flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
resources=root/'common/src/main/resources'
resource_names=['assets/veil/pinwheel/post/core/bloom.json','assets/veil/pinwheel/framebuffers/post.json','assets/veil/pinwheel/shaders/program/blit_screen.vsh','assets/veil/pinwheel/shaders/include/color_utilities.glsl']+[f'assets/veil/pinwheel/shaders/program/core/{name}.{ext}' for name in ['bloom','blur','unblur'] for ext in ['json','fsh']]
inputs=[*sources,Path(__file__).resolve(),*[resources/name for name in resource_names]]
manifest={'scope':'New Bloom lifecycle and actual original seven-stage core/bloom only; no old GPU groups or full port startup','sources':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},'boundaries':{str(folder):{str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*.class'))} for folder in (gpu/'classes',native/'classes')}}
(work/'inputs.json').write_text(json.dumps(manifest,indent=2)+'\n')
if not options.client:raise SystemExit(0)
jar=work/'native-bloom-acceptance.jar'
with zipfile.ZipFile(native/'native-rendertype-acceptance.jar') as existing,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 for item in existing.infolist():
  if item.filename=='fabric.mod.json' or item.filename.endswith('.class') or item.filename in resource_names:continue
  target.writestr(item,existing.read(item))
 target.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'veil_native_bloom_acceptance','version':'1.0.0','environment':'client','name':'Veil native original Bloom acceptance (test only)','entrypoints':{'main':['NativeBloomAcceptance'],'client':['NativeBloomAcceptance']},'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}))
 target.writestr('assets/veil/shaders/core/native_bloom_emission.fsh','#version 450\nlayout(location=0) out vec4 fragColor;\nvoid main(){gl_FragDepth=gl_FragCoord.x<8.0?0.25:0.9;fragColor=vec4(0.5,0.125,0.0625,1);}')
 for name in resource_names:target.write(resources/name,name)
 seen=set()
 for folder in (classes,gpu/'classes',native/'classes'):
  for p in folder.rglob('*.class'):
   name=str(p.relative_to(folder))
   if name not in seen:target.write(p,name);seen.add(name)
launcher[index]=os.pathsep.join([str(jar) if entry==str(native/'native-rendertype-acceptance.jar') else entry for entry in classpath]);run=work/'run';run.mkdir(exist_ok=True);launcher[launcher.index('--gameDir')+1]=str(run)
launcher=[entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')];report=work/'runtime.json';report.unlink(missing_ok=True);launcher.insert(0,'-Dveil.bloom.report='+str(report))
argfile=work/'client.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in launcher))
print('Launching only new original Bloom lifecycle/post pipeline on actual Vulkan.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode:raise SystemExit(result.returncode)
if not report.is_file():raise SystemExit('Native Bloom fixture produced no report')
data=json.loads(report.read_text());print('New native Bloom result:',data)
if data.get('status')!='passed':raise SystemExit(1)
