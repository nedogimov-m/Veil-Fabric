#!/usr/bin/env python3
"""Verify real26.3 dynamic registry task orchestration and production mixin on a headless Fabric launch."""
import hashlib,json,os,subprocess,zipfile,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'build/native-registry-verification';CLASSES=WORK/'classes';CLASSES.mkdir(parents=True,exist_ok=True)
CACHE=Path.home()/'.gradle/caches';MODULES=CACHE/'modules-2/files-2.1';LOOM=CACHE/'fabric-loom'
JAVA=Path(os.environ['JAVA_HOME'])/'bin'
def artifact(group,name,version):return next((MODULES/group/name/version).glob('*/'+name+'-'+version+'.jar'))
mc=LOOM/'minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar'
metadata=json.loads((LOOM/'26.3/mojang_minecraft_info.json').read_text());cp=[mc]
for lib in metadata['libraries']:
 group,name,version,*_=lib['name'].split(':');cp+=list((MODULES/group/name/version).glob('*/'+Path(lib['downloads']['artifact']['path']).name))[:1]
cp+=[artifact('net.fabricmc','fabric-loader','0.19.5'),artifact('org.jetbrains','annotations','26.0.2'),artifact('net.fabricmc','sponge-mixin','0.17.4+mixin.0.8.7')]
for name in ['asm','asm-analysis','asm-commons','asm-tree','asm-util']:cp.append(artifact('org.ow2.asm',name,'9.10.1'))
sources=[ROOT/'common/src/main/java/foundry/veil/api/resource/VeilDynamicRegistry.java',ROOT/'common/src/main/java/foundry/veil/mixin/registry/accessor/RegistryDataAccessor.java',ROOT/'port-tests/NativeRegistryAcceptance.java']
subprocess.run([str(JAVA/'javac'),'--release','25','-proc:none','-implicit:none','-cp',os.pathsep.join(map(str,cp)),'-d',str(CLASSES),*map(str,sources)],check=True)
jar=WORK/'veil-native-registry-acceptance.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as out:
 out.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'veil_native_registry_acceptance','version':'1.0.0','environment':'*','entrypoints':{'preLaunch':['foundry.veil.porttest.NativeRegistryAcceptance']},'mixins':['veil-native-registry.mixins.json'],'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5'}}))
 out.writestr('veil-native-registry.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'foundry.veil.mixin.registry','compatibilityLevel':'JAVA_25','mixins':['accessor.RegistryDataAccessor'],'injectors':{'defaultRequire':1}}))
 for p in CLASSES.rglob('*.class'):out.write(p,str(p.relative_to(CLASSES)))
cp.append(jar);run=WORK/'run';run.mkdir(exist_ok=True);report=WORK/'runtime.json';report.unlink(missing_ok=True)
shutil.rmtree(WORK/'resources',ignore_errors=True)
args=['-Xmx1G','-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace=official','-Dfabric.defaultMixinRemapType=static','-Dfabric.gameJarPath='+str(mc),'-Dveil.registry.report='+str(report),'-Dveil.registry.work='+str(WORK/'resources'),'-classpath',os.pathsep.join(map(str,cp)),'net.fabricmc.loader.impl.launch.knot.KnotServer','nogui']
argfile=WORK/'server.args';argfile.write_text('\n'.join('"'+a.replace('\\','\\\\').replace('"','\\"')+'"' for a in args))
subprocess.run([str(JAVA/'java'),'@'+str(argfile)],cwd=run,timeout=90,check=True)
result=json.loads(report.read_text());assert result.get('status')=='passed',result
record={'check':'native-dynamic-registries','minecraft':'26.3','result':'PASS','runtime':result,'scope':'production real native ResourceManagerRegistryLoadTask/context invoker with actual pack resources, cross-registry holders, partial parse/freeze/validation errors and async/inline/failure thread-local restoration; no full Veil startup','inputs':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,Path(__file__).resolve()]},'classpath':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in cp}}
(WORK/'inputs.json').write_text(json.dumps(record,indent=2)+'\n');print('Native dynamic registry acceptance:',result)
