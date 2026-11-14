import android.graphics.RuntimeShader;
import java.lang.reflect.Field;

/** Run with the release APK on app_process's classpath. No settings changes. */
public class LiquidShaderCompileCheck {
    public static void main(String[] args) throws Exception {
        Class<?> lens = Class.forName("com.cosmod.launcherglass.xposed.LiquidGlassShader");
        for (String name : new String[]{"SOURCE", "LIFTED_SOURCE"}) {
            Field source = lens.getDeclaredField(name);
            source.setAccessible(true);
            RuntimeShader shader = new RuntimeShader((String) source.get(null));
            // Validate the uniform API used by both Frosted and Liquid Kyant.
            for (float mode : new float[]{0f, 1f}) {
                shader.setFloatUniform("size", 200f, 200f);
                shader.setFloatUniform("rounding", .18f);
                shader.setFloatUniform("kyantBand", 23f);
                shader.setFloatUniform("kyantAmount", mode * 16.2f);
                shader.setFloatUniform("kyantLight", mode * .105f);
                shader.setFloatUniform("kyantDispersion", mode * .5f);
                shader.setFloatUniform("kyantVibrancy", 1f);
                shader.setFloatUniform("rowR", 1f, 0f, 0f, 0f);
                shader.setFloatUniform("rowG", 0f, 1f, 0f, 0f);
                shader.setFloatUniform("rowB", 0f, 0f, 1f, 0f);
                shader.setFloatUniform("bias", 0f, 0f, 0f);
                if (name.equals("SOURCE")) shader.setFloatUniform("crop", 0f, 0f, 200f, 200f);
                else {
                    shader.setFloatUniform("origin", 20f, 20f);
                    shader.setFloatUniform("limit", 240f, 240f);
                }
            }
            System.out.println("PASS: " + name + " compiles and accepts Frosted/Liquid Kyant uniforms");
        }
    }
}
