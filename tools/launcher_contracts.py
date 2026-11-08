"""Read declared DEX contracts locally; never install or execute launcher APKs."""
from pathlib import Path
import argparse, hashlib, json, struct, zipfile

def uleb(data, offset):
    value=shift=0
    while True:
        byte=data[offset];offset+=1;value|=(byte&127)<<shift
        if byte<128:return value,offset
        shift+=7

def dex_classes(data):
    def table(offset):return struct.unpack_from('<II',data,offset)
    def uint(offset):return struct.unpack_from('<I',data,offset)[0]
    count,offset=table(56);strings=[]
    for i in range(count):
        _,start=uleb(data,uint(offset+4*i));strings.append(data[start:data.index(b'\0',start)].decode('utf-8','replace'))
    count,offset=table(64);types=[strings[uint(offset+4*i)] for i in range(count)]
    count,offset=table(72);protos=[]
    for i in range(count):
        _,result,params=struct.unpack_from('<III',data,offset+i*12)
        args=[] if not params else [types[struct.unpack_from('<H',data,params+4+j*2)[0]] for j in range(uint(params))]
        protos.append('('+''.join(args)+')'+types[result])
    count,offset=table(80);fields=[]
    for i in range(count):
        owner,kind,name=struct.unpack_from('<HHI',data,offset+i*8);fields.append((types[owner],strings[name],types[kind]))
    count,offset=table(88);methods=[]
    for i in range(count):
        owner,proto,name=struct.unpack_from('<HHI',data,offset+i*8);methods.append((types[owner],strings[name],protos[proto]))
    count,offset=table(96)
    for i in range(count):
        owner,flags,parent,_,_,_,encoded,_=struct.unpack_from('<8I',data,offset+i*32)
        name=types[owner];record={'parent':types[parent] if parent!=0xffffffff else None,'fields':{},'methods':[]}
        if encoded:
            sizes=[]
            for _ in range(4):value,encoded=uleb(data,encoded);sizes.append(value)
            for size in sizes[:2]:
                index=0
                for _ in range(size):
                    delta,encoded=uleb(data,encoded);access,encoded=uleb(data,encoded);index+=delta
                    _,field,kind=fields[index];record['fields'][field]=kind
            for size in sizes[2:]:
                index=0
                for _ in range(size):
                    delta,encoded=uleb(data,encoded);access,encoded=uleb(data,encoded);code,encoded=uleb(data,encoded);index+=delta
                    _,method,signature=methods[index];record['methods'].append({'name':method,'signature':signature,'access':access})
        yield name,record

def load_apk(path):
    classes={}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith('.dex'):classes.update(dex_classes(archive.read(name)))
    return classes

def inherited(classes,name,member,kind):
    seen=set()
    while name in classes and name not in seen:
        seen.add(name);record=classes[name]
        if kind=='field' and member in record['fields']:return record['fields'][member]
        if kind=='method':
            found=[m for m in record['methods'] if m['name']==member]
            if found:return found
        name=record['parent']
    return None

def summarize(classes):
    prefix='Lcom/android/launcher3/'
    keys={
        'snapshot':('Lcom/oplus/basecommon/util/BitmapUtils;','convertViewToBitmapInMainExecutor'),
        'hotseat':(prefix+'OplusHotseat;','onDraw'),
        'folder_paging':(prefix+'folder/FolderIcon;','isScrolling'),
        'folder_page':(prefix+'folder/FolderPreviewDrawable;','draw'),
        'grid':(prefix+'viewcontainer/GridRecyclerView;','draw'),
        'sizing_profile':(prefix+'DeviceProfile;','config'),
        'sizing_icons':('Lcom/android/launcher/theme/LauncherIconConfig;','calculateIconSizeByUxDesign'),
        'editor_apply':('Lcom/android/launcher/togglebar/LayoutSettingsHelper;','applyLayoutSettings'),
    }
    result={key:inherited(classes,owner,method,'method') for key,(owner,method) in keys.items()}
    result['hotseat_fields']=classes.get(prefix+'OplusHotseat;',{}).get('fields',{})
    result['snapshot_recorders']=[m for m in classes.get(keys['snapshot'][0],{}).get('methods',[]) if 'convertViewToBitmapInMainExecutor$lambda$' in m['name']]
    result['editor_helpers']={name:record for name,record in classes.items() if name.startswith('Lcom/android/launcher/togglebar/LayoutSettingsHelper$')}
    result['layout_methods']=classes.get('Lcom/android/common/util/LauncherLayoutUtils;',{}).get('methods',[])
    result['surface_namespace']=[name for name in classes if name.endswith('/IconSurfaceRenderer;') or name.endswith('/IconSurfaceRecord;')]
    result['wallpaper_factories']=[name for name,record in classes.items() if any('Bitmap' in m['signature'] and ('Listener' in m['name'] or m['name']=='capture') for m in record['methods']) and ('wallpaper' in name.lower() or 'blur' in name.lower())]
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True);results=[]
    for apk in sorted(args.directory.glob('*.apk')):
        digest=hashlib.sha256(apk.read_bytes()).hexdigest();cache=args.output/(digest+'.json')
        if cache.exists():classes=json.loads(cache.read_text())
        else:
            classes=load_apk(apk);cache.write_text(json.dumps(classes,separators=(',',':')),encoding='utf-8')
        result={'file':apk.name,'sha256':digest,'classes':len(classes),'contracts':summarize(classes)}
        results.append(result);print(apk.name,len(classes),digest[:12],flush=True)
    (args.output/'summary.json').write_text(json.dumps(results,indent=2,ensure_ascii=False),encoding='utf-8')

if __name__=='__main__':main()
