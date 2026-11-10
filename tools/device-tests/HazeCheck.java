import android.graphics.*;
import java.lang.reflect.*;

/** Runs in the isolated pixel-contract process, never changes launcher preferences. */
public final class HazeCheck {
 static void run() throws Exception {
  Class<?> prefs=ParentBackdropCheck.cls("xposed.PrefsHelper");
  Class<?> haze=ParentBackdropCheck.cls("xposed.GlassHaze");Object instance=ParentBackdropCheck.obj(haze);
  Method cpu=haze.getMethod("applyCpu",Bitmap.class);
  Method effect=haze.getMethod("effect",RenderEffect.class,int.class,int.class);
  Class<?> finish=ParentBackdropCheck.cls("xposed.MaterialFinish");Object finishInstance=ParentBackdropCheck.obj(finish);
  Method key=finish.getMethod("key");
  Bitmap source=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
  Canvas canvas=new Canvas(source);Paint paint=new Paint();
  paint.setColor(Color.rgb(8,12,16));canvas.drawRect(0,0,100,200,paint);
  paint.setColor(Color.argb(160,230,80,35));canvas.drawRect(100,0,200,200,paint);
  ParentBackdropCheck.field(prefs,"hazeEnabled",true);
  Bitmap[] outputs=new Bitmap[2];int oldKey=0;
  for(int mode=0;mode<2;mode++) {
   ParentBackdropCheck.field(prefs,"hazeBloomEnabled",mode==1);
   outputs[mode]=(Bitmap)cpu.invoke(instance,source);
   int currentKey=(Integer)key.invoke(finishInstance);
   if(mode==0)oldKey=currentKey;else ParentBackdropCheck.check((currentKey^oldKey)==64,"Bloom variant aliases cached original haze");
   RenderNode node=new RenderNode("HazeCheck");node.setPosition(0,0,200,200);
   node.setRenderEffect((RenderEffect)effect.invoke(instance,null,200,200));
   Canvas recording=node.beginRecording(200,200);recording.drawBitmap(source,0,0,null);node.endRecording();
   Method hardware=HardwareRenderer.class.getDeclaredMethod("createHardwareBitmap",RenderNode.class,int.class,int.class);hardware.setAccessible(true);
   Bitmap hw=(Bitmap)hardware.invoke(null,node,200,200);Bitmap gpu=hw.copy(Bitmap.Config.ARGB_8888,false);hw.recycle();node.discardDisplayList();
   for(int x:new int[]{50,150}) {
    int expected=outputs[mode].getPixel(x,100),actual=gpu.getPixel(x,100);
    for(int shift:new int[]{0,8,16,24})ParentBackdropCheck.check(Math.abs(((expected>>>shift)&255)-((actual>>>shift)&255))<=4,"CPU/GPU haze mismatch: "+Integer.toHexString(expected)+" / "+Integer.toHexString(actual));
    ParentBackdropCheck.check(Color.alpha(actual)==Color.alpha(source.getPixel(x,100)),"Haze changes alpha");
   }
   gpu.recycle();
  }
  ParentBackdropCheck.check(Color.blue(outputs[1].getPixel(50,100))<Color.blue(outputs[0].getPixel(50,100)),"Bloom does not deepen dark background");
  int before=outputs[0].getPixel(150,100),after=outputs[1].getPixel(150,100);
  ParentBackdropCheck.check(Color.red(after)>Color.red(before),"Bright color receives no glow");
  ParentBackdropCheck.check(Color.red(after)-Color.red(before)>Color.blue(after)-Color.blue(before),"Glow loses source color");
  for(Bitmap bitmap:outputs)bitmap.recycle();source.recycle();
  ParentBackdropCheck.field(prefs,"hazeBloomEnabled",false);ParentBackdropCheck.field(prefs,"hazeEnabled",false);
  System.out.println("PASS: haze GPU/CPU parity, alpha retained, shadows deepened, colored highlights lifted, variant cache isolated");
 }
}
