import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Native touch dispatch regression: row highlighting must not consume child button/switch clicks. */
public final class CouiInteractionCheck {
    static class Button extends FrameLayout {
        int clicks;
        boolean pressedOnUp;
        Button(Context context) { super(context); setOnClickListener(v -> clicks++); }
        @Override public boolean post(Runnable action) { action.run(); return true; }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if(event.getActionMasked()==MotionEvent.ACTION_UP) pressedOnUp=isPressed();
            return super.dispatchTouchEvent(event);
        }
    }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void send(View view,int action,long time) {
        MotionEvent event=MotionEvent.obtain(time,time+8,action,40,40,0);
        view.dispatchTouchEvent(event);event.recycle();
    }
    static LinearLayout row(Context context,View child)throws Exception {
        LinearLayout row=(LinearLayout)Class.forName("com.cosmod.launcherglass.ui.CouiPressRow").getConstructor(Context.class).newInstance(context);
        row.addView(child,new LinearLayout.LayoutParams(200,80));
        row.measure(View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(80,View.MeasureSpec.EXACTLY));
        row.layout(0,0,200,80);return row;
    }
    public static void main(String[] args)throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context base=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        Context context=new ContextWrapper(base) {
            @Override public Object getSystemService(String name) {
                // These managers require an installed process identity and are
                // unrelated to offscreen native View touch dispatch.
                if("autofill".equals(name)||"content_capture".equals(name)||"translation".equals(name))return null;
                return super.getSystemService(name);
            }
        };
        try {
            Class<?> shared=Class.forName("com.android.internal.os.ApplicationSharedMemory");
            shared.getMethod("setInstance",shared).invoke(null,shared.getMethod("create").invoke(null));
        }catch(ClassNotFoundException ignored){}
        Button button=new Button(context);LinearLayout parent=row(context,button);
        long time=SystemClock.uptimeMillis();
        send(parent,MotionEvent.ACTION_DOWN,time);
        check(parent.isPressed(),"Child press does not highlight the full row");
        send(parent,MotionEvent.ACTION_UP,time);
        check(button.pressedOnUp,"Parent cleared the child before UP");
        check(button.clicks==1,"Toolbar button click was swallowed or duplicated");
        check(!parent.isPressed(),"Row remained pressed after release");
        send(parent,MotionEvent.ACTION_DOWN,time+40);send(parent,MotionEvent.ACTION_CANCEL,time+40);
        check(button.clicks==1 && !parent.isPressed() && !button.isPressed(),"Cancel clicked or left a pressed state");
        System.out.println("PASS: full-row feedback preserves native toolbar button clicks and cancellation");
    }
}
