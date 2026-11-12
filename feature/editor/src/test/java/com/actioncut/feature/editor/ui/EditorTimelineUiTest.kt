package com.actioncut.feature.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.Track
import com.actioncut.core.model.TrackType
import com.actioncut.feature.editor.ui.timeline.EditorTimeline
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditorTimelineUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun timelineWithOneVideo(): Timeline {
        val clip = Clip(
            id = "c1",
            type = ClipType.VIDEO,
            mediaUri = "file:///movies/holiday.mp4",
            timelineStartMs = 0L,
            timelineEndMs = 4_000L,
            sourceOutMs = 4_000L,
        )
        return Timeline(tracks = listOf(Track(id = "t1", type = TrackType.VIDEO, clips = listOf(clip))))
    }

    @Test
    fun tappingClip_reportsSelection() {
        val timeline = timelineWithOneVideo()
        var selected: String? = null

        compose.setContent {
            EditorTimeline(
                timeline = timeline,
                playheadMs = 0L,
                durationMs = 4_000L,
                pxPerSecond = 80f,
                isPlaying = false,
                selectedClipId = null,
                onScrub = {},
                onSelectClip = { selected = it },
                onTrimStart = { _, _ -> },
                onTrimEnd = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }

        assertEquals(1, timeline.allClips.size)
        assertEquals("c1", timeline.allClips.first().id)
    }
}
