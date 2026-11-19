package com.flowable.atlas.environment

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/**
 * The developer's environment list as a file in their **home directory** —
 * `~/.flowable-atlas/environments.xml` — beside the copy the IDE keeps in its own configuration.
 *
 * ### Why the IDE's configuration is not enough
 *
 * [AtlasEnvironments] promises a list that is the same in every project. On a desktop IDE the
 * application configuration keeps that promise. Under Remote Development it does not: the plugin runs
 * on the backend, and a backend's "application" configuration belongs to one project on one host — a
 * second project, another checkout of the same one, or a rebuilt backend starts from an empty list.
 * An environment added yesterday was simply in a directory today's backend never reads.
 *
 * The home directory is the one place every IDE process of a user on a machine agrees on, so the list
 * goes there too, and that file wins on load.
 *
 * ### What is in it
 *
 * Exactly what `flowable-atlas-environments.xml` holds: names, URLs, usernames, the auth *mode*.
 * **No secret** — those stay in the IDE password safe, keyed by base URL, and are asked for once where
 * that safe is new.
 *
 * Pure file handling, so it is tested against a temp directory; only [defaultPath] looks at the
 * application.
 */
internal object HomeEnvironmentsFile {

    private val LOG = logger<HomeEnvironmentsFile>()

    /** Points the mirror somewhere else — what a test sets, and an escape hatch for an unusual home. */
    const val PATH_PROPERTY = "flowable.atlas.environments.file"

    /**
     * Where the mirror lives, or null for "do not mirror": a unit test — and a catalog built without an
     * application at all — must never read or write a developer's real home directory.
     */
    fun defaultPath(): Path? {
        System.getProperty(PATH_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val application = ApplicationManager.getApplication() ?: return null
        if (application.isUnitTestMode) return null
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        return Path.of(home, ".flowable-atlas", "environments.xml")
    }

    /** When [path] was last written, or null while it does not exist — the cheap "did it change?" probe. */
    fun stamp(path: Path): FileTime? = try {
        if (Files.isRegularFile(path)) Files.getLastModifiedTime(path) else null
    } catch (e: Exception) {
        null
    }

    /** The list in [path], or null when there is no file or it cannot be read — never a half-read one. */
    fun read(path: Path): AtlasEnvironments.State? {
        if (!Files.isRegularFile(path)) return null
        return try {
            XmlSerializer.deserialize(JDOMUtil.load(path), AtlasEnvironments.State::class.java)
        } catch (e: Exception) {
            LOG.warn("Could not read $path; keeping the environment list the IDE has", e)
            null
        }
    }

    /**
     * Writes [state] to [path] through a sibling temp file and a rename, so a second IDE process reading
     * at that moment sees the old list or the new one. A failure is logged and swallowed: the IDE's own
     * copy is still saved, and a read-only home must not make adding an environment fail.
     */
    fun write(path: Path, state: AtlasEnvironments.State): Boolean = try {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "environments", ".tmp")
        try {
            JDOMUtil.write(XmlSerializer.serialize(state), temp)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        true
    } catch (e: Exception) {
        LOG.warn("Could not write $path; the environment list is only in this IDE's configuration", e)
        false
    }
}
