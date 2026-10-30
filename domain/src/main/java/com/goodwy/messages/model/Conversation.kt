/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.goodwy.messages.model

import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

open class Conversation(
    @PrimaryKey var id: Long = 0,
    @Index var archived: Boolean = false,
    @Index var blocked: Boolean = false,
    @Index var pinned: Boolean = false,
    var recipients: RealmList<Recipient> = RealmList(),
    var lastMessage: Message? = null,
    var draft: String = "",

    var blockingClient: Int? = null,
    var blockReason: String? = null,

    var name: String = "" // For group chats, the user is allowed to set a custom title for the conversation
) : RealmObject() {

    val date: Long get() = if (isValid && lastMessage?.isValid == true) lastMessage?.date ?: 0L else 0L
    val snippet: String? get() = if (isValid && lastMessage?.isValid == true) lastMessage?.getSummary() else null
    val unread: Boolean get() = if (isValid && lastMessage?.isValid == true) lastMessage?.read == false else false
    val me: Boolean get() = if (isValid && lastMessage?.isValid == true) lastMessage?.isMe() == true else false

    fun getTitle(): String {
        if (!isValid) return ""
        if (name.isNotBlank()) return name
        val distinctNames = recipients.filter { it.isValid }.map { it.getDisplayName() }.distinct()
        return if (distinctNames.isNotEmpty()) distinctNames.joinToString(", ") else ""
    }

}
