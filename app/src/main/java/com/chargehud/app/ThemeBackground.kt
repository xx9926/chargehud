package com.chargehud.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsetsController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * 主题背景：相册照片（可高斯模糊）或纯色，画在页面最底下那一层 `bgLayer` 上，
 * 上面再叠内容，所以拖透明度 / 模糊时看到的就是最终效果。
 *
 * 模糊优先用 Android 12 的 `RenderEffect`：GPU 直接作用在这一层视图上，
 * 滑块每动一下只是换一个效果对象，不用重算位图，拖动才是连续的。
 * 更早的系统没有 GPU 模糊，退回"缩小再放大"的近似做法（一次缩放几毫秒，不必开线程）。
 */
object ThemeBackground {

    const val KIND_IMAGE = "image"
    const val KIND_VIDEO = "video"

    /** 视频整段复制进私有目录，太大的短片解码也吃力，这里给个上限。 */
    private const val MAX_VIDEO_BYTES = 60L * 1024 * 1024

    private const val MAX_EDGE = 720
    private const val MAX_BLUR_PX = 60f
    private const val IMAGE_FILE = "bg_image.jpg"
    private const val VIDEO_FILE = "bg_video.mp4"

    private var decodedKey = ""
    private var decoded: Bitmap? = null
    private var decodedLuma = 0.0

    fun imageFile(context: Context): File = File(context.filesDir, IMAGE_FILE)

    fun videoFile(context: Context): File = File(context.filesDir, VIDEO_FILE)

    /** 把相册里的短视频复制一份到私有目录，和照片一样，之后原片删掉也不影响背景。 */
    fun importVideo(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val ok = runCatching {
                val size = querySize(app, uri)
                if (size <= 0 || size > MAX_VIDEO_BYTES) return@runCatching false
                app.contentResolver.openInputStream(uri)?.use { input ->
                    videoFile(app).outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            postResult(onDone, ok)
        }.start()
    }

    private fun querySize(context: Context, uri: Uri): Long {
        val columns = arrayOf(OpenableColumns.SIZE)
        val size = runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
        }.getOrNull()
        return size ?: -1L
    }

