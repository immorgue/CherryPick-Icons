"""Validate the concrete private API contracts used by CherryPick. Static only."""
from pathlib import Path
import argparse,json,sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from launcher_contracts import inherited,load_apk

def verify(d):
 errors=[];groups={}
 def check(group,condition,message):
  groups.setdefault(group,True)
  if not condition:groups[group]=False;errors.append(group+': '+message)
 def field(group,owner,name,kind):
  check(group,inherited(d,owner,name,'field')==kind,owner+'.'+name+' '+kind)
 def method(group,owner,name,signature=None):
  ms=inherited(d,owner,name,'method') or []
  check(group,any(m['signature']==signature for m in ms) if signature else bool(ms),owner+'.'+name+' '+str(signature))
 p='Lcom/android/launcher3/';modern=p+'anim/iconsurfacemanager/surface/IconSurfaceRenderer;' in d
 ns=p+'anim/iconsurfacemanager/'+('surface/' if modern else '')
 for n in ['getSurfaceControl','hideSurfaceControl','resetSurfaceControl','releaseSurfaceControl']:method('live_flight',ns+'IconSurfaceProvider;',n)
 for n in ['getRecordId','reuseIconSurface','showOriginalIcon']:method('live_flight',ns+'IconSurfaceRecord;',n)
 method('live_flight',ns+('IconSurfaceRenderer;' if modern else 'IconSurfaceRecord;'),'drawViewToSurface' if modern else 'transferViewToSurface')
 method('folder_paging',p+'folder/FolderIcon;','isScrolling','()Z')
 method('folder_paging',p+'folder/FolderPreviewDrawable;','draw','(Landroid/graphics/Canvas;)V')
 field('folder_paging',p+'folder/FolderPreviewDrawable;','mInvalid','Z')
 for n in ['ShortcutContainer','GridRecyclerView']:check('multi_icons',p+'viewcontainer/'+n+';' in d,n)
 for name,kind in [('mRadius','F'),('mRect','Landroid/graphics/Rect;'),('attachedShortcutContainer',p+'viewcontainer/ShortcutContainer;')]:
  field('multi_icons',p+'viewcontainer/GridRecyclerView;',name,kind)
 method('dock',p+'OplusHotseat;','onDraw','(Landroid/graphics/Canvas;)V')
 field('dock',p+'CellLayout;','mShortcutsAndWidgets','Lcom/android/launcher3/ShortcutAndWidgetContainer;')
 for n in ['setDockerBackground','setBackgroundAlphaInner']:method('dock',p+'OplusHotseat;',n)
 # The snapshot lambda's numeric suffix differs between OEM builds.
 bitmap='Lcom/oplus/basecommon/util/BitmapUtils;'
 check('snapshots',any(m['name'].startswith('convertViewToBitmapInMainExecutor$lambda$') and m['signature']=='(Ljava/lang/Runnable;IILjava/util/concurrent/CompletableFuture;Landroid/view/View;)V' and m['access']&8 for m in d.get(bitmap,{}).get('methods',[])),'snapshot recorder static signature')
 legacy='Lcom/android/launcher/layoutparam/ConfigParam;' in d
 compact='Lcom/android/common/util/LauncherLayoutUtils;' not in d
 layout='Lcom/android/common/util/'+('c' if compact else 'LauncherLayoutUtils')+';'
 helper='Ltv5;' if compact else 'Lcom/android/launcher/togglebar/LayoutSettingsHelper;'
 args='Landroid/content/Context;IIIIZZZZ'+('Ljava/util/List;' if legacy else 'Ljava/util/ArrayList;')+('ZLjava/util/HashMap;Ljava/util/ArrayList;' if compact else '')
 method('desktop_editor',layout,'arrangeDesktopLayouts' if legacy else 'c','('+args+')Ljava/util/List;')
 method('desktop_editor',layout,'clearEmptyGroupItem' if legacy else 'p','(Landroid/content/ContentResolver;II)[I' if legacy else '(IILandroid/content/ContentResolver;)[I')
 method('desktop_editor',layout,'switchCellsLayout' if legacy else ('F' if compact else 'B'),'(Landroid/content/Context;[I[IZZ[Z)Z')
 method('desktop_editor',helper,'b' if compact else 'applyLayoutSettings','(Lcom/android/launcher/Launcher;IIZ)V')
 job='Lsv5;' if compact else 'Lcom/android/launcher/togglebar/LayoutSettingsHelper$'+('SwitchLayoutTask' if legacy else 'f')+';'
 method('desktop_editor',job,'onPostExecute','(Ljava/lang/Object;)V')
 check('desktop_editor',sum(t=='Ljava/lang/ref/WeakReference;' for t in d.get(job,{}).get('fields',{}).values())==1,'unique completion owner')
 if compact:
  field('desktop_editor',helper,'i','Z');field('desktop_editor','Lz5c;','a','Landroid/net/Uri;')
 else:
  method('desktop_editor',helper,'isInApplySwitchLayout','()Z');method('desktop_editor',helper,'setAddNewScreenQuantityWhenSwitchLayout','(I)V')
 page='Lcom/android/launcher/bean/WorkSpaceScreenData;' if legacy else ('Lf4c;' if compact else 'Lj3/d;')
 for a,b,k in [('mHotSeat','c','Z'),('mScreenRank','b','I'),('workspaceItems','d','Ljava/util/ArrayList;'),('appWidgets','e','Ljava/util/ArrayList;')]:field('desktop_editor',page,a if legacy else b,k)
 profile=p+'DeviceProfile;'
 for n in ['mConfig','mWorkspaceParam','mHotseatParam','mPageIndicatorParam']:check('desktop_geometry',bool(inherited(d,profile,n,'field')),n)
 config=inherited(d,profile,'mConfig','field');workspace=inherited(d,profile,'mWorkspaceParam','field');hotseat=inherited(d,profile,'mHotseatParam','field');indicator=inherited(d,profile,'mPageIndicatorParam','field')
 for a,b,k in [('widthPx','s','I'),('heightPx','t','I'),('insets','k','Landroid/graphics/Rect;')]:field('desktop_geometry',config,a if legacy else b,k)
 view=inherited(d,workspace,'mViewParam' if legacy else 'b','field')
 field('desktop_geometry',view,'mPadding' if legacy else 'a','Landroid/graphics/Rect;')
 for a,b in [('mWorkspaceHeightPx','c'),('mWorkspacePaddingBottomAdjustment','d')]:field('desktop_geometry',workspace,a if legacy else b,'I')
 field('desktop_geometry',hotseat,'hotseatBarSizePx' if legacy else 'n','I');field('desktop_geometry',indicator,'workspacePageIndicatorHeight' if legacy else 'd','I')
 method('desktop_geometry',hotseat,'getMarginBottom' if legacy else ('b' if compact else 'i'),'(Z)I' if compact else '()I')
 method('desktop_geometry',profile,'updateInsets','(Landroid/graphics/Rect;)V')
 icon='Lto5;' if compact else 'Lcom/android/launcher/theme/LauncherIconConfig;'
 method('icon_size',icon,'b' if compact else 'calculateIconSizeByUxDesign','([I)I')
 field('icon_theme',icon,'D' if compact else 'sInstance',icon)
 field('icon_theme',icon,'q' if compact else 'mIconConfig','Lcom/oplus/uxicon/helper/IconConfig;')
 if not legacy:method('icon_theme',icon,'r' if compact else 'isDarkIconEnable','(Landroid/content/res/Configuration;)Z')
 for n in (['x','z','B'] if compact else ['update','updateIconConfigIfNecessary','updateSettingConfig']):method('icon_theme',icon,n)
 return {'families':groups,'errors':errors,'desktop_api':'16 named' if legacy else ('17.5 compact' if compact else '17.3'),'surface':'renderer' if modern else 'legacy'}

def main():
 p=argparse.ArgumentParser();p.add_argument('cache',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();results=[]
 for r in json.loads((a.cache/'summary.json').read_text(encoding='utf-8')):
  result=verify(json.loads((a.cache/(r['sha256']+'.json')).read_text()))
  results.append({'file':r['file'],'sha256':r['sha256'],**result});print(r['file'],result,flush=True)
 a.output.write_text(json.dumps(results,indent=2,ensure_ascii=False),encoding='utf-8')
 raise SystemExit(any(r['errors'] for r in results))
if __name__=='__main__':main()
