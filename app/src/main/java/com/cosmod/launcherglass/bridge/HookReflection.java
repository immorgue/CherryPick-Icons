package com.cosmod.launcherglass.bridge;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HookReflection {
  private static final Map<Class<?>, Map<String, Field>> fields = new ConcurrentHashMap<>();
  private static final Map<Class<?>, Map<String, Method>> methods = new ConcurrentHashMap<>();
  private static final Map<Object, Map<String, Object>> extra =
      Collections.synchronizedMap(new WeakHashMap<>());

  public static Class<?> findClass(String name, ClassLoader loader) {
    try {
      return Class.forName(name, false, loader);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(name, e);
    }
  }

  public static Class<?> findClassIfExists(String name, ClassLoader loader) {
    try {
      return Class.forName(name, false, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  public static Field findField(Class<?> type, String name) {
    Map<String, Field> cache = fields.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
    Field f = cache.get(name);
    if (f != null) return f;
    for (Class<?> c = type; c != null; c = c.getSuperclass())
      try {
        f = c.getDeclaredField(name);
        f.setAccessible(true);
        cache.put(name, f);
        return f;
      } catch (NoSuchFieldException ignored) {
      }
    throw new IllegalArgumentException("Field " + type.getName() + "." + name);
  }

  public static Field findFieldIfExists(Class<?> type, String name) {
    try {
      return findField(type, name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public static Object getObjectField(Object o, String n) {
    try {
      return findField(o.getClass(), n).get(o);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Object getStaticObjectField(Class<?> c, String n) {
    try {
      return findField(c, n).get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int getIntField(Object o, String n) {
    return ((Number) getObjectField(o, n)).intValue();
  }

  public static float getFloatField(Object o, String n) {
    return ((Number) getObjectField(o, n)).floatValue();
  }

  public static boolean getBooleanField(Object o, String n) {
    return (Boolean) getObjectField(o, n);
  }

  public static int getStaticIntField(Class<?> c, String n) {
    return ((Number) getStaticObjectField(c, n)).intValue();
  }

  public static void setObjectField(Object o, String n, Object v) {
    try {
      findField(o.getClass(), n).set(o, v);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void setIntField(Object o, String n, int v) {
    setObjectField(o, n, v);
  }

  public static void setFloatField(Object o, String n, float v) {
    setObjectField(o, n, v);
  }

  public static void setBooleanField(Object o, String n, boolean v) {
    setObjectField(o, n, v);
  }

  public static Object getAdditionalInstanceField(Object o, String n) {
    synchronized (extra) {
      Map<String, Object> m = extra.get(o);
      return m == null ? null : m.get(n);
    }
  }

  public static Object setAdditionalInstanceField(Object o, String n, Object v) {
    synchronized (extra) {
      return extra.computeIfAbsent(o, k -> new HashMap<>()).put(n, v);
    }
  }

  public static Method findMethodExact(Class<?> c, String n, Class<?>... types) {
    String key = n + Arrays.toString(types);
    Map<String, Method> cache = methods.computeIfAbsent(c, k -> new ConcurrentHashMap<>());
    Method m = cache.get(key);
    if (m != null) return m;
    try {
      m = c.getDeclaredMethod(n, types);
      m.setAccessible(true);
      cache.put(key, m);
      return m;
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(key, e);
    }
  }

  private static Class<?> box(Class<?> c) {
    if (!c.isPrimitive()) return c;
    if (c == int.class) return Integer.class;
    if (c == boolean.class) return Boolean.class;
    if (c == float.class) return Float.class;
    if (c == double.class) return Double.class;
    if (c == long.class) return Long.class;
    if (c == short.class) return Short.class;
    if (c == byte.class) return Byte.class;
    if (c == char.class) return Character.class;
    return c;
  }

  private static int score(Class<?>[] parameters, Class<?>[] args) {
    if (parameters.length != args.length) return -1;
    int score = 0;
    for (int i = 0; i < args.length; i++) {
      Class<?> p = box(parameters[i]), a = args[i] == null ? null : box(args[i]);
      if (a == null) {
        if (parameters[i].isPrimitive()) return -1;
        score += 10;
      } else if (p == a) {
      } else if (p.isAssignableFrom(a)) {
        score += 2;
        Class<?> k = a;
        while (k != null && k != p) {
          score++;
          k = k.getSuperclass();
        }
      } else if (parameters[i].isPrimitive() && widening(a, p) >= 0) {
        score += 8 + widening(a, p);
      } else return -1;
    }
    return score;
  }

  private static int widening(Class<?> from, Class<?> to) {
    Class<?>[] path;
    if (from == Byte.class)
      path = new Class[] {Short.class, Integer.class, Long.class, Float.class, Double.class};
    else if (from == Short.class || from == Character.class)
      path = new Class[] {Integer.class, Long.class, Float.class, Double.class};
    else if (from == Integer.class) path = new Class[] {Long.class, Float.class, Double.class};
    else if (from == Long.class) path = new Class[] {Float.class, Double.class};
    else if (from == Float.class) path = new Class[] {Double.class};
    else return -1;
    for (int i = 0; i < path.length; i++) if (path[i] == to) return i;
    return -1;
  }

  private static boolean moreSpecific(Class<?>[] left, Class<?>[] right) {
    boolean narrower = false;
    for (int i = 0; i < left.length; i++) {
      if (left[i] == right[i]) continue;
      if (!box(right[i]).isAssignableFrom(box(left[i]))) return false;
      narrower = true;
    }
    return narrower;
  }

  public static Method findMethodBestMatch(Class<?> type, String name, Class<?>... args) {
    String key = "best:" + name + Arrays.toString(args);
    Map<String, Method> cache = methods.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
    Method hit = cache.get(key);
    if (hit != null) return hit;
    Method best = null;
    int min = Integer.MAX_VALUE;
    for (Class<?> c = type; c != null; c = c.getSuperclass())
      for (Method m : c.getDeclaredMethods()) {
        if (!m.getName().equals(name)) continue;
        int s = score(m.getParameterTypes(), args);
        if (s >= 0
            && (s < min
                || (s == min
                    && best != null
                    && moreSpecific(m.getParameterTypes(), best.getParameterTypes())))) {
          best = m;
          min = s;
        }
      }
    if (best == null)
      throw new IllegalArgumentException(
          "Method " + type.getName() + "." + name + Arrays.toString(args));
    best.setAccessible(true);
    cache.put(key, best);
    return best;
  }

  private static Class<?>[] types(Object[] args) {
    Class<?>[] out = new Class<?>[args.length];
    for (int i = 0; i < args.length; i++) out[i] = args[i] == null ? null : args[i].getClass();
    return out;
  }

  private static Object invoke(Method m, Object o, Object[] args) {
    try {
      return m.invoke(o, args);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Object callMethod(Object o, String n, Object... args) {
    return invoke(findMethodBestMatch(o.getClass(), n, types(args)), o, args);
  }

  public static Object callStaticMethod(Class<?> c, String n, Object... args) {
    return invoke(findMethodBestMatch(c, n, types(args)), null, args);
  }

  public static Object newInstance(Class<?> c, Object... args) {
    Constructor<?> best = null;
    int min = Integer.MAX_VALUE;
    for (Constructor<?> k : c.getDeclaredConstructors()) {
      int s = score(k.getParameterTypes(), types(args));
      if (s >= 0 && s < min) {
        best = k;
        min = s;
      }
    }
    if (best == null) throw new IllegalArgumentException("Constructor " + c.getName());
    try {
      best.setAccessible(true);
      return best.newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  public static HookCallback.Unhook findAndHookMethod(Class<?> c, String name, Object... params) {
    Class<?>[] types = new Class<?>[params.length - 1];
    for (int i = 0; i < types.length; i++)
      types[i] =
          params[i] instanceof Class
              ? (Class<?>) params[i]
              : findClass((String) params[i], c.getClassLoader());
    return HookRuntime.hookMethod(
        findMethodExact(c, name, types), (HookCallback) params[params.length - 1]);
  }

  public static HookCallback.Unhook findAndHookMethod(
      String name, ClassLoader loader, String method, Object... params) {
    return findAndHookMethod(findClass(name, loader), method, params);
  }
}
