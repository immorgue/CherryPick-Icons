package com.cosmod.launcherglass.bridge;

import java.lang.reflect.Member;
import java.util.HashMap;

public abstract class HookCallback {
  public static final int PRIORITY_HIGHEST = 10000, PRIORITY_LOWEST = -10000, PRIORITY_DEFAULT = 50;
  public final int priority;

  public HookCallback() {
    this(50);
  }

  public HookCallback(int priority) {
    this.priority = priority;
  }

  protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}

  protected void afterHookedMethod(MethodHookParam p) throws Throwable {}

  public static final class MethodHookParam {
    public Member method;
    public Object thisObject;
    public Object[] args;
    Object value;
    Throwable error;
    boolean early;
    private HashMap<String, Object> extras;

    public Object getResult() {
      return value;
    }

    public void setResult(Object v) {
      value = v;
      error = null;
      early = true;
    }

    public Throwable getThrowable() {
      return error;
    }

    public void setThrowable(Throwable t) {
      error = t;
      value = null;
      early = true;
    }

    public boolean hasThrowable() {
      return error != null;
    }

    public void setObjectExtra(String key, Object v) {
      if (extras == null) extras = new HashMap<>();
      extras.put(key, v);
    }

    public Object getObjectExtra(String key) {
      return extras == null ? null : extras.get(key);
    }
  }

  public static final class Unhook {
    private final io.github.libxposed.api.XposedInterface.HookHandle handle;

    Unhook(io.github.libxposed.api.XposedInterface.HookHandle handle) {
      this.handle = handle;
    }

    public void unhook() {
      handle.unhook();
    }
  }
}
