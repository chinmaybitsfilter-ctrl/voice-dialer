package com.chinmay.voicedialer

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private val onCall: (Contact) -> Unit,
    private val onLongPress: (Contact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.Holder>() {

    private var items: List<Contact> = emptyList()

    fun submit(list: List<Contact>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: TextView = view.findViewById(R.id.avatar)
        private val name: TextView = view.findViewById(R.id.name)
        private val detail: TextView = view.findViewById(R.id.detail)
        private val callButton: ImageButton = view.findViewById(R.id.callButton)

        fun bind(c: Contact) {
            name.text = c.name
            val first = c.numbers.firstOrNull()
            val more = if (c.numbers.size > 1) "  ·  +${c.numbers.size - 1} more" else ""
            val nick = c.nickname?.let { "  ·  “$it”" } ?: ""
            detail.text = "${first?.label.orEmpty()} ${first?.number.orEmpty()}$more$nick".trim()

            avatar.text = initials(c.name)
            avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AVATAR_COLORS[(c.name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
            }

            itemView.setOnClickListener { onCall(c) }
            callButton.setOnClickListener { onCall(c) }
            itemView.setOnLongClickListener { onLongPress(c); true }
        }
    }

    companion object {
        private val AVATAR_COLORS = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFFAB47BC.toInt(),
            0xFF42A5F5.toInt(), 0xFFEC407A.toInt(), 0xFF7CB342.toInt(), 0xFF8D6E63.toInt()
        )

        fun initials(name: String): String {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val letters = parts.take(2).mapNotNull { p -> p.firstOrNull { it.isLetterOrDigit() } }
            return letters.joinToString("").uppercase().ifEmpty { "#" }
        }
    }
}
