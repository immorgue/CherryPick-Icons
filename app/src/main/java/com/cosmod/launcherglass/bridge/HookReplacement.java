package com.cosmod.launcherglass.bridge;

public abstract class HookReplacement extends HookCallback {
  protected abstract Object replaceHookedMethod(MethodHookParam p) throws Throwable;

  @Override
  protected final void beforeHookedMethod(MethodHookParam p) throws Throwable {
    p.setResult(replaceHookedMethod(p));
  }

  public static HookReplacement returnConstant(Object value) {
    return new HookReplacement() {
      protected Object replaceHookedMethod(MethodHookParam p) {
        return value;
      }
    };
  }
}
