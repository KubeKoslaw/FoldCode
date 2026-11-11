package io.fold.runtime

import java.io.File

/**
 * VS Code settings for a phone, written once on first start (never over the user's own file):
 * theme follows Android's dark mode, the AI chat panel starts hidden (still one tap away),
 * no Restricted Mode for local projects, no Welcome page, no telemetry, autosave on.
 */
object VsCodeDefaults {
    private val SETTINGS = """
        {
          "window.autoDetectColorScheme": true,
          "workbench.preferredDarkColorTheme": "Default Dark Modern",
          "workbench.preferredLightColorTheme": "Default Light Modern",
          "workbench.colorTheme": "Default Dark Modern",
          "workbench.secondarySideBar.defaultVisibility": "hidden",
          "workbench.startupEditor": "none",
          "workbench.tips.enabled": false,
          "security.workspace.trust.enabled": false,
          "telemetry.telemetryLevel": "off",
          "update.mode": "none",
          "files.autoSave": "afterDelay",
          "editor.minimap.enabled": false,
          "editor.fontSize": 15,
          "terminal.integrated.fontSize": 14
        }
    """.trimIndent() + "\n"

    fun settingsFile(rt: Runtime) = File(rt.home, ".local/share/code-server/User/settings.json")

    /** Returns true if the defaults were written now. */
    fun ensure(rt: Runtime): Boolean {
        File(rt.home, "projects").mkdirs()
        val f = settingsFile(rt)
        if (f.exists()) return false
        f.parentFile?.mkdirs()
        f.writeText(SETTINGS)
        return true
    }
}
