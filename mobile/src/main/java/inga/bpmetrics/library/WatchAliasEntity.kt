package inga.bpmetrics.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Another id one watch has been known by.
 *
 * A watch's id is made up by the watch app and kept in its own data, so reinstalling the app (as
 * switching between a debug and a release build forces) gives the same watch a new one. Very old
 * builds sent no id at all and were filed under the model name. Either way the phone saw a new
 * watch (Audit #39).
 *
 * An alias is written when a recording arrives with an unknown id from a watch the phone already
 * knows by its Data Layer node, and when two entries are merged. Later recordings under that id
 * then land on [watchId] directly, even if the node is never seen again.
 *
 * No foreign key, like every other reference in this model: an alias pointing at a deleted watch
 * is simply never matched.
 */
@Entity(tableName = "watch_aliases", indices = [Index("watchId")])
data class WatchAliasEntity(
    @PrimaryKey val alias: String,
    /** The registry entry this id belongs to. */
    val watchId: String
)
