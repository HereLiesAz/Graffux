package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrushAliasTableTest {
    @Test
    fun everyRemovedBrushHasASuccessorInAPainterlyPack() {
        assertEquals(10, BrushAliasTable.removedNames.size)
        for (name in BrushAliasTable.removedNames) {
            val s = BrushAliasTable.successorOf(name)!!
            assertTrue(s.pack in listOf("acrylic", "oil", "water", "ink"), "$name -> ${s.pack}")
            assertTrue(s.brush.isNotBlank())
        }
    }

    @Test
    fun aNameThatStillExistsIsKeptAndARemovedOneResolvesToItsSuccessor() {
        val installed = setOf("acrylic" to "Flat", "oil" to "Hog Bristle")
        val exists = { pack: String?, brush: String -> pack == null && brush == "Round" || (pack to brush) in installed }
        assertEquals("Round", BrushAliasTable.resolve("Round", exists)!!.brush)
        assertEquals("Flat", BrushAliasTable.resolve("Square Flat", exists)!!.brush)
        assertEquals("oil", BrushAliasTable.resolve("Heavy Oil", exists)!!.pack)
    }

    @Test
    fun aRemovedBrushWhosePackIsNotInstalledAndAnUnknownNameResolveToNothingSoTheCallerFallsBackToRound() {
        val none = { _: String?, _: String -> false }
        assertNull(BrushAliasTable.resolve("Square Flat", none))
        assertNull(BrushAliasTable.resolve("Never Existed", none))
        assertEquals("Round", BuiltInBrushes.DEFAULT_NAME)
    }

    @Test
    fun resolveOrDefaultNeverReturnsNothing() {
        val none = { _: String?, _: String -> false }
        assertEquals("Round", BrushAliasTable.resolveOrDefault("Square Flat", none).brush)
        assertEquals("Round", BrushAliasTable.resolveOrDefault("Never Existed", none).brush)
        val installed = { pack: String?, brush: String -> pack == "acrylic" && brush == "Flat" }
        assertEquals("Flat", BrushAliasTable.resolveOrDefault("Square Flat", installed).brush)
    }

    @Test
    fun aFavouriteOfARemovedBrushIsRenamedToRoundOrItsBuiltInSuccessor() {
        val builtIns = setOf("Round", "Flat")
        val keys = listOf("builtin.Heavy Oil", "builtin.Round", "custom.abc", "ink.pen", "builtin.Unknown", "builtin.Square Flat")
        val renames = BrushAliasTable.carouselKeyRenames(keys) { it in builtIns }
        assertEquals("builtin.Round", renames["builtin.Heavy Oil"], "no built-in successor: Round")
        assertEquals(setOf("builtin.Heavy Oil", "builtin.Square Flat"), renames.keys, "only removed built-ins move")
        assertNull(renames["builtin.Unknown"], "an unknown name is not a removed brush: left alone")
    }

    @Test
    fun aBrushThatStillExistsIsNotRenamed() {
        val renames = BrushAliasTable.carouselKeyRenames(listOf("builtin.Heavy Oil")) { it == "Heavy Oil" || it == "Round" }
        assertTrue(renames.isEmpty())
    }
}
