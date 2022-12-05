package com.haishinkit.graphics.gles

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Size
import android.view.Surface

internal class Texture {
    companion object {
        fun create(width: Int, height: Int): Texture {
            return Texture().apply {
                val textures = intArrayOf(1)
                GLES20.glGenTextures(1, textures, 0)
                id = textures[0]
                extent = Size(width, height)
                surfaceTexture = SurfaceTexture(textures[0]).apply {
                    setDefaultBufferSize(width, height)
                }
                surface = surfaceTexture?.let {
                    Surface(it)
                }
            }
        }
    }

    var id: Int = 0
        private set
    var extent: Size = Size(0, 0)
        private set
    var surface: Surface? = null
        private set
    private var surfaceTexture: SurfaceTexture? = null

    fun isValid(width: Int, height: Int): Boolean {
        return extent.width == width && extent.height == height
    }

    fun updateTexImage() {
        surfaceTexture?.updateTexImage()
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
