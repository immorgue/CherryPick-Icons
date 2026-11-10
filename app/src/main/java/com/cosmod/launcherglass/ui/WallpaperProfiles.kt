package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Appearance only: switching a wallpaper must never change layout or developer choices. */
internal object WallpaperProfiles {
    const val ENABLED="wallpaper_profiles_enabled"
    private const val FILE="wallpaper_profiles_v1"
    private const val ACTIVE="active"
    private val excluded=setOf(SettingsProvider.KEY_ENABLED,SettingsProvider.KEY_DEVELOPER_MODE,
        SettingsProvider.KEY_SCENE_DRAG_BACKDROP,SettingsProvider.KEY_STATIC_ANIMATION_BLUR,
        SettingsProvider.KEY_GPU_EFFECTS,SettingsProvider.KEY_EXPERIMENTAL_LIQUID,
        SettingsProvider.KEY_DOCK_EVENT_DRIVEN,SettingsProvider.KEY_COMPACT_DOCK_EFFECTS,
        SettingsProvider.KEY_REUSE_STATIC_WALLPAPER,SettingsProvider.KEY_LIMIT_CLOSING_BLUR,
        SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION,SettingsProvider.KEY_OPTIMIZE_MINI_ICONS,
        SettingsProvider.KEY_HAZE_ENABLED,SettingsProvider.KEY_HAZE_BLOOM,SettingsProvider.KEY_SMOOTH_SWIPE_LEAD,SettingsProvider.KEY_COLOR_DEPTH_ENABLED,
        SettingsProvider.KEY_COLOR_DEPTH_BITS,SettingsProvider.KEY_COLOR_DEPTH_STAGE)
    private val keys get()=SettingsDefaults.portable().keys-excluded-
        com.cosmod.launcherglass.util.DesktopSizing.ranges.keys
    internal fun appearanceKeys():Set<String> = keys
    private fun store(context:Context)=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    private fun snapshot(prefs:SharedPreferences)=prefs.all.filter {it.key in keys && (it.value is Int || it.value is Boolean)}.mapValues {it.value!!}
    private fun profileKey(identity:String)="profile:$identity"
    @Synchronized fun saveCurrent(context:Context,prefs:SharedPreferences) {
        val db=store(context);val id=db.getString(ACTIVE,null) ?: return
        check(db.edit().putString(profileKey(id),JSONObject(snapshot(prefs)).toString()).commit())
    }
    @Synchronized fun switchTo(context:Context,prefs:SharedPreferences,identity:String,label:String):Boolean {
        require(identity.length in 1..256 && label.length<=256)
        val db=store(context);val old=db.getString(ACTIVE,null)
        if(old==identity){db.edit().putString("label:$identity",label).apply();return false}
        val auto=prefs.getBoolean(ENABLED,false)
        val saved=if(auto)db.getString(profileKey(identity),null)?.let(::JSONObject)else null
        val restored=saved?.let {json -> keys.mapNotNull {key -> json.opt(key)?.takeIf {it is Int || it is Boolean}?.let {key to it}}.toMap()}
        val plan=WallpaperProfileSwitch.plan(old,identity,auto,snapshot(prefs),restored)
        // Persist the old appearance before applying any incoming values.
        val edit=db.edit()
        if(plan.savePrevious!=null && old!=null)edit.putString(profileKey(old),JSONObject(plan.savePrevious).toString())
        if(plan.saveNew!=null)edit.putString(profileKey(identity),JSONObject(plan.saveNew).toString())
        check(edit.putString(ACTIVE,identity).putString("label:$identity",label).commit())
        if(plan.restore!=null){
            val settings=prefs.edit()
            val schema=SettingsDefaults.portable()
            for(key in keys){
                val value=plan.restore[key]
                when {
                    value is Boolean && schema[key] is Boolean -> settings.putBoolean(key,value)
                    value is Int && schema[key] is Int -> settings.putInt(key,value)
                    else -> settings.remove(key) // Scoped absence continues to mean inheritance.
                }
            }
            check(settings.commit())
        }
        return plan.restore!=null
    }
    fun currentLabel(context:Context):String? {
        val db=store(context);val id=db.getString(ACTIVE,null) ?: return null
        return db.getString("label:$id",id)
    }
    fun count(context:Context)=store(context).all.keys.count {it.startsWith("profile:")}
    @Synchronized fun copyFrom(context:Context,prefs:SharedPreferences,identity:String):Boolean {
        val saved=store(context).getString(profileKey(identity),null)?.let(::JSONObject) ?: return false
        val schema=SettingsDefaults.portable();val editor=prefs.edit()
        for(key in keys){
            val value=saved.opt(key)
            when {
                value is Boolean && schema[key] is Boolean -> editor.putBoolean(key,value)
                value is Int && schema[key] is Int -> editor.putInt(key,value)
                else -> editor.remove(key)
            }
        }
        check(editor.commit());saveCurrent(context,prefs)
        return true
    }
}
