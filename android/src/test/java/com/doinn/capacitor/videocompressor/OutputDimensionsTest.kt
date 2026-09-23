package com.doinn.capacitor.videocompressor

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDimensionsTest {

    @Test
    fun scalesRotatedPortraitVideoIntoPortraitBounds() {
        assertEquals(Pair(480, 848), OutputDimensions.calculate(1920, 1080, 90, 854, 480))
        assertEquals(Pair(480, 848), OutputDimensions.calculate(1920, 1080, 270, 854, 480))
    }

    @Test
    fun scalesUnrotatedPortraitVideoIntoPortraitBounds() {
        assertEquals(Pair(480, 848), OutputDimensions.calculate(1080, 1920, 0, 854, 480))
    }

    @Test
    fun scalesLandscapeVideoIntoLandscapeBounds() {
        assertEquals(Pair(848, 480), OutputDimensions.calculate(1920, 1080, 0, 854, 480))
    }

    @Test
    fun appliesOrientedBoundsToEveryPreset() {
        assertEquals(Pair(720, 1280), OutputDimensions.calculate(3840, 2160, 90, 1280, 720))
        assertEquals(Pair(1280, 720), OutputDimensions.calculate(3840, 2160, 0, 1280, 720))
    }

    @Test
    fun treatsUpsideDownVideoAsUnrotated() {
        assertEquals(Pair(848, 480), OutputDimensions.calculate(1920, 1080, 180, 854, 480))
    }

    @Test
    fun treatsPortraitBoundsLikeLandscapeBounds() {
        assertEquals(Pair(480, 848), OutputDimensions.calculate(1920, 1080, 90, 480, 854))
        assertEquals(Pair(848, 480), OutputDimensions.calculate(1920, 1080, 0, 480, 854))
    }

    @Test
    fun scalesSquareVideoByTheShortBound() {
        assertEquals(Pair(480, 480), OutputDimensions.calculate(1080, 1080, 0, 854, 480))
    }

    @Test
    fun keepsVideoAlreadyWithinBounds() {
        assertEquals(Pair(640, 368), OutputDimensions.calculate(640, 360, 0, 854, 480))
        assertEquals(Pair(368, 640), OutputDimensions.calculate(640, 360, 90, 854, 480))
    }

    @Test
    fun neverRoundsAboveTheBounds() {
        assertEquals(Pair(1072, 1920), OutputDimensions.calculate(1080, 1920, 0, 1920, 1080))
        assertEquals(Pair(1920, 1072), OutputDimensions.calculate(1920, 1080, 0, 1920, 1080))
    }

    @Test
    fun roundsToMultiplesOf16WithMinimum16() {
        assertEquals(848, OutputDimensions.roundTo16(853))
        assertEquals(16, OutputDimensions.roundTo16(3))
    }
}
