package com.cosmod.launcherglass.bridge;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;

public class ModernHookRuntimeTest {
  private XposedInterface.Hooker installed;
  private int calls;

  private Object proxy(Class<?> type, InvocationHandler h) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, h);
  }

  private void install(HookCallback callback, Method method) {
    Object handle = proxy(XposedInterface.HookHandle.class, (p, m, a) -> null);
    Object builder =
        proxy(
            XposedInterface.HookBuilder.class,
            (p, m, a) -> {
              if (m.getName().equals("intercept")) {
                installed = (XposedInterface.Hooker) a[0];
                return handle;
              }
              return p;
            });
    HookRuntime.attach(
        (XposedInterface)
            proxy(XposedInterface.class, (p, m, a) -> m.getName().equals("hook") ? builder : null));
    HookRuntime.hookMethod(method, callback);
  }

  private Object run(Method method, Object receiver, Object... args) throws Throwable {
    return installed.intercept(
        (XposedInterface.Chain)
            proxy(
                XposedInterface.Chain.class,
                (p, m, a) -> {
                  switch (m.getName()) {
                    case "getExecutable":
                      return method;
                    case "getThisObject":
                      return receiver;
                    case "getArgs":
                      return Arrays.asList(args);
                    case "proceed":
                      calls++;
                      try {
                        return method.invoke(receiver, a == null ? args : (Object[]) a[0]);
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                    case "proceedWith":
                      if (Modifier.isStatic(method.getModifiers()))
                        throw new AssertionError("Static receiver path");
                      calls++;
                      try {
                        return method.invoke(a[0], a.length == 1 ? args : (Object[]) a[1]);
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                  }
                  throw new AssertionError(m.getName());
                }));
  }

  public static int sum(int a, int b) {
    return a + b;
  }

  public static int fail() {
    throw new IllegalArgumentException("native failure");
  }

  @Test
  public void argumentsResultsAndExtrasSurviveBothPhases() throws Throwable {
    Method m = getClass().getMethod("sum", int.class, int.class);
    install(
        new HookCallback() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.args[0] = 5;
            p.setObjectExtra("scope", 11);
          }

          protected void afterHookedMethod(MethodHookParam p) {
            assertEquals(11, p.getObjectExtra("scope"));
            p.setResult((int) p.getResult() * 2);
          }
        },
        m);
    assertEquals(16, run(m, null, 1, 3));
    assertEquals(1, calls);
  }

  @Test
  public void replacementSkipsOriginal() throws Throwable {
    Method m = getClass().getMethod("sum", int.class, int.class);
    install(HookReplacement.returnConstant(99), m);
    assertEquals(99, run(m, null, 1, 3));
    assertEquals(0, calls);
  }

  @Test
  public void afterHookCanRecoverOriginalException() throws Throwable {
    Method m = getClass().getMethod("fail");
    install(
        new HookCallback() {
          protected void afterHookedMethod(MethodHookParam p) {
            assertTrue(p.hasThrowable());
            assertEquals("native failure", p.getThrowable().getMessage());
            p.setResult(7);
          }
        },
        m);
    assertEquals(7, run(m, null));
  }

  @Test
  public void callbackFailurePreservesOriginalResult() throws Throwable {
    Method m = getClass().getMethod("sum", int.class, int.class);
    install(
        new HookCallback() {
          protected void afterHookedMethod(MethodHookParam p) {
            p.setResult(100);
            throw new IllegalStateException();
          }
        },
        m);
    assertEquals(4, run(m, null, 1, 3));
  }

  @Test
  public void nativeExceptionIsNotSwallowed() throws Throwable {
    Method m = getClass().getMethod("fail");
    install(new HookCallback() {}, m);
    try {
      run(m, null);
      org.junit.Assert.fail("Expected native exception");
    } catch (IllegalArgumentException e) {
      assertEquals("native failure", e.getMessage());
    }
  }

  @Test
  public void inheritedFieldsAndOverloadsRemainAccessible() {
    assertEquals(42, HookReflection.getIntField(new Child(), "secret"));
    assertEquals("integer", HookReflection.callMethod(new Child(), "over", 3));
    assertEquals("text", HookReflection.callMethod(new Child(), "over", "a"));
    HookReflection.setAdditionalInstanceField(this, "pending", 7);
    assertEquals(7, HookReflection.getAdditionalInstanceField(this, "pending"));
  }

  @Test
  public void numericWideningAndNullOverloadsKeepJavaResolution() {
    assertEquals(6L, HookReflection.callMethod(new Child(), "widen", 3));
    assertEquals("text", HookReflection.callMethod(new Child(), "over", new Object[] {null}));
  }

  static class Parent {
    private int secret = 42;

    private long widen(long n) {
      return n * 2;
    }

    private String over(int v) {
      return "integer";
    }

    private String over(String v) {
      return "text";
    }
  }

  static class Child extends Parent {}
}
