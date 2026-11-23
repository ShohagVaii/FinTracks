package com.fintracks.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One push the owner sent (or one addressed to this user), with this user's read state. */
data class InboxItem(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val target: String,
    val sentAt: String,
    val read: Boolean
)

/**
 * In-app inbox. Items come from the server (push_campaigns), so a push that was missed on the device is still listed.
 * Read state is stored per user in push_reads, so it is the same on every device.
 */
object Inbox {
    var items by mutableStateOf<List<InboxItem>>(emptyList())
        private set

    val unread: Int get() = items.count { !it.read }

    /** Reloads from the server. Signed out: the list is cleared. Errors keep the last list. */
    suspend fun refresh(repo: Repository) {
        if (repo.session == null) {
            items = emptyList()
            return
        }
        try {
            val campaigns = repo.list(
                "push_campaigns",
                "select=id,title,body,image_url,target,sent_at&status=eq.sent&order=sent_at.desc&limit=100"
            )
            val read = repo.list("push_reads", "select=campaign_id").mapNotNull { it.strOrNull("campaign_id") }.toSet()
            items = campaigns.mapNotNull { c ->
                val id = c.strOrNull("id") ?: return@mapNotNull null
                InboxItem(
                    id = id,
                    title = c.str("title"),
                    body = c.str("body"),
                    imageUrl = c.strOrNull("image_url"),
                    target = c.strOrNull("target") ?: "dashboard",
                    sentAt = c.str("sent_at"),
                    read = id in read
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the last list on a network error.
        }
    }

    /** Marks campaigns as read on the server, then locally. */
    suspend fun markRead(repo: Repository, ids: Collection<String>) {
        val fresh = ids.filter { id -> items.any { it.id == id && !it.read } }
        if (fresh.isEmpty()) return
        try {
            repo.upsert(
                "push_reads",
                fresh.map { buildJsonObject { put("campaign_id", it) } },
                onConflict = "user_id,campaign_id"
            )
            items = items.map { if (it.id in fresh) it.copy(read = true) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stays unread; the next open tries again.
        }
    }
}