    private fun postResult(onDone: (Boolean) -> Unit, ok: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(ok) }
    }

    /** 把照片复制成私有目录里的一份降采样副本，之后不再依赖相册里的原图和 SAF 授权。 */
    fun importImage(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val ok = runCatching {
                val resolver = app.contentResolver
                val bitmap = decodeSampled { resolver.openInputStream(uri) } ?: return@runCatching false
                imageFile(app).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                true
            }.getOrDefault(false)
            clearDecoded()
            postResult(onDone, ok)
        }.start()
    }

    /** 换图或清除之后把解码缓存丢掉，下次重新读文件。 */
    fun dropCache() = clearDecoded()

    fun applyTo(activity: Activity, layer: View, config: HudConfig) {
        val percent = config.pageBgAlphaPercent
        val radius = blurRadius(config.pageBgBlurPercent)
        val file = imageFile(activity)
        val bitmap =
            if (percent > 0 && config.bgMediaKind != KIND_VIDEO && file.exists()) decodeLocal(activity, file)
            else null
        val base = ColorDrawable(Prefs.DEFAULT_BG_COLOR)
        // 透明度烘在 drawable 里、底下永远垫一层不透明底色：档案页的窗口是半透明的，
        // 用 view.alpha 会把整层（含底色）一起变透，主页的内容就会从后面透出来。
        // 视频那一层是独立的 TextureView，这里只负责给它垫纯色底，不再叠一次透明度。
        layer.background = when {
            percent <= 0 -> base
            bitmap != null -> LayerDrawable(arrayOf(base, CropBackground(soften(bitmap, radius), percent)))
            config.bgMediaKind == KIND_VIDEO -> ColorDrawable(config.bgColor)
            else -> LayerDrawable(arrayOf(base, ColorDrawable(config.bgColor).apply { alpha = percent * 255 / 100 }))
        }
        applyBlurEffect(layer, if (bitmap != null) radius else 0f)
        applyStatusBarIcons(activity, bitmap, config)
    }

    /** 模糊百分比（0~100）换成 RenderEffect 的像素半径。 */
    fun blurRadius(percent: Int): Float = percent * MAX_BLUR_PX / 100f

    fun applyBlurEffect(layer: View, radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        layer.setRenderEffect(
            if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
        )
    }

    /**
     * 没有 GPU 模糊的系统上用的近似：按半径把图缩小，再靠双线性放大画回去。
     * Android 12 及以上走 RenderEffect，这里不会生效。
     */
    private fun soften(bitmap: Bitmap, radius: Float): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || radius <= 0f) return bitmap
        val factor = 1f + radius / 8f
        val w = max(1f, bitmap.width / factor).toInt()
        val h = max(1f, bitmap.height / factor).toInt()
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun clearDecoded() {
        decoded?.recycle()
        decoded = null
        decodedKey = ""
        decodedLuma = 0.0
    }

    private fun decodeLocal(context: Context, file: File): Bitmap? {
        val key = file.lastModified().toString()
        decoded?.takeIf { key == decodedKey && !it.isRecycled }?.let { return it }
        val bitmap = runCatching { decodeSampled { file.inputStream() } }.getOrNull() ?: return null
        clearDecoded()
        decoded = bitmap
        decodedKey = key
        decodedLuma = stripLuminance(bitmap)
        return bitmap
    }

    /** 只解到最长边约 720 px：背景用不到原图精度，全尺寸解码又慢又吃内存。 */
    private fun decodeSampled(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    /**
     * 状态栏图标跟着背景深浅走：背景压暗就把图标切成白色，否则维持深色。
     * 两套写法都下：MIUI 上只调 WindowInsetsController 会被窗口重算盖掉。
     */
    @Suppress("DEPRECATION")
    private fun applyStatusBarIcons(activity: Activity, bitmap: Bitmap?, config: HudConfig) {
        val alpha = config.pageBgAlphaPercent / 100.0
        val source = if (bitmap != null) decodedLuma else colorLuminance(config.bgColor)
        val light = colorLuminance(Prefs.DEFAULT_BG_COLOR) * (1 - alpha) + source * alpha < 0.5
        val decor = activity.window.decorView
        val flags = decor.systemUiVisibility
        decor.systemUiVisibility = if (light) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (light) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            activity.window.insetsController?.setSystemBarsAppearance(appearance, appearance)
        }
    }

    /** 取图片顶部一条（状态栏那一带）的平均亮度。 */
    private fun stripLuminance(bitmap: Bitmap): Double {
        val rows = max(1, bitmap.height / 8)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in 0 until rows step 2) {
            for (i in 0 until 32) {
                val p = bitmap.getPixel(i * bitmap.width / 32, y)
                r += p ushr 16 and 0xFF
                g += p ushr 8 and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return 0.0
        return colorLuminance(
            0xFF000000.toInt() or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        )
    }

    private fun colorLuminance(color: Int): Double {
        val r = (color ushr 16 and 0xFF) / 255.0
        val g = (color ushr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

/**
 * 让页面铺到状态栏底下：背景层填满整个窗口，内容自己按系统栏高度留白。
 * 两个页面都要这么处理，否则背景到不了状态栏那一条，或者拖返回时背景不跟着走。
 */
fun applyEdgeToEdge(activity: Activity, content: View) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/**
 * 背景视频那一层：相册里选的短视频复制到私有目录后，在 TextureView 上静音循环播放。
 *
 * 必须是 TextureView：SurfaceView 的内容走在独立的表面层上，RenderEffect 模糊不到它。
 * 播放器是把画面按视图尺寸拉伸铺满的，所以 centerCrop 靠**把视图本身摆成裁剪后的尺寸**
 * 来实现（配合 XML 里的 `layout_gravity="center"`）；不用 `setTransform`，
 * 那个矩阵和播放器自带的变换在这台机器上叠不出预期结果，实测会把画面推出屏幕外。
 */
class BackgroundVideo {

    private var player: MediaPlayer? = null
    private var boundView: TextureView? = null
    private var rotation = 0
    private var playingKey = ""

    /** @param active 只在页面可见时播放，离开页面就释放解码器，省电也省内存。 */
    fun apply(context: Context, view: TextureView, config: HudConfig, active: Boolean) {
        val file = ThemeBackground.videoFile(context)
        val wanted = active && config.bgMediaKind == ThemeBackground.KIND_VIDEO &&
            config.pageBgAlphaPercent > 0 && file.exists()
        view.visibility = if (wanted) View.VISIBLE else View.GONE
        view.alpha = config.pageBgAlphaPercent / 100f
        ThemeBackground.applyBlurEffect(view, ThemeBackground.blurRadius(config.pageBgBlurPercent))
        if (!wanted) {
            stop()
            return
        }
        // 换过素材（副本文件被重写）就得停掉重开，否则播放器还抱着旧文件的那份句柄。
        if (file.lastModified().toString() != playingKey) stop()
        if (player != null) return
        boundView = view
        view.surfaceTextureListener = surfaceListener
        if (view.isAvailable) start(context, view)
    }

    fun stop() {
        playingKey = ""
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        mp.release()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            val view = boundView ?: return
            if (player == null) start(view.context, view)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            boundView?.let { fit(it) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun start(context: Context, view: TextureView) {
        val file = ThemeBackground.videoFile(context)
        playingKey = file.lastModified().toString()
        rotation = readRotation(file)
        val mp = MediaPlayer()
        player = mp
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        mp.setOnVideoSizeChangedListener { _, _, _ -> fit(view) }
        mp.setOnErrorListener { _, _, _ -> true }
        mp.setOnPreparedListener {
            fit(view)
            runCatching { it.start() }
        }
        val surface = view.surfaceTexture?.let(::Surface)
        val ok = runCatching {
            mp.setDataSource(file.absolutePath)
            if (surface != null) mp.setSurface(surface)
            mp.prepareAsync()
        }.isSuccess
        if (!ok) stop()
    }

    /** 按 centerCrop 把视图撑到刚好盖住整屏：多出来的一边靠居中裁掉，画面不会被拉伸。 */
    private fun fit(view: TextureView) {
        val mp = player ?: return
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        val parent = view.parent as? View ?: return
        if (vw <= 0 || vh <= 0 || parent.width <= 0 || parent.height <= 0) return
        val swapped = rotation % 180 != 0
        val cw = if (swapped) vh else vw
        val ch = if (swapped) vw else vh
        val scale = max(parent.width.toFloat() / cw, parent.height.toFloat() / ch)
        val params = view.layoutParams
        val width = (cw * scale).toInt()
        val height = (ch * scale).toInt()
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        view.layoutParams = params
    }

    /** 手机拍的视频常常只写旋转标记，解码出来的帧还是转正前的样子。 */
    private fun readRotation(file: File): Int = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        retriever.release()
        value?.toIntOrNull() ?: 0
    }.getOrDefault(0)
}

/** 按 centerCrop 把背景画满视图：照片比例和屏幕不一致时裁掉多出来的一边，不拉伸变形。 */
class CropBackground(private val src: Bitmap, private var alphaPercent: Int) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        if (src.isRecycled || alphaPercent <= 0) return
        val b = bounds
        if (b.isEmpty || src.width <= 0 || src.height <= 0) return
        val scale = max(b.width().toFloat() / src.width, b.height().toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = b.left + (b.width() - w) / 2f
        val top = b.top + (b.height() - h) / 2f
        paint.alpha = alphaPercent.coerceIn(0, 100) * 255 / 100
        canvas.drawBitmap(src, null, RectF(left, top, left + w, top + h), paint)
    }

    override fun setAlpha(alpha: Int) {
        // Drawable 约定是 0~255，内部统一按百分比存。
        alphaPercent = alpha * 100 / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
