import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.content.Context;
import java.lang.reflect.*;
public class ParentBackdropCheck {
 public static final class NativeCornerProbe extends Drawable {
  public final Path mStrokePath=new Path();public float requestedRadius;
  public void setCornerRadius(float value){requestedRadius=value;rebuild();}
  private void rebuild(){mStrokePath.reset();mStrokePath.addRoundRect(new RectF(0,0,getBounds().width(),getBounds().height()),12,12,Path.Direction.CW);}
  protected void onBoundsChange(Rect bounds){rebuild();}
  public void draw(Canvas canvas){}
  public void setAlpha(int value){}
  public void setColorFilter(ColorFilter value){}
  public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static Class<?> cls(String name)throws Exception{return Class.forName("com.cosmod.launcherglass."+name);}
 static Object obj(Class<?> c)throws Exception{return c.getField("INSTANCE").get(null);}
 static void field(Class<?> c,String name,Object value)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 static Bitmap render(Shader shader)throws Exception{
  RenderNode n=new RenderNode("ParentBackdropCheck");n.setPosition(0,0,200,200);Canvas c=n.beginRecording(200,200);Paint p=new Paint();p.setShader(shader);c.drawRect(0,0,200,200,p);n.endRecording();
  Method m=HardwareRenderer.class.getDeclaredMethod("createHardwareBitmap",RenderNode.class,int.class,int.class);m.setAccessible(true);Bitmap hw=(Bitmap)m.invoke(null,n,200,200);Bitmap out=hw.copy(Bitmap.Config.ARGB_8888,false);hw.recycle();n.discardDisplayList();return out;
 }
 public static void main(String[] args)throws Exception {
  Looper.prepareMainLooper();Object at=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);Context context=(Context)at.getClass().getMethod("getSystemContext").invoke(at);
  Thread.setDefaultUncaughtExceptionHandler((t,e)->{e.printStackTrace();System.exit(1);});
  Class<?> shared=Class.forName("com.android.internal.os.ApplicationSharedMemory"); shared.getMethod("setInstance",shared).invoke(null,shared.getMethod("create").invoke(null)); Class<?> prefs=cls("xposed.PrefsHelper");field(prefs,"enabled",true);field(prefs,"blurEnabled",true);field(prefs,"gradientOpacity",0);field(prefs,"blurRadius",0);field(prefs,"gpuEffects",true);
  int sw=context.getResources().getDisplayMetrics().widthPixels,sh=context.getResources().getDisplayMetrics().heightPixels;
  Bitmap wallpaper=Bitmap.createBitmap(sw,sh,Bitmap.Config.ARGB_8888);wallpaper.eraseColor(Color.BLUE);
  Class<?> helper=cls("util.WallpaperHelper");field(helper,"blurredWallpaper",wallpaper);field(helper,"cachedBlurRadius",0);field(helper,"sourceRevision",0L);field(helper,"cachedRevision",0L);
  FrameLayout owner=(FrameLayout)Class.forName("com.android.launcher3.viewcontainer.GridRecyclerView").getConstructor(Context.class).newInstance(context);View child=new View(context);owner.addView(child,new FrameLayout.LayoutParams(200,200));owner.layout(0,0,200,200);child.layout(0,0,200,200);
  Bitmap mask=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);Canvas maskCanvas=new Canvas(mask);Paint white=new Paint(Paint.ANTI_ALIAS_FLAG);white.setColor(Color.WHITE);maskCanvas.drawRoundRect(new RectF(0,0,200,200),35,35,white);
  Class<?> optics=cls("xposed.KyantOptics");Class<?> style=cls("xposed.GlassStyle");Constructor<?> constructor=style.getConstructor(boolean.class,int.class,int.class,int.class,int.class,ColorMatrixColorFilter.class,boolean.class,Integer.class,boolean.class,boolean.class,boolean.class,optics);
  Object kyant=optics.getConstructor(int.class,int.class,int.class).newInstance(40,60,0);
  Object kyantStyle=constructor.newInstance(true,30,80,0,0,null,false,null,false,false,false,kyant);
  Class<?> materialType=cls("xposed.IconMaterialRenderer$Material");
  Constructor<?> materialConstructor=materialType.getDeclaredConstructor(Bitmap.class,Bitmap.class,BitmapShader.class,float.class,Bitmap.class);
  materialConstructor.setAccessible(true);
  Object material=materialConstructor.newInstance(mask,null,null,.175f,mask);
  Class<?> samplerType=cls("xposed.IconMaterialRenderer$Sampler");
  Constructor<?> samplerConstructor=samplerType.getDeclaredConstructor(RenderNode.class,materialType,RectF.class,int.class,int.class,style);
  samplerConstructor.setAccessible(true);
  RectF current=new RectF(15,0,215,200);
  Object sampler=samplerConstructor.newInstance(new RenderNode("SwipeLeadCheck"),material,current,sw,sh,kyantStyle);
  Method previous=samplerType.getDeclaredMethod("setPreviousSource",RectF.class);
  previous.setAccessible(true);previous.invoke(sampler,new RectF(10,0,210,200));
  Method predict=samplerType.getDeclaredMethod("predict",RectF.class,boolean.class,long.class,boolean.class);
  predict.setAccessible(true);
  check(((RectF)predict.invoke(sampler,current,true,1_000_000_000L,true)).left==20f,"Classic swipe lead did not move the sample");
  field(prefs,"swipeLead",false);
  for(boolean smooth:new boolean[]{false,true}){
   field(prefs,"smoothSwipeLead",smooth);
   check(predict.invoke(sampler,current,true,1_016_000_000L,true)==current,"Disabled swipe lead still adjusted the crop");
  }
  field(prefs,"swipeLead",true);field(prefs,"smoothSwipeLead",false);
  System.out.println("PASS: disabling swipe anticipation bypasses both predictors");
  Bitmap gradient=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
  for(int x=0;x<200;x++)for(int y=0;y<200;y++)gradient.setPixel(x,y,Color.rgb(x,0,0));
  Shader maskShader=new BitmapShader(mask,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
  Class<?> lensType=cls("xposed.LiquidGlassShader");
  Object lensCompanion=lensType.getField("Companion").get(null);
  Object kyantLens=lensCompanion.getClass().getMethod("create").invoke(lensCompanion);
  check(kyantLens!=null,"Kyant AGSL did not compile");
  RectF crop=new RectF(0,0,200,200);
  Shader kyantGpu=(Shader)lensType.getMethod("update",Bitmap.class,Shader.class,RectF.class,int.class,int.class,ColorMatrixColorFilter.class,float.class,style,Shader.class)
      .invoke(kyantLens,gradient,maskShader,crop,200,200,null,.175f,kyantStyle,null);
  Bitmap gpuPixels=render(kyantGpu);
  Class<?> cpuType=cls("xposed.CpuLiquidGlass");
  Shader kyantCpu=(Shader)cpuType.getMethod("shader",Bitmap.class,Bitmap.class,int.class,int.class,RectF.class,float.class,style)
      .invoke(obj(cpuType),gradient,mask,200,200,crop,.175f,kyantStyle);
  Bitmap cpuPixels=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
  Paint cpuPaint=new Paint();cpuPaint.setShader(kyantCpu);new Canvas(cpuPixels).drawRect(0,0,200,200,cpuPaint);
  for(Bitmap pixels:new Bitmap[]{gpuPixels,cpuPixels}){
   check(Math.abs(Color.red(pixels.getPixel(100,100))-100)<=1,"Kyant shifted the center");
   check(Color.red(pixels.getPixel(2,100))>10,"Kyant did not refract near the edge");
   check(Color.alpha(pixels.getPixel(0,0))==0,"Kyant changed the native silhouette");
  }
  check(Math.abs(Color.red(gpuPixels.getPixel(2,100))-Color.red(cpuPixels.getPixel(2,100)))<=2,"Kyant software and GPU optics diverge");
  Object litOptics=optics.getConstructor(int.class,int.class,int.class).newInstance(40,60,100);
  Object litStyle=constructor.newInstance(true,30,80,0,0,null,false,null,false,false,false,litOptics);
  Object lifted=lensCompanion.getClass().getMethod("lifted").invoke(lensCompanion);
  check(lifted!=null,"Lifted Kyant AGSL did not compile");
  RuntimeShader liftShader=(RuntimeShader)lensType.getMethod("updateLifted",Shader.class,int.class,int.class,float.class,int.class,int.class,
      ColorMatrixColorFilter.class,float.class,style).invoke(lifted,maskShader,200,200,0f,200,200,null,.175f,litStyle);
  liftShader.setInputShader("backdrop",new BitmapShader(gradient,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
  Bitmap liftPixels=render(liftShader);
  check(Color.blue(liftPixels.getPixel(2,100))>8 && Color.blue(liftPixels.getPixel(100,100))==0,
      "Kyant highlight should affect only the edge");
  check(Color.alpha(liftPixels.getPixel(0,0))==0,"Lifted Kyant changed the native silhouette");
  Object magnifier=optics.getConstructor(int.class,int.class,int.class,boolean.class).newInstance(40,60,30,true);
  Object magnifierStyle=constructor.newInstance(true,30,80,0,0,null,false,null,false,false,false,magnifier);
  Shader magnifierGpu=(Shader)lensType.getMethod("update",Bitmap.class,Shader.class,RectF.class,int.class,int.class,ColorMatrixColorFilter.class,float.class,style,Shader.class)
      .invoke(kyantLens,gradient,maskShader,crop,200,200,null,.175f,magnifierStyle,null);
  Bitmap magnifierPixels=render(magnifierGpu);
  Shader magnifierCpu=(Shader)cpuType.getMethod("shader",Bitmap.class,Bitmap.class,int.class,int.class,RectF.class,float.class,style)
      .invoke(obj(cpuType),gradient,mask,200,200,crop,.175f,magnifierStyle);
  cpuPaint.setShader(magnifierCpu);cpuPixels.eraseColor(Color.TRANSPARENT);
  new Canvas(cpuPixels).drawRect(0,0,200,200,cpuPaint);
  check(Color.red(magnifierPixels.getPixel(50,100))>60,"Magnifier did not zoom the backdrop");
  check(Color.alpha(magnifierPixels.getPixel(0,0))==0,"Magnifier changed the native silhouette");
  for(int x:new int[]{2,50,100,196})
   check(Math.abs(Color.red(magnifierPixels.getPixel(x,100))-Color.red(cpuPixels.getPixel(x,100)))<=2,"Magnifier GPU/CPU divergence at "+x);
  magnifierPixels.recycle();
  Object controlCenter=optics.getConstructor(int.class,int.class,int.class,boolean.class,boolean.class)
      .newInstance(40,60,30,false,true);
  Object centerStyle=constructor.newInstance(true,30,80,0,0,null,false,null,false,false,false,controlCenter);
  Shader centerGpu=(Shader)lensType.getMethod("update",Bitmap.class,Shader.class,RectF.class,int.class,int.class,ColorMatrixColorFilter.class,float.class,style,Shader.class)
      .invoke(kyantLens,gradient,maskShader,crop,200,200,null,.175f,centerStyle,null);
  Bitmap centerPixels=render(centerGpu);
  Shader centerCpu=(Shader)cpuType.getMethod("shader",Bitmap.class,Bitmap.class,int.class,int.class,RectF.class,float.class,style)
      .invoke(obj(cpuType),gradient,mask,200,200,crop,.175f,centerStyle);
  cpuPaint.setShader(centerCpu);cpuPixels.eraseColor(Color.TRANSPARENT);
  new Canvas(cpuPixels).drawRect(0,0,200,200,cpuPaint);
  check(Math.abs(Color.red(centerPixels.getPixel(50,100))-70)<=2,"Control Center changed central geometry or lost vibrancy");
  check(Color.alpha(centerPixels.getPixel(0,0))==0,"Control Center changed the native silhouette");
  for(int x:new int[]{2,25,50,100,196})
   check(Math.abs(Color.red(centerPixels.getPixel(x,100))-Color.red(cpuPixels.getPixel(x,100)))<=2,"Control Center GPU/CPU divergence at "+x);
  centerPixels.recycle();
  System.out.println("PASS: Control Center depth, saturation and native mask match on GPU and software");
  gpuPixels.recycle();cpuPixels.recycle();liftPixels.recycle();gradient.recycle();
  System.out.println("PASS: Kyant GPU, software and lifted shaders preserve the center and silhouette, with edge refraction and highlight");
  Class<?> parent=cls("xposed.ParentBackdrop");Object singleton=obj(parent);Method record=parent.getMethod("record",View.class,Drawable.class,RectF.class,Bitmap.class,float.class,style,boolean.class);Method resolve=parent.getMethod("resolve",View.class,Drawable.class,Bitmap.class);
  Drawable plate=new ColorDrawable(Color.RED),icon=new ColorDrawable(Color.WHITE);
  for(boolean liquid:new boolean[]{false,true}) {
   Object appearance=constructor.newInstance(liquid,30,80,0,100,null,false,Integer.valueOf(Color.RED),true,false,false,null);
   record.invoke(singleton,owner,plate,new RectF(0,0,200,200),mask,35f,appearance,false);
   parent.getMethod("beginFrame").invoke(singleton);
   Object sample=resolve.invoke(singleton,child,icon,wallpaper);check(sample!=null,"Parent plate not found");Shader shader=(Shader)sample.getClass().getMethod("getShader").invoke(sample);
   Bitmap pixels=render(shader);int center=pixels.getPixel(100,100),corner=pixels.getPixel(0,0);
   check(Color.red(center)>245&&Color.blue(center)<10,"Child does not see red parent: "+Integer.toHexString(center));check(Color.blue(corner)>245&&Color.red(corner)<10,"Parent mask leaks outside rounded contour");pixels.recycle();
      if(liquid){
    Class<?> liquidType=cls("xposed.LiquidGlassShader");Object companion=liquidType.getField("Companion").get(null);Object childLens=companion.getClass().getMethod("create").invoke(companion);
    Shader solid=new LinearGradient(0,0,1,0,Color.WHITE,Color.WHITE,Shader.TileMode.CLAMP);
    Shader childShader=(Shader)liquidType.getMethod("update",Bitmap.class,Shader.class,RectF.class,int.class,int.class,ColorMatrixColorFilter.class,float.class,style,Shader.class).invoke(childLens,wallpaper,solid,new RectF(20,20,180,180),200,200,null,.15f,appearance,shader);
    Bitmap childPixels=render(childShader);check(Color.red(childPixels.getPixel(100,100))>245,"Child liquid lens bypassed parent material");childPixels.recycle();
   }check(resolve.invoke(singleton,owner,plate,wallpaper)==null,"Plate samples itself");
   owner.setTranslationX(80);parent.getMethod("beginFrame").invoke(singleton);Object moved=resolve.invoke(singleton,child,icon,wallpaper);pixels=render((Shader)moved.getClass().getMethod("getShader").invoke(moved));check(Color.blue(pixels.getPixel(40,100))>245,"Parent shader lags behind translated owner");check(Color.red(pixels.getPixel(150,100))>245,"Moved parent missing");pixels.recycle();owner.setTranslationX(0);
  }
    ColorMatrixColorFilter filter=new ColorMatrixColorFilter(new float[]{0,0,0,0,0, 0,0,1,0,0, 0,0,0,0,0, 0,0,0,1,0});
  Object colored=constructor.newInstance(false,30,80,0,0,filter,false,Integer.valueOf(Color.RED),true,false,false,null);
  record.invoke(singleton,owner,plate,new RectF(0,0,200,200),mask,35f,colored,false);parent.getMethod("beginFrame").invoke(singleton);
  Object coloredSample=resolve.invoke(singleton,child,icon,wallpaper);Bitmap filtered=render((Shader)coloredSample.getClass().getMethod("getShader").invoke(coloredSample));check(Color.green(filtered.getPixel(100,100))>245,"Parent color filter was not applied");filtered.recycle();
  for(String name:new String[]{"com.android.launcher3.folder.FolderIcon","com.android.launcher3.folder.OplusFolder"}) {
   FrameLayout folder=(FrameLayout)Class.forName(name).getConstructor(Context.class).newInstance(context);View background=new View(context),content=new View(context);folder.addView(background,new FrameLayout.LayoutParams(200,200));folder.addView(content,new FrameLayout.LayoutParams(200,200));folder.layout(0,0,200,200);background.layout(0,0,200,200);content.layout(0,0,200,200);
   Drawable folderIdentity=new ColorDrawable(Color.RED);Object appearance=constructor.newInstance(false,30,80,0,100,null,true,Integer.valueOf(Color.RED),true,false,false,null);
   record.invoke(singleton,background,folderIdentity,new RectF(0,0,200,200),mask,35f,appearance,false);parent.getMethod("beginFrame").invoke(singleton);
   Object folderSample=resolve.invoke(singleton,content,icon,wallpaper);check(folderSample!=null,"Sibling folder background unavailable: "+name);Bitmap folderPixels=render((Shader)folderSample.getClass().getMethod("getShader").invoke(folderSample));check(Color.red(folderPixels.getPixel(100,100))>245,"Folder child ignored sibling plate");folderPixels.recycle();
   check(resolve.invoke(singleton,folder,icon,wallpaper)!=null,"Inline miniature cannot see FolderIcon background");
  }
  Class<?> contours=cls("xposed.NativeFolderPath");Object contourObject=obj(contours);
  Method fill=contours.getMethod("fill",Drawable.class,Rect.class);
  check(fill.invoke(contourObject,new ColorDrawable(Color.RED),new Rect(0,0,200,200))==null,"Rectangular paint must not override the native rounded clip");
  GradientDrawable rounded=new GradientDrawable();rounded.setColor(Color.RED);rounded.setCornerRadius(24);rounded.setBounds(7,9,50,60);
  Path filled=(Path)fill.invoke(contourObject,rounded,new Rect(10,20,210,220));check(filled!=null&&!filled.isEmpty(),"Rounded native outline missing");
  check(rounded.getBounds().equals(new Rect(7,9,50,60)),"Native drawable bounds were mutated");
  Method drawableMask=contours.getMethod("drawableMask",Drawable.class,Rect.class);
  NativeCornerProbe policy=new NativeCornerProbe();policy.setBounds(7,9,50,60);
  Path policyPath=(Path)contours.getMethod("policyPath",Drawable.class,Rect.class,float.class).invoke(contourObject,policy,new Rect(10,20,210,220),77f);
  Region policyRegion=new Region();policyRegion.setPath(policyPath,new Region(0,0,300,300));
  check(policyRegion.contains(18,28)&&policyRegion.contains(190,200),"Native compensated contour was replaced by raw-radius geometry");
  check(policy.requestedRadius==77f && policy.getBounds().equals(new Rect(7,9,50,60)),"Native policy inputs/bounds changed");
  check(drawableMask.invoke(contourObject,new ColorDrawable(Color.RED),new Rect(0,0,200,200))==null,"Plain paint was mistaken for rounded theme coverage");
  Bitmap themed=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);themed.setDensity(Bitmap.DENSITY_NONE);
  Paint themePaint=new Paint(Paint.ANTI_ALIAS_FLAG);themePaint.setColor(Color.WHITE);
  new Canvas(themed).drawRoundRect(new RectF(0,0,200,200),24f,24f,themePaint);
  BitmapDrawable theme=new BitmapDrawable(context.getResources(),themed);theme.setBounds(7,9,50,60);
  Bitmap themeMask=(Bitmap)drawableMask.invoke(contourObject,theme,new Rect(0,0,200,200));
  check(themeMask!=null,"Rounded theme bitmap lost its alpha contour");
  for(int y=0;y<200;y++)for(int x=0;x<200;x++)check(Color.alpha(themed.getPixel(x,y))==Color.alpha(themeMask.getPixel(x+1,y+1)),"Theme alpha contour changed at "+x+","+y);
  check(theme.getBounds().equals(new Rect(7,9,50,60)),"Theme bitmap drawable bounds mutated");
  themed.recycle();themeMask.recycle();
  System.out.println("PASS: bitmap theme contour copied pixel-exactly; rectangular paint rejected; drawable bounds restored");
  // A surface without its first wallpaper must still receive the ready repaint.
  field(helper,"blurredWallpaper",null);field(helper,"isReloading",true);field(helper,"appContext",context);
  View waiting=new View(context);waiting.layout(0,0,200,200);
  Bitmap pending=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
  Object waitingStyle=constructor.newInstance(false,30,80,7,100,null,false,Integer.valueOf(Color.RED),true,false,false,null);
  Class<?> surface=cls("xposed.SurfaceMaterial");Method draw=null;
  for(Method method:surface.getMethods())if(method.getName().equals("draw")){draw=method;break;}
  check(draw!=null,"Surface draw unavailable");
  Object drawn=draw.invoke(obj(surface),new Canvas(pending),new Rect(0,0,200,200),waiting,new ColorDrawable(Color.RED),35f,null,false,255,waitingStyle,null,null,null,null,null,true);
  check(Boolean.FALSE.equals(drawn),"Unavailable wallpaper unexpectedly drew a material");
  Field registry=cls("xposed.IconGlassHook").getDeclaredField("liveViews");registry.setAccessible(true);
  check(((java.util.Map<?,?>)registry.get(null)).containsKey(waiting),"Waiting surface missed registration for wallpaper-ready repaint");
  field(helper,"blurredWallpaper",wallpaper);field(helper,"isReloading",false);pending.recycle();
  System.out.println("PASS: missing-wallpaper surface remains registered for the ready repaint");
  field(prefs,"blurStyle",2);field(helper,"blurredWallpaper",wallpaper);
  check(!(Boolean)prefs.getMethod("getIconBackdropEnabled").invoke(obj(prefs)),"Color-only icons enabled backdrop work");
  check(!(Boolean)prefs.getMethod("getBackdropEnabled").invoke(obj(prefs)),"Color-only mode kept the background pipeline active");
  check(helper.getMethod("getBlurredWallpaper",Context.class).invoke(obj(helper),context)==null,"Color-only mode returned a retained background");
  check(helper.getMethod("getDockWallpaper",Context.class).invoke(obj(helper),context)==null,"Color-only dock returned a retained background");
  // Grid background uses the OEM destination clip, never another rounded alpha mask.
  Bitmap rectangle=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);rectangle.eraseColor(Color.WHITE);
  Bitmap flat=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888),expected=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
  Path stockClip=new Path();stockClip.addRoundRect(new RectF(0,0,200,200),24,24,Path.Direction.CW);
  Canvas actualCanvas=new Canvas(flat);actualCanvas.clipPath(stockClip);
  Canvas stockCanvas=new Canvas(expected);stockCanvas.clipPath(stockClip);stockCanvas.drawColor(Color.RED);
  Object colorStyle=constructor.newInstance(false,30,80,0,100,null,false,Integer.valueOf(Color.RED),true,false,true,null);
  check(Boolean.TRUE.equals(draw.invoke(obj(surface),actualCanvas,new Rect(0,0,200,200),waiting,new ColorDrawable(Color.RED),77f,stockClip,false,255,colorStyle,null,rectangle,null,null,null,false)),"Native clipped flat plate unavailable");
  for(int y=0;y<200;y++)for(int x=0;x<200;x++)check(Color.alpha(flat.getPixel(x,y))==Color.alpha(expected.getPixel(x,y)),"Native destination clip changed at "+x+","+y);
  field(prefs,"iconStroke",true);field(prefs,"strokeOpacity",100);
  Bitmap outlined=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
  Canvas outlineCanvas=new Canvas(outlined);outlineCanvas.clipPath(stockClip);
  check(Boolean.TRUE.equals(draw.invoke(obj(surface),outlineCanvas,new Rect(0,0,200,200),waiting,new ColorDrawable(Color.RED),77f,stockClip,true,255,colorStyle,null,rectangle,null,null,null,false)),"Native clipped plate with contour unavailable");
  int changed=0;
  for(int y=0;y<200;y++)for(int x=0;x<200;x++){
   int before=flat.getPixel(x,y),after=outlined.getPixel(x,y);
   check(Color.alpha(before)==Color.alpha(after),"Contour changed the native silhouette at "+x+","+y);
   if(before!=after)changed++;
  }
  check(changed>100 && flat.getPixel(100,100)==outlined.getPixel(100,100),"Native path rim missing or painted across the center");
  outlined.recycle();field(prefs,"iconStroke",false);
  System.out.println("PASS: multi-folder contour changes edge lighting while leaving stock silhouette and center intact");
  rectangle.recycle();flat.recycle();expected.recycle();
  Class<?> folders=cls("xposed.FolderSettings");Object folderSettings=obj(folders);
  android.os.Bundle own=new android.os.Bundle();own.putBoolean("folder_own_settings",true);own.putBoolean("folder_blur_radius_enabled",true);own.putInt("folder_blur_radius",7);
  folders.getMethod("update",android.os.Bundle.class).invoke(folderSettings,own);field(prefs,"folderBlurEnabled",true);
  check((Boolean)prefs.getMethod("getBackdropEnabled").invoke(obj(prefs)),"Optional folder glass failed to enable shared background work");
  check(!(Boolean)prefs.getMethod("getIconBackdropEnabled").invoke(obj(prefs)),"Optional folder glass enabled icon sampling");
  Object ownStyle=folders.getMethod("style").invoke(folderSettings);
  check(!(Boolean)style.getMethod("getColorOnly").invoke(ownStyle),"Folder override inherited color-only icon style");
  own.putBoolean("folder_own_settings",false);folders.getMethod("update",android.os.Bundle.class).invoke(folderSettings,own);
  field(prefs,"blurStyle",0);
  System.out.println("PASS: native destination clip preserved pixel-exactly; color-only rejects retained backgrounds; optional folder glass leaves icons flat");
  HazeCheck.run();
  System.out.println("PASS: rectangular paint rejected as folder contour; rounded native outline retained; drawable bounds restored");
  System.out.println("PASS: frosted/liquid parent visible under ordinary child, rounded contour preserved, transforms follow owner, self-sampling excluded");System.exit(0);
 }
}
