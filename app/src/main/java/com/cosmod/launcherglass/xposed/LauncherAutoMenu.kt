package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.bridge.*
import com.cosmod.launcherglass.ui.ColorBand
import com.cosmod.launcherglass.ui.FloatingNavigationPill
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.UiLiquidGlass
import com.cosmod.launcherglass.util.AutoIconStyle as Auto
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.GlassVolume
import com.cosmod.launcherglass.util.WallpaperHelper
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** A native edit-toolbar entry. The launcher can change automatic appearance only. */
internal object LauncherAutoMenu {
    private val entries=WeakHashMap<ViewGroup,Entry>()
    private val main=Handler(Looper.getMainLooper())
    private class Entry(var theme:Int,var popup:Panel?=null)
    private fun activity(context:Context):Activity? {
        var value=context
        repeat(12){
            if(value is Activity)return value as Activity
            value=(value as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
    private fun strings(context:Context)=context.createPackageContext("com.cosmod.launcherglass",Context.CONTEXT_IGNORE_SECURITY)
    private fun dark(context:Context)=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    private fun ink(context:Context)=if(dark(context))0xfff5f5f7.toInt()else 0xff19191c.toInt()
    private fun dp(context:Context,value:Int)=(context.resources.displayMetrics.density*value).roundToInt()
    private fun lookup(parent:ViewGroup,name:String):View? {
        val id=parent.resources.getIdentifier(name,"id",parent.context.packageName)
        return if(id==0)null else parent.findViewById(id)
    }
    fun install(loader:ClassLoader){
        val secondary=HookReflection.findClassIfExists("com.android.launcher.togglebar.views.ToggleToolbarSecondaryContainer",loader)
        val type=secondary ?: HookReflection.findClassIfExists(
            "com.android.launcher.togglebar.views.ToggleStateToolbar",loader) ?: return
        ModuleHooks.hookAllMethods(type,"onFinishInflate",object:HookCallback(){
            override fun afterHookedMethod(param:MethodHookParam){
                val toolbar=param.thisObject as? ViewGroup ?: return
                // Older launchers use an ordinary ConstraintLayout for the edit buttons.
                val parent=if(secondary!=null) toolbar else lookup(toolbar,"apply")?.parent as? ViewGroup ?: return
                runCatching {attach(parent)}.onFailure {GlassLogger.e("AutoMenu","Cannot attach edit toolbar entry",it)}
            }
        })
    }
    /** Reuse the installed launcher's XML attributes, native optics and touch feedback. */
    private fun nativeButton(template:TextView):TextView {
        val context=template.context
        val declared=runCatching {HookReflection.callMethod(template,"getSourceLayoutResId") as? Int}.getOrNull() ?: 0
        val layout=declared.takeIf {it!=0} ?: context.resources.getIdentifier("toggle_state_tool_bar","layout",context.packageName)
        if(layout!=0)context.resources.getLayout(layout).use {parser ->
            var event=parser.eventType
            while(event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                if(event==org.xmlpull.v1.XmlPullParser.START_TAG &&
                    parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android","id",0)==template.id){
                    return HookReflection.newInstance(template.javaClass,context,android.util.Xml.asAttributeSet(parser)) as TextView
                }
                event=parser.next()
            }
        }
        return HookReflection.newInstance(template.javaClass,context) as TextView
    }
    private fun attach(parent:ViewGroup){
        if(entries.containsKey(parent))return
        val context=parent.context
        val done=lookup(parent,"apply") as? TextView ?: return
        val group=lookup(parent,"back_to_main_page") as? TextView ?: return
        if(done.parent!==parent || group.parent!==parent)return
        val host=activity(context) ?: return
        val source=Backdrop(host)
        val button=nativeButton(done).apply {
            id=View.generateViewId()
            visibility=View.INVISIBLE
            alpha=0f
            text=strings(context).getString(R.string.ui_auto_launcher_customize)
            gravity=Gravity.CENTER;typeface=done.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_PX,done.textSize)
            setTextColor(done.textColors);includeFontPadding=done.includeFontPadding
            setPadding(done.paddingLeft,done.paddingTop,done.paddingRight,done.paddingBottom)
        }
        button.contentDescription=button.text
        button.isFocusable=true;button.isClickable=true
        // OEM builds rename ConstraintLayout's nested classes and constraint fields.
        // Reuse the native parameter type; position in the existing toolbar's gap.
        val paramsType=done.layoutParams.javaClass
        val params=HookReflection.newInstance(paramsType,dp(context,100),dp(context,34)) as ViewGroup.LayoutParams
        parent.addView(button,params)
        val entry=Entry(context.resources.configuration.uiMode)
        entries[parent]=entry
        val secondaryTitle=lookup(parent,"secondary_title_container")
        val blocksDraw=runCatching {done.javaClass.getMethod("getForceBlockDraw")}.getOrNull()
        var placing=false
        fun place(){
            if(placing)return
            placing=true
            try {
            val gap=(done.left-group.right-dp(context,24)).coerceAtLeast(0)
            if(gap<=0 || done.height<=0)return
            val width=maxOf(done.width,(button.paint.measureText(button.text.toString())+done.compoundPaddingLeft+done.compoundPaddingRight).roundToInt()).coerceAtMost(gap)
            val left=(group.right+done.left-width)/2
            button.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(done.height,View.MeasureSpec.EXACTLY))
            if(button.left!=left || button.top!=done.top || button.right!=left+width || button.bottom!=done.bottom)
                button.layout(left,done.top,left+width,done.bottom)
            source.resize()
            }finally{placing=false}
        }
        parent.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ ->place()}
        button.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ ->place()}
        parent.viewTreeObserver.addOnPreDrawListener {
            val visible=PrefsHelper.enabled && done.isShown && group.isShown && done.height>0 &&
                (secondaryTitle==null || secondaryTitle.visibility!=View.VISIBLE || secondaryTitle.alpha<.05f)
            val nativeAlpha=if(visible && runCatching {blocksDraw?.invoke(done)!=true}.getOrDefault(false))
                done.alpha*done.transitionAlpha else 0f
            // The common toolbar already applies the stock parent fade/translation.
            // Follow Done's own fade too, including frames before it becomes INVISIBLE.
            val visibility=if(visible)View.VISIBLE else View.INVISIBLE
            if(button.visibility!=visibility)button.visibility=visibility
            val alpha=nativeAlpha*if(PrefsHelper.iconPalette.autoLook>=0)1f else .5f
            if(button.alpha!=alpha)button.alpha=alpha
            if(button.translationY!=done.translationY)button.translationY=done.translationY
            button.isClickable=nativeAlpha>.05f
            button.isFocusable=button.isClickable
            if(nativeAlpha<=0f)entry.popup?.dismiss()
            val theme=context.resources.configuration.uiMode
            if(entry.theme!=theme){
                entry.theme=theme;entry.popup?.dismiss()
            }
            true
        }
        button.setOnClickListener { anchor ->
            if(!anchor.isClickable || anchor.alpha<=0f || !anchor.isShown || anchor.windowToken==null ||
                host.isFinishing || host.isDestroyed || entry.popup?.isShowing==true)return@setOnClickListener
            anchor.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            runCatching {
                val panel=Panel(host,source)
                entry.popup=panel
                panel.show(anchor)
            }.onFailure {error ->
                entry.popup?.dismissImmediately();entry.popup=null
                if(error is WindowManager.BadTokenException &&
                    (anchor.windowToken==null || !anchor.isShown || host.isFinishing || host.isDestroyed))return@onFailure
                GlassLogger.e("AutoMenu","Cannot show appearance menu",error)
                runCatching {Toast.makeText(context,strings(context).getString(R.string.ui_auto_launcher_open_failed),Toast.LENGTH_LONG).show()}
            }
        }
        parent.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){}
            override fun onViewDetachedFromWindow(view:View){entry.popup?.dismissImmediately();entry.popup=null}
        })
        parent.post {place()}
    }

    /** Wallpaper is a separate system surface; include it beneath the launcher drawing. */
    private class Backdrop(val activity:Activity):View(activity){
        private val root get()=activity.window.decorView
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val transform=Matrix()
        private val scene=RenderNode("CherryPick.editScene")
        private var dirty=true
        private val layers=listOf("workspace","hotseat","page_indicator","toggle_bar_root_top")
        fun resize(){
            val w=root.width.coerceAtLeast(1);val h=root.height.coerceAtLeast(1)
            if(width!=w || height!=h){
                measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
                layout(0,0,w,h)
            }
            dirty=true;invalidate()
        }
        override fun onDraw(canvas:Canvas){
            if(!canvas.isHardwareAccelerated){drawScene(canvas);return}
            if(dirty || !scene.hasDisplayList()){
                scene.setPosition(0,0,width,height)
                val recording=scene.beginRecording(width,height)
                try {drawScene(recording)}finally{scene.endRecording()}
                dirty=false
            }
            canvas.drawRenderNode(scene)
        }
        fun release(){scene.discardDisplayList();dirty=true}
        private fun drawScene(canvas:Canvas){
            val image=WallpaperHelper.getBlurredWallpaper(activity)
            if(image!=null && !image.isRecycled){
                val bitmap=WallpaperHelper.forCanvas(image,canvas)
                canvas.drawBitmap(bitmap,null,Rect(0,0,width,height),paint)
            }else canvas.drawColor(if(dark(context))0xff292b30.toInt()else 0xffd6d8dc.toInt())
            // Explicitly compose siblings below the sheet. Never record the decor
            // or toolbar, which can contain the surface we are painting.
            for(name in layers){
                val id=resources.getIdentifier(name,"id",activity.packageName)
                val view=if(id==0)null else root.findViewById<View>(id)
                if(view==null || !view.isShown || view.alpha<=0f)continue
                val save=canvas.save()
                try {
                    transform.reset();view.transformMatrixToGlobal(transform);canvas.concat(transform)
                    if(view.alpha<1f)canvas.saveLayerAlpha(0f,0f,view.width.toFloat(),view.height.toFloat(),(view.alpha*255).roundToInt())
                    view.draw(canvas)
                }catch(error:RuntimeException){GlassLogger.e("AutoMenu","Unable to sample editor layer $name",error)}
                finally{canvas.restoreToCount(save)}
            }
        }
    }

    /** Animate the shell without reallocating its optics on every frame. */
    private class MorphCard(context:Context,private val glass:View,private val radius:Float):FrameLayout(context){
        var visibleHeight=0f
            set(value){field=value;positionChildren();invalidate()}
        var offsetY:Float
            get()=translationY
            set(value){translationY=value}
        private val outline=Path()
        private val shape=RectF()
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
            super.onLayout(changed,l,t,r,b);positionChildren()
        }
        private fun positionChildren(){
            if(glass.height>0){glass.pivotY=glass.height.toFloat();glass.scaleY=visibleHeight/glass.height}
            getChildAt(1)?.let {it.translationY=it.height-visibleHeight}
            // Keep the handle at the visible edge while content scrolls below it.
            getChildAt(2)?.translationY=height-visibleHeight
        }
        override fun dispatchDraw(canvas:Canvas){
            if(visibleHeight<=0f)return
            shape.set(0f,height-visibleHeight,width.toFloat(),height.toFloat())
            com.cosmod.launcherglass.util.ContinuousCorners.path(outline,shape,radius)
            super.dispatchDraw(canvas)
        }
        override fun drawChild(canvas:Canvas,child:View,drawingTime:Long):Boolean {
            val save=canvas.save()
            if(child!==glass)canvas.clipPath(outline)
            val result=super.drawChild(canvas,child,drawingTime)
            canvas.restoreToCount(save)
            return result
        }
        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            if(!isEnabled)return true
            if(event.actionMasked==MotionEvent.ACTION_DOWN && event.y<height-visibleHeight)return false
            return super.dispatchTouchEvent(event)
        }
    }

    private class Panel(private val activity:Activity,private val source:Backdrop):PopupWindow(activity){
        private val labels=strings(activity)
        private fun s(id:Int)=labels.getString(id)
        private fun d(n:Int)=dp(activity,n)
        private val inkColor=ink(activity)
        private val muted=if(dark(activity))0xffc7c7cc.toInt()else 0xff636366.toInt()
        private val glass=UiLiquidGlass(activity,{source},32f,trackSourceContent=false,
            initialSurface=UiLiquidGlass.Surface.LIQUID_SHEET,refract=false)
        private val materialButton=MaterialButton()
        private val tintPicker=headerButton().apply {
            setImageDrawable(labels.getDrawable(R.drawable.ic_tint_eyedropper))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                choose(Auto.LOOK to Auto.TINTED,Auto.SOURCE to if(value(Auto.LOOK)==Auto.TINTED && value(Auto.SOURCE)==1)0 else 1)
            }
        }
        private val hazeButton=headerButton().apply {
            setImageDrawable(labels.getDrawable(R.drawable.ic_glass_haze))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                applyChoices(Bundle().apply {
                    putBoolean(SettingsProvider.KEY_HAZE_ENABLED,!settings.getBoolean(SettingsProvider.KEY_HAZE_ENABLED))
                })
            }
        }
        private val panel=MorphCard(activity,glass,d(32).toFloat())
        private val handle=SheetHandle()
        private val root=FrameLayout(activity).apply {isForceDarkAllowed=false;setOnClickListener {dismiss()}}
        private val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(d(20),d(20),d(20),d(20))}
        private val controls=object:LinearLayout(activity){
            override fun onInterceptTouchEvent(event:MotionEvent)=!isEnabled || super.onInterceptTouchEvent(event)
            override fun onTouchEvent(event:MotionEvent)=!isEnabled || super.onTouchEvent(event)
        }.apply {orientation=LinearLayout.VERTICAL}
        private val warning=TextView(activity).apply {textSize=14f;setTextColor(inkColor);setPadding(d(4),d(8),d(4),d(12))}
        private var settings=AutoMenuBridge.snapshot()
        private var available=AutoMenuBridge.hasSettings
        private var received=AutoMenuBridge.hasRead
        private var closing=false
        private var disposed=false
        private var ready=false
        private var morph:ValueAnimator?=null
        private var travel:ValueAnimator?=null
        private var observer:ContentObserver?=null
        private var appearanceTransition:LauncherAppearanceTransition?=null
        private val sample by lazy {com.cosmod.launcherglass.ui.AppearanceIcons.app(labels)}
        private val modeTiles=ArrayList<ModeTile>()
        private val bands=ArrayList<ColorBand>()
        private data class Choice(val key:String,val values:List<Int>,val pill:FloatingNavigationPill)
        private val strips=ArrayList<Choice>()
        private val sections=ArrayList<Section>()
        private lateinit var statusSection:Section
        private lateinit var bandSection:Section
        private lateinit var themeSection:Section
        private var baseHeight=0
        private val gutter=d(16)
        private var bottomGap=gutter
        private var toolbarBottom=0f
        private var restingTop:Float?=null
        private val screenBounds=activity.windowManager.currentWindowMetrics.bounds
        private val panelWidth=minOf(screenBounds.width()-2*gutter,d(430)).coerceAtLeast(1)
        private val panelHeight=(screenBounds.height()*.65f).roundToInt()
        private val windowPosition=IntArray(2)
        private val refreshGlass=Runnable { if(!closing && !disposed){source.resize();glass.invalidateBackdrop()} }
        private val changed:(Bundle,Boolean)->Unit={next,ok ->
            if(!closing && !disposed){
                val read=AutoMenuBridge.hasRead
                val needsUpdate=available!=ok || (!ok && received!=read) || Auto.defaults.keys.any {next.get(it)!=settings.get(it)} ||
                    next.getBoolean(SettingsProvider.KEY_HAZE_ENABLED)!=settings.getBoolean(SettingsProvider.KEY_HAZE_ENABLED) ||
                    next.get(SettingsProvider.KEY_NATIVE_ICON_DARK)!=settings.get(SettingsProvider.KEY_NATIVE_ICON_DARK)
                settings=next;available=ok;received=read
                if(needsUpdate)update(ready)
            }
        }
        private fun value(key:String)=settings.getInt(key,Auto.defaults.getValue(key))
        private fun text(label:String,size:Float=14f)=TextView(activity).apply {
            text=label;textSize=size;setTextColor(inkColor);includeFontPadding=false
        }
        private fun row()=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        private fun enabled()=available && value(Auto.ENGINE)==1
        private fun choice(key:String,options:List<Pair<Int,Int>>):FloatingNavigationPill {
            val pill=FloatingNavigationPill(activity,options.map {s(it.second)},null,true,{glass},inkColor,
                trackBackdrop=false,compactHeight=36,refract=false){index ->
                choose(key to options[index].first)
            }
            strips.add(Choice(key,options.map {it.first},pill))
            return pill
        }
        private fun section(parent:LinearLayout,view:View,top:Int=0):Section {
            val padded=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(0,d(top),0,0);addView(view,LinearLayout.LayoutParams(-1,-2))}
            return Section(padded).also {sections.add(it);parent.addView(it,LinearLayout.LayoutParams(-1,0))}
        }
        init {
            width=ViewGroup.LayoutParams.MATCH_PARENT
            height=ViewGroup.LayoutParams.MATCH_PARENT
            isFocusable=true;setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setIsLaidOutInScreen(true);isAttachedInDecor=false;isClippingEnabled=false
            animationStyle=0;inputMethodMode=INPUT_METHOD_NOT_NEEDED
            panel.isClickable=true
            panel.addView(glass,FrameLayout.LayoutParams(-1,1,Gravity.BOTTOM))
            panel.addView(ScrollView(activity).apply {
                isFillViewport=false;isVerticalScrollBarEnabled=false;addView(body)
            },FrameLayout.LayoutParams(-1,-1))
            panel.addView(handle,FrameLayout.LayoutParams(d(100),body.paddingTop,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            root.addView(panel,FrameLayout.LayoutParams(panelWidth,panelHeight,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL));contentView=root
            build()
            setOnDismissListener {dispose()}
        }
        private fun build(){
            val header=row()
            header.addView(text(s(R.string.ui_auto_launcher_customize),20f).apply {
                typeface=Typeface.create("sans-serif",Typeface.BOLD)
                gravity=Gravity.CENTER_VERTICAL
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
                setAutoSizeTextTypeUniformWithConfiguration(12,20,1,TypedValue.COMPLEX_UNIT_SP)
                isAccessibilityHeading=true
            },LinearLayout.LayoutParams(0,d(44),1f))
            listOf(materialButton.button,tintPicker,hazeButton).forEachIndexed {index,button ->
                header.addView(button,LinearLayout.LayoutParams(d(44),d(44)).apply {marginStart=d(if(index==0)8 else 2)})
            }
            body.addView(header)
            statusSection=section(body,warning)
            val modes=row().apply {gravity=Gravity.TOP}
            for((look,title)in listOf(Auto.DEFAULT to R.string.ui_auto_icons_default,Auto.DARK to R.string.ui_auto_icons_dark,
                Auto.CLEAR to R.string.ui_auto_icons_clear,Auto.TINTED to R.string.ui_auto_icons_tinted)){
                modes.addView(ModeTile(look,s(title)).also {modeTiles.add(it)},LinearLayout.LayoutParams(0,-2,1f))
            }
            controls.addView(modes,LinearLayout.LayoutParams(-1,-2).apply {topMargin=d(8)})
            val colorRows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
            for((component,key,title)in listOf(Triple(0,Auto.HUE,R.string.ui_auto_icons_hue),Triple(1,Auto.SATURATION,R.string.ui_auto_icons_saturation))){
                val band=ColorBand(activity,component,compact=true,insetDp=0f).apply {
                    contentDescription=s(title)
                    onChange={next,finished ->
                        if(enabled()){
                            bands.forEach {it.setColor(if(component==0)next else value(Auto.HUE),
                                if(component==1)next else value(Auto.SATURATION),85)}
                            if(finished)choose(key to next)
                        }
                    }
                }
                bands.add(band);colorRows.addView(band,LinearLayout.LayoutParams(-1,-2).apply {topMargin=d(4)})
            }
            bandSection=section(controls,colorRows)
            val themePill=choice(Auto.THEME,listOf(1 to R.string.ui_icons_light,2 to R.string.ui_icons_dark,0 to R.string.ui_auto_icons_auto))
            val themeTrack=row().apply {
                gravity=Gravity.CENTER
                addView(themePill,LinearLayout.LayoutParams((panelWidth-d(40)).coerceIn(1,d(204)),-2))
            }
            themeSection=section(controls,themeTrack,6)
            body.addView(controls,LinearLayout.LayoutParams(-1,-2))
            // Measure optional content once; animation only changes section clips and spacing.
            val inner=panelWidth-d(40)
            sections.forEach {it.prepare(inner)}
            body.measure(View.MeasureSpec.makeMeasureSpec(panelWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            baseHeight=body.measuredHeight
            update(false)
        }
        fun show(anchor:View){
            if(anchor.windowToken==null || activity.isFinishing || activity.isDestroyed)return
            anchor.getLocationOnScreen(windowPosition)
            toolbarBottom=(windowPosition[1]+anchor.height+d(8)).toFloat()
            source.resize()
            panel.alpha=.001f;panel.offsetY=0f
            panel.setLayerType(View.LAYER_TYPE_HARDWARE,null)
            val insets=activity.window.decorView.rootWindowInsets
            val bottom=insets?.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())?.bottom ?: 0
            bottomGap=maxOf(gutter,bottom)
            panel.layoutParams=(panel.layoutParams as FrameLayout.LayoutParams).apply {bottomMargin=bottomGap}
            root.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ ->alignToBottom()}
            showAtLocation(anchor,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,0,0)
            // PopupWindow fits the nav inset by default, even with LAYOUT_IN_SCREEN.
            // Let the window reach the physical edge and apply the safe padding to the card once.
            val window=root.rootView
            (window.layoutParams as? WindowManager.LayoutParams)?.let {params ->
                runCatching {params.setFitInsetsTypes(0);activity.windowManager.updateViewLayout(window,params)}
            }
            ready=true
            AutoMenuBridge.subscribe(activity,changed)
            appearanceTransition=LauncherAppearanceTransition(activity) {
                // The wallpaper below the sheet stays stable while the desktop changes.
                main.removeCallbacks(refreshGlass)
                main.postDelayed(refreshGlass,300)
            }.also { AutoMenuBridge.transition=it }
            IconGlassHook.invalidateActiveIcons()
            observer=object:ContentObserver(main){override fun onChange(selfChange:Boolean){AutoMenuBridge.refresh()}}
                .also {runCatching {activity.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI,true,it)}}
            // Warm the unchanged blur/shaders before the visible translation starts.
            var started=false
            val start=Runnable {if(!started && !closing && !disposed){started=true;move(panel.visibleHeight+bottomGap,0f,420,startAlpha=.55f)}}
            panel.post {
                runCatching {panel.viewTreeObserver.registerFrameCommitCallback {main.post(start)}}
                panel.postDelayed(start,100)
                panel.invalidate()
            }
        }
        private fun alignToBottom(){
            root.getLocationOnScreen(windowPosition)
            val margin=(windowPosition[1]+root.height-(screenBounds.bottom-bottomGap)).coerceAtLeast(0)
            val layout=panel.layoutParams as FrameLayout.LayoutParams
            if(layout.bottomMargin!=margin){layout.bottomMargin=margin;panel.layoutParams=layout}
        }
        private fun bottomPositionTop()=screenBounds.bottom-bottomGap-panel.visibleHeight
        private fun highestOffset()=(toolbarBottom-bottomPositionTop()).coerceAtMost(0f)
        private fun restingOffset()=restingTop?.let {(it-bottomPositionTop()).coerceIn(highestOffset(),0f)} ?: 0f
        private fun setVisibleHeight(height:Float){
            panel.visibleHeight=height
            if(!closing && !handle.isPressed && travel?.isRunning!=true)panel.offsetY=restingOffset()
        }
        private fun dispose(){
            if(disposed)return
            disposed=true;AutoMenuBridge.unsubscribe(changed)
            handle.stopFollowing()
            main.removeCallbacks(refreshGlass)
            appearanceTransition?.let {
                if(AutoMenuBridge.transition===it)AutoMenuBridge.transition=null
                it.close()
            }
            observer?.let {runCatching {activity.contentResolver.unregisterContentObserver(it)}};observer=null
            morph?.cancel();travel?.cancel()
            modeTiles.forEach {it.stop()};source.release()
        }
        fun dismissImmediately(){
            if(disposed)return
            closing=true;dispose()
            if(isShowing)super.dismiss()
        }
        override fun dismiss(){
            if(!isShowing || closing)return
            closing=true;panel.isEnabled=false;morph?.cancel()
            handle.stopFollowing()
            panel.setLayerType(View.LAYER_TYPE_HARDWARE,null)
            panel.postOnAnimation {if(!disposed)move(panel.offsetY,panel.visibleHeight+bottomGap,400,endAlpha=0f){superDismiss()}}
        }
        private fun superDismiss(){super.dismiss()}
        private fun move(from:Float,to:Float,duration:Long,
                         startAlpha:Float=panel.alpha,endAlpha:Float=1f,finished:(()->Unit)?=null){
            travel?.cancel()
            if(!ValueAnimator.areAnimatorsEnabled()){
                panel.offsetY=to;panel.alpha=endAlpha;panel.setLayerType(View.LAYER_TYPE_NONE,null);finished?.invoke();return
            }
            val fadingOut=endAlpha==0f
            travel=ValueAnimator.ofFloat(0f,1f).apply {
                this.duration=duration
                interpolator=if(fadingOut)android.view.animation.AccelerateDecelerateInterpolator()
                    else android.view.animation.PathInterpolator(.22f,.8f,.25f,1f)
                addUpdateListener {
                    val t=it.animatedValue as Float
                    panel.offsetY=from+(to-from)*t
                    val fade=if(fadingOut)((t-.25f)/.75f).coerceIn(0f,1f).let {x ->x*x*(3f-2f*x)} else t
                    panel.alpha=startAlpha+(endAlpha-startAlpha)*fade
                }
                addListener(object:android.animation.AnimatorListenerAdapter(){
                    private var cancelled=false
                    override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                    override fun onAnimationEnd(animation:android.animation.Animator){
                        if(!cancelled){
                            if(!closing)panel.offsetY=restingOffset()
                            panel.setLayerType(View.LAYER_TYPE_NONE,null)
                            finished?.invoke()
                        }
                    }
                });start()
            }
        }
        private inner class SheetHandle:View(activity){
            private val slop=ViewConfiguration.get(activity).scaledTouchSlop
            private var downX=0f
            private var downY=0f
            private var startOffset=0f
            private var dragging=false
            private var targetOffset=0f
            private var previousFrame=0L
            private val choreographer=Choreographer.getInstance()
            private val followFrame=Choreographer.FrameCallback(::advance)
            init {
                val stripe=GradientDrawable().apply {
                    cornerRadius=d(2).toFloat()
                    val color=inkColor and 0xffffff
                    setColor(android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_pressed),intArrayOf()),
                        intArrayOf(color or 0x80000000.toInt(),color or 0x52000000)))
                }
                background=InsetDrawable(stripe,d(32),d(8),d(32),d(8))
                contentDescription=s(R.string.ui_close)
                isFocusable=true
                setOnClickListener {dismiss()}
            }
            private fun offsetFor(rawY:Float):Float {
                val distance=rawY-downY
                val delta=distance-distance.coerceIn(-slop.toFloat(),slop.toFloat())
                return (startOffset+delta).coerceIn(highestOffset(),panel.visibleHeight+bottomGap)
            }
            private fun follow(rawY:Float) {
                targetOffset=offsetFor(rawY)
                if(!ValueAnimator.areAnimatorsEnabled()){
                    panel.offsetY=targetOffset
                }else if(previousFrame==0L){
                    previousFrame=System.nanoTime()
                    choreographer.postFrameCallback(followFrame)
                }
            }
            private fun advance(frameTime:Long){
                val elapsed=(frameTime-previousFrame).coerceIn(1_000_000L,32_000_000L)
                previousFrame=frameTime
                val target=targetOffset.coerceIn(highestOffset(),panel.visibleHeight+bottomGap)
                val remaining=target-panel.offsetY
                if(abs(remaining)<=.5f){
                    panel.offsetY=target
                    previousFrame=0L
                }else{
                    val fraction=1f-exp(-elapsed/38_000_000f)
                    panel.offsetY=(panel.offsetY+remaining*fraction).coerceAtLeast(highestOffset())
                    choreographer.postFrameCallback(followFrame)
                }
            }
            fun stopFollowing(){
                if(previousFrame!=0L)choreographer.removeFrameCallback(followFrame)
                previousFrame=0L
            }
            override fun onTouchEvent(event:MotionEvent):Boolean {
                if(closing || disposed)return false
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN -> {
                        if(panel.alpha<.99f)return false
                        stopFollowing()
                        travel?.cancel()
                        panel.alpha=1f
                        downX=event.rawX
                        downY=event.rawY
                        startOffset=panel.offsetY
                        dragging=false
                        isPressed=true
                    }
                    MotionEvent.ACTION_MOVE -> if(isPressed){
                        val distance=event.rawY-downY
                        if(!dragging && abs(distance)>maxOf(slop.toFloat(),abs(event.rawX-downX))){
                            dragging=true
                            panel.setLayerType(View.LAYER_TYPE_HARDWARE,null)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if(dragging)follow(event.rawY)
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN -> if(isPressed){
                        isPressed=false
                        stopFollowing()
                        if(event.actionMasked==MotionEvent.ACTION_UP){
                            if(dragging) {
                                val releasedOffset=offsetFor(event.rawY)
                                if(releasedOffset>=minOf(d(64).toFloat(),panel.visibleHeight*.25f)){
                                    dismiss()
                                    return true
                                }
                                restingTop=releasedOffset.takeIf {it<0f}?.let {bottomPositionTop()+it}
                            }else if(abs(event.rawY-downY)<=slop && abs(event.rawX-downX)<=slop){
                                performClick()
                                return true
                            }
                        }
                        val target=restingOffset()
                        if(panel.offsetY!=target)move(panel.offsetY,target,200)
                        else panel.setLayerType(View.LAYER_TYPE_NONE,null)
                    }
                }
                return true
            }
        }
        private fun choose(vararg values:Pair<String,Int>){
            applyChoices(Bundle().apply {
                for((key,next) in values)if(value(key)!=next)putInt(key,next)
            })
        }
        private fun applyChoices(changes:Bundle){
            if(closing || disposed || !enabled() || changes.isEmpty)return
            settings.putAll(changes)
            AutoMenuBridge.choose(changes)
            update(true)
        }
        private fun update(animate:Boolean){
            if(closing || disposed)return
            val canEdit=enabled()
            val message=when {
                received && !available ->s(R.string.ui_auto_launcher_unavailable)
                available && value(Auto.ENGINE)!=1 ->s(R.string.ui_auto_launcher_custom_warning)
                else ->""
            }
            warning.text=message
            statusSection.prepare(panelWidth-d(40))
            val tint=value(Auto.LOOK)==Auto.TINTED
            val thematic=tint || value(Auto.LOOK)==Auto.CLEAR
            val targets=sections.map {section ->
                if(when(section){
                    statusSection ->message.isNotEmpty()
                    bandSection ->tint && value(Auto.SOURCE)==1
                    themeSection ->thematic
                    else ->false
                })section.naturalHeight.toFloat()else 0f
            }
            fun enable(view:View){view.isEnabled=canEdit;if(view is ViewGroup)for(i in 0 until view.childCount)enable(view.getChildAt(i))}
            enable(controls);controls.alpha=if(canEdit)1f else .32f
            modeTiles.forEach {it.bind(animate)}
            strips.forEach {choice ->
                choice.pill.isEnabled=canEdit
                choice.pill.select(choice.values.indexOf(value(choice.key)).coerceAtLeast(0),animate)
            }
            materialButton.bind(value(Auto.MATERIAL),canEdit)
            tintPicker.isEnabled=canEdit
            tintPicker.isSelected=tint && value(Auto.SOURCE)==1
            tintPicker.alpha=if(canEdit)1f else .4f
            tintPicker.contentDescription=s(if(tintPicker.isSelected)R.string.ui_auto_icons_wallpaper else R.string.ui_auto_icons_manual)
            val supportsHaze=android.os.Build.VERSION.SDK_INT>=33
            hazeButton.isEnabled=canEdit && supportsHaze
            hazeButton.alpha=if(hazeButton.isEnabled)1f else .4f
            hazeButton.isSelected=supportsHaze && settings.getBoolean(SettingsProvider.KEY_HAZE_ENABLED)
            hazeButton.contentDescription=s(if(!supportsHaze)R.string.ui_auto_launcher_haze_unavailable
                else if(hazeButton.isSelected)R.string.ui_auto_launcher_haze_off else R.string.ui_auto_launcher_haze_on)
            bands.forEach {it.setColor(value(Auto.HUE),value(Auto.SATURATION),85)}
            if(sections.indices.all {kotlin.math.abs(sections[it].shown-targets[it])<.5f} && morph?.isRunning!=true){
                setVisibleHeight(minOf(panelHeight.toFloat(),baseHeight+sections.sumOf {it.shown.toDouble()}.toFloat()))
                fitGlass(panel.visibleHeight.roundToInt())
                return
            }
            morph?.cancel()
            val from=sections.map {it.shown}
            fitGlass(minOf(panelHeight,baseHeight+targets.sum().roundToInt()))
            val duration=if(animate && ready && ValueAnimator.areAnimatorsEnabled())280L else 0L
            fun apply(t:Float){
                sections.forEachIndexed {i,section ->section.reveal(from[i]+(targets[i]-from[i])*t)}
                setVisibleHeight(minOf(panelHeight.toFloat(),baseHeight+sections.sumOf {it.shown.toDouble()}.toFloat()))
            }
            if(duration==0L)apply(1f)else {
                morph=ValueAnimator.ofFloat(0f,1f).apply {
                    this.duration=duration;interpolator=android.view.animation.PathInterpolator(.2f,0f,.2f,1f)
                    addUpdateListener {apply(it.animatedValue as Float)}
                    addListener(object:android.animation.AnimatorListenerAdapter(){
                        override fun onAnimationEnd(animation:android.animation.Animator){strips.forEach {it.pill.invalidateBackdrop()}}
                    })
                    start()
                }
            }
        }
        private fun fitGlass(height:Int){
            val lp=glass.layoutParams
            if(lp.height!=height)glass.layoutParams=lp.apply {this.height=height}
        }
        private fun headerButton()=ImageButton(activity).apply {
            scaleType=ImageView.ScaleType.CENTER
            imageTintList=android.content.res.ColorStateList.valueOf(inkColor)
            val circle=android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(if(dark(activity))0x30ffffff else 0x20000000),
                GradientDrawable().apply {
                    shape=GradientDrawable.OVAL
                    val dark=dark(activity)
                    setColor(android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_selected),intArrayOf()),
                        intArrayOf(if(dark)0x50ffffff else 0x32000000,if(dark)0x26ffffff else 0x18000000)))
                },null)
            background=InsetDrawable(circle,d(3))
        }
        private inner class MaterialButton {
            private var material=-1
            val button=headerButton().apply {
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    choose(Auto.MATERIAL to nextMaterial(material))
                }
            }
            private fun nextMaterial(current:Int)=when(current){
                Auto.LIQUID -> Auto.FROSTED
                Auto.FROSTED -> Auto.COLOR_ONLY
                else -> if(android.os.Build.VERSION.SDK_INT>=33)Auto.LIQUID else Auto.FROSTED
            }
            private fun title(current:Int)=when(current){
                Auto.LIQUID -> R.string.ui_auto_launcher_liquid
                Auto.FROSTED -> R.string.ui_auto_launcher_frosted
                else -> R.string.ui_mode_color
            }
            fun bind(value:Int,enabled:Boolean){
                button.isEnabled=enabled;button.alpha=if(enabled)1f else .4f
                val current=if(value==Auto.LIQUID && android.os.Build.VERSION.SDK_INT<33)Auto.FROSTED else value
                if(material==current)return
                material=current
                val icon=when(current){
                    Auto.LIQUID -> R.drawable.ic_glass_drop
                    Auto.FROSTED -> R.drawable.ic_glass_snow
                    else -> R.drawable.ic_glass_flower
                }
                button.setImageDrawable(labels.getDrawable(icon))
                button.stateDescription=s(title(current))
                button.contentDescription=labels.getString(R.string.ui_auto_launcher_switch_material,s(title(nextMaterial(current))))
            }
        }
        private inner class Section(val content:View):FrameLayout(activity){
            var naturalHeight=0
                private set
            var shown=0f
                private set
            init {addView(content);clipChildren=true}
            fun prepare(w:Int){
                content.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                naturalHeight=content.measuredHeight
            }
            fun reveal(value:Float){
                shown=value
                val h=value.roundToInt()
                if(layoutParams.height!=h)layoutParams=layoutParams.apply {height=h}
                alpha=if(naturalHeight==0)0f else (value/naturalHeight).coerceIn(0f,1f)
            }
            override fun onMeasure(w:Int,h:Int){prepare(View.MeasureSpec.getSize(w));setMeasuredDimension(View.MeasureSpec.getSize(w),View.MeasureSpec.getSize(h))}
            override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){content.layout(0,0,width,naturalHeight)}
        }
        private inner class ModeTile(private val look:Int,title:String):LinearLayout(activity){
            private val icon=ModeIcon(look)
            private val label=text(title,12f).apply {gravity=Gravity.CENTER}
            private val ring=GradientDrawable().apply {cornerRadius=d(16).toFloat();setStroke(d(2),inkColor);alpha=0}
            private var selectedState:Boolean?=null
            private var animator:ValueAnimator?=null
            init {
                orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isFocusable=true;contentDescription=title
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if(look==Auto.TINTED)choose(Auto.LOOK to look,Auto.SOURCE to 0)else choose(Auto.LOOK to look)
                }
                addView(FrameLayout(activity).apply {
                    addView(icon,FrameLayout.LayoutParams(d(50),d(50),Gravity.CENTER));foreground=ring
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },LinearLayout.LayoutParams(d(58),d(58)))
                addView(label,LinearLayout.LayoutParams(-1,d(28)))
            }
            fun bind(animate:Boolean){
                icon.settingsChanged(animate)
                val next=value(Auto.LOOK)==look
                isSelected=next;label.setTextColor(if(next)inkColor else muted)
                if(selectedState==next)return
                selectedState=next;animator?.cancel()
                if(!animate || !ValueAnimator.areAnimatorsEnabled()){ring.alpha=if(next)255 else 0;return}
                animator=ValueAnimator.ofInt(ring.alpha,if(next)255 else 0).apply {
                    duration=180;addUpdateListener {ring.alpha=it.animatedValue as Int};start()
                }
            }
            fun stop(){animator?.cancel();icon.stop()}
        }
        private inner class ModeIcon(private val look:Int):View(activity){
            private var stamp:List<Int> = emptyList()
            private var palette:com.cosmod.launcherglass.util.IconPalette?=null
            private val bounds=Rect()
            private val boundsF=RectF()
            private val platePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val glyphPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val frostPaint=GlassVolume.paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)}
            private var node=RenderNode("CherryPick.autoMenuMode").apply {setClipToBounds(true)}
            private var outgoing:RenderNode?=null
            private var fade:ValueAnimator?=null
            private var dirty=true
            fun settingsChanged(animate:Boolean){
                val nativeDark=settings.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK,dark(activity))
                val next=when(look){
                    Auto.DEFAULT ->listOf(nativeDark.hashCode())
                    Auto.CLEAR ->listOf(value(Auto.THEME))
                    Auto.TINTED ->listOf(Auto.THEME,Auto.SOURCE,Auto.HUE,Auto.SATURATION).map(::value)
                    else ->listOf(look)
                }
                if(stamp==next)return
                val colors=com.cosmod.launcherglass.util.AutoIconPalette.resolve(
                    activity,Bundle(settings).apply {putInt(Auto.ENGINE,1);putInt(Auto.LOOK,look)},nativeDark) ?: return
                stamp=next
                if(colors==palette)return
                if(animate && !dirty && node.hasDisplayList() && ValueAnimator.areAnimatorsEnabled()){
                    stop()
                    val old=node
                    outgoing=old
                    node=RenderNode("CherryPick.autoMenuMode").apply {
                        setPosition(0,0,this@ModeIcon.width,this@ModeIcon.height)
                        setClipToBounds(true)
                    }
                    fade=ValueAnimator.ofFloat(1f,0f).apply {
                        duration=190
                        interpolator=android.view.animation.PathInterpolator(.25f,0f,.2f,1f)
                        addUpdateListener {old.alpha=it.animatedValue as Float;invalidate()}
                        addListener(object:android.animation.AnimatorListenerAdapter(){
                            override fun onAnimationEnd(animation:android.animation.Animator){
                                if(outgoing===old){outgoing=null;fade=null;invalidate()}
                            }
                        })
                        start()
                    }
                }
                palette=colors
                platePaint.colorFilter=PorterDuffColorFilter(colors.background or Color.BLACK,PorterDuff.Mode.SRC_IN)
                glyphPaint.colorFilter=colors.filter
                dirty=true
                invalidate()
            }
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
                node.setPosition(0,0,w,h)
                bounds.set(0,0,w,h)
                boundsF.set(bounds)
                frostPaint.shader=GlassVolume.shader(h.toFloat(),65)
                dirty=true
            }
            fun stop(){fade?.cancel();fade=null;outgoing=null}
            override fun onDetachedFromWindow(){stop();node.discardDisplayList();super.onDetachedFromWindow()}
            override fun onDraw(canvas:Canvas){
                if(palette==null || bounds.isEmpty || !canvas.isHardwareAccelerated)return
                val mask=sample.plate ?: return
                val glyph=sample.glyph ?: return
                if(dirty || !node.hasDisplayList()){
                    val recording=node.beginRecording(width,height)
                    try {
                        // Frosted shading stays within the opaque swatch. The sheet never shows through it.
                        val save=recording.saveLayer(boundsF,null)
                        recording.drawBitmap(mask,null,bounds,platePaint)
                        recording.drawRect(boundsF,frostPaint)
                        recording.restoreToCount(save)
                        recording.drawBitmap(glyph,null,bounds,glyphPaint)
                    }finally{node.endRecording()}
                    dirty=false
                }
                canvas.drawRenderNode(node)
                outgoing?.let(canvas::drawRenderNode)
            }
        }
    }

}
