package com.flowable.atlas.settings

import com.flowable.atlas.design.DesignAppListUi
import com.flowable.atlas.design.DesignAuthMode
import com.flowable.atlas.design.DesignClient
import com.flowable.atlas.design.DesignCreateTokenDialog
import com.flowable.atlas.design.DesignCredentials
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
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
import com.intellij.ui.components.ActionLink
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
 * Connections (formerly the "Configure Design Connection" dialog): server, credentials,
 * workspace/app picked from live server lists, and the project-relative target folder for the
 * pulled ZIP. The saved workspace/app show immediately (no network needed to hit Apply) and the
 * lists refresh silently once the credentials are restored; "Refresh workspaces" reloads explicitly
 * and doubles as the connection test — for either auth mode.
 *
 * Authentication is a [DesignAuthMode] picked from a combo: username/password, or a Design personal
 * access token that can be minted right here ([DesignCreateTokenDialog]) or managed in Design. Only the
 * selected mode's row is visible, but both keep their values, so switching modes back and forth loses
 * neither secret — matching the two separate keychain entries in [DesignCredentials].
 *
 * Server/mode/workspace/app/folder persist in the VCS-shared project settings
 * ([FlowableAtlasProjectSettings]); password and token go to the PasswordSafe ([DesignCredentials]).
 * Network and keychain access stay off the EDT.
 */
class DesignConnectionPanel(private val project: Project) : JPanel(), Disposable {

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    private val baseUrlField = JBTextField(30).apply {
        emptyText.text = "http://localhost:8888/flowable-design"
    }
    private val usernameField = JBTextField(12)
    private val passwordField = JBPasswordField().apply { columns = 12 }
    private val authModeCombo = JComboBox(DesignAuthMode.values()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val tokenField = JBPasswordField().apply { columns = 32 }
    private val manageTokensLink = ActionLink("Manage in Design…") { openTokenManagement() }
    private val createTokenButton = JButton("Create Token…")

    /** Both rows stay in the layout; only the selected mode's is visible, so a switch keeps both values. */
    private val basicRow = row(JBLabel("Username:"), usernameField, JBLabel("  Password:"), passwordField)
    private val tokenRow = row(JBLabel("Access token:"), tokenField, manageTokensLink, createTokenButton)

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

    @Volatile
    private var loadedToken: String = ""

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
        createTokenButton.addActionListener { createToken() }
        workspaceCombo.addActionListener {
            if (!populating) selectedWorkspace()?.let { loadApps(it.key) }
        }
        // Switching mode must not fire a refresh: a half-pasted token would only produce 401 noise.
        authModeCombo.addActionListener {
            if (!populating) {
                updateAuthRows()
                status.text = ""
            }
        }
        add(row(JBLabel("Server URL:"), baseUrlField))
        add(row(JBLabel("Authentication:"), authModeCombo))
        add(basicRow)
        add(tokenRow)
        add(row(refreshButton))
        add(row(JBLabel("Workspace:"), workspaceCombo))
        add(row(JBLabel("Apps:"), JBScrollPane(appList).apply { preferredSize = Dimension(320, 120) }))
        add(row(JBLabel(" "), JBLabel(
            "<html><small>The shared default for this project. Ticking apps in the Atlas Hub is a " +
                "personal override and leaves this untouched.</small></html>",
        ).apply { foreground = JBColor.GRAY }))
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
        populating = true
        try {
            authModeCombo.selectedItem = settings.designAuthMode
        } finally {
            populating = false
        }
        updateAuthRows()
        seedFromSettings()
        prefillCredentials()
        suggestTargetFolder()
        status.text = ""
    }

