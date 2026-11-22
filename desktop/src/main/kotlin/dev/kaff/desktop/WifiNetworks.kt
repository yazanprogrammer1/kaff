package dev.kaff.desktop

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Kaff's own network (the phone's Wi-Fi Direct group, "DIRECT-KF-…") through Windows' netsh:
 * listed after a fresh scan, and joined with a Wi-Fi profile so Windows remembers the password.
 */
object WifiNetworks {

    /** Asks the Wi-Fi adapter for a fresh scan; netsh alone only reads a cached list. */
    fun scan() {
        val script = """
            Add-Type -TypeDefinition @'
            using System; using System.Runtime.InteropServices;
            public static class KaffWlan {
              [DllImport("wlanapi.dll")] static extern int WlanOpenHandle(uint v, IntPtr r, out uint nv, out IntPtr h);
              [DllImport("wlanapi.dll")] static extern int WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
              [DllImport("wlanapi.dll")] static extern int WlanScan(IntPtr h, ref Guid g, IntPtr s, IntPtr i, IntPtr r);
              [DllImport("wlanapi.dll")] static extern int WlanCloseHandle(IntPtr h, IntPtr r);
              public static void Scan() {
                uint v; IntPtr h; if (WlanOpenHandle(2, IntPtr.Zero, out v, out h) != 0) return;
                IntPtr list; if (WlanEnumInterfaces(h, IntPtr.Zero, out list) == 0) {
                  int n = Marshal.ReadInt32(list);
                  for (int i = 0; i < n; i++) {
                    IntPtr p = new IntPtr(list.ToInt64() + 8 + i * 532);
                    Guid g = (Guid)Marshal.PtrToStructure(p, typeof(Guid));
                    WlanScan(h, ref g, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                  }
                }
                WlanCloseHandle(h, IntPtr.Zero);
              }
            }
            '@
            [KaffWlan]::Scan()
        """.trimIndent()
        run("powershell", "-NoProfile", "-NonInteractive", "-Command", script, timeoutSec = 15)
    }

    /** SSIDs of Kaff networks in range. */
    fun kaffNetworksInRange(): List<String> =
        Regex("""^SSID \d+ : (.+)$""", RegexOption.MULTILINE).findAll(run("netsh", "wlan", "show", "networks"))
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith(PREFIX) }
            .distinct()
            .toList()

    fun currentSsid(): String? =
        Regex("""^\s+SSID\s+:\s(.+)$""", RegexOption.MULTILINE).find(run("netsh", "wlan", "show", "interfaces"))?.groupValues?.get(1)?.trim()

    fun hasProfile(ssid: String): Boolean = run("netsh", "wlan", "show", "profiles").lines().any { it.substringAfter(':', "").trim() == ssid }

    /** Saves the password as a Windows Wi-Fi profile (so it is asked once) and connects. */
    fun join(ssid: String, password: String?): Boolean {
        if (password != null) {
            val profile = File.createTempFile("kaff-wifi", ".xml")
            try {
                profile.writeText(profileXml(ssid, password))
                run("netsh", "wlan", "add", "profile", "filename=${profile.absolutePath}", "user=current")
            } finally {
                profile.delete()
            }
        }
        return run("netsh", "wlan", "connect", "name=$ssid", "ssid=$ssid").contains("success", ignoreCase = true)
    }

    fun connect(ssid: String): Boolean = run("netsh", "wlan", "connect", "name=$ssid", "ssid=$ssid").contains("success", ignoreCase = true)

    private fun profileXml(ssid: String, password: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>${esc(ssid)}</name>
  <SSIDConfig><SSID><name>${esc(ssid)}</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM><security>
    <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
    <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>${esc(password)}</keyMaterial></sharedKey>
  </security></MSM>
</WLANProfile>"""
    }

    private fun run(vararg command: String, timeoutSec: Long = 10): String = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes()
        process.waitFor(timeoutSec, TimeUnit.SECONDS)
        // netsh writes in the console's OEM code page; English keys survive either way.
        String(out, Charset.forName(System.getProperty("sun.stdout.encoding") ?: "UTF-8"))
    } catch (e: Exception) {
        ""
    }

    const val PREFIX = "DIRECT-KF-"
}
