package dev.kaff.desktop

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Properties

/**
 * Who this computer is to the phone (a stable random id, so an approval can be remembered there)
 * and the few things the app keeps between runs. Stored in %APPDATA%\Kaff.
 */
class Identity private constructor(private val file: Path, private val props: Properties) {

    val deviceId: String get() = props.getProperty("device")

    val computerName: String = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Windows")

    /** Where files from the phone land: Downloads\Kaff unless changed. */
    var downloads: Path
        get() = props.getProperty("downloads")?.let(Paths::get) ?: Paths.get(System.getProperty("user.home"), "Downloads", "Kaff")
        set(value) = put("downloads", value.toString())

    /** The phone connected last time; the app reconnects to it by itself. */
    var lastPhone: String?
        get() = props.getProperty("lastPhone")
        set(value) = put("lastPhone", value)

    var language: String?
        get() = props.getProperty("language")
        set(value) = put("language", value)

    private fun put(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { props.store(it, "Kaff for Windows") }
    }

    companion object {
        fun load(): Identity {
            val dir = Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Kaff")
            val file = dir.resolve("settings.properties")
            val props = Properties()
            if (Files.exists(file)) Files.newInputStream(file).use(props::load)
            val identity = Identity(file, props)
            if (props.getProperty("device") == null) {
                val id = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                identity.put("device", id)
            }
            return identity
        }
    }
}
