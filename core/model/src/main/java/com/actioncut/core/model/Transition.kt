package com.actioncut.core.model

import kotlinx.serialization.Serializable

/** ட்ரான்சிஷன் பிரிவுகள் (Tabs) */
@Serializable
enum class TransitionCategory(val displayName: String) {
    ALL("All"),
    SLIDE("Slide"),
    MOVE("Move"),
    WIPE("Wipe"),
    MIX("Mix"),
    SEAMLESS("Seamless"),
}

/** கிடைக்கும் அனைத்து ட்ரான்சிஷன் எஃபெக்ட்கள் */
@Serializable
enum class TransitionType(
    val displayName: String,
    val category: TransitionCategory,
) {
    SEAMLESS_MERGE("Seamless Merge", TransitionCategory.SEAMLESS),
    CROSSFADE("Crossfade", TransitionCategory.MIX),
    DISSOLVE("Dissolve", TransitionCategory.MIX),
    FADE("Fade", TransitionCategory.MIX),
    BLUR("Blur", TransitionCategory.MIX),
    SLIDE_LEFT("Slide Left", TransitionCategory.SLIDE),
    SLIDE_RIGHT("Slide Right", TransitionCategory.SLIDE),
    SLIDE_UP("Slide Up", TransitionCategory.SLIDE),
    SLIDE_DOWN("Slide Down", TransitionCategory.SLIDE),
    ZOOM_IN("Zoom In", TransitionCategory.MOVE),
    ZOOM_OUT("Zoom Out", TransitionCategory.MOVE),
    WIPE("Wipe", TransitionCategory.WIPE),
    NONE("None", TransitionCategory.SEAMLESS);
}

@Serializable
data class Transition(
    val type: TransitionType,
    val durationMs: Long = 500L,
) {
    companion object {
        val NONE = Transition(TransitionType.NONE, 0L)
        val SEAMLESS = Transition(TransitionType.SEAMLESS_MERGE, 0L)
        val DEFAULT_DURATION_MS = 500L
    }
}
