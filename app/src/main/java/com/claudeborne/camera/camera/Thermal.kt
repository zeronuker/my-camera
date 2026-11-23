package com.claudeborne.camera.camera

/** How hot the phone is, boiled down from Android's thermal status (0 none … 6 shutdown) to what the camera does about it. */
enum class Heat {
    OK,

    /** Severe: warn, and stop bursts and interval runs. Single photos still work. */
    HOT,

    /** Critical or worse: no photos until it cools down. */
    TOO_HOT,
    ;

    companion object {
        fun of(thermalStatus: Int): Heat = when {
            thermalStatus >= 4 -> TOO_HOT
            thermalStatus >= 3 -> HOT
            else -> OK
        }
    }
}
