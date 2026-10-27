package net.jonesie.traveldiary.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Reads the phone's contacts (which includes Google contacts when the account is synced) so
 * companions can be matched to real people. Only names are read; nothing leaves the phone.
 */
object ContactsHelper {

    /** [lookupKey] is Android's stable identifier for a contact, safe to store. */
    data class Contact(val lookupKey: String, val name: String)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun contactUri(lookupKey: String): Uri =
        Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey))

    /** Contacts whose name contains [text], starred ones first. */
    fun search(context: Context, text: String, limit: Int = 6): List<Contact> {
        if (!hasPermission(context) || text.isBlank()) return emptyList()
        return query(context, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'", "%${escapeLike(text.trim())}%", limit)
    }

    /**
     * The contact for [name]: an exact (case-insensitive) match, or with [allowFirstName] a single
     * contact whose name starts with it, e.g. "Bob" for "Bob Smith" when there's only one Bob.
     */
    fun match(context: Context, name: String, allowFirstName: Boolean = false): Contact? {
        if (!hasPermission(context) || name.isBlank()) return null
        val trimmed = name.trim()
        query(context, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE", trimmed, 2).firstOrNull()?.let { return it }
        if (!allowFirstName) return null
        val starts = query(context, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'", "${escapeLike(trimmed)} %", 3)
        return starts.singleOrNull()
    }

    private fun query(context: Context, selection: String, arg: String, limit: Int): List<Contact> {
        val found = mutableListOf<Contact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                selection,
                arrayOf(arg),
                "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && found.size < limit) {
                    val key = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    found += Contact(key, name)
                }
            }
        }
        return found
    }

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
