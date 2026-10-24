#!/usr/bin/env python3
"""Verify only new native editor model baking using real loaded Minecraft resources and sprites."""
from pathlib import Path
import argparse,hashlib,json,os,shlex,subprocess,zipfile
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client',action='store_true');options=parser.parse_args()
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin';work=root/'build/native-block-model-editor';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
args=shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text());cp=args[args.index('-classpath')+1]
sources=[root/'common/src/main/java/foundry/veil'/name for name in ['api/client/util/NativeBlockModelBaker.java','ext/TextureAtlasExtension.java','mixin/resource/client/ResourceTextureAtlasMixin.java']]+[root/'port-tests/NativeBlockModelBakerAcceptance.java']
result=subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-cp',cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True);(work/'compile.log').write_text(result.stdout+result.stderr);print('Three genuine native editor bake/sprite sources plus new fixture javac:',result.returncode,flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
(work/'inputs.json').write_text(json.dumps({'scope':'New native editor CuboidModel/ResolvedModel/MaterialBaker/FaceBakery/resident sprite path only; no old GPU tests or whole-mod startup','sources':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,Path(__file__).resolve()]}},indent=2)+'\n')
if not options.client:raise SystemExit(0)
jar=work/'native-block-model-editor-acceptance.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 target.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'veil_native_block_model_bake_acceptance','version':'1.0.0','environment':'client','name':'Veil real native editor block bake acceptance (test only)','entrypoints':{'client':['NativeBlockModelBakerAcceptance']},'mixins':['native-block-model-editor.mixins.json'],'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}))
 target.writestr('native-block-model-editor.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'foundry.veil.mixin.resource','compatibilityLevel':'JAVA_25','client':['client.ResourceTextureAtlasMixin'],'injectors':{'defaultRequire':1}}))
 for p in classes.rglob('*.class'):target.write(p,str(p.relative_to(classes)))
native=root/'build/rendertype-verification';launcher=shlex.split((native/'client.args').read_text());index=launcher.index('-classpath')+1;launcher[index]=os.pathsep.join([entry for entry in launcher[index].split(os.pathsep) if entry!=str(native/'native-rendertype-acceptance.jar')]+[str(jar)])
launcher=[entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')];run=work/'run';run.mkdir(exist_ok=True);launcher[launcher.index('--gameDir')+1]=str(run);report=work/'runtime.json';report.unlink(missing_ok=True);launcher.insert(0,'-Dveil.block.model.bake.report='+str(report))
argfile=work/'client.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in launcher));print('Launching only new real native editor model bake acceptance.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode:raise SystemExit(result.returncode)
if not report.is_file():raise SystemExit('Native block editor bake fixture produced no report')
data=json.loads(report.read_text());print('New actual native block editor bake result:',data)
if data.get('status')!='passed':raise SystemExit(1)
