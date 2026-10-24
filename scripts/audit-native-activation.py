#!/usr/bin/env python3
"""Read-only Veil metadata and exact target-member audit; actual Mixin application remains a startup gate."""
from pathlib import Path
import argparse, collections, hashlib, importlib.util, json, os, re, shlex, zipfile

default_root = Path(__file__).resolve().parents[1]
repo = default_root.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project', type=Path, default=default_root)
parser.add_argument('--all-configs', action='store_true', help='Inventory every on-disk config, including explicit unregistered obligations.')
parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'), help='The Java25 diagnostic toolchain; supplies genuine JDK class definitions.')
parser.add_argument('--artifact', type=Path, action='append', default=[], help='Actual production JAR or class directory to audit before the canonical diagnostic classpath; repeatable.')
options = parser.parse_args()
root = options.project.resolve()
work = root / 'build/native-activation-audit'
work.mkdir(parents=True, exist_ok=True)
args = shlex.split((repo/'build/porting/suite-diagnostic/javac.args').read_text())
classpath = [str(path.resolve()) for path in options.artifact]+args[args.index('-classpath')+1].split(os.pathsep)
production_artifacts = {}
for path in options.artifact:
    path = path.resolve()
    if path.is_file(): production_artifacts[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    elif path.is_dir(): production_artifacts[str(path)] = {str(item.relative_to(path)):hashlib.sha256(item.read_bytes()).hexdigest() for item in sorted(path.rglob('*.class'))}
    else: raise SystemExit('Missing actual production artifact: '+str(path))
source_index = {}
for name in json.loads((repo/'build/porting/suite-diagnostic/inputs.json').read_text()).get('sources',{}):
    path = repo/name
    if path.is_file() and '/src/main/java/' in name:
        owner = name.split('/src/main/java/',1)[1][:-5]
        source_index[owner] = path
spec = importlib.util.spec_from_file_location('native_member_audit', repo/'tools/audit_minecraft_mixins.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
index = {}
for entry in classpath:
    path = Path(entry)
    if path.is_dir():
        for item in path.rglob('*.class'):
            index.setdefault(item.relative_to(path).as_posix()[:-6], (path, item))
    elif path.is_file() and path.suffix == '.jar':
        with zipfile.ZipFile(path) as jar:
            for name in jar.namelist():
                if name.endswith('.class'):
                    index.setdefault(name[:-6], (path, name))

# JDK owners are absent from an ordinary javac classpath. Read their genuine
# toolchain definitions rather than reporting Optional/List calls as missing.
java_home = options.java_home
if java_home is None:
    baseline = repo/'ports/sable/build/diagnostic/bridge-26-javac.inputs.json'
    if baseline.is_file():
        value = json.loads(baseline.read_text()).get('java_home')
        if value: java_home = Path(value)
if java_home is None or not (java_home/'jmods').is_dir():
    raise SystemExit('Supply the Java25 diagnostic toolchain with JAVA_HOME or --java-home.')
if not re.search(r'(?m)^JAVA_VERSION="25(?:[.\-"]|$)', (java_home/'release').read_text()):
    raise SystemExit('The activation audit requires the same Java25 toolchain as the canonical diagnostic.')
jdk_modules = {}
for path in sorted((java_home/'jmods').glob('*.jmod')):
    jdk_modules[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if name.startswith('classes/') and name.endswith('.class') and name != 'classes/module-info.class':
                index.setdefault(name[len('classes/'):-6], (path, name))

# JDK targets are genuine members too (e.g. Objects.requireNonNull, List.isEmpty and Math.clamp).
java_base = Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'jmods/java.base.jmod'
if java_base.is_file():
    with zipfile.ZipFile(java_base) as jar:
        for name in jar.namelist():
            if name.startswith('classes/') and name.endswith('.class'):
                index.setdefault(name[8:-6],(java_base,name))

cache = {}
def members(owner):
    if owner not in cache:
        source = index.get(owner)
        if source is None:
            cache[owner] = None
        else:
            path, entry = source
            if path.is_dir(): data = entry.read_bytes()
            else:
                with zipfile.ZipFile(path) as jar: data = jar.read(entry)
            info = base.class_members(data)
            info['source'] = str(path)
            info['references'] = references(data)
            info['methodAccess'] = method_access(data)
            info['methodReferences'] = method_references(data)
            cache[owner] = info
    return cache[owner]

def method_access(data):
    r = base.Reader(data)
    r.skip(8)
    pool = [None]*r.number(2)
    index = 1
    while index < len(pool):
        tag = r.number(1)
        if tag == 1:
            size = r.number(2)
            pool[index] = data[r.offset:r.offset+size].decode('utf8',errors='replace')
            r.skip(size)
        elif tag in (7,8,16,19,20):r.skip(2)
        elif tag in (3,4,9,10,11,12,17,18):r.skip(4)
        elif tag in (5,6):r.skip(8);index += 1
        elif tag == 15:r.skip(3)
        else:raise ValueError('Unknown constant tag '+str(tag))
        index += 1
    r.skip(6)
    r.skip(r.number(2)*2)
    groups = []
    for _ in range(2):
        result = {}
        for _ in range(r.number(2)):
            access,name,descriptor = r.number(2),pool[r.number(2)],pool[r.number(2)]
            result[(name,descriptor)] = access
            for _ in range(r.number(2)):
                r.skip(2)
                r.skip(r.number(4))
        groups.append(result)
    return groups[1]

def method_references(data):
    """Read exact member references from each real Code body, not just its pool."""
    r = base.Reader(data)
    r.skip(8)
    pool = [None]*r.number(2)
    index = 1
    while index < len(pool):
        tag = r.number(1)
        if tag == 1:
            size = r.number(2)
            pool[index] = data[r.offset:r.offset+size].decode('utf8',errors='replace')
            r.skip(size)
        elif tag in (7,8,16,19,20):pool[index] = (tag,r.number(2))
        elif tag in (9,10,11,12,17,18):pool[index] = (tag,r.number(2),r.number(2))
        elif tag in (3,4):r.skip(4)
        elif tag in (5,6):r.skip(8);index += 1
        elif tag == 15:r.skip(3)
        else:raise ValueError('Unknown constant tag '+str(tag))
        index += 1
    r.skip(6)
    r.skip(r.number(2)*2)
    # Field attributes precede the methods.
    for _ in range(r.number(2)):
        r.skip(6)
        for _ in range(r.number(2)):
            r.skip(2)
            r.skip(r.number(4))
    lengths = {opcode:2 for opcode in [16,18,*range(21,26),*range(54,59),169,188]}
    lengths.update({opcode:3 for opcode in [17,19,20,132,*range(153,169),*range(178,185),187,189,192,193,198,199]})
    lengths.update({185:5,186:5,197:4,200:5,201:5})
    result = {}
    for _ in range(r.number(2)):
        r.skip(2)
        name,descriptor = pool[r.number(2)],pool[r.number(2)]
        calls = set()
        for _ in range(r.number(2)):
            attribute,size = pool[r.number(2)],r.number(4)
            end = r.offset+size
            if attribute == 'Code':
                r.skip(4)
                code = data[r.offset+4:r.offset+4+int.from_bytes(data[r.offset:r.offset+4],'big')]
                offset = 0
                while offset < len(code):
                    opcode = code[offset]
                    if 178 <= opcode <= 185:
                        item = pool[int.from_bytes(code[offset+1:offset+3],'big')]
                        owner = pool[pool[item[1]][1]]
                        pair = pool[item[2]]
                        calls.add((owner,pool[pair[1]],pool[pair[2]]))
                    if opcode in (170,171):
                        aligned = (offset+4)&~3
                        if opcode == 170:
                            low = int.from_bytes(code[aligned+4:aligned+8],'big',signed=True)
                            high = int.from_bytes(code[aligned+8:aligned+12],'big',signed=True)
                            offset = aligned+12+4*(high-low+1)
                        else:
                            pairs = int.from_bytes(code[aligned+4:aligned+8],'big')
                            offset = aligned+8+8*pairs
                    elif opcode == 196:offset += 6 if code[offset+1] == 132 else 4
                    else:offset += lengths.get(opcode,1)
                if offset != len(code):raise ValueError('Invalid Code instruction boundary in '+name+descriptor)
            r.offset = end
        result[(name,descriptor)] = calls
    return result

def references(data):
    r = base.Reader(data)
    r.skip(8)
    pool = [None] * r.number(2)
    i = 1
    while i < len(pool):
        tag = r.number(1)
        if tag == 1:
            size = r.number(2)
            pool[i] = data[r.offset:r.offset+size].decode('utf8',errors='replace')
            r.skip(size)
        elif tag in (7,8,16,19,20): pool[i] = (tag,r.number(2))
        elif tag in (9,10,11,12,17,18): pool[i] = (tag,r.number(2),r.number(2))
        elif tag in (3,4): r.skip(4)
        elif tag in (5,6): r.skip(8); i += 1
        elif tag == 15: r.skip(3)
        else: raise ValueError('Unknown constant tag '+str(tag))
        i += 1
    result = set()
    for item in pool:
        if isinstance(item,tuple) and item[0] in (9,10,11):
            owner = pool[pool[item[1]][1]]
            pair = pool[item[2]]
            result.add((owner,pool[pair[1]],pool[pair[2]]))
    return result

def has(owner, group, name, descriptor=None, inherited=False):
    pending = [owner]
    visited = set()
    while pending:
        current = pending.pop()
        if not current or current in visited: continue
        visited.add(current)
        info = members(current)
        if info is None: continue
        if any(n == name and (descriptor is None or descriptor == d) for n,d in info[group]): return True
        if inherited: pending += [info['parent'],*info['interfaces']]
    return False

def method_descriptors(owner, name):
    pending, visited, result = [owner], set(), set()
    while pending:
        current = pending.pop()
        if not current or current in visited: continue
        visited.add(current)
        info = members(current)
        if info is None: continue
        result.update(d for n,d in info['methods'] if n == name)
        pending += [info['parent'],*info['interfaces']]
    return result

def native_method_access(owner,name,descriptor):
    pending,visited = [owner],set()
    while pending:
        current = pending.pop()
        if not current or current in visited:continue
        visited.add(current)
        info = members(current)
        if info is None:continue
        if (name,descriptor) in info['methodAccess']:return info['methodAccess'][(name,descriptor)]
        pending += [info['parent'],*info['interfaces']]
    return None

def resolve(name):
    owner = name.replace('.','/')
    attempts = [owner]
    while '/' in owner:
        at = owner.rfind('/')
        owner = owner[:at]+'$'+owner[at+1:]
        attempts.append(owner)
    return next((owner for owner in attempts if owner in index or owner in source_index),attempts[0])

def erase_generics(value):
    depth = 0
    result = []
    for char in value:
        if char == '<': depth += 1
        elif char == '>': depth -= 1
        elif not depth: result.append(char)
    return ''.join(result)

def shadow_descriptor(declaration, imported, wildcards, package):
    """Resolve concrete Java shadow signatures; keep unresolved generics explicit."""
    declaration = re.sub(r'@[\w.]+(?:\([^)]*\))?\s*', '', declaration)
    declaration = re.sub(r'\b(?:public|protected|private|abstract|static|final|native|synchronized|strictfp|default)\b', '', declaration).strip()
    type_variables = {}
    generic_declaration = re.match(r'^<([^>]+)>',declaration)
    if generic_declaration:
        for parameter in generic_declaration[1].split(','):
            match = re.match(r'\s*(\w+)(?:\s+extends\s+([\w.$]+))?',parameter)
            if match: type_variables[match[1]] = match[2] or 'java.lang.Object'
    declaration = erase_generics(declaration)
    declaration = declaration.strip()
    method = re.fullmatch(r'(.+?)\s+([\w$]+)\s*\((.*?)\)\s*(?:throws\s+.*)?', declaration, re.S)
    if not method: return None
    primitives = dict(zip(('void','boolean','byte','char','short','int','long','float','double'), 'VZBCSIJFD'))
    def descriptor(value):
        value = re.sub(r'\s+', '', value.replace('...', '[]'))
        dimensions = value.count('[]')
        value = value.replace('[]', '')
        if value in primitives: return '['*dimensions+primitives[value]
        value = type_variables.get(value,value)
        first,_,suffix = value.partition('.')
        candidates = [imported[first]+('.'+suffix if suffix else '')] if first in imported else []
        candidates += [value,package+'.'+value,'java.lang.'+value,*[item+'.'+value for item in wildcards]]
        owner = next((resolved for name in candidates if (resolved := resolve(name)) in index or resolved in source_index), None)
        return '['*dimensions+'L'+owner+';' if owner else None
    result = descriptor(method[1])
    parameters = []
    for parameter in method[3].split(',') if method[3].strip() else []:
        match = re.fullmatch(r'\s*(.+?)\s+[\w$]+(\s*(?:\[\])*)\s*', parameter, re.S)
        parameters.append(descriptor(match[1]+match[2]) if match else None)
    if result is None or any(item is None for item in parameters): return None
    return '('+''.join(parameters)+')'+result

def annotated_methods(source, annotation):
    for match in re.finditer('@'+annotation+r'\b\s*', source):
        has_arguments = source[match.end():].startswith('(')
        start = match.end()+int(has_arguments)
        cursor, depth, quoted, escaped = start, int(has_arguments), False, False
        while cursor < len(source) and depth:
            char = source[cursor]
            if quoted:
                if escaped: escaped = False
                elif char == '\\': escaped = True
                elif char == '"': quoted = False
            elif char == '"': quoted = True
            elif char == '(': depth += 1
            elif char == ')': depth -= 1
            cursor += 1
        end = source.find('{',cursor)
        if depth == 0 and end >= 0:
            yield source[start:cursor-1] if has_arguments else '',source[cursor:end].strip()

issues = []
shadow_methods = []
wrapper_methods = []
overwrite_methods = []
inject_callbacks = []
static_callbacks = []
instruction_bodies = []
configs = []
mixins = []
declarations = collections.defaultdict(list)
inputs = {}
def read(path):
    raw = path.read_bytes()
    inputs[str(path.relative_to(root))] = hashlib.sha256(raw).hexdigest()
    return raw.decode()
def issue(source, kind, detail, optional=None):
    issues.append({'source':str(source.relative_to(root)),'kind':kind,'detail':detail,'optionalMod':optional})
metadata_path = root/'fabric/src/main/resources/fabric.mod.json'
metadata_source = read(metadata_path)
properties = root/'gradle.properties'
if properties.is_file():
    values = dict(re.findall(r'(?m)^([\w.]+)=(.*)$',read(properties)))
    # Source metadata deliberately contains Gradle substitutions. Resolve only
    # known declared values; never invent missing mod IDs or class owners.
    metadata_source = re.sub(r'\$\{([\w.]+)\}', lambda match:values.get(match[1],match[0]), metadata_source)
metadata = json.loads(metadata_source)
java_roots = [root/f'{loader}/src/main/java' for loader in ('common','fabric')]
resource_roots = [root/f'{loader}/src/main/resources' for loader in ('common','fabric')]
for entrypoint in [e for group in metadata.get('entrypoints',{}).values() for e in group]:
    name = entrypoint if isinstance(entrypoint,str) else entrypoint['value']
    owner, _, method = name.partition('::')
    sources = [directory/(owner.replace('.','/')+'.java') for directory in java_roots if (directory/(owner.replace('.','/')+'.java')).is_file()]
    if not sources:
        issue(metadata_path,'missing_entrypoint',name)
    elif method and not re.search(r'public\s+static\s+void\s+'+re.escape(method)+r'\s*\(\s*\)',read(sources[0])):
        issue(metadata_path,'missing_static_entrypoint_method',name)
selected = [name if isinstance(name,str) else name['config'] for name in metadata.get('mixins',[])]
config_names = sorted({p.name for directory in resource_roots for p in directory.glob('*.mixins.json')}) if options.all_configs else selected
for name in config_names:
    name = name if isinstance(name,str) else name['config']
    found = [directory/name for directory in resource_roots if (directory/name).is_file()]
    if len(found)!=1:
        issue(metadata_path,'mixin_config_count',name+': '+str(len(found)))
        continue
    config = found[0]
    data = json.loads(read(config))
    configs.append({'resource':name,'source':str(config.relative_to(root)),'registered':name in selected,'required':data.get('required'),'compatibilityLevel':data.get('compatibilityLevel')})
    plugin = data.get('plugin')
    if plugin and not any((directory/(plugin.replace('.','/')+'.java')).is_file() for directory in java_roots):
        issue(config,'missing_plugin',plugin)
    for side in ('mixins','client','server'):
        for short in data.get(side,[]):
            name = data['package']+'.'+short
            matches = [directory/(name.replace('.','/')+'.java') for directory in java_roots if (directory/(name.replace('.','/')+'.java')).is_file()]
            optional = None
            match = re.search(r'\.(?:compat|compatibility)\.([^.]+)\.',name) or re.search(r'\.client\.(?:perspective|stage|debug)\.(sodium|iris)\.',name)
            if match: optional = match[1]
            if len(matches)!=1:
                issue(config,'mixin_source_count',name+': '+str(len(matches)),optional)
                continue
            path = matches[0]
            declarations[name].append({'configuration':config.name,'registered':config.name in selected,'side':side})
            source = re.sub(r'/\*.*?\*/','',read(path),flags=re.S)
            source = re.sub(r'(?m)^\s*//.*','',source)
            imported = {n.rsplit('.',1)[-1]:n for n in re.findall(r'^import\s+([\w.$]+);',source,re.M)}
            wildcards = re.findall(r'^import\s+([\w.$]+)\.\*;',source,re.M)
            package = re.search(r'^package\s+([\w.]+);',source,re.M)[1]
            annotation = re.search(r'@Mixin\s*\((.*?)\)',source,re.S)
            targets = []
            if annotation:
                for target in re.findall(r'([\w.$]+)\.class',annotation[1]):
                    first,_,suffix = target.partition('.')
                    qualified = imported.get(first,first)+('.'+suffix if suffix else '')
                    owner = resolve(qualified)
                    if owner not in index and owner not in source_index and first not in imported:
                        owner = next((resolve(package+'.'+target) for package in wildcards if resolve(package+'.'+target) in index or resolve(package+'.'+target) in source_index),owner)
                    targets.append(owner)
                targets += [resolve(target) for target in re.findall(r'"([\w.$]+)"',annotation[1]) if '.' in target]
            targets = list(dict.fromkeys(targets))
            details = {'source':str(path.relative_to(root)),'class':name,'side':side,'optionalMod':optional,'targets':[]}
            for owner in targets:
                info = members(owner)
                details['targets'].append({'owner':owner,'artifact':info['source'] if info else None})
                if info is None:
                    if owner in source_index:
                        details['targets'][-1]['pendingSourceOwner'] = str(source_index[owner].relative_to(repo))
                        issue(path,'target_bytecode_pending_for_source_owner',owner,optional)
                    else:issue(path,'missing_target_class',owner,optional)
                    continue
                for declaration in re.findall(r'\bmethod\s*=\s*(\{[^}]*\}|"[^"]+")',source,re.S):
                    for selector in re.findall(r'"([^"]+)"',declaration):
                        if any(c in selector for c in '*?') or selector.startswith('L'):continue
                        method,_,signature = selector.partition('(')
                        if not has(owner,'methods',method,'('+signature if signature else None,inherited=True):
                            issue(path,'missing_injection_selector',owner+';'+selector,optional)
                # Ordinary injectors cannot modify a method body inherited
                # from another class. WrapMethod's inherited wrapping support
                # is checked separately below.
                for injector in ('Inject','Redirect','WrapOperation','ModifyExpressionValue','ModifyReturnValue','ModifyVariable','ModifyArg','ModifyArgs','ModifyConstant'):
                    for annotation,header in annotated_methods(source,injector):
                        selected_bodies = set()
                        for declaration in re.findall(r'\bmethod\s*=\s*(\{[^}]*\}|"[^"]+")',annotation,re.S):
                            for selector in re.findall(r'"([^"]+)"',declaration):
                                if any(c in selector for c in '*?') or selector.startswith('L'):continue
                                method,_,signature = selector.partition('(')
                                descriptor = '('+signature if signature else None
                                if not has(owner,'methods',method,descriptor) and has(owner,'methods',method,descriptor,inherited=True):
                                    issue(path,'inherited_body_unavailable_to_injector',owner+';'+selector+' @'+injector,optional)
                                native_descriptors = method_descriptors(owner,method)
                                if descriptor:native_descriptors &= {descriptor}
                                selected_bodies.update((method,native) for native in native_descriptors if (method,native) in info['methodReferences'])
                                if not re.search(r'\bstatic\b',header):
                                    for native in sorted(native_descriptors):
                                        if native_method_access(owner,method,native) & 0x0008:
                                            static_callbacks.append({'source':str(path.relative_to(root)),'owner':owner,'selector':selector,'injector':injector,'nativeDescriptor':native})
                                            issue(path,'instance_callback_for_static_target',owner+';'+method+native+' @'+injector,optional)
                        selected_references = set().union(*(info['methodReferences'][body] for body in selected_bodies)) if selected_bodies else set()
                        if selected_bodies:
                            for called,member in re.findall(r'\btarget\s*=\s*"L([^;]+);([^" ]+)"',annotation):
                                if '(' in member:
                                    method,signature = member.split('(',1)
                                    signature = '('+signature
                                elif ':' in member:method,signature = member.split(':',1)
                                else:continue
                                instruction_bodies.append({'source':str(path.relative_to(root)),'owner':owner,'injector':injector,'selectedBodies':[list(body) for body in sorted(selected_bodies)],'target':called+';'+member})
                                if (called,method,signature) not in selected_references:
                                    issue(path,'instruction_reference_absent_from_selected_bodies',owner+' '+str(sorted(selected_bodies))+' -> '+called+';'+member,optional)
                for annotation,header in annotated_methods(source,'WrapMethod'):
                    descriptor = shadow_descriptor(header,imported,wildcards,package)
                    operation = 'Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;'
                    if descriptor and descriptor.count(operation) == 1:
                        descriptor = descriptor.replace(operation,'',1)
                        # MixinExtras @Share parameters are injected local
                        # references and are not arguments of the native method.
                        if '@Share' in header:
                            descriptor = re.sub(r'Lcom/llamalad7/mixinextras/sugar/ref/[^;]+;', '', descriptor)
                        for declaration in re.findall(r'\bmethod\s*=\s*(\{[^}]*\}|"[^"]+")',annotation,re.S):
                            for selector in re.findall(r'"([^"]+)"',declaration):
                                method = selector.split('(',1)[0]
                                wrapper_methods.append({'source':str(path.relative_to(root)),'owner':owner,'selector':selector,'wrapperDescriptor':descriptor})
                                if not has(owner,'methods',method,descriptor,inherited=True):issue(path,'missing_wrap_method_descriptor',owner+';'+method+descriptor,optional)
                for annotation,header in annotated_methods(source,'Overwrite'):
                    descriptor = shadow_descriptor(header,imported,wildcards,package)
                    name = re.search(r'([\w$]+)\s*\(',re.sub(r'@[\w.]+(?:\([^)]*\))?\s*','',header))
                    if name:
                        overwrite_methods.append({'source':str(path.relative_to(root)),'owner':owner,'method':name[1],'descriptor':descriptor})
                        if descriptor is None:issue(path,'unresolved_overwrite_method_descriptor',owner+';'+name[1],optional)
                        elif not has(owner,'methods',name[1],descriptor,inherited=True):issue(path,'missing_overwrite_method_descriptor',owner+';'+name[1]+descriptor,optional)
                        elif bool(native_method_access(owner,name[1],descriptor) & 0x0008) != bool(re.search(r'\bstatic\b',header)):
                            issue(path,'overwrite_static_modifier_mismatch',owner+';'+name[1]+descriptor,optional)
                for annotation,header in annotated_methods(source,'Inject'):
                    descriptor = shadow_descriptor(header,imported,wildcards,package)
                    if descriptor is None or '@Coerce' in header: continue
                    marker = re.search(r'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo(Returnable)?;',descriptor)
                    if marker is None: continue
                    prefix = descriptor[1:marker.start()]
                    for declaration in re.findall(r'\bmethod\s*=\s*(\{[^}]*\}|"[^"]+")',annotation,re.S):
                        for selector in re.findall(r'"([^"]+)"',declaration):
                            if any(c in selector for c in '*?') or selector.startswith('L'):continue
                            method,_,signature = selector.partition('(')
                            candidates = method_descriptors(owner,method)
                            if signature:candidates &= {'('+signature}
                            if not candidates:continue
                            matching = [native for native in candidates if (not prefix or prefix == native[1:native.index(')')]) and bool(marker[1]) == (native[native.index(')')+1:] != 'V')]
                            inject_callbacks.append({'source':str(path.relative_to(root)),'owner':owner,'selector':selector,'callbackDescriptor':descriptor,'nativeDescriptors':sorted(candidates)})
                            if not matching:issue(path,'incompatible_inject_callback_descriptor',owner+';'+selector+' callback '+descriptor+' native '+str(sorted(candidates)),optional)
                for declaration in re.findall(r'@Shadow(?:\([^)]*\))?\s*((?:@[\w.]+(?:\([^)]*\))?\s*)*[^;{}]+)[;{]',source):
                    before = declaration.split('=',1)[0]
                    match = re.search(r'(\w+)\s*\(',before) if '(' in before else re.search(r'(\w+)\s*$',before.strip())
                    group = 'methods' if '(' in before else 'fields'
                    if match and not has(owner,group,match[1],inherited=True):issue(path,'missing_shadow_name',owner+';'+match[1],optional)
                    if match and group == 'methods':
                        descriptor = shadow_descriptor(before,imported,wildcards,package)
                        shadow_methods.append({'source':str(path.relative_to(root)),'owner':owner,'method':match[1],'descriptor':descriptor})
                        if descriptor is None:issue(path,'unresolved_shadow_method_descriptor',owner+';'+match[1],optional)
                        elif not has(owner,'methods',match[1],descriptor,inherited=True):issue(path,'missing_shadow_method_descriptor',owner+';'+match[1]+descriptor,optional)
                        elif bool(native_method_access(owner,match[1],descriptor) & 0x0008) != bool(re.search(r'\bstatic\b',before)):
                            issue(path,'shadow_static_modifier_mismatch',owner+';'+match[1]+descriptor,optional)
                for kind,member in re.findall(r'@(Accessor|Invoker)\s*\(\s*"([^"]+)"',source):
                    group = 'fields' if kind=='Accessor' else 'methods'
                    if not has(owner,group,member,inherited=True):issue(path,'missing_accessor_target',owner+';'+member,optional)
                for method in re.findall(r'@Accessor\s+(?:[\w.$<>?,\s]+\s+)(\w+)\s*\(',source):
                    match = re.match(r'(?:get|set|is)([A-Z].*)',method)
                    if match:
                        member = match[1][0].lower()+match[1][1:]
                        if not has(owner,'fields',member,inherited=True):issue(path,'missing_inferred_accessor_target',owner+';'+member,optional)
                for called,member in re.findall(r'\btarget\s*=\s*"L([^;]+);([^" ]+)"',source):
                    if '(' in member:
                        method,signature = member.split('(',1)
                        signature = '('+signature
                        group = 'methods'
                    elif ':' in member:
                        method,signature = member.split(':',1)
                        group = 'fields'
                    else:continue
                    if not has(called,group,method,signature,inherited=True):issue(path,'missing_instruction_target',called+';'+member,optional)
                    if (called,method,signature) not in info['references']:
                        issue(path,'instruction_reference_absent_from_target_class',owner+' -> '+called+';'+member,optional)
            if not targets:issue(path,'unresolved_mixin_annotation',name,optional)
            mixins.append(details)
access = metadata.get('accessWidener')
if access:
    found = [p/access for p in resource_roots if (p/access).is_file()]
    if len(found)!=1:issue(metadata_path,'access_file_count',str(found))
    else:read(found[0])
for directory in resource_roots:
    for service in (directory/'META-INF/services').glob('*'):
        for line in read(service).splitlines():
            provider = line.split('#',1)[0].strip()
            owner = provider.replace('.','/')
            if provider and owner not in index and owner not in source_index and not any((p/(owner+'.java')).is_file() for p in java_roots):issue(service,'missing_service_provider',provider)
duplicates = {name:items for name,items in declarations.items() if len(items)>1}
for name,items in duplicates.items():
    if sum(item['registered'] for item in items)>1:issue(metadata_path,'duplicate_active_mixin',name)
report = {'scope':'Read-only source metadata and actual class/member/constant-reference structural audit, including concrete Shadow/Overwrite/WrapMethod signatures and resolvable Inject argument prefixes/return kinds; not actual Mixin application, injection cardinality, local capture correctness or world startup proof',
          'metadata':str(metadata_path.relative_to(root)),'configs':configs,'mixins':mixins,'issues':issues,
          'counts':{'configs':len(configs),'mixins':len(mixins),'issues':len(issues),'byKind':dict(collections.Counter(i['kind'] for i in issues)),
                    'requiredIssues':sum(i['optionalMod'] is None for i in issues),'optionalIssues':sum(i['optionalMod'] is not None for i in issues)},
          'allOnDiskObligations':options.all_configs,'duplicateDeclarations':duplicates,
          'optionalProvenanceWarning':'Canonical source classpath contains explicitly old compile-only optional artifacts. A found old artifact target does not establish native 26.3 compatibility.',
          'shadowMethods':shadow_methods,'wrapperMethods':wrapper_methods,'overwriteMethods':overwrite_methods,'injectCallbacks':inject_callbacks,'staticCallbacks':static_callbacks,'instructionBodies':instruction_bodies,'javaHome':str(java_home),'jdkModules':jdk_modules,'productionArtifacts':production_artifacts,
          'inputs':inputs,'scriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(work/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report['counts']))
for item in issues:
    print((item['optionalMod'] or 'required')+': '+item['kind']+': '+item['source']+': '+item['detail'])
