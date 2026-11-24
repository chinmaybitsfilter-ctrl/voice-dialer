package com.chinmay.voicedialer

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone

data class PhoneNumber(
    val number: String,
    val label: String,
    /** One of ContactsContract.CommonDataKinds.Phone.TYPE_* */
    val type: Int
)

data class Contact(
    val id: Long,
    val name: String,
    val numbers: List<PhoneNumber>,
    val nickname: String? = null
)

object ContactRepository {

    /** Reads every contact that has at least one phone number, sorted by name. */
    fun load(context: Context): List<Contact> {
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL
        )

        val names = LinkedHashMap<Long, String>()
        val numbers = HashMap<Long, MutableList<PhoneNumber>>()
        val seen = HashMap<Long, MutableSet<String>>()

        context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = c.getColumnIndexOrThrow(Phone.NUMBER)
            val typeIdx = c.getColumnIndexOrThrow(Phone.TYPE)
            val labelIdx = c.getColumnIndexOrThrow(Phone.LABEL)

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx)?.trim().orEmpty()
                val number = c.getString(numIdx)?.trim().orEmpty()
                if (number.isEmpty()) continue

                val type = c.getInt(typeIdx)
                val customLabel = c.getString(labelIdx)
                val label = Phone.getTypeLabel(context.resources, type, customLabel).toString()

                // Same number often appears twice (e.g. "+91 98..." and "98...") – keep one.
                val key = number.filter { it.isDigit() }.takeLast(10)
                if (!seen.getOrPut(id) { HashSet() }.add(key)) continue

                names.putIfAbsent(id, name.ifEmpty { number })
                numbers.getOrPut(id) { ArrayList() }.add(PhoneNumber(number, label, type))
            }
        }

        val nicknames = NicknameStore(context)
        return names.map { (id, name) ->
            Contact(id, name, numbers[id].orEmpty(), nicknames.get(id))
        }
    }
}

/** Voice nicknames ("mom", "bhai", "boss") saved per contact on this phone. */
class NicknameStore(context: Context) {
    private val prefs = context.getSharedPreferences("nicknames", Context.MODE_PRIVATE)

    fun get(contactId: Long): String? = prefs.getString(contactId.toString(), null)

    fun set(contactId: Long, nickname: String?) {
        prefs.edit().apply {
            if (nickname.isNullOrBlank()) remove(contactId.toString())
            else putString(contactId.toString(), nickname.trim())
        }.apply()
    }
}
