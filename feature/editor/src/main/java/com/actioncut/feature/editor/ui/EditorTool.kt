package com.actioncut.feature.editor.ui

/**
 * Editing tools shown in the bottom toolbar.
 * Music பட்டன் Fty Video Editor போலவே "NEWS ALERT" ஆக மாற்றப்பட்டுள்ளது.
 */
enum class EditorTool(val label: String, val opensPanel: Boolean) {
    SPLIT("Split", false),
    DELETE("Delete", false),
    TRIM("Trim", true),
    SPEED("Speed", true),
    VOLUME("Volume", true),
    MUTE("Mute", false),
    AUDIO("Audio", false),
    NEWS_ALERT("NEWS ALERT", false), // Fty Video Editor NEWS ALERT Button
    VOICEOVER("Voiceover", true),
    PIP("PiP", false),
    CRIC("CRIC", false),
    CARD_ADD("Card Add", false),
    PLAYER("Player", false),
    EXTRACT_AUDIO("Detach", false),
    REVERSE("Reverse", false),
    ROTATE("Rotate", false),
    FILTERS("Filters", true),
    ADJUST("Adjust", true),
    CROP("Crop", true),
    CANVAS("Canvas", true),
    TEXT("Text", true),
    STICKER("Sticker", true),
    TRANSITIONS("Transition", true),
    EFFECTS("Effects", true),
    KEYFRAME("Keyframe", true),
}
