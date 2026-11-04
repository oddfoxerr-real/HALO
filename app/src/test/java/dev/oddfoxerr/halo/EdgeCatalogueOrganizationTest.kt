package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selected tab is presentational. Every preset must remain reachable. */
class EdgeCatalogueOrganizationTest {
    @Test fun categoriesAccountForEveryEffectOnce() {
        val expected = mapOf(
            EdgeGroup.LINES to 9,
            EdgeGroup.PARTICLES to 7,
            EdgeGroup.LIGHTS to 17,
            EdgeGroup.WAVES to 7,
            EdgeGroup.RHYTHM to 15,
            EdgeGroup.DYNAMIC to 4
        )
        assertEquals(59, EdgeCatalogue.visible(null).size)
        assertEquals(59, expected.values.sum())
        assertEquals(59, EdgeMode.entries.size)
        assertEquals((0..58).toList(),
            EdgeCatalogue.visible(null).map { it.shaderId }.sorted())
        for ((group, count) in expected) {
            val modes = EdgeCatalogue.visible(group)
            assertEquals(group.title, count, modes.size)
            assertEquals(group.title, count, EdgeCatalogue.count(group))
            assertTrue(group.title, modes.all { it.group == group })
            assertEquals(modes.map { it.name }.toSet().size, modes.size)
        }
    }

    @Test fun referenceMotionStylesBelongToTheCorrectFamilies() {
        assertEquals(EdgeGroup.LINES, EdgeMode.CLASSIC_OUTLINE.group)
        assertEquals(EdgeGroup.LINES, EdgeMode.CORNER_TRACE.group)
        assertEquals(EdgeGroup.PARTICLES, EdgeMode.LIGHT_BEADS.group)
        assertEquals(EdgeGroup.PARTICLES, EdgeMode.SERIAL_LIGHTS.group)
        assertEquals(EdgeGroup.PARTICLES, EdgeMode.CORNER_SPARKS.group)
        assertEquals(EdgeGroup.LIGHTS, EdgeMode.SOFT_BEAMS.group)
        assertEquals(EdgeGroup.LIGHTS, EdgeMode.CHASE_LIGHT.group)
        assertEquals(EdgeGroup.WAVES, EdgeMode.SIDE_WAVES.group)
        assertEquals(EdgeGroup.WAVES, EdgeMode.TOP_BOTTOM_WAVES.group)
        assertEquals(EdgeGroup.RHYTHM, EdgeMode.SIDE_BARS.group)
        assertEquals(EdgeGroup.RHYTHM, EdgeMode.KICK_RIPPLE.group)
        assertEquals(EdgeGroup.DYNAMIC, EdgeMode.ELECTRIC_ARCS.group)
        assertEquals(EdgeGroup.DYNAMIC, EdgeMode.FIRE_LINES.group)
    }

    @Test fun reorganizationDoesNotChangeSavedPreferencesOrShaderIds() {
        for (mode in EdgeMode.entries) {
            assertEquals(mode, EdgeSpectrum.mode(mode.name))
            assertEquals(mode, EdgeCatalogue.visible(mode.group).single { it.name == mode.name })
        }
        assertEquals(0, EdgeMode.ELECTRIC_ARCS.shaderId)
        assertEquals(9, EdgeMode.HAIRLINE.shaderId)
        assertEquals(38, EdgeMode.PEARL_BEADS.shaderId)
        assertEquals(46, EdgeMode.SIDE_ENERGY.shaderId)
        assertEquals(58, EdgeMode.FIRE_LINES.shaderId)
    }
}
