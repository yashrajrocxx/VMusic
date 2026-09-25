package app.pulse.android.preferences

import android.net.Uri
import android.provider.DocumentsContract
import app.pulse.android.GlobalPreferencesHolder

object LocalPreferences : GlobalPreferencesHolder() {
    /**
     * User-picked music folders, stored as path segments ("Music",
     * "Download/Songs") decoded from document-tree URIs. Empty = scan
     * everywhere except system audio. An explicitly added folder always wins
     * over the automatic system-audio exclusions.
     */
    var musicFolders by stringSet(emptySet())

    fun addFolder(treeUri: Uri): Boolean {
        val folder = treeUriToPath(treeUri) ?: return false
        musicFolders = musicFolders + folder
        return true
    }

    fun removeFolder(folder: String) {
        musicFolders = musicFolders - folder
    }

    fun treeUriToPath(treeUri: Uri): String? {
        return runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
                .substringAfter(':', "")
                .trim('/')
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
