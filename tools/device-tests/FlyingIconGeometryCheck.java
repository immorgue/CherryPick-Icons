import android.graphics.Matrix;
import android.graphics.Rect;
import java.lang.reflect.*;

/** Verify the icon keeps its proportions inside the app's projected inset. */
public final class FlyingIconGeometryCheck {
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName("com.cosmod.launcherglass.xposed.FlyingIconBounds");
        Class<?> deformation=Class.forName("com.cosmod.launcherglass.util.AppWindowDeformation");
        Object fit=type.getDeclaredConstructor().newInstance();
        Method method=type.getMethod("fit",Rect.class,Matrix.class,Rect.class,Matrix.class,
                float.class,deformation,float.class,float.class,float.class);
        Method normalize=type.getMethod("normalize",Rect.class,Matrix.class);
        Method project=deformation.getMethod("writeMatrix",float[].class,float.class,float.class,float.class,float.class);
        Field output=type.getDeclaredField("surfaceAffine");output.setAccessible(true);
        int count=0;
        for(float tilt:new float[]{-.3f,-.12f,0f,.12f,.3f}) for(float scale:new float[]{.12f,.4f,.8f}) {
            Rect app=new Rect(0,0,1200,2670),icon=new Rect(0,0,1200,1200);
            Matrix appPose=new Matrix();appPose.setScale(scale,scale);appPose.postTranslate(50,180);
            Matrix iconPose=new Matrix();iconPose.setScale(.65f,.83f);iconPose.postTranslate(10,90);
            if (!(Boolean)normalize.invoke(fit,icon,iconPose)) throw new AssertionError("normalization rejected");
            float[] pose=(float[])output.get(fit);
            if (pose[0] != .65f || pose[1] != .65f) throw new AssertionError("native preview was stretched");
            Object warp=deformation.getDeclaredConstructors()[0].newInstance(tilt,-tilt*.5f,1-tilt,1+tilt,.5f,.5f);
            if(!(Boolean)method.invoke(fit,icon,iconPose,app,appPose,160f,warp,.94f,0f,1f))
                throw new AssertionError("fit rejected");
            if (pose[0] != pose[1]) throw new AssertionError("fitted icon was stretched");
            float[] a=new float[16];
            project.invoke(warp,a,0f,0f,1200f,2670f);
            Matrix appProjection=new Matrix();appProjection.setValues(new float[]{a[0],a[1],a[3],a[4],a[5],a[7],a[12],a[13],a[15]});
            Matrix inverseProjection=new Matrix(),inversePose=new Matrix();
            if(!appProjection.invert(inverseProjection)||!appPose.invert(inversePose))throw new AssertionError("singular");
            float[] points={0,0,1200,0,1200,1200,0,1200};
            Matrix iconOnScreen=new Matrix();iconOnScreen.setScale(pose[0],pose[1]);
            iconOnScreen.postTranslate(pose[2],pose[3]);
            iconOnScreen.mapPoints(points);inversePose.mapPoints(points);inverseProjection.mapPoints(points);
            for(int i=0;i<8;i+=2)if(points[i]<80.8f||points[i]>1119.2f||points[i+1]<80.8f||points[i+1]>2589.2f)throw new AssertionError("escaped corner inset");
            count++;
        }
        System.out.println("PASS: "+count+" strong-tilt icon/window geometry cases");
    }
}
