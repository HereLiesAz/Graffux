package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/** Painting before a model's saved texture has loaded must not start from a blank bitmap. */
class ModelPaintBlockTest {
    private val loaded = ModelTexture.ofPng(byteArrayOf(1, 2, 3))

    @Test
    fun `a never-painted model can be painted`() {
        assertEquals(ModelPaintBlock.NONE, ModelLayerContent(objFile = "m.obj").paintBlock(loadFailed = false))
    }

    @Test
    fun `a model whose saved texture has not loaded yet waits`() {
        val content = ModelLayerContent(objFile = "m.obj", textureFile = "t.png", texture = null)
        assertEquals(ModelPaintBlock.TEXTURE_LOADING, content.paintBlock(loadFailed = false))
    }

    @Test
    fun `a model whose saved texture is loaded can be painted`() {
        val content = ModelLayerContent(objFile = "m.obj", textureFile = "t.png", texture = loaded)
        assertEquals(ModelPaintBlock.NONE, content.paintBlock(loadFailed = false))
    }

    @Test
    fun `a saved texture that could not be read is refused, not painted over blank`() {
        val content = ModelLayerContent(objFile = "m.obj", textureFile = "t.png", texture = null)
        assertEquals(ModelPaintBlock.TEXTURE_UNREADABLE, content.paintBlock(loadFailed = true))
    }

    @Test
    fun `a load failure flag does not matter once there is a texture or no saved file`() {
        assertEquals(
            ModelPaintBlock.NONE,
            ModelLayerContent(objFile = "m.obj", textureFile = "t.png", texture = loaded).paintBlock(loadFailed = true),
        )
        assertEquals(ModelPaintBlock.NONE, ModelLayerContent(objFile = "m.obj").paintBlock(loadFailed = true))
    }
}
