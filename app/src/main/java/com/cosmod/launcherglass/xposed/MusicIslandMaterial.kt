package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Restyle the music card without reparenting OEM views or changing their binding hierarchy. */
internal object MusicIslandMaterial {
    data class Options(val blur:Boolean=false,val glow:Boolean=true,val outer:Int=8,val side:Int=0,val top:Int=8,val radius:Int=28)
    var options=Options();private set
    fun outerPixels(context:android.content.Context)=(options.outer.coerceIn(0,48)*context.resources.displayMetrics.density).toInt()
    fun radiusPixels(view:View)=(options.radius.coerceIn(0,48)*view.resources.displayMetrics.density)
        .coerceAtMost(minOf(view.measuredWidth,view.measuredHeight)/2f)
    private data class Saved(val background:Drawable?,val visibility:Int,val animation:Boolean?=null)
    private data class Glow(val source: View, val drawable: Drawable, val layer: LayerDrawable)
    private data class Placement(val width: Int, val height: Int, val top: Int, val start: Int, val end: Int)
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        constructor(view: View) : this(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

        fun apply(view: View, side: Int = 0, above: Int = 0) {
            if (view.paddingLeft != left + side || view.paddingRight != right + side ||
                view.paddingTop != top + above || view.paddingBottom != bottom
            ) {
                view.setPadding(left + side, top + above, right + side, bottom)
            }
        }
    }
    private class Card(val original:Drawable?,val fill:GradientDrawable) {
        val children=WeakHashMap<View,Saved>()
        val controls=WeakHashMap<View,Pair<Boolean?,Int?>>()
        val padding=WeakHashMap<View,Padding>()
        val placements=WeakHashMap<View,Placement>()
        val translations=WeakHashMap<View,Float>()
        var glow: Glow? = null
        var content:java.lang.ref.WeakReference<View>?=null
    }
    private val cards=WeakHashMap<View,Card>()
    private var enabled:()->Boolean={false}
    private var media:(View)->Boolean={false}

