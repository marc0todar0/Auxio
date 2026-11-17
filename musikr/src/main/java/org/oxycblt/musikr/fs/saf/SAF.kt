/*
 * Copyright (c) 2025 Auxio Project
 * SAF.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.musikr.fs.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore as AOSPMediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File as JavaFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Directory
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.track.LocationObserver
import org.oxycblt.musikr.util.tryAsync
import org.oxycblt.musikr.util.tryAsyncWith
import org.oxycblt.musikr.util.tryAwaitAll

@OptIn(ExperimentalCoroutinesApi::class)
class SAF
private constructor(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val query: Query,
) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        tryAsyncWith(files, Dispatchers.Main) {
            val exclude = query.exclude.mapTo(mutableSetOf()) { it.path }
            query.source
                .map { location ->
                    val treeDocumentId = DocumentsContract.getTreeDocumentId(location.uri)
                    val directRoot = resolveDirectRoot(location.uri, treeDocumentId)
                    if (directRoot != null) {
                        exploreDirectImpl(
                            location.uri,
                            treeDocumentId,
                            directRoot,
                            location.path,
                            null,
                            exclude,
                            files,
                        )
                    } else {
                        exploreDirectoryImpl(
                            location.uri,
                            treeDocumentId,
                            location.path,
                            null,
                            exclude,
                            files,
                        )
                    }
                }
                .tryAwaitAll()
        }
    }

    /**
     * Find the real directory backing a document tree, if we are allowed to read it directly.
     *
     * Going through the documents provider costs a binder round-trip and a pile of provider-side
     * work per directory. With all-files access the same tree can be walked with plain file calls,
     * producing the exact same document URIs.
     */
    private fun resolveDirectRoot(rootUri: Uri, treeDocumentId: String): JavaFile? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (rootUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        if (!Environment.isExternalStorageManager()) return null
        val split = treeDocumentId.indexOf(':')
        if (split < 0) return null
        val rootId = treeDocumentId.substring(0, split)
        val relative = treeDocumentId.substring(split + 1)
        val base =
            if (rootId == PRIMARY_ROOT_ID) {
                Environment.getExternalStorageDirectory()
            } else {
                JavaFile("/storage/$rootId")
            }
        val directory = if (relative.isEmpty()) base else JavaFile(base, relative)
        return directory.takeIf { it.isDirectory && it.canRead() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun CoroutineScope.exploreDirectImpl(
        rootUri: Uri,
        documentId: String,
        directory: JavaFile,
        relativePath: Path,
        parent: Deferred<Directory>?,
        exclude: Set<Path>,
        files: Channel<File>,
    ): Deferred<Result<Unit>> =
        tryAsync(Dispatchers.IO) {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, documentId)
            val directoryDeferred = CompletableDeferred<Directory>()
            val recursive = mutableListOf<Deferred<Result<Unit>>>().takeIf { query.multithread }
            val children = mutableListOf<File>()
            for (name in directory.list().orEmpty()) {
                if (!query.withHidden && name.startsWith(".")) {
                    continue
                }
                val newPath = relativePath.file(name)
                if (newPath in exclude) {
                    continue
                }
                val child = JavaFile(directory, name)
                val stat =
                    try {
                        Os.stat(child.path)
                    } catch (e: ErrnoException) {
                        continue
                    }
                // Mirrors how the external storage provider derives its document ids.
                val childId =
                    if (documentId.endsWith(":")) documentId + name else "$documentId/$name"
                if (OsConstants.S_ISDIR(stat.st_mode)) {
                    val subtask =
                        exploreDirectImpl(
                            rootUri,
                            childId,
                            child,
                            newPath,
                            directoryDeferred,
                            exclude,
                            files,
                        )
                    if (recursive != null) {
                        recursive.add(subtask)
                    } else {
                        subtask.await()
                    }
                } else {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId)
                    val file =
                        File(
                            uri = childUri,
                            mimeType = documentMimeType(childId),
                            path = newPath,
                            size = stat.st_size,
                            // Must match the provider's millisecond timestamps exactly, or
                            // every cached song would be considered stale.
                            modifiedMs =
                                stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000,
                            parent = directoryDeferred,
                            addedMs = JoinAddedMs(context, childUri),
                        )
                    children.add(file)
                    files.send(file)
                }
            }
            directoryDeferred.complete(Directory(uri, relativePath, parent, children))
            recursive?.tryAwaitAll()
        }

    /** Same extension-based lookup the external storage provider uses for its documents. */
    private fun documentMimeType(documentId: String): String {
        val lastDot = documentId.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = documentId.substring(lastDot + 1).lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let {
                return it
            }
        }
        return "application/octet-stream"
    }

    override fun track(): Flow<FSUpdate> = callbackFlow {
        val observers = mutableListOf<LocationObserver>()

        query.source.forEach { location ->
            val observer =
                LocationObserver(context, location.uri) {
                    trySend(FSUpdate.LocationChanged(location))
                }
            observers.add(observer)
        }

        awaitClose { observers.forEach { observer -> observer.release() } }
    }

    private fun CoroutineScope.exploreDirectoryImpl(
        rootUri: Uri,
        treeDocumentId: String,
        relativePath: Path,
        parent: Deferred<Directory>?,
        exclude: Set<Path>,
        files: Channel<File>,
    ): Deferred<Result<Unit>> =
        tryAsync(Dispatchers.IO) {
            // Make a kotlin future
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeDocumentId)
            val directoryDeferred = CompletableDeferred<Directory>()
            val recursive = mutableListOf<Deferred<Result<Unit>>>().takeIf { query.multithread }
            val children = mutableListOf<File>()
            contentResolver.useQuery(uri, PROJECTION) { cursor ->
                val childUriIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val lastModifiedIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(childUriIndex)
                    val displayName = cursor.getString(displayNameIndex)

                    // Skip hidden files/directories if ignoreHidden is true
                    if (!query.withHidden && displayName.startsWith(".")) {
                        continue
                    }

                    val newPath = relativePath.file(displayName)
                    val mimeType = cursor.getString(mimeTypeIndex)
                    val lastModified = cursor.getLong(lastModifiedIndex)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId)

                    // We can check for direct equality as if we block out an excluded directory we
                    // will by proxy block out it's children.
                    if (newPath in exclude) {
                        continue
                    }

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subtask =
                            exploreDirectoryImpl(
                                rootUri,
                                childId,
                                newPath,
                                directoryDeferred,
                                exclude,
                                files,
                            )
                        if (recursive != null) {
                            recursive.add(subtask)
                        } else {
                            // cannot pool, single-thread it
                            subtask.await()
                        }
                    } else {
                        val size = cursor.getLong(sizeIndex)
                        val file =
                            File(
                                uri = childUri,
                                mimeType = mimeType,
                                path = newPath,
                                size = size,
                                modifiedMs = lastModified,
                                parent = directoryDeferred,
                                addedMs =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        JoinAddedMs(context, childUri)
                                    } else {
                                        NullAddedMs
                                    },
                            )
                        children.add(file)
                        files.send(file)
                    }
                }
                directoryDeferred.complete(Directory(uri, relativePath, parent, children))
            }
            recursive?.tryAwaitAll()
        }

    data class Query(
        val source: List<Location.Opened>,
        val exclude: List<Location.Unopened>,
        val withHidden: Boolean,
        val multithread: Boolean,
    )

    @RequiresApi(Build.VERSION_CODES.Q)
    private class JoinAddedMs(private val context: Context, private val uri: Uri) : AddedMs {
        override suspend fun resolve(): Long? {
            val mediaUri =
                try {
                    AOSPMediaStore.getMediaUri(context, uri) ?: return null
                } catch (e: Exception) {
                    return null
                }
            return context.contentResolverSafe.useQuery(
                mediaUri,
                arrayOf(AOSPMediaStore.Files.FileColumns.DATE_ADDED),
            ) {
                if (it.moveToFirst()) {
                    val dateAddedIndex =
                        it.getColumnIndexOrThrow(AOSPMediaStore.Files.FileColumns.DATE_ADDED)
                    it.getLong(dateAddedIndex) * 1000
                } else {
                    null
                }
            }
        }
    }

    private object NullAddedMs : AddedMs {
        override suspend fun resolve(): Long? = null
    }

    companion object {
        fun from(context: Context, query: Query) = SAF(context, context.contentResolverSafe, query)

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val PRIMARY_ROOT_ID = "primary"

        private val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
