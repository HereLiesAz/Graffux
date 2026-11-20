package com.hereliesaz.graffitixr.data

import android.content.Context
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.LegacyVisuals
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.scene.PlanePose
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProjectManagerBodyPoseTest {

    private lateinit var mockContext: Context
    private lateinit var tempFilesDir: File
    private lateinit var uriProvider: UriProvider
    private lateinit var manager: ProjectManager

    @Before
    fun setup() {
        tempFilesDir = File(System.getProperty("java.io.tmpdir"), "graffitixr_test_files")
        tempFilesDir.mkdirs()

        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempFilesDir
        every { mockContext.cacheDir } returns File(tempFilesDir, "cache").also { it.mkdirs() }

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)

        // The hardened import/spectator paths log skipped hostile entries; android.util.Log is
        // not available in plain JVM tests.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        uriProvider = mockk(relaxed = true)
        val projectRepositoryProvider = mockk<javax.inject.Provider<ProjectRepository>>(relaxed = true)
        manager = ProjectManager(mockContext, uriProvider, projectRepositoryProvider)
    }

    @After
    fun teardown() {
        tempFilesDir.deleteRecursively()
        unmockkStatic(Uri::class)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `a pose turned about its body axes loads as the same orientation, with no format change`() {
        val axes = RotationAxis.entries
        var pose = PlanePose(rotationX = 20f, rotationY = -35f, rotationZ = 10f)
        val pivot = floatArrayOf(500f, 800f, 800f)
        for ((i, deg) in listOf(40f, -25f, 70f, 15f).withIndex()) {
            pose = SceneMath.rotatePoseAbout(pose, axes[(i + 1) % 3], deg, pivot, 1000f, 1600f)
        }
        val project = GraffitiProject(
            id = "body",
            layers = listOf(
                OverlayLayer(
                    id = "l", uri = mockk(relaxed = true), positionZ = pose.positionZ,
                    rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ,
                ),
            ),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = manager.decodeProject(json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(project.formatVersion, back.formatVersion)
        val l = back.layers.single()
        val a = SceneMath.rotation(pose.rotationX, pose.rotationY, pose.rotationZ)
        val b = SceneMath.rotation(l.rotationX, l.rotationY, l.rotationZ)
        for (k in 0..8) assertEquals(a[k], b[k], 0f)
        for (ax in axes) {
            assertEquals(
                SceneMath.bodyAngle(pose, ax),
                SceneMath.bodyAngle(
                    PlanePose(0f, 0f, 0f, l.rotationX, l.rotationY, l.rotationZ), ax,
                ),
                0f,
            )
        }
    }

    @Test
    fun `an older project with a posed sheet group migrates with its surface and pose untouched`() {
        val profile = com.hereliesaz.graffitixr.common.scene.DefaultSurface.profile(
            com.hereliesaz.graffitixr.common.scene.SceneRect(125f, 266.6667f, 750f, 1066.6667f),
            1000f, 1600f, emptyList(),
        )!!
        val project = GraffitiProject(
            id = "old",
            layers = listOf(
                OverlayLayer(
                    id = "g", uri = mockk(relaxed = true), layerType = LayerType.GROUP, surface = profile,
                    offset = androidx.compose.ui.geometry.Offset(300f, 0f), scale = 0.5f,
                    rotationX = 20f, positionZ = 90f,
                ),
            ),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(GraffitiProject.serializer(), project)
            .replace("\"formatVersion\":14", "\"formatVersion\":13")
            .replaceFirst("{", "{\"orbitPivot\":[1.0,2.0,3.0],")
        val back = manager.decodeProject(text)
        assertEquals(com.hereliesaz.graffitixr.common.model.CURRENT_FORMAT_VERSION, back.formatVersion)
        assertNull(back.orbitPivot)
        val g = back.layers.single()
        assertEquals(profile, g.surface)
        assertEquals(androidx.compose.ui.geometry.Offset(300f, 0f), g.offset)
        assertEquals(0.5f, g.scale, 0f)
        assertEquals(20f, g.rotationX, 0f)
        assertEquals(90f, g.positionZ, 0f)
    }
}
