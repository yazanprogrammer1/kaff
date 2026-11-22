package dev.kaff.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    val identity = Identity.load()
    // Arabic unless the user picked English, or Windows itself is not in Arabic and nothing was picked.
    Strings.t = when (identity.language) {
        "en" -> En
        "ar" -> Ar
        else -> if (Locale.getDefault().language == "ar") Ar else En
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = KaffController(scope, identity).also { it.start() }
    val icon = BitmapPainter(ImageIO.read(Thread.currentThread().contextClassLoader.getResource("kaff.png")).toComposeImageBitmap())

    application {
        var language by remember { mutableStateOf(Strings.t) }
        val tray = rememberTrayState()
        var visible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            for ((title, body) in controller.notifications) tray.sendNotification(Notification(title, body, Notification.Type.Info))
        }
        Tray(icon = icon, state = tray, tooltip = "Kaff", onAction = { visible = true })

        val windowState = rememberWindowState(size = DpSize(1080.dp, 740.dp), position = WindowPosition.PlatformDefault)
        Window(
            onCloseRequest = ::exitApplication,
            visible = visible,
            title = language.appName,
            icon = icon,
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(860, 600)
            var dragging by remember { mutableStateOf(false) }
            // Files dragged from Explorer anywhere onto the window go to the phone.
            DisposableEffect(window) {
                window.contentPane.dropTarget = DropTarget(
                    window.contentPane,
                    DnDConstants.ACTION_COPY,
                    object : DropTargetAdapter() {
                        override fun dragEnter(e: DropTargetDragEvent) {
                            dragging = true
                        }

                        override fun dragExit(e: DropTargetEvent) {
                            dragging = false
                        }

                        override fun drop(e: DropTargetDropEvent) {
                            dragging = false
                            e.acceptDrop(DnDConstants.ACTION_COPY)
                            @Suppress("UNCHECKED_CAST")
                            val files = runCatching { e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrDefault(emptyList())
                            controller.send(files.map(File::toPath))
                            e.dropComplete(true)
                        }
                    },
                )
                onDispose { window.contentPane.dropTarget = null }
            }
            KaffTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (language.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    key(language) {
                        KaffApp(
                            controller = controller,
                            dragging = dragging,
                            onChooseFiles = {
                                val dialog = FileDialog(window, language.chooseFiles, FileDialog.LOAD).apply { isMultipleMode = true }
                                dialog.isVisible = true
                                controller.send(dialog.files.orEmpty().map(File::toPath))
                            },
                            onToggleLanguage = {
                                val next = if (language.rtl) En else Ar
                                Strings.t = next
                                language = next
                                identity.language = if (next.rtl) "ar" else "en"
                            },
                        )
                    }
                }
            }
        }
    }
}
