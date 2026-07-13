package com.flowable.atlas.settings

import com.flowable.atlas.design.DesignClient
import com.flowable.atlas.design.DesignCredentials
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Flowable Design connection editor, embedded in Settings → Tools → Flowable Atlas →
 * Connections (formerly the "Configure Design Connection" dialog): server + basic-auth credentials,
 * workspace/app picked from live server lists, and the project-relative target folder for the
 * pulled ZIP. The saved workspace/app show immediately (no network needed to hit Apply) and the
 * lists refresh silently once the credentials are restored; "Refresh workspaces" reloads explicitly
 * and doubles as the connection test. Server/workspace/app/folder persist in the VCS-shared project
 * settings ([FlowableAtlasProjectSettings]); username/password go to the PasswordSafe
 * ([DesignCredentials]). Network and keychain access stay off the EDT.
 */
class DesignConnectionPanel(private val project: Project) : JPanel(), Disposable {

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    private val baseUrlField = JBTextField(30).apply {
        emptyText.text = "http://localhost:8888/flowable-design"
    }
    private val usernameField = JBTextField(12)
    private val passwordField = JBPasswordField().apply { columns = 12 }
    private val refreshButton = JButton("Refresh Workspaces", AllIcons.Actions.Refresh)
    private val workspaceCombo = JComboBox<DesignClient.Workspace>().apply {
        renderer = SimpleListCellRenderer.create("") { ws ->
            if (ws.name == ws.key) ws.key else "${ws.name} (${ws.key})"
        }
    }
    /** Multi-select: several apps in the chosen workspace can be pulled in one go. */
    private val appList = CheckBoxList<DesignClient.App>()
    private val targetFolderField = TextFieldWithBrowseButton().apply {
        textField.columns = 25
    }
    private val status = JBLabel()

    /** Guards the workspace-combo listener against firing during programmatic population. */
    private var populating = false

    /** Credentials as loaded from the PasswordSafe, to detect modification without keychain reads. */
    @Volatile
    private var loadedUsername: String = ""

    @Volatile
    private var loadedPassword: String = ""

