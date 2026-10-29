package dev.kaff.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons from the Kaff design (24 x 24, round caps). Drawn in black and tinted by `Icon`.
 * Kept in-house instead of pulling the large material-icons-extended artifact.
 */
object KaffIcons {
    private fun icon(name: String, strokeWidth: Float = 1.8f, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,$r" + "v${h - 2 * r}a$r,$r 0 0 1 ${-r},$r" +
            "h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}" + "v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

    val Hand = icon(
        "Hand", 2.2f,
        "M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10V4.5a1.5 1.5 0 0 1 3 0V11M14 10V6a1.5 1.5 0 0 1 3 0v8a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-2.7L3.5 14a1.5 1.5 0 0 1 2.5-1.6L8 15",
    )
    val Close = icon("Close", 2f, "M6 6l12 12M18 6L6 18")
    val Settings = icon("Settings", 1.8f, "M4 7h10M18 7h2M4 17h2M10 17h10", circle(16f, 7f, 2f), circle(8f, 17f, 2f))
    val Shake = icon("Shake", 1.8f, "M3 12l2-2 2 4 3-8 3 10 2-6 2 2h3")
    val Tile = icon(
        "Tile", 1.8f,
        rect(4f, 4f, 7f, 7f, 2f), rect(13f, 4f, 7f, 7f, 2f), rect(4f, 13f, 7f, 7f, 2f), rect(13f, 13f, 7f, 7f, 2f),
    )
    val Bubble = icon("Bubble", 1.8f, circle(12f, 12f, 8f), circle(12f, 12f, 2.5f))
    val ArrowForward = icon("ArrowForward", 2.4f, "M19 12H5M11 6l-6 6 6 6")
    val ArrowBack = icon("ArrowBack", 2.2f, "M5 12h14M13 6l6 6-6 6")
    val Eye = icon("Eye", 2f, "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val ShieldCheck = icon("ShieldCheck", 2f, "M12 3l8 3v6c0 5-3.4 8.2-8 9-4.6-.8-8-4-8-9V6z", "M9 12l2 2 4-4")
    val Phone = icon("Phone", 1.8f, rect(7f, 2.5f, 10f, 19f, 2.5f), "M11 18.5h2")
    val Folder = icon("Folder", 1.8f, "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Check = icon("Check", 3f, "M5 12.5l4.5 4.5L19 7.5")
    val Bolt = icon("Bolt", 2.4f, "M13 2L4 14h7l-1 8 9-12h-7z")
    val History = icon("History", 1.8f, "M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5", "M12 7v5l3 2")
    val Lab = icon("Lab", 1.8f, "M9 3h6M10 3v6l-5 9a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3l-5-9V3", "M7.5 15h9")
    val File = icon("File", 1.8f, "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
    val Image = icon("Image", 1.8f, rect(3f, 4f, 18f, 16f, 3f), circle(9f, 9.5f, 1.8f), "M21 16l-5-5-9 9")
    val Video = icon("Video", 1.8f, rect(3f, 6f, 13f, 12f, 2.5f), "M16 10l5-3v10l-5-3z")
    val Plus = icon("Plus", 2.2f, "M12 5v14M5 12h14")
    val Camera = icon("Camera", 1.8f, "M4 8a2 2 0 0 1 2-2h2l1.5-2h5L16 6h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", circle(12f, 12.5f, 3.5f))
    val Lock = icon("Lock", 1.8f, rect(5f, 11f, 14f, 10f, 2f), "M8 11V8a4 4 0 0 1 8 0v3")
    val Bell = icon("Bell", 1.8f, "M6 16V11a6 6 0 0 1 12 0v5l2 2H4z", "M10 20a2 2 0 0 0 4 0")
    val Wifi = icon("Wifi", 1.8f, "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0", "M12 19.5h0")
    val Alert = icon("Alert", 2f, circle(12f, 12f, 9f), "M12 7.5v5.5M12 16.5h0")
    val Refresh = icon("Refresh", 2f, "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val Trash = icon("Trash", 1.8f, "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
    val Search = icon("Search", 2f, circle(11f, 11f, 6.5f), "M16 16l4.5 4.5")
    val Music = icon("Music", 1.8f, "M9 18V6l11-2v12", circle(6.5f, 18f, 2.5f), circle(17.5f, 16f, 2.5f))
    val Apps = icon(
        "Apps", 1.8f,
        rect(4f, 4f, 7f, 7f, 2.5f), rect(13f, 4f, 7f, 7f, 2.5f), rect(4f, 13f, 7f, 7f, 2.5f), circle(16.5f, 16.5f, 3.5f),
    )
    val Share = icon("Share", 1.8f, circle(18f, 5f, 2.5f), circle(6f, 12f, 2.5f), circle(18f, 19f, 2.5f), "M8.2 10.8l7.6-4.4M8.2 13.2l7.6 4.4")
    val External = icon("External", 2f, "M14 4h6v6M20 4l-9 9", "M19 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4")
    val Text = icon("Text", 1.8f, "M5 6h14M5 11h14M5 16h9")
    val Desktop = icon("Desktop", 1.8f, rect(2.5f, 4f, 19f, 12.5f, 2.5f), "M8.5 20.5h7M12 16.5v4")
    val Copy = icon("Copy", 1.8f, rect(8f, 8f, 12f, 12f, 2.5f), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")
    val Upload = icon("Upload", 2f, "M12 16V5M7 10l5-5 5 5", "M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3")
    val Download = icon("Download", 2f, "M12 4v11M7 10l5 5 5-5M5 20h14")
    val Play = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M8 5.5v13l11-6.5z"), fill = SolidColor(Color.Black))
    }.build()
}
