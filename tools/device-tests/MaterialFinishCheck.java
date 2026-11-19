import android.graphics.*;
import android.os.Looper;
import java.lang.reflect.*;

/** Isolated pixel contracts. Never writes settings or opens/restarts a launcher. */
public final class MaterialFinishCheck {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static Class<?> cls(String name) throws Exception {
        return Class.forName("com.cosmod.launcherglass.xposed." + name);
    }
    static void field(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    static Bitmap render(RenderNode node, int width, int height) throws Exception {
        Method method = HardwareRenderer.class.getDeclaredMethod("createHardwareBitmap", RenderNode.class, int.class, int.class);
        method.setAccessible(true);
        Bitmap hardware = (Bitmap) method.invoke(null, node, width, height);
        Bitmap pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
        hardware.recycle(); node.discardDisplayList(); return pixels;
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
        Looper.prepareMainLooper();
        Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        try {
            Class<?> shared = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            shared.getMethod("setInstance", shared).invoke(null, shared.getMethod("create").invoke(null));
        } catch (ClassNotFoundException ignored) {}
        Class<?> options = cls("GlassFinish"), finish = cls("MaterialFinish");
        Object instance = finish.getField("INSTANCE").get(null);
        Constructor<?> constructor = options.getConstructor(int.class, boolean.class);
        Method cpu = finish.getMethod("applyCpu", Bitmap.class, options);
        Method effect = finish.getMethod("effect", RenderEffect.class, int.class, int.class, options);
        Method shaderMethod = finish.getMethod("shader", Shader.class, int.class, int.class, options, RuntimeShader.class);
        int width = 64, height = 64;
        Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas sourceCanvas = new Canvas(source); Paint paint = new Paint();
        paint.setColor(Color.rgb(8, 12, 16)); sourceCanvas.drawRect(0, 0, 32, height, paint);
        paint.setColor(Color.argb(160, 230, 80, 35)); sourceCanvas.drawRect(32, 0, width, height, paint);
        for (boolean haze : new boolean[]{false, true}) for (int transparency : new int[]{0, 50, 100}) {
            Object config = constructor.newInstance(transparency, haze);
            Bitmap expected = (Bitmap) cpu.invoke(instance, source, config);
            RenderEffect finishEffect = (RenderEffect) effect.invoke(instance, null, width, height, config);
            if (!haze) {
                check(expected == source && finishEffect == null, "Disabled finish allocates or alters the baseline");
            }
            RenderNode node = new RenderNode("FinishEffectCheck"); node.setPosition(0, 0, width, height);
            node.setRenderEffect(finishEffect);
            Canvas recording = node.beginRecording(width, height); recording.drawBitmap(source, 0, 0, null); node.endRecording();
            Bitmap gpu = render(node, width, height);
            Shader shader = (Shader) shaderMethod.invoke(instance, new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), width, height, config, null);
            RenderNode parent = new RenderNode("ParentFinishCheck"); parent.setPosition(0, 0, width, height);
            recording = parent.beginRecording(width, height); paint.setColor(Color.WHITE); paint.setShader(shader);
            recording.drawRect(0, 0, width, height, paint); parent.endRecording(); paint.setShader(null);
            Bitmap parentPixels = render(parent, width, height);
            for (int x : new int[]{16, 48}) {
                int reference = expected.getPixel(x, 32);
                int alpha = Color.alpha(source.getPixel(x, 32));
                check(Math.abs(Color.alpha(reference) - alpha) <= 1, "Optical finish faded refraction coverage");
                for (Bitmap actual : new Bitmap[]{gpu, parentPixels}) for (int shift : new int[]{0, 8, 16, 24}) {
                    int delta = Math.abs(((reference >>> shift) & 255) - ((actual.getPixel(x, 32) >>> shift) & 255));
                    check(delta <= 5, "CPU/GPU/parent mismatch at haze=" + haze + ", transparency=" + transparency + ": " + Integer.toHexString(reference) + " / " + Integer.toHexString(actual.getPixel(x, 32)));
                }
            }
            gpu.recycle(); parentPixels.recycle(); if (expected != source) expected.recycle();
        }
        System.out.println("PASS: original haze matches on CPU/GPU/parent shaders; the optical finish never fades material coverage");

        // Exercise the real dock renderer: fading its backdrop must keep the tint overlay.
        Class<?> prefs = cls("PrefsHelper"); field(prefs, "gpuEffects", true); field(prefs, "gradientOpacity", 0); field(prefs, "iconStroke", false);
        Class<?> style = cls("GlassStyle"), optics = cls("KyantOptics");
        Constructor<?> styleConstructor = style.getConstructor(boolean.class, int.class, int.class, int.class, int.class,
            ColorMatrixColorFilter.class, boolean.class, Integer.class, boolean.class, boolean.class, boolean.class, optics, options, Bitmap.class);
        Class<?> renderer = cls("DockRenderer"); Object dock = renderer.getConstructor().newInstance();
        Method draw = java.util.Arrays.stream(renderer.getDeclaredMethods()).filter(m -> m.getName().startsWith("drawPlate")).findFirst().orElseThrow();
        draw.setAccessible(true);
        Object config = constructor.newInstance(100, false);
        Object glass = styleConstructor.newInstance(false, 40, 50, 10, 30, null, false, Integer.valueOf(Color.GREEN), true, true, false, null, config, null);
        Path shape = new Path(); shape.addRoundRect(new RectF(0, 0, width, height), 12, 12, Path.Direction.CW);
        RenderNode node = new RenderNode("DockFinishCheck"); node.setPosition(0, 0, width, height);
        Canvas recording = node.beginRecording(width, height);
        draw.invoke(dock, recording, new Rect(0, 0, width, height), shape, 12f, 1f, source, new RectF(0, 0, width, height), glass);
        node.endRecording(); Bitmap result = render(node, width, height);
        check(Color.alpha(result.getPixel(16, 32)) == 255, "Blur transparency faded the dock backdrop");
        check(Color.green(result.getPixel(16, 32)) > Color.green(source.getPixel(16, 32)), "Dock tint disappeared");
        result.recycle(); source.recycle();
        System.out.println("PASS: the dock keeps opaque backdrop coverage and tint at 100% blur transparency");

        Bitmap pattern = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) pattern.setPixel(x, y, Color.rgb(x * 4, y * 4, x % 8 < 4 ? 255 : 0));
        Class<?> mixing = Class.forName("com.cosmod.launcherglass.util.BlurOpacity");
        Object mixer = mixing.getField("INSTANCE").get(null);
        Method mixEffect = mixing.getMethod("effect", RenderEffect.class, RenderEffect.class, int.class);
        RenderEffect blur = RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.CLAMP);
        check(mixEffect.invoke(mixer, blur, null, 0) == blur, "0% replaced the normal blur path");
        check(mixEffect.invoke(mixer, blur, null, 100) == null, "100% did not select the sharp input");
        Method blurCpu = mixing.getMethod("blurCpu", Bitmap.class, float.class, int.class);
        Bitmap sharp = (Bitmap) blurCpu.invoke(mixer, pattern, 8f, 100);
        check(sharp.getPixel(3, 32) == pattern.getPixel(3, 32), "CPU 100% altered the sharp input"); sharp.recycle();
        Bitmap[] stages = new Bitmap[3];
        int[] levels = new int[]{0, 50, 100};
        for (int i = 0; i < levels.length; i++) {
            RenderNode stage = new RenderNode("BlurBlendCheck"); stage.setPosition(0, 0, width, height);
            stage.setRenderEffect((RenderEffect) mixEffect.invoke(mixer, blur, null, levels[i]));
            recording = stage.beginRecording(width, height); recording.drawBitmap(pattern, 0, 0, null); stage.endRecording();
            stages[i] = render(stage, width, height);
        }
        for (int x : new int[]{3, 17, 32, 60}) {
            int a = stages[0].getPixel(x, 32), b = stages[2].getPixel(x, 32), middle = stages[1].getPixel(x, 32);
            check(Color.alpha(middle) == 255, "Blur mixing introduced transparency");
            for (int shift : new int[]{0, 8, 16}) check(Math.abs(((middle >>> shift) & 255) - ((((a >>> shift) & 255) + ((b >>> shift) & 255)) / 2)) <= 2, "Blur mixing is not a 50/50 sum");
        }
        Object kyant = optics.getConstructor(int.class, int.class, int.class, int.class, int.class).newInstance(60, 300, 30, 50, 100);
        Bitmap[] lenses = new Bitmap[2];
        for (int i = 0; i < lenses.length; i++) {
            Object lensStyle = styleConstructor.newInstance(true, 40, 50, 10, 0, null, false, Integer.valueOf(Color.GREEN), true, true, false, kyant, constructor.newInstance(i * 100, false), null);
            RenderNode stage = new RenderNode("RefractionCoverageCheck"); stage.setPosition(0, 0, width, height);
            recording = stage.beginRecording(width, height);
            draw.invoke(dock, recording, new Rect(0, 0, width, height), shape, 12f, 1f, stages[2], new RectF(0, 0, width, height), lensStyle);
            stage.endRecording(); lenses[i] = render(stage, width, height);
        }
        boolean refracted = false;
        for (int[] point : new int[][]{{3,32},{10,10},{60,32},{32,3}}) {
            int x = point[0], y = point[1], a = lenses[0].getPixel(x,y), b = lenses[1].getPixel(x,y);
            check(a == b && Color.alpha(b) == 255, "100% blur transparency faded Kyant refraction");
            refracted |= b != stages[2].getPixel(x,y);
        }
        check(refracted, "The preserved lens is flat and has no refraction");
        for (Bitmap bitmap : stages) bitmap.recycle(); for (Bitmap bitmap : lenses) bitmap.recycle();
        System.out.println("PASS: sharp/blur blending stays opaque and linear; Kyant refraction survives at 100%");

        Class<?> corners = Class.forName("com.cosmod.launcherglass.util.ContinuousCorners");
        Object cornerOwner = corners.getField("INSTANCE").get(null);
        check((Float)corners.getMethod("innerRadius",float.class,float.class).invoke(cornerOwner,28f,12f)==16f, "Concentric inner radius is wrong");
        Method cornerPath = corners.getMethod("path",Path.class,RectF.class,float.class,float.class);
        Path maximum = (Path)cornerPath.invoke(cornerOwner,new Path(),new RectF(0,0,width,height),width/2f,.6f);
        Bitmap smoothMaximum=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Bitmap roundMaximum=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        paint.setColor(Color.WHITE);paint.setAntiAlias(true);
        new Canvas(smoothMaximum).drawPath(maximum,paint);
        new Canvas(roundMaximum).drawRoundRect(0,0,width,height,width/2f,width/2f,paint);
        int difference=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)difference+=Math.abs(Color.alpha(smoothMaximum.getPixel(x,y))-Color.alpha(roundMaximum.getPixel(x,y)));
        check(difference>100,"Maximum dock radius discarded 60% smoothing and became a circle");
        smoothMaximum.recycle();roundMaximum.recycle();
        Path continuous = (Path)cornerPath.invoke(cornerOwner,new Path(),new RectF(0,0,width,height),12f,.6f);
        Bitmap contour = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        paint.setColor(Color.WHITE); new Canvas(contour).drawPath(continuous,paint);
        Class<?> geometry = cls("GlassGeometry");
        Bitmap cornerField = (Bitmap)geometry.getMethod("contourField",Bitmap.class).invoke(geometry.getField("INSTANCE").get(null),contour);
        Object continuousStyle = styleConstructor.newInstance(true,40,50,10,0,null,false,null,true,true,false,kyant,constructor.newInstance(0,false),cornerField);
        Class<?> lensType = cls("LiquidGlassShader"); Object companion = lensType.getField("Companion").get(null);
        Object lens = companion.getClass().getMethod("create").invoke(companion);
        Shader gpuShader = (Shader)lensType.getMethod("update",Bitmap.class,Shader.class,RectF.class,int.class,int.class,
            ColorMatrixColorFilter.class,float.class,style,Shader.class).invoke(lens,pattern,new BitmapShader(contour,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP),
                new RectF(0,0,width,height),width,height,null,12f/width,continuousStyle,null);
        Class<?> cpuType = cls("CpuLiquidGlass");
        Shader cpuShader = (Shader)cpuType.getMethod("shader",Bitmap.class,Bitmap.class,int.class,int.class,RectF.class,float.class,style)
            .invoke(cpuType.getField("INSTANCE").get(null),pattern,contour,width,height,new RectF(0,0,width,height),12f/width,continuousStyle);
        Bitmap[] cornerResults = new Bitmap[2]; Shader[] cornerShaders = new Shader[]{gpuShader,cpuShader};
        for(int i=0;i<2;i++) {
            RenderNode stage=new RenderNode("ContinuousLensCheck");stage.setPosition(0,0,width,height);
            recording=stage.beginRecording(width,height);paint.setShader(cornerShaders[i]);recording.drawRect(0,0,width,height,paint);stage.endRecording();paint.setShader(null);
            cornerResults[i]=render(stage,width,height);
        }
        for(int[] point:new int[][]{{3,32},{10,10},{60,32},{32,3}}) {
            int a=cornerResults[0].getPixel(point[0],point[1]),b=cornerResults[1].getPixel(point[0],point[1]);
            for(int shift:new int[]{0,8,16,24})check(Math.abs(((a>>>shift)&255)-((b>>>shift)&255))<=8,"Continuous corner lens GPU/CPU mismatch: "+Integer.toHexString(a)+" / "+Integer.toHexString(b));
        }
        for(Bitmap bitmap:cornerResults)bitmap.recycle();contour.recycle();pattern.recycle();
        System.out.println("PASS: concentric radius and continuous contour refraction agree on GPU and CPU");
    }
}