    private var disposed = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        targetFolderField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Target Folder")
                .withDescription("The pulled app ZIP is written into this folder inside the project"),
        )
        refreshButton.addActionListener { loadWorkspaces() }
        workspaceCombo.addActionListener {
            if (!populating) selectedWorkspace()?.let { loadApps(it.key) }
        }
        add(row(JBLabel("Server URL:"), baseUrlField))
        add(row(JBLabel("Username:"), usernameField, JBLabel("  Password:"), passwordField))
        add(row(refreshButton))
        add(row(JBLabel("Workspace:"), workspaceCombo))
        add(row(JBLabel("Apps:"), JBScrollPane(appList).apply { preferredSize = Dimension(320, 120) }))
        add(row(JBLabel("Target folder:"), targetFolderField))
        add(row(status))
    }

    private fun row(vararg parts: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { parts.forEach { add(it) } }

    // ---- Configurable contract, driven by the surrounding page ------------------------------

    fun reset() {
        baseUrlField.text = settings.designBaseUrl
        targetFolderField.text =
            settings.designTargetFolder.ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER }
        seedFromSettings()
        prefillCredentials()
        suggestTargetFolder()
        status.text = ""
    }

    fun isModified(): Boolean =
        DesignClient.normalizeBaseUrl(baseUrlField.text) != settings.designBaseUrl ||
            usernameField.text.trim() != loadedUsername ||
            String(passwordField.password) != loadedPassword ||
            (selectedWorkspace()?.key ?: "") != settings.designWorkspaceKey ||
            checkedAppKeys().toSet() != settings.designAppKeys.toSet() ||
            targetFolderField.text.trim() != settings.designTargetFolder

    @Throws(ConfigurationException::class)
    fun apply() {
        val folder = relativeTargetFolder()
            ?: throw ConfigurationException("The Design target folder must be a folder inside the project")
        val excluded = folder.map { it.toString() }.firstOrNull { it in ModelPaths.EXCLUDE_DIRS }
        if (excluded != null) {
            throw ConfigurationException(
                "Folder '$excluded' is excluded from indexing — models pulled there would be ignored",
            )
        }
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        val username = usernameField.text.trim()
        val password = String(passwordField.password)
        settings.designBaseUrl = baseUrl
        settings.designWorkspaceKey = selectedWorkspace()?.key.orEmpty()
        settings.designAppKeys = checkedAppKeys().toMutableList()
        settings.designTargetFolder = folder.joinToString("/")
            .ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER }
        loadedUsername = username
        loadedPassword = password
        if (baseUrl.isNotBlank() && username.isNotBlank()) {
            // PasswordSafe can block on the OS keychain — save off the EDT (same as the old dialog).
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { DesignCredentials.save(baseUrl, username, password) }
            }
        }
        // Let status surfaces (the Atlas Hub) re-read the just-saved connection immediately, instead
        // of waiting for an unrelated event or a tool-window reopen.
        project.messageBus.syncPublisher(AtlasEvents.TOPIC).designSettingsChanged()
    }

    override fun dispose() {
        disposed = true
    }

    // ---- population (ported from the retired DesignPullDialog) ------------------------------

    /**
     * Shows the persisted workspace/app immediately (as key-only placeholders), so the page doesn't
     * force a server round-trip before Apply; the silent background refresh replaces them with the
     * live lists, keeping the selection by key.
     */
    private fun seedFromSettings() {
        populate(
            workspaceCombo,
            if (settings.designWorkspaceKey.isBlank()) emptyList()
            else listOf(DesignClient.Workspace(settings.designWorkspaceKey, settings.designWorkspaceKey)),
            settings.designWorkspaceKey,
        ) { it.key }
        val keys = settings.designAppKeys
        populateApps(keys.map { DesignClient.App(it, it, null, null) }, keys.toSet())
    }

    /** PasswordSafe access can block on the OS keychain, so prefill runs on a pooled thread. */
    private fun prefillCredentials() {
        loadedUsername = ""
        loadedPassword = ""
        usernameField.text = ""
        passwordField.text = ""
        val baseUrl = settings.designBaseUrl
        if (baseUrl.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val credentials = runCatching { DesignCredentials.load(baseUrl) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                loadedUsername = credentials.userName.orEmpty()
                loadedPassword = credentials.getPasswordAsString().orEmpty()
                if (usernameField.text.isBlank()) usernameField.text = loadedUsername
                if (passwordField.password.isEmpty()) passwordField.text = loadedPassword
                loadWorkspaces(quiet = true)
            }, ModalityState.any())
        }
    }

    /**
     * Pre-suggests the folder that already holds the project's indexed model archives — where the
     * manually exported ZIPs live today — unless a folder was configured or typed already.
     */
    private fun suggestTargetFolder() {
        val default = FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER
        if (settings.designTargetFolder.isNotBlank() && settings.designTargetFolder != default) return
        val base = AtlasProjectRootService.getInstance(project).activeProjectDir()?.toString() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val suggestion = runCatching { modelArchiveFolder(base) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                val current = targetFolderField.text.trim()
                if (!disposed && (current.isBlank() || current == default)) targetFolderField.text = suggestion
            }, ModalityState.any())
        }
    }

    /**
     * The project-relative folder that holds the most indexed models today — the archive's own
     * folder for models mounted from a `.zip`/`.bar`, or the file's folder for loose models. Null
     * when nothing is indexed yet. Reads only the cached index — never triggers a scan.
     */
    private fun modelArchiveFolder(basePath: String): String? {
        val index = project.service<FlowableModelIndexService>().cachedOrNull() ?: return null
        val jarFs = JarFileSystem.getInstance()
        val folders = index.allDistinct().mapNotNull { entry ->
            (jarFs.getVirtualFileForJar(entry.file) ?: entry.file).parent?.path
        }
        val best = folders.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val relative = runCatching { Path.of(basePath).relativize(Path.of(best)).normalize() }.getOrNull() ?: return null
        if (relative.startsWith("..")) return null
        return relative.joinToString("/").takeUnless { it.isBlank() }
    }

    private fun currentConnection(quiet: Boolean = false): DesignClient.Connection? {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank() || usernameField.text.isBlank()) {
            if (!quiet) showError("Enter server URL and username first")
            return null
        }
        return DesignClient.Connection(baseUrl, usernameField.text.trim(), String(passwordField.password))
    }

    private fun loadWorkspaces(quiet: Boolean = false) {
        val conn = currentConnection(quiet) ?: return
        refreshButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = "Loading workspaces…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DesignClient.listWorkspaces(conn)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                refreshButton.isEnabled = true
                when (result) {
                    is DesignClient.Result.Success -> {
                        populate(workspaceCombo, result.value, selectedWorkspaceKey()) { it.key }
                        if (result.value.isEmpty()) {
                            showError("No workspaces visible for this user")
                        } else {
                            status.text = ""
                            selectedWorkspace()?.let { loadApps(it.key) }
                        }
                    }
                    is DesignClient.Result.Failed -> showError(result.message)
                }
            }, ModalityState.any())
        }
    }

    private fun loadApps(workspaceKey: String) {
        val conn = currentConnection(quiet = true) ?: return
        // Keep whatever is checked now (seeded from settings on open, or the user's picks) checked
        // after the live list replaces the placeholders — snapshot before the async replace.
        val checked = checkedAppKeys().toSet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DesignClient.listApps(conn, workspaceKey)
            ApplicationManager.getApplication().invokeLater({
                if (disposed || selectedWorkspace()?.key != workspaceKey) return@invokeLater
                when (result) {
                    is DesignClient.Result.Success -> {
                        populateApps(result.value, checked)
                        status.text = if (result.value.isEmpty()) "No apps in workspace '$workspaceKey'" else ""
                    }
                    is DesignClient.Result.Failed -> showError(result.message)
                }
            }, ModalityState.any())
        }
    }

    /** The key to keep selected across a refresh: the current pick, falling back to the saved one. */
    private fun selectedWorkspaceKey(): String = selectedWorkspace()?.key ?: settings.designWorkspaceKey

    /** Checkbox text for an app: display name (+ key when they differ) and version when known. */
    private fun appLabel(app: DesignClient.App): String {
        val label = if (app.name == app.key) app.key else "${app.name} (${app.key})"
        return label + (app.version?.let { " v$it" } ?: "")
    }

    /** Rebuilds the app checkbox list, checking every app whose key is in [checkedKeys]. */
    private fun populateApps(items: List<DesignClient.App>, checkedKeys: Set<String>) {
        appList.clear()
        items.forEach { app -> appList.addItem(app, appLabel(app), app.key in checkedKeys) }
    }

    private fun checkedApps(): List<DesignClient.App> =
        (0 until appList.model.size).mapNotNull { i -> appList.getItemAt(i)?.takeIf { appList.isItemSelected(i) } }

    private fun checkedAppKeys(): List<String> = checkedApps().map { it.key }

    private fun <T> populate(combo: JComboBox<T>, items: List<T>, persistedKey: String, key: (T) -> String) {
        populating = true
        try {
            combo.model = DefaultComboBoxModel<T>().apply { items.forEach(::addElement) }
            items.firstOrNull { key(it) == persistedKey }?.let { combo.selectedItem = it }
        } finally {
            populating = false
        }
    }

    private fun showError(message: String) {
        status.foreground = JBColor.RED
        status.text = message
    }

    private fun selectedWorkspace(): DesignClient.Workspace? = workspaceCombo.selectedItem as? DesignClient.Workspace

    /**
     * The target folder as a normalized project-relative path, or null when it escapes the project
     * or lies outside it. A blank field falls back to the default folder.
     */
    private fun relativeTargetFolder(): Path? {
        val text = targetFolderField.text.trim().ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER }
        val base = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        return try {
            val path = Path.of(text)
            val relative = (if (path.isAbsolute) base.relativize(path) else path).normalize()
            relative.takeUnless { it.toString().isBlank() || it.startsWith("..") }
        } catch (e: InvalidPathException) {
            null
        } catch (e: IllegalArgumentException) {
            null   // relativize: different roots (other drive/filesystem)
        }
    }
}
