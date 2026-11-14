package com.cosmod.launcherglass.ui

internal enum class SettingsScreen(val tab:Int) {
    HOME(0), MODULE(1), APP(2), COLORS(1), BLUR(1), ICONS(1), FOLDERS(1),
    DOCK(1), WIDGETS(1), DESKTOP(1), WALLPAPERS(1), BEHAVIOR(1), DEVELOPER(2), POSITIONS(1), ANIMATIONS(1)
}

/** A real history: back from a category or another tab restores the previous screen. */
internal class SettingsNavigation(saved:IntArray?=null) {
    private val history=ArrayList<SettingsScreen>().apply {
        saved?.takeLast(32)?.mapNotNull {SettingsScreen.entries.getOrNull(it)}?.let(::addAll)
        if(isEmpty())add(SettingsScreen.HOME)
    }
    val current get()=history.last()
    val canGoBack get()=history.size>1
    fun open(screen:SettingsScreen):Boolean {
        if(current==screen)return false
        history.add(screen)
        if(history.size>32)history.removeAt(0)
        return true
    }
    fun back():Boolean {if(!canGoBack)return false;history.removeAt(history.lastIndex);return true}
    fun save()=history.map {it.ordinal}.toIntArray()
}
