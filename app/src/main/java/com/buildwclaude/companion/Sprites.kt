package com.buildwclaude.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import kotlin.math.roundToInt

enum class Anim(val dir: String) { WALK("walk"), IDLE("idle"), CHEER("cheer") }

/**
 * Pre-rendered knight frames (made by knight/sprites_knight.py in the Blender project),
 * scaled once to the on-screen size. Walk frames face right; draw them mirrored to face left.
 */
class Sprites private constructor(
    private val frames: Map<Anim, List<Bitmap>>,
    private val fps: Map<Anim, Int>,
    val width: Int,
    val height: Int,
    /** Where his feet are, as a fraction of the frame height measured from the top. */
    val footY: Float,
) {
    fun frames(anim: Anim): List<Bitmap> = frames.getValue(anim)
    fun fps(anim: Anim): Int = fps.getValue(anim)
    fun durationSec(anim: Anim): Float = frames(anim).size / fps(anim).toFloat()

    fun recycle() = frames.values.flatten().forEach { it.recycle() }

    companion object {
        /** Loads every frame scaled so the full sprite box is [heightPx] tall. */
        fun load(context: Context, heightPx: Int): Sprites {
            val assets = context.assets
            val meta = assets.open("knight/meta.json").bufferedReader().use { JSONObject(it.readText()) }
            val srcW = meta.getInt("width")
            val srcH = meta.getInt("height")
            val h = heightPx.coerceAtLeast(8)
            val w = (h * srcW.toFloat() / srcH).roundToInt().coerceAtLeast(4)
            val counts = meta.getJSONObject("frames")
            val fpsJson = meta.getJSONObject("fps")

            val frames = Anim.entries.associateWith { anim ->
                (0 until counts.getInt(anim.dir)).map { i ->
                    val name = "knight/${anim.dir}/${i.toString().padStart(2, '0')}.png"
                    val src = assets.open(name).use { BitmapFactory.decodeStream(it) }
                    val scaled = Bitmap.createScaledBitmap(src, w, h, true)
                    if (scaled !== src) src.recycle()
                    scaled
                }
            }
            return Sprites(
                frames = frames,
                fps = Anim.entries.associateWith { fpsJson.getInt(it.dir) },
                width = w,
                height = h,
                footY = meta.getDouble("footY").toFloat(),
            )
        }
    }
}