    /**
     * Both secrets are compared in **both** modes — against the cached values, so this never touches the
     * keychain on the EDT — so that editing a password and then switching mode still reports modified.
     */
    fun isModified(): Boolean =
        DesignClient.normalizeBaseUrl(baseUrlField.text) != settings.designBaseUrl ||
            selectedAuthMode() != settings.designAuthMode ||
            usernameField.text.trim() != loadedUsername ||
            String(passwordField.password) != loadedPassword ||
            String(tokenField.password) != loadedToken ||
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
        val token = DesignClient.normalizeAccessToken(String(tokenField.password))
        settings.designBaseUrl = baseUrl
        settings.designAuthMode = selectedAuthMode()
        settings.designWorkspaceKey = selectedWorkspace()?.key.orEmpty()
        settings.designAppKeys = checkedAppKeys().toMutableList()
        settings.designTargetFolder = folder.joinToString("/")
            .ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER }
        tokenField.text = token   // reflect the normalization back, so isModified() settles
        loadedUsername = username
        loadedPassword = password
        loadedToken = token
        if (baseUrl.isNotBlank()) {
            // PasswordSafe can block on the OS keychain — save off the EDT (same as the old dialog).
            // Never clear the other mode's secret: switching back has to keep working.
            ApplicationManager.getApplication().executeOnPooledThread {
                if (username.isNotBlank()) runCatching { DesignCredentials.save(baseUrl, username, password) }
                if (token.isNotBlank()) runCatching { DesignCredentials.saveToken(baseUrl, token) }
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

    /**
     * PasswordSafe access can block on the OS keychain, so prefill runs on a pooled thread. Both modes'
     * secrets are loaded in the same trip and applied in one `invokeLater`, so switching mode afterwards
     * needs no further keychain access.
     */
    private fun prefillCredentials() {
        loadedUsername = ""
        loadedPassword = ""
        loadedToken = ""
        usernameField.text = ""
        passwordField.text = ""
        tokenField.text = ""
        val baseUrl = settings.designBaseUrl
        if (baseUrl.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val credentials = runCatching { DesignCredentials.load(baseUrl) }.getOrNull()
            val token = runCatching { DesignCredentials.loadToken(baseUrl) }.getOrNull()
            if (credentials == null && token == null) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                loadedUsername = credentials?.userName.orEmpty()
                loadedPassword = credentials?.getPasswordAsString().orEmpty()
                loadedToken = token.orEmpty()
                if (usernameField.text.isBlank()) usernameField.text = loadedUsername
                if (passwordField.password.isEmpty()) passwordField.text = loadedPassword
                if (tokenField.password.isEmpty()) tokenField.text = loadedToken
                loadWorkspaces(quiet = true)
            }, ModalityState.any())
        }
    }

    private fun selectedAuthMode(): DesignAuthMode =
        authModeCombo.selectedItem as? DesignAuthMode ?: DesignAuthMode.BASIC

    /** Shows only the selected mode's credential row; both keep their values, so switching is lossless. */
    private fun updateAuthRows() {
        val token = selectedAuthMode() == DesignAuthMode.ACCESS_TOKEN
        basicRow.isVisible = !token
        tokenRow.isVisible = token
        revalidate()
        repaint()
    }

    private fun openTokenManagement() {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the Design server URL first")
            return
        }
        BrowserUtil.browse(DesignClient.tokenManagementUrl(baseUrl))
    }

    /**
     * Mints a personal access token and drops it into the token field — the dialog only collects input,
     * the request itself runs off the EDT. The value is not stored until Apply, which the status says.
     */
    private fun createToken() {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the Design server URL first")
            return
        }
        val dialog = DesignCreateTokenDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        val username = dialog.username
        val password = dialog.password
        val name = dialog.tokenName
        val validFor = dialog.validFor
        createTokenButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = "Creating access token…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DesignClient.createAccessToken(baseUrl, username, password, name, validFor)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                createTokenButton.isEnabled = true
                when (result) {
                    is DesignClient.Result.Success -> {
                        tokenField.text = result.value.value
                        populating = true
                        try {
                            authModeCombo.selectedItem = DesignAuthMode.ACCESS_TOKEN
                        } finally {
                            populating = false
                        }
                        updateAuthRows()
                        val expires = result.value.expirationTime?.let { ", expires $it" } ?: ""
                        status.foreground = JBColor.foreground()
                        status.text = "Access token created$expires — click Apply to store it"
                    }
                    is DesignClient.Result.Failed -> showError(result.message)
                }
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
        if (baseUrl.isBlank()) {
            if (!quiet) showError("Enter the Design server URL first")
            return null
        }
        val auth = when (selectedAuthMode()) {
            DesignAuthMode.BASIC -> {
                if (usernameField.text.isBlank()) {
                    if (!quiet) showError("Enter server URL and username first")
                    return null
                }
                DesignClient.Auth.Basic(usernameField.text.trim(), String(passwordField.password))
            }
            DesignAuthMode.ACCESS_TOKEN -> {
                val token = DesignClient.normalizeAccessToken(String(tokenField.password))
                if (token.isBlank()) {
                    if (!quiet) showError("Paste a Flowable Design access token first")
                    return null
                }
                DesignClient.Auth.Token(token)
            }
        }
        return DesignClient.Connection(baseUrl, auth)
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

    // list rendering/reading shared with the Atlas Hub — see [DesignAppListUi]
    private fun populateApps(items: List<DesignClient.App>, checkedKeys: Set<String>) =
        DesignAppListUi.populateApps(appList, items, checkedKeys)

    private fun checkedAppKeys(): List<String> = DesignAppListUi.checkedAppKeys(appList)

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