    fun install(loader:ClassLoader,card:Class<*>,active:()->Boolean,isMedia:(View)->Boolean){
        enabled=active;media=isMedia
        XposedBridge.hookAllMethods(card,"n",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                if(cards.containsKey(view)){stopHighlight(view);p.result=null}
            }
        })
        XposedBridge.hookAllMethods(card,"j",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){restore(p.thisObject as? View ?: return)}
        })
        XposedBridge.hookAllMethods(card,"onDetachedFromWindow",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){restore(p.thisObject as? View ?: return)}
        })
        XposedBridge.hookAllMethods(card,"onLayout",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){layout(p.thisObject as? View ?: return)}
        })
        val preDraw=XposedHelpers.findClassIfExists("com.oplus.systemui.plugins.seedling.card.ui.view.b",loader)
        val target=preDraw?.declaredFields?.singleOrNull {
            it.name=="c" && ViewGroup::class.java.isAssignableFrom(it.type)
        }?.apply {isAccessible=true}
        if(preDraw!=null && target!=null)XposedBridge.hookAllMethods(preDraw,"onPreDraw",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(options.blur)return
                val view=target.get(p.thisObject) as? View ?: return
                // This branch registers a render-thread callback solely to sync blur opacity.
                if(cards.containsKey(view))p.result=true
            }
        })
        val light=XposedHelpers.findClassIfExists("com.oplus.systemui.plugins.shared.template.component.light.f",loader)
        light?.declaredMethods?.singleOrNull {
            it.name=="c" && it.parameterTypes.contentEquals(arrayOf(View::class.java))
        }?.let { bind ->
            XposedBridge.hookMethod(bind,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val source=p.args[0] as? View ?: return
                    if(source.javaClass.simpleName!="MediaBackGroundView")return
                    val host=owner(source) ?: return
                    val state=cards[host] ?: return
                    if(options.glow)attachGlow(host,state,source)
                    else {
                        val saved=state.children[source] ?: Saved(source.background,source.visibility)
                        state.children[source]=saved.copy(background=source.background)
                        stopMediaGlow(source)
                    }
                }
            })
        }
        val bg=XposedHelpers.findClassIfExists("com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView",loader) ?: return
        XposedBridge.hookAllMethods(bg,"setBlurRadiusFollowAlpha",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                if(!options.blur && owner(view)!=null){stopBlur(view);p.result=null}
            }
        })
        XposedBridge.hookAllMethods(bg,"onAttachedToWindow",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                val host=owner(view) ?: return
                if(!options.blur){
                    cards[host]?.children?.let {saved->
                        saved[view]?.let {previous->if(view.background!=null)saved[view]=previous.copy(background=view.background)}
                    }
                    stopBlur(view);view.background=null;view.visibility=View.INVISIBLE
                }
            }
        })
    }

    private fun name(view:View)=try {view.resources.getResourceEntryName(view.id)}catch(_:Throwable){""}
    private fun find(view:View,id:String,depth:Int=0):View? {
        if(name(view)==id)return view
        if(depth<14 && view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i),id,depth+1)?.let {return it}
        return null
    }
    fun isTrackTitleHit(card: ViewGroup, x: Float, y: Float): Boolean {
        val content=cards[card]?.content?.get() ?: return false
        val title=find(content,"text_info_container") ?: return false
        if(!title.isShown)return false
        val bounds=Rect(0,0,title.width,title.height)
        card.offsetDescendantRectToMyCoords(title,bounds)
        return bounds.contains(x.toInt(),y.toInt())
    }
    private fun layout(card:View){
        val state=cards[card] ?: return
        val content=state.content?.get() ?: return
        val density=card.resources.displayMetrics.density
        val cutout=card.display?.cutout ?: card.rootWindowInsets?.displayCutout
        val camera=cutout?.boundingRects?.maxOfOrNull {it.bottom} ?: 0
        val extra=(options.top*density).toInt()
        val header=find(content,"header_container")
        val art=header?.let { find(it,"img_album_art") }
        val width=card.measuredWidth
        val compactHeader=header is RelativeLayout && art!=null && width>0
        val top=if(compactHeader)extra else (camera-outerPixels(card.context)-(16*density).toInt()).coerceAtLeast(0)+extra
        state.padding.getOrPut(card) { Padding(card) }.apply(card, above=top)
        if(header!=null)(header.parent as? LinearLayout)?.let {body->
            // The native header, progress row and controls already share the same margin.
            // Add the corner inset to their common parent so the columns stay aligned.
            val cornerInset=(radiusPixels(card)*(1f-.7071068f)).toInt()
            val inset=(cornerInset-(16*density).toInt()).coerceAtLeast(0)
            state.padding.getOrPut(body) { Padding(body) }.apply(
                body, side = (options.side*density).toInt()+inset
            )
            if(compactHeader && art!=null){
                val base=state.padding.getOrPut(header) { Padding(header) }
                val aboveArt=(width*.049f).toInt()-(8*density).toInt()
                base.apply(header,above=aboveArt-base.top)
                val size=(width*.20f).toInt().coerceAtMost((78*density).toInt())
                alignHeaderItems(card,header,art,size,state)
                find(header,"text_info_container")?.let {text->
                    val center=(size-text.measuredHeight)/2
                    val underCamera=camera-outerPixels(card.context)-top-aboveArt
                    place(state,text){it.copy(top=maxOf(it.top,center,underCamera),start=(width*.045f).toInt())}
                }
                alignProgressRow(body,header,state,density)
            }
        }
        val radius=radiusPixels(card)
        if(state.fill.cornerRadius!=radius)state.fill.cornerRadius=radius
    }

    private fun alignHeaderItems(card: View, header: View, art: View, artSize: Int, state: Card) {
        val container=card as? ViewGroup ?: return
        if(card.width<=0)return
        val bounds=Rect(0,0,header.width,header.height)
        container.offsetDescendantRectToMyCoords(header,bounds)
        val headerTop=bounds.top+header.paddingTop
        val left=bounds.left+header.paddingLeft
        val right=card.width-bounds.right+header.paddingRight
        val rtl=header.layoutDirection==View.LAYOUT_DIRECTION_RTL
        val start=if(rtl)right else left
        val end=if(rtl)left else right
        place(state,art){base->base.copy(width=artSize,height=artSize,start=headerTop+base.top-start)}
        for(id in listOf("img_top_right_icon","img_top_right_Immersive_icon")){
            val badge=find(header,id) ?: continue
            if(badge.visibility!=View.VISIBLE || badge.width<=0)continue
            val badgeRadius=minOf(badge.width,badge.height)*.24f
            place(state,badge){base->
                // The gap between concentric corner arcs is the difference of their radii.
                val inset=maxOf(end+base.end,(radiusPixels(card)-badgeRadius).toInt())
                base.copy(top=inset-headerTop,end=inset-end)
            }
        }
    }

    private fun alignProgressRow(body: LinearLayout, header: View, state: Card, density: Float) {
        val row=find(body,"seekbar_container") ?: return
        val controls=find(body,"layout_operate") ?: return
        if(row.parent!==body || controls.parent!==body || row.height<=0 ||
            header.bottom>row.top || controls.top<row.bottom)return
        val base=state.translations.getOrPut(row){row.translationY}
        val center=(header.bottom+controls.top-row.top-row.bottom)*.5f
        // Give the row a small optical offset towards the controls.
        val offset=(center+2*density).coerceIn((header.bottom-row.top).toFloat(),(controls.top-row.bottom).toFloat())
        row.translationY=base+offset
    }

    private fun place(state: Card, view: View, transform: (Placement)->Placement) {
        val params=view.layoutParams as? RelativeLayout.LayoutParams ?: return
        val current=Placement(params.width,params.height,params.topMargin,params.marginStart,params.marginEnd)
        val target=transform(state.placements.getOrPut(view){current})
        if(current==target)return
        params.width=target.width
        params.height=target.height
        params.topMargin=target.top
        params.marginStart=target.start
        params.marginEnd=target.end
        view.layoutParams=params
    }

    fun refresh(card:View,content:View?){
        if(!enabled() || content==null || !media(content)){restore(card);return}
        val state=cards.getOrPut(card){Card(card.background,GradientDrawable().apply {setColor(Color.BLACK)})}
        state.content=java.lang.ref.WeakReference(content)
        state.padding.getOrPut(card) { Padding(card) }
        if(state.glow==null)card.background=baseBackground(state)
        stopHighlight(card)
        fun visit(view:View,depth:Int){
            if(depth>14)return
            val type=view.javaClass.simpleName
            if(type=="MediaBackGroundView" && options.glow)attachGlow(card,state,view)
            if(type=="CardBackgroundView" && options.blur)try {XposedHelpers.callMethod(view,"setBlurRadiusFollowAlpha",card.alpha)}catch(_:Throwable){}
            if(!options.glow && type=="DirectionAwareSeekBar" && !state.controls.containsKey(view)){
                state.controls[view]=(try {XposedHelpers.getBooleanField(view,"p0")}catch(_:Throwable){null}) to
                    (try {XposedHelpers.callMethod(view,"getUseMaterialEffect") as? Int}catch(_:Throwable){null})
                try {XposedHelpers.callMethod(view,"setConicLightEnabled",false)}catch(_:Throwable){}
                try {XposedHelpers.callMethod(view,"setUseMaterialEffect",0)}catch(_:Throwable){}
            }
            if((type=="CardBackgroundView" && !options.blur) || type=="PageBackgroundView" || (!options.glow && type=="MediaBackGroundView")){
                if(!state.children.containsKey(view))state.children[view]=Saved(view.background,view.visibility,
                    if(type=="PageBackgroundView")try {XposedHelpers.getBooleanField(view,"c")}catch(_:Throwable){null}else null)
                when(type){
                    "CardBackgroundView"->stopBlur(view)
                    "PageBackgroundView"->try {XposedHelpers.callMethod(view,"setAnimationState",false)}catch(_:Throwable){}
                    "MediaBackGroundView"->stopMediaGlow(view)
                }
                view.background=null;view.visibility=View.INVISIBLE
            }
            if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i),depth+1)
        }
        visit(card,0);layout(card);card.invalidate()
    }

    fun configure(value:Options){
        options=value
        cards.keys.toList().forEach {card->
            val content=try {XposedHelpers.callMethod(card,"getContentView") as? View}catch(_:Throwable){null}
            restore(card);refresh(card,content)
        }
    }
    private fun owner(view:View):View? {
        var parent=view.parent
        while(parent is View){val next:View=parent;if(cards.containsKey(next))return next;parent=next.parent}
        return null
    }
    private fun stopHighlight(card:View){
        try {XposedHelpers.getObjectField(card,"f")?.let {XposedHelpers.callMethod(it,"e")};XposedHelpers.setObjectField(card,"f",null)}catch(_:Throwable){}
    }
    private fun stopBlur(view:View){
        try {XposedHelpers.getObjectField(view,"c")?.let {XposedHelpers.callMethod(it,"setBlurRadius",0)}}catch(_:Throwable){}
    }
    private fun baseBackground(state: Card): Drawable? = if(options.blur)state.original else state.fill

    private fun attachGlow(card: View, state: Card, source: View) {
        val drawable=source.background ?: return
        val native=try {XposedHelpers.getObjectField(source,"F")}catch(_:Throwable){null}
        if(drawable!==native)return
        releaseGlow(card,state)
        source.background=null
        val base=baseBackground(state)
        val layer=object : LayerDrawable(listOfNotNull(base,drawable).toTypedArray()) {
            // The glow's intrinsic size changes when its bounds change.
            override fun getMinimumWidth()=base?.minimumWidth ?: 0
            override fun getMinimumHeight()=base?.minimumHeight ?: 0

            override fun draw(canvas: Canvas) {
                base?.draw(canvas)
                val saved=canvas.save()
                val area=drawable.bounds
                canvas.clipRect(area.left,area.centerY(),area.right,area.bottom)
                drawable.draw(canvas)
                canvas.restoreToCount(saved)
            }
        }.apply {setPaddingMode(LayerDrawable.PADDING_MODE_STACK)}
        state.glow=Glow(source,drawable,layer)
        // The shader sees the card bounds, independent of the inset content and its old clip.
        card.background=layer
        layout(card)
    }

    private fun releaseGlow(card: View, state: Card) {
        val glow=state.glow ?: return
        state.glow=null
        if(card.background===glow.layer)card.background=null
        if(glow.source.background==null)glow.source.background=glow.drawable
    }

    private fun stopMediaGlow(view: View) {
        try {XposedHelpers.getObjectField(view,"F")?.let {XposedHelpers.callMethod(it,"i")}}catch(_:Throwable){}
        view.background=null
        view.visibility=View.INVISIBLE
    }

    private fun restore(card:View){
        val state=cards.remove(card) ?: return
        val managedBackground=card.background===state.fill || card.background===state.glow?.layer
        releaseGlow(card,state)
        if(managedBackground)card.background=state.original
        state.padding.forEach { (view, base) -> base.apply(view) }
        state.placements.forEach { (view, base) -> place(state,view){base} }
        state.translations.forEach { (view, base) -> view.translationY=base }
        state.children.entries.toList().forEach {(view,base)->
            view.background=base.background;view.visibility=base.visibility
            base.animation?.let {try {XposedHelpers.callMethod(view,"setAnimationState",it)}catch(_:Throwable){}}
            if(view.javaClass.simpleName=="MediaBackGroundView" && base.visibility==View.VISIBLE)try {
                XposedHelpers.getObjectField(view,"F")?.let {XposedHelpers.callMethod(it,"k")}
            }catch(_:Throwable){}
        }
        state.controls.entries.toList().forEach {(view,base)->
            base.first?.let {try {XposedHelpers.callMethod(view,"setConicLightEnabled",it)}catch(_:Throwable){}}
            base.second?.let {try {XposedHelpers.callMethod(view,"setUseMaterialEffect",it)}catch(_:Throwable){}}
        }
        try {XposedHelpers.callMethod(card,"n")}catch(_:Throwable){}
        card.invalidate()
    }
}
