package com.claudeborne.camera.capture

import java.io.ByteArrayOutputStream

/**
 * Google's Motion Photo format: a normal JPEG with an XMP block that describes a short MP4 appended after the picture's last byte.
 * Gallery apps that know the format (Google Photos, most others) show the still and play the clip; others just show the JPEG.
 */
object MotionPhoto {
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    /** The XMP text: both the current "Container" description and the older MicroVideo keys, so more apps understand it. */
    fun xmp(videoLength: Int, presentationUs: Long): String =
        """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>""" +
            """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Camera"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" """ +
            """xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" """ +
            """GCamera:MotionPhotoPresentationTimestampUs="$presentationUs" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" """ +
            """GCamera:MicroVideoOffset="$videoLength" GCamera:MicroVideoPresentationTimestampUs="$presentationUs">""" +
            """<Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li>""" +
            """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""

    /** [jpeg] with the XMP block inserted after its first segments (and the EXIF block, if it leads) and [mp4] appended. [presentationUs] is where the still sits in the clip. */
    fun build(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val payload = (XMP_HEADER + xmp(mp4.size, presentationUs)).toByteArray(Charsets.ISO_8859_1)
        val length = payload.size + 2
        require(length <= 0xFFFF) { "XMP too long" }
        var at = 2 // right after SOI, or after a leading EXIF segment (EXIF should stay first)
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE1.toByte()) {
            val exifLen = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            at = 4 + exifLen
        }
        val out = ByteArrayOutputStream(jpeg.size + payload.size + mp4.size + 4)
        out.write(jpeg, 0, at)
        out.write(0xFF); out.write(0xE1); out.write(length shr 8); out.write(length and 0xFF)
        out.write(payload)
        out.write(jpeg, at, jpeg.size - at)
        out.write(mp4)
        return out.toByteArray()
    }
}
