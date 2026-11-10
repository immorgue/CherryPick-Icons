"""Negative checks for the added contracts, using the locally audited DEX cache."""
from pathlib import Path
import argparse,json,sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_launcher_contracts import verify

def changed(classes,owner,kind,name):
    result=dict(classes)
    record=dict(classes[owner])
    if kind=='fields':
        record[kind]={k:v for k,v in record[kind].items() if k!=name}
    else:
        record[kind]=[m for m in record[kind] if m['name']!=name]
    result[owner]=record
    # A virtual method may also be implemented by an OEM base class. Removing
    # only the override leaves a valid contract, so remove its inherited copies.
    parent=record.get('parent')
    if kind=='methods' and parent in classes:
        inherited_change=changed(classes,parent,kind,name)
        for ancestor,value in inherited_change.items():
            if value is not classes[ancestor]:result[ancestor]=value
    return result

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('cache',type=Path)
    args=parser.parse_args()
    hashes=set();checks=0
    for item in json.loads((args.cache/'summary.json').read_text(encoding='utf-8')):
        if item['sha256'] in hashes:continue
        hashes.add(item['sha256'])
        classes=json.loads((args.cache/(item['sha256']+'.json')).read_text(encoding='utf-8'))
        assert not verify(classes)['errors'],item['file']
        p='Lcom/android/launcher3/'
        ns=p+'anim/iconsurfacemanager/'+('surface/' if p+'anim/iconsurfacemanager/surface/IconSurfaceRenderer;' in classes else '')
        cases=[
            ('folder_geometry',p+'folder/big/SmoothRoundDrawable;','fields','mPath'),
            ('folder_geometry',p+'folder/OplusPreviewBackground;','methods','getNormalDrawable'),
            ('folder_backdrops',p+'folder/OplusFolder;','fields','mContentBackground'),
            ('folder_backdrops',p+'folder/OplusFolder;','fields','mContentAnimationBackground'),
            ('folder_backdrops',p+'folder/FolderRoundImageView;','methods','onDraw'),
            ('dock_visibility',p+'OplusHotseat;','methods','onLayout'),
            ('desktop_offsets',p+'OplusDragLayer;','methods','onLayout'),
            ('desktop_cells',p+'CellLayout;','methods','setCellDimensionsInner'),
            ('desktop_cells',p+'CellLayout;','fields','mContainerType'),
            ('dock_icon_size',p+'BubbleTextView;','methods','applyCompoundDrawables'),
            ('surface_handoff',ns+'IconSurfaceRecord;','methods','showOriginalIcon'),
        ]
        if p+'drawable/GlassDrawable;' in classes:
            cases.append(('folder_geometry',p+'drawable/GlassDrawable;','fields','backgroundDrawable'))
        smooth=p+'util/SmoothRoundedManager;'
        if any(m['name']=='createIconFolderClipPath' for m in classes.get(smooth,{}).get('methods',[])):
            cases.append(('folder_geometry',p+'uioverrides/states/blurdrawable/GradientDrawableWrapper;','fields','mStrokePath'))
        grid=p+'viewcontainer/GridRecyclerView;'
        clip='clipRoundBackground' if any(m['name']=='clipRoundBackground' for m in classes[grid]['methods'])else 'createClipSmoothRoundBitmap'
        cases.append(('native_grid_mask',grid,'methods',clip))
        for group,owner,kind,name in cases:
            result=verify(changed(classes,owner,kind,name))
            assert result['families'][group] is False,(item['file'],owner,name)
            checks+=1
    print(f'PASS: {checks} missing-contract cases detected across {len(hashes)} unique launcher APKs')

if __name__=='__main__':main()
