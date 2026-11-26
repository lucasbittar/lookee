package com.lookee.tv.ui.player

import com.lookee.tv.data.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class CueTextTest {
    private val cues = listOf(Cue(1000, 3000, "one"), Cue(2000, 2500, "overlap"), Cue(5000, 6000, "two"))

    @Test fun beforeFirst() = assertEquals("", cueTextAt(cues, 500))
    @Test fun inside() = assertEquals("one", cueTextAt(cues, 1500))
    @Test fun overlapping() = assertEquals("one\noverlap", cueTextAt(cues, 2200))
    @Test fun afterOverlapEnds() = assertEquals("one", cueTextAt(cues, 2600))
    @Test fun gap() = assertEquals("", cueTextAt(cues, 4000))
    @Test fun endIsExclusive() = assertEquals("", cueTextAt(cues, 6000))
    @Test fun empty() = assertEquals("", cueTextAt(emptyList(), 100))
}
