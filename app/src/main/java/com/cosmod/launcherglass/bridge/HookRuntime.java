package com.cosmod.launcherglass.bridge;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

public final class HookRuntime {
  private static volatile XposedInterface api;

  public static void attach(XposedInterface value) {
    api = value;
  }

  public static HookCallback.Unhook hookMethod(Member member, HookCallback cb) {
    if (!(member instanceof Executable))
      throw new IllegalArgumentException("Not executable: " + member);
    final java.util.function.BooleanSupplier allowed =
        com.cosmod.launcherglass.xposed.FeaturePolicy.guard(cb.getClass().getName());
    return new HookCallback.Unhook(
        api.hook((Executable) member)
            .setPriority(cb.priority)
            .intercept(
                chain -> {
                  if (!allowed.getAsBoolean()) return chain.proceed();
                  HookCallback.MethodHookParam p = new HookCallback.MethodHookParam();
                  p.method = member;
                  p.thisObject = chain.getThisObject();
                  p.args = chain.getArgs().toArray();
                  try {
                    cb.beforeHookedMethod(p);
                  } catch (Throwable t) {
                    log(t);
                    p.early = false;
                    p.value = null;
                    p.error = null;
                  }
                  if (!p.early)
                    try {
                      p.value =
                          Modifier.isStatic(((Executable) member).getModifiers())
                              ? chain.proceed(p.args)
                              : chain.proceedWith(p.thisObject, p.args);
                    } catch (Throwable t) {
                      p.error = t;
                    }
                  Object previous = p.value;
                  Throwable error = p.error;
                  try {
                    cb.afterHookedMethod(p);
                  } catch (Throwable t) {
                    log(t);
                    p.value = previous;
                    p.error = error;
                  }
                  if (p.error != null) throw p.error;
                  return p.value;
                }));
  }

  public static Set<HookCallback.Unhook> hookAllMethods(
      Class<?> type, String name, HookCallback cb) {
    Set<HookCallback.Unhook> result = new HashSet<>();
    for (Method m : type.getDeclaredMethods())
      if (m.getName().equals(name)) result.add(hookMethod(m, cb));
    return result;
  }

  public static Set<HookCallback.Unhook> hookAllConstructors(Class<?> type, HookCallback cb) {
    Set<HookCallback.Unhook> result = new HashSet<>();
    for (Constructor<?> c : type.getDeclaredConstructors()) result.add(hookMethod(c, cb));
    return result;
  }

  public static Object invokeOriginalMethod(Member member, Object receiver, Object[] args)
      throws Throwable {
    try {
      if (member instanceof Method)
        return api.getInvoker((Method) member)
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(receiver, args);
      return api.getInvoker((Constructor<?>) member)
          .setType(XposedInterface.Invoker.Type.ORIGIN)
          .newInstance(args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  public static void log(String message) {
    if (api != null) api.log(android.util.Log.INFO, "CherryPick", message);
  }

  public static void log(Throwable error) {
    if (api != null) api.log(android.util.Log.ERROR, "CherryPick", String.valueOf(error), error);
  }
}
