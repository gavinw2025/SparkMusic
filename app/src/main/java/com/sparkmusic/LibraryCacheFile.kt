package com.sparkmusic

import java.io.File

/**
 * File mechanics for the library index.
 *
 * Kept free of Android types so the safeguards can be verified by JVM tests. The index is written
 * to a temporary file first and only then swapped in, so a failed write never truncates a valid
 * index.
 */
internal object LibraryCacheFile {

    private const val TEMP_SUFFIX = ".tmp"
    private const val BACKUP_SUFFIX = ".bak"

    /** Reads [file], or returns null when it is missing or unreadable. */
    fun read(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: Exception) {
        null
    }

    /**
     * Writes [content] to a temporary file next to [file], verifies it, and replaces [file].
     *
     * @return true only when the new content is in place; on failure the previous index is kept.
     */
    fun write(file: File, content: String): Boolean {
        val directory = file.parentFile ?: return false
        if (!directory.isDirectory) return false

        val temp = File(directory, file.name + TEMP_SUFFIX)
        val bytes = content.toByteArray(Charsets.UTF_8)

        return try {
            temp.writeBytes(bytes)
            if (temp.length() != bytes.size.toLong()) {
                temp.delete()
                return false
            }
            if (replace(temp, file)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    /**
     * POSIX rename replaces the destination atomically. Platforms that refuse to rename over an
     * existing file use a backup step so a failure can still restore the previous index.
     */
    private fun replace(temp: File, destination: File): Boolean {
        if (temp.renameTo(destination)) return true
        if (!destination.isFile) return false

        val backup = File(destination.parentFile, destination.name + BACKUP_SUFFIX)
        backup.delete()
        if (!destination.renameTo(backup)) return false

        if (temp.renameTo(destination)) {
            backup.delete()
            return true
        }
        backup.renameTo(destination)
        return false
    }
}
