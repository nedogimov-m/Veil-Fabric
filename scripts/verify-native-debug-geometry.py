#!/usr/bin/env python3
"""Compile and check actual native debug-line attributes and detached perspective frustum geometry."""
from pathlib import Path
import hashlib,json,os,subprocess
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'build/native-debug-geometry-verification';CLASSES=WORK/'classes';CLASSES.mkdir(parents=True,exist_ok=True)
SUITE=ROOT.parent.parent/'build/porting/suite-diagnostic/inputs.json'
inputs=json.loads(SUITE.read_text());cp=os.pathsep.join(inputs['classpath'])
JAVA=Path(os.environ['JAVA_HOME'])/'bin'
sources=[ROOT/'common/src/main/java/foundry/veil'/name for name in ['api/client/util/DebugRenderHelper.java','api/client/util/DebugFrustumRenderer.java','api/client/render/gpu/NativeVertexSnapshot.java']]+[ROOT/'port-tests/NativeDebugGeometryAcceptance.java']
subprocess.run([str(JAVA/'javac'),'--release','25','-proc:none','-implicit:none','-cp',cp,'-d',str(CLASSES),*map(str,sources)],check=True)
result=subprocess.run([str(JAVA/'java'),'-cp',str(CLASSES)+os.pathsep+cp,'NativeDebugGeometryAcceptance'],capture_output=True,text=True)
(WORK/'runtime.log').write_text(result.stdout+result.stderr);print(result.stdout+result.stderr)
if result.returncode:raise SystemExit(result.returncode)
(WORK/'inputs.json').write_text(json.dumps({'result':'PASS','checks':3,'sources':{str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},'scope':'Actual native line format/width/tangent with pose transform, detached perspective frustum homogeneous division and invalid-matrix rejection; no GPU pixel claim'},indent=2)+'\n')
