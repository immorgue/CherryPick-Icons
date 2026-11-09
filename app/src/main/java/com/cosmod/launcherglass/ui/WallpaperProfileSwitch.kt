package com.cosmod.launcherglass.ui

/** Snapshot the departing appearance before selecting the arriving one. */
internal object WallpaperProfileSwitch {
    data class Plan(val savePrevious:Map<String,Any>?,val restore:Map<String,Any>?,val saveNew:Map<String,Any>?)
    fun plan(previous:String?,next:String,enabled:Boolean,current:Map<String,Any>,saved:Map<String,Any>?):Plan {
        if(!enabled || previous==next)return Plan(null,null,null)
        return Plan(if(previous!=null)current.toMap()else null,saved?.toMap(),if(saved==null)current.toMap()else null)
    }
}
