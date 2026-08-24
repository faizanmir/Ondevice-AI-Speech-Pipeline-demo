package com.example.aiagenttestapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * One accent per voice in a speaker transcript.
 *
 * A transcript is read by tracking one person down the page while skipping everyone else, and a
 * name at the top of each turn is the slowest possible way to do that -- it has to be read to be
 * used. A colour is matched at a glance, which is the whole job.
 *
 * These are their own hues rather than the scheme's `primary`/`secondary`/`tertiary` roles. Three
 * roles cannot carry eight speakers, and borrowing them would also mean a speaker sharing a colour
 * with every interactive control on the screen -- so the one colour that means "you can tap this"
 * would also mean "Anita is talking".
 *
 * Two sets, because a hue legible on a near-white surface is invisible on a near-black one and the
 * reverse. Each is contrast-checked against the surface it is used on at the weight it is drawn:
 * the light values sit at or above 4.5:1 on the light surfaces, the dark values likewise on the dark
 * ones.
 */
private val SpeakerAccentsLight = listOf(
    Color(0xFF4F46C9), // indigo -- the brand hue, so the first speaker looks native to the app
    Color(0xFF00696E), // teal
    Color(0xFF984061), // rose
    Color(0xFF8A5100), // amber
    Color(0xFF3A6A2C), // green
    Color(0xFF7A3E9D), // purple
    Color(0xFF0B5FA5), // blue
    Color(0xFF7A5539), // brown
)

private val SpeakerAccentsDark = listOf(
    Color(0xFFC3C0FF),
    Color(0xFF4FD8E0),
    Color(0xFFFFB1C8),
    Color(0xFFFFB86B),
    Color(0xFFA5D68B),
    Color(0xFFDDB4FF),
    Color(0xFF9CCAFF),
    Color(0xFFE7BE94),
)

/**
 * The accent for the [index]th speaker to say anything, wrapping past the palette's end.
 *
 * Indexed by order of first speech rather than by cluster id: the clustering numbers voices in
 * whatever order it happened to find them, so colouring by cluster would give the same conversation
 * a different colour scheme on every re-run.
 *
 * Which set is in play is read off the surface's luminance rather than from `isSystemInDarkTheme`,
 * so it stays right if the app is ever handed a scheme that does not match the system -- a dynamic
 * palette, or a screen that themes itself.
 */
@Composable
@ReadOnlyComposable
fun speakerAccent(index: Int): Color {
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        SpeakerAccentsDark
    } else {
        SpeakerAccentsLight
    }
    return palette[((index % palette.size) + palette.size) % palette.size]
}
