import android.graphics.*;
import android.os.Looper;
import java.lang.reflect.*;

/** Real dock renderer, including its downscaled contour field, without highlight/color cues. */
public final class DockRefractionCheck {
 public static void main(String[] args)throws Exception{
  Looper.prepareMainLooper();Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
  try{Class<?> s=Class.forName("com.android.internal.os.ApplicationSharedMemory");s.getMethod("setInstance",s).invoke(null,s.getMethod("create").invoke(null));}catch(ClassNotFoundException ignored){}
  Class<?> prefs=MaterialFinishCheck.cls("PrefsHelper"),style=MaterialFinishCheck.cls("GlassStyle"),optics=MaterialFinishCheck.cls("KyantOptics"),finish=MaterialFinishCheck.cls("GlassFinish"),dock=MaterialFinishCheck.cls("DockRenderer");
  MaterialFinishCheck.field(prefs,"gpuEffects",true);MaterialFinishCheck.field(prefs,"gradientOpacity",0);MaterialFinishCheck.field(prefs,"iconStroke",false);
  Constructor<?> styleCtor=style.getConstructor(boolean.class,int.class,int.class,int.class,int.class,ColorMatrixColorFilter.class,boolean.class,Integer.class,boolean.class,boolean.class,boolean.class,optics,finish,Bitmap.class);
  Method draw=java.util.Arrays.stream(dock.getDeclaredMethods()).filter(m->m.getName().startsWith("drawPlate")).findFirst().orElseThrow();draw.setAccessible(true);
  for(int[] size:new int[][]{{360,96},{1200,330},{160,360},{160,160}}){
   int w=size[0],h=size[1];float radius=Math.min(w,h)*.32f;
   Bitmap input=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(input);Paint p=new Paint();
   p.setShader(new LinearGradient(0,0,w,h,Color.RED,Color.BLUE,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);p.setColor(Color.GREEN);
   for(int x=0;x<w;x+=30)c.drawRect(x,0,x+5,h,p);
   Path path=new Path();Class<?> corners=Class.forName("com.cosmod.launcherglass.util.ContinuousCorners");corners.getMethod("path",Path.class,RectF.class,float.class,float.class).invoke(corners.getField("INSTANCE").get(null),path,new RectF(0,0,w,h),radius,.6f);
   Bitmap[] results=new Bitmap[2];
   for(int i=0;i<2;i++){
    Object settings=optics.getConstructor(int.class,int.class,int.class,int.class,int.class).newInstance(55,i*300,0,0,100);
    Object material=styleCtor.newInstance(true,40,50,20,0,null,false,null,true,true,false,settings,finish.getConstructor(int.class,boolean.class).newInstance(100,false),null);
    Object renderer=dock.getDeclaredConstructor().newInstance();RenderNode n=new RenderNode("DockActualRefraction");n.setPosition(0,0,w,h);Canvas rec=n.beginRecording(w,h);
    draw.invoke(renderer,rec,new Rect(0,0,w,h),path,radius,1f,input,new RectF(0,0,w,h),material);n.endRecording();results[i]=MaterialFinishCheck.render(n,w,h);
   }
   long error=0;int changed=0,total=0;
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){
    int a=results[0].getPixel(x,y),b=results[1].getPixel(x,y);if(Color.alpha(a)<250)continue;total++;
    int diff=Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b));error+=diff;if(diff>12)changed++;
   }
   System.out.println(w+"x"+h+" changed="+changed+"/"+total+" mean="+(error/(double)Math.max(1,total)));
   MaterialFinishCheck.check(changed>total/100,"Actual dock refraction is absent at "+w+"x"+h);
   for(Bitmap b:results)b.recycle();input.recycle();
  }
  System.out.println("PASS: dock/folder aspect ratios refract without highlights at 100% blur transparency");System.exit(0);
 }
}
