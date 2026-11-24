package com.claudeborne.camera.data

/**
 * The 11 "Preserve settings" switches, as the stock camera has them. Everything is saved while the app runs; a switch that is OFF
 * puts the settings listed for it back to their defaults the next time the app starts. [resets] are Settings keys.
 */
enum class Keep(val title: String, val note: String, val default: Boolean, val resets: Set<String>) {
    MODE("Shooting mode", "Start Camera in the shooting mode used last time, for example Portrait.", false, setOf("lastMode")),
    STYLES("Styles & effects", "Keep the Filters, Soft light, Master styles and Scene that were applied last time.", false,
        setOf("filter", "softLight", "styleTone", "styleSat", "styleWarm", "styleTint", "styleSharp", "styleVignette", "stylePage", "scene")),
    CREATIVE("Creative effects", "Keep the Retouch and Portrait blur that were applied last time.", true, setOf("retouch", "portraitF")),
    EXPOSURE("Exposure setting", "Keep the last used exposure value (EV).", true, setOf("evSaved")),
    MACRO("Auto macro", "Keep the Auto macro setting used last time.", false, setOf("autoMacro")),
    MOTION("Motion Photo", "Keep the Live photo and Motion Photo settings used last time.", true, setOf("livePhoto", "videoMotion")),
    AI_ZOOM("AI Telescope Zoom", "Keep the AI Telescope Zoom setting used last time.", true, setOf("aiZoom")),
    PORTRAIT_ZOOM("Portrait mode zooming", "Keep the last used zoom in Portrait mode.", false, setOf("portraitZoom")),
    PRO("Pro assistance", "Keep the last used Master and Pro video settings, including Pro, log, histogram, peaking and zebra.", true,
        setOf("proOn", "showHistogram", "peaking", "zebra", "levelLine", "logProfile", "lutPreview", "shutterAngle")),
    METERING("Metering", "Keep the face metering setting last used in Master mode.", false, setOf("faceMetering")),
    FORMATS("Photo formats", "Keep the photo format setting for Master mode that was used last time.", false, setOf("masterFormat")),
    ;

    /** The Pro group is listed under its own heading. */
    val isPro get() = this == PRO || this == METERING || this == FORMATS
}

object Preserve {
    /** The keys to put back to their defaults at start-up, given which switches are on. */
    fun keysToReset(isOn: (Keep) -> Boolean): Set<String> = Keep.entries.filter { !isOn(it) }.flatMapTo(mutableSetOf()) { it.resets }
}
