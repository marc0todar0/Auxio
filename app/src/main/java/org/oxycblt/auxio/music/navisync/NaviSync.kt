/*
 * Copyright (c) 2026 Auxio Project
 * NaviSync.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.navisync

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.musikr.Playlist

/**
 * Fork-only bridge to the NaviSync app, which uploads playlists to a Navidrome server. Auxio stays
 * offline: it only hands NaviSync the playlists (name + song paths) through a one-shot content URI,
 * and NaviSync does the matching and the upload in the background with its own credentials.
 */
object NaviSync {
    private const val PACKAGE = "eu.todaro.navisync"
    private const val ACTIVITY = "$PACKAGE.push.PlaylistPushActivity"
    private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.navisync"

    /** Mirrors PlaylistPushActivity.RESULT_NOT_CONFIGURED in NaviSync. */
    private const val RESULT_NOT_CONFIGURED = Activity.RESULT_FIRST_USER + 1

    /**
     * Create the [Intent] that makes NaviSync upload the given playlists. It must be launched for a
     * result: NaviSync only accepts requests whose caller it can identify.
     */
    suspend fun createSyncIntent(context: Context, playlists: Collection<Playlist>): Intent {
        val file =
            withContext(Dispatchers.IO) {
                val json =
                    JSONObject()
                        .put("version", 1)
                        .put(
                            "playlists",
                            JSONArray(
                                playlists.map { playlist ->
                                    JSONObject()
                                        .put("name", playlist.name.raw)
                                        .put(
                                            "paths",
                                            JSONArray(
                                                playlist.songs.map { it.path.components.unixString }
                                            ),
                                        )
                                }
                            ),
                        )
                File(context.cacheDir, "navisync").run {
                    mkdirs()
                    File(this, "playlists.json").also { it.writeText(json.toString()) }
                }
            }
        return Intent()
            .setClassName(PACKAGE, ACTIVITY)
            .setDataAndType(
                FileProvider.getUriForFile(context, AUTHORITY, file),
                "application/json",
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The message to show the user for the result code NaviSync answered with. */
    @StringRes
    fun messageFor(resultCode: Int) =
        when (resultCode) {
            Activity.RESULT_OK -> R.string.lng_navisync_started
            RESULT_NOT_CONFIGURED -> R.string.err_navisync_unconfigured
            else -> R.string.err_navisync_failed
        }
}
