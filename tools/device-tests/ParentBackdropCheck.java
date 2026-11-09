import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.content.Context;
import java.lang.reflect.*;
public class ParentBackdropCheck {
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
  FrameLayout owner=new FrameLayout(context);View child=new View(context);owner.addView(child,new FrameLayout.LayoutParams(200,200));owner.layout(0,0,200,200);child.layout(0,0,200,200);
  Bitmap mask=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);Canvas maskCanvas=new Canvas(mask);Paint white=new Paint(Paint.ANTI_ALIAS_FLAG);white.setColor(Color.WHITE);maskCanvas.drawRoundRect(new RectF(0,0,200,200),35,35,white);
  Class<?> style=cls("xposed.GlassStyle");Constructor<?> constructor=style.getConstructor(boolean.class,int.class,int.class,int.class,int.class,ColorMatrixColorFilter.class,boolean.class,Integer.class,boolean.class);
  Class<?> parent=cls("xposed.ParentBackdrop");Object singleton=obj(parent);Method record=parent.getMethod("record",View.class,Drawable.class,RectF.class,Bitmap.class,float.class,style,boolean.class);Method resolve=parent.getMethod("resolve",View.class,Drawable.class,Bitmap.class);
  Drawable plate=new ColorDrawable(Color.RED),icon=new ColorDrawable(Color.WHITE);
  for(boolean liquid:new boolean[]{false,true}) {
   Object appearance=constructor.newInstance(liquid,30,80,0,100,null,false,Integer.valueOf(Color.RED),true);
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
  Object colored=constructor.newInstance(false,30,80,0,0,filter,false,Integer.valueOf(Color.RED),true);
  record.invoke(singleton,owner,plate,new RectF(0,0,200,200),mask,35f,colored,false);parent.getMethod("beginFrame").invoke(singleton);
  Object coloredSample=resolve.invoke(singleton,child,icon,wallpaper);Bitmap filtered=render((Shader)coloredSample.getClass().getMethod("getShader").invoke(coloredSample));check(Color.green(filtered.getPixel(100,100))>245,"Parent color filter was not applied");filtered.recycle();
  for(String name:new String[]{"com.android.launcher3.folder.FolderIcon","com.android.launcher3.folder.OplusFolder"}) {
   FrameLayout folder=(FrameLayout)Class.forName(name).getConstructor(Context.class).newInstance(context);View background=new View(context),content=new View(context);folder.addView(background,new FrameLayout.LayoutParams(200,200));folder.addView(content,new FrameLayout.LayoutParams(200,200));folder.layout(0,0,200,200);background.layout(0,0,200,200);content.layout(0,0,200,200);
   Drawable folderIdentity=new ColorDrawable(Color.RED);Object appearance=constructor.newInstance(false,30,80,0,100,null,true,Integer.valueOf(Color.RED),true);
   record.invoke(singleton,background,folderIdentity,new RectF(0,0,200,200),mask,35f,appearance,false);parent.getMethod("beginFrame").invoke(singleton);
   Object folderSample=resolve.invoke(singleton,content,icon,wallpaper);check(folderSample!=null,"Sibling folder background unavailable: "+name);Bitmap folderPixels=render((Shader)folderSample.getClass().getMethod("getShader").invoke(folderSample));check(Color.red(folderPixels.getPixel(100,100))>245,"Folder child ignored sibling plate");folderPixels.recycle();
   check(resolve.invoke(singleton,folder,icon,wallpaper)!=null,"Inline miniature cannot see FolderIcon background");
  }System.out.println("PASS: frosted/liquid parent visible under ordinary child, rounded contour preserved, transforms follow owner, self-sampling excluded");System.exit(0);
 }
}




