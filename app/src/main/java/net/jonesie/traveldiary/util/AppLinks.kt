package net.jonesie.traveldiary.util

/** Where the app lives online, and the line added to the bottom of things people share. */
object AppLinks {
    const val WEBSITE = "https://jonesie.github.io/adventure-timeline/"
    const val FOOTER_TITLE = "Made with Adventure Timeline"

    /** Two lines: the name, then the link. */
    fun footerText(): String = "$FOOTER_TITLE\n$WEBSITE"
}
