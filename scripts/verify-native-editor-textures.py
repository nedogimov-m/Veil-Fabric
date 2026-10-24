#!/usr/bin/env python3
"""Verify only the new actual native inspector sampling, typed inventory and PNG export."""
from pathlib import Path
import argparse,hashlib,json,os,shlex,subprocess,zipfile
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client',action='store_true');options=parser.parse_args()
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
work=root/'build/native-editor-textures';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
native=root/'build/rendertype-verification';gpu=root/'build/gpu-verification';layered=root/'build/native-layered-textures'
launcher=shlex.split((native/'client.args').read_text());index=launcher.index('-classpath')+1;classpath=launcher[index].split(os.pathsep)
base=root/'common/src/main/java/foundry/veil'
sources=[base/name for name in ['api/client/util/NativeTexturePreview.java','api/client/util/NativeTextureRegistry.java','api/client/util/TextureDownloader.java','api/client/render/texture/NativeArrayTextureViews.java','mixin/pipeline/client/NativeTextureRegistryMixin.java']]+[root/'port-tests/NativeEditorTextureAcceptance.java']
args=shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text());boundaries=(layered/'classes',gpu/'classes',native/'classes');compile_cp=os.pathsep.join([*map(str,boundaries),args[args.index('-classpath')+1]])
result=subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-cp',compile_cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True)
(work/'compile.log').write_text(result.stdout+result.stderr);print('Five genuine native inspector helpers + new fixture javac:',result.returncode,flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
manifest={'scope':'New native typed inspector previews/inventory/PNG export only; no old GPU groups or whole-port startup','sources':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,Path(__file__).resolve()]},'boundaries':{str(folder):{str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*.class'))} for folder in boundaries}}
(work/'inputs.json').write_text(json.dumps(manifest,indent=2)+'\n')
if not options.client:raise SystemExit(0)
jar=work/'native-editor-textures-acceptance.jar'
with zipfile.ZipFile(native/'native-rendertype-acceptance.jar') as existing,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 for item in existing.infolist():
  if item.filename=='fabric.mod.json' or item.filename.endswith('.class'):continue
  target.writestr(item,existing.read(item))
 target.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'veil_native_editor_textures_acceptance','version':'1.0.0','environment':'client','name':'Veil native inspector previews and PNG acceptance (test only)','entrypoints':{'client':['NativeEditorTextureAcceptance']},'mixins':['native-editor-textures.mixins.json'],'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}))
 target.writestr('native-editor-textures.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'foundry.veil.mixin.pipeline','compatibilityLevel':'JAVA_25','client':['client.NativeTextureRegistryMixin','client.NativeVulkanArrayViewMixin','accessor.NativeTextureDeviceAccessor'],'injectors':{'defaultRequire':1}}))
 seen=set()
 for folder in (classes,*boundaries):
  for p in folder.rglob('*.class'):
   name=str(p.relative_to(folder))
   if name not in seen:target.write(p,name);seen.add(name)
launcher[index]=os.pathsep.join([str(jar) if entry==str(native/'native-rendertype-acceptance.jar') else entry for entry in classpath]);run=work/'run';run.mkdir(exist_ok=True);launcher[launcher.index('--gameDir')+1]=str(run)
launcher=[entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')];report=work/'runtime.json';report.unlink(missing_ok=True);launcher.insert(0,'-Dveil.editor.texture.report='+str(report));launcher.insert(0,'-Dveil.editor.texture.output='+str(work/'png'))
argfile=work/'client.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in launcher))
print('Launching only new native inspector previews/inventory/PNG on actual Vulkan.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode:raise SystemExit(result.returncode)
if not report.is_file():raise SystemExit('Native editor texture fixture produced no report')
data=json.loads(report.read_text());print('New native editor texture result:',data)
if data.get('status')!='passed':raise SystemExit(1)
