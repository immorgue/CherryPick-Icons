package de.robv.android.xposed;
public class XposedBridge {
 public static void log(String s){System.out.println(s);}
 public static void log(Throwable t){t.printStackTrace();}
}
