#!/usr/bin/env python3
"""Compile/run the new native cube/array texture and actual native reload/sampling only."""
from pathlib import Path
import argparse,hashlib,json,os,shlex,subprocess,zipfile,struct,zlib
root=Path(__file__).resolve().parents[1];repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client',action='store_true');options=parser.parse_args()
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
work=root/'build/native-layered-textures';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
native=root/'build/rendertype-verification';gpu=root/'build/gpu-verification'
launcher=shlex.split((native/'client.args').read_text());index=launcher.index('-classpath')+1;classpath=launcher[index].split(os.pathsep)
base=root/'common/src/main/java/foundry/veil';sources=[base/('api/client/render/texture/'+name+'.java') for name in ('NativeLayeredTexture','NativeTextureImages','NativeArrayTextureViews','CubemapTexture','SimpleCubemapTexture','DynamicCubemapTexture','ArrayTexture','SimpleArrayTexture','TextureTypeMetadataSection','VeilPreloadedTexture')]+[base/'ext/TextureManagerExtension.java',base/'mixin/pipeline/client/PipelineTextureManagerMixin.java',base/'mixin/pipeline/client/NativeVulkanArrayViewMixin.java',base/'mixin/pipeline/accessor/NativeTextureDeviceAccessor.java',root/'port-tests/NativeLayeredTextureAcceptance.java']
args=shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text());compile_cp=os.pathsep.join([str(gpu/'classes'),str(native/'classes'),args[args.index('-classpath')+1]])
result=subprocess.run([str(java/'javac'),'--release','25','-proc:none','-sourcepath','','-cp',compile_cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True)
(work/'compile.log').write_text(result.stdout+result.stderr);print('Fourteen genuine texture production sources+new explicit fixture javac:',result.returncode,flush=True)
if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
manifest={'scope':'New genuine cube/array views, pixel sampling and actual metadata/preload/reload lifecycle only; no old GPU groups or full port startup','sources':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,Path(__file__).resolve()]},'boundaries':{str(folder):{str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*.class'))} for folder in (gpu/'classes',native/'classes')}}
(work/'inputs.json').write_text(json.dumps(manifest,indent=2)+'\n')
if not options.client:raise SystemExit(0)
jar=work/'native-layered-texture-acceptance.jar'
with zipfile.ZipFile(native/'native-rendertype-acceptance.jar') as existing,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
 for item in existing.infolist():
  if item.filename=='fabric.mod.json' or item.filename.endswith('.class'):continue
  target.writestr(item,existing.read(item))
 target.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'veil_native_layered_texture_acceptance','version':'1.0.0','environment':'client','name':'Veil native layered texture acceptance (test only)','entrypoints':{'client':['NativeLayeredTextureAcceptance']},'mixins':['native-layered-textures.mixins.json'],'depends':{'minecraft':'26.3','fabricloader':'>=0.19.5','fabric-api':'*'}}))
 target.writestr('native-layered-textures.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'foundry.veil.mixin.pipeline','compatibilityLevel':'JAVA_25','client':['client.PipelineTextureManagerMixin','client.NativeVulkanArrayViewMixin','accessor.NativeTextureDeviceAccessor'],'injectors':{'defaultRequire':1}}))
 def png(width,height,pixel):
  raw=b''.join(b'\0'+b''.join(bytes(pixel(x,y)) for x in range(width)) for y in range(height))
  def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
  return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
 def color(face,x,y):return ((face*24+16)&255,40+x*80,40+y*80,255)
 tiles={(2,1):0,(0,1):1,(1,0):2,(1,2):3,(1,1):4,(3,1):5}
 target.writestr('assets/veil/textures/test/native_cube.png',png(8,6,lambda x,y:color(tiles[(x//2,y//2)],x%2,y%2) if (x//2,y//2) in tiles else (0,0,0,0)))
 target.writestr('assets/veil/textures/test/native_cube.png.mcmeta',json.dumps({'veil:texture_type':{'type':'cube_map'},'texture':{'blur':False,'clamp':True}}))
 for name,face in [('a',6),('b',7)]:
  target.writestr('assets/veil/textures/test/native_array_'+name+'.png',png(2,2,lambda x,y:color(face,x,y)))
  target.writestr('assets/veil/textures/test/native_array_'+name+'.png.mcmeta',json.dumps({'veil:texture_type':{'type':'2d_array'},'texture':{'blur':False,'clamp':True}}))
 target.writestr('assets/veil/shaders/core/native_layered_array.fsh',"#version 450\nlayout(set=0,binding=0) uniform sampler2DArray InSampler;\nlayout(location=0) out vec4 fragColor;\nvoid main(){vec2 uv=fract(gl_FragCoord.xy/2.0);fragColor=texture(InSampler,vec3(uv,floor(gl_FragCoord.x/2.0)));}\n")
 target.writestr('assets/veil/shaders/core/native_layered_cube.fsh',"#version 450\nlayout(set=0,binding=0) uniform samplerCube InSampler;\nlayout(location=0) out vec4 fragColor;\nvoid main(){vec2 s=fract(gl_FragCoord.xy/2.0)*2.0-1.0;int f=int(gl_FragCoord.x)/2;vec3 d;if(f==0)d=vec3(1,-s.y,-s.x);else if(f==1)d=vec3(-1,-s.y,s.x);else if(f==2)d=vec3(s.x,1,s.y);else if(f==3)d=vec3(s.x,-1,-s.y);else if(f==4)d=vec3(s.x,-s.y,1);else d=vec3(-s.x,-s.y,-1);fragColor=texture(InSampler,d);}\n")
 seen=set()
 for folder in (classes,gpu/'classes',native/'classes'):
  for p in folder.rglob('*.class'):
   name=str(p.relative_to(folder))
   if name not in seen:target.write(p,name);seen.add(name)
launcher[index]=os.pathsep.join([str(jar) if entry==str(native/'native-rendertype-acceptance.jar') else entry for entry in classpath]);run=work/'run';run.mkdir(exist_ok=True);launcher[launcher.index('--gameDir')+1]=str(run)
launcher=[entry for entry in launcher if not entry.startswith('-Dveil.rendertype.')];report=work/'runtime.json';report.unlink(missing_ok=True);launcher.insert(0,'-Dveil.layered.texture.report='+str(report))
argfile=work/'client.args';argfile.write_text('\n'.join('"'+entry.replace('\\','\\\\').replace('"','\\"')+'"' for entry in launcher))
print('Launching only new cube/array texture pixel/reload scope on actual Vulkan.',flush=True)
result=subprocess.run([str(java/'java'),'@'+str(argfile)],cwd=run,timeout=180)
if result.returncode:raise SystemExit(result.returncode)
if not report.is_file():raise SystemExit('Native layered texture fixture produced no report')
data=json.loads(report.read_text());print('New native layered texture result:',data)
if data.get('status')!='passed':raise SystemExit(1)
