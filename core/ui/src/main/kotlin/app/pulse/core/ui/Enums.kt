package app.pulse.core.ui

enum class ColorSource {
    Default,
    Dynamic,
    MaterialYou,
    Pink
}

enum class ColorMode {
    System,
    Light,
    Dark
}

enum class Darkness {
    Normal,
    AMOLED,
    // Legacy only: hidden from settings and auto-migrated to AMOLED on launch.
    // Kept so stored "PureBlack" preferences don't silently fall back to Normal.
    PureBlack
}
