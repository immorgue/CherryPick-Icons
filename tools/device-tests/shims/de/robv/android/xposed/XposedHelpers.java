package de.robv.android.xposed;
public class XposedHelpers {
 public static Object callMethod(Object owner,String name,Object... args) {
  try {
   for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(java.lang.reflect.Method m:type.getDeclaredMethods()) {
    if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
    boolean ok=true;Class<?>[] types=m.getParameterTypes();for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i]))ok=false;
    if(ok){m.setAccessible(true);return m.invoke(owner,args);}
   }
   throw new NoSuchMethodException(name);
  }catch(Exception e){throw new RuntimeException(e);}
 }
}
