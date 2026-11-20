package com.goodwy.messages.repository

import com.goodwy.messages.extensions.anyOf
import com.goodwy.messages.model.BlockedNumber
import com.goodwy.messages.model.BlockedRegex
import com.goodwy.messages.util.PhoneNumberUtils
import io.realm.Realm
import io.realm.RealmResults
import javax.inject.Inject

class BlockingRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils
) : BlockingRepository {

    override fun blockNumber(vararg addresses: String) {
        if (addresses.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.executeTransaction { r ->
                val existingBlocked = r.where(BlockedNumber::class.java).findAll()
                var currentMaxId = r.where(BlockedNumber::class.java).max("id")?.toLong() ?: 0L

                for (address in addresses) {
                    if (address.isBlank()) continue
                    val alreadyBlocked = existingBlocked.any { phoneNumberUtils.compare(it.address, address) }
                    if (!alreadyBlocked) {
                        currentMaxId++
                        while (r.where(BlockedNumber::class.java).equalTo("id", currentMaxId).findFirst() != null) {
                            currentMaxId++
                        }
                        r.insertOrUpdate(BlockedNumber(currentMaxId, address))
                    }
                }
            }
        }
    }

    override fun blockRegex(vararg regexps: String) {
        if (regexps.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.executeTransaction { r ->
                val existingRegexps = r.where(BlockedRegex::class.java).findAll()
                var currentMaxId = r.where(BlockedRegex::class.java).max("id")?.toLong() ?: 0L

                for (regex in regexps) {
                    if (regex.isBlank()) continue
                    val alreadyBlocked = existingRegexps.any { it.regex == regex }
                    if (!alreadyBlocked) {
                        currentMaxId++
                        while (r.where(BlockedRegex::class.java).equalTo("id", currentMaxId).findFirst() != null) {
                            currentMaxId++
                        }
                        r.insertOrUpdate(BlockedRegex(currentMaxId, regex))
                    }
                }
            }
        }
    }

    override fun getBlockedNumbers(): RealmResults<BlockedNumber> {
        return Realm.getDefaultInstance()
                .where(BlockedNumber::class.java)
                .findAllAsync()
    }

    override fun getBlockedNumber(id: Long): BlockedNumber? {
        return Realm.getDefaultInstance()
                .where(BlockedNumber::class.java)
                .equalTo("id", id)
                .findFirst()
    }

    override fun getBlockedRegexps(): RealmResults<BlockedRegex> {
        return Realm.getDefaultInstance()
            .where(BlockedRegex::class.java)
            .findAllAsync()
    }

    override fun getBlockedRegex(id: Long): BlockedRegex? {
        return Realm.getDefaultInstance()
            .where(BlockedRegex::class.java)
            .equalTo("id", id)
            .findFirst()
    }

    override fun isBlockedAddress(address: String): Boolean {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java)
                    .findAll()
                    .any { number -> phoneNumberUtils.compare(number.address, address) }
        }
    }

    override fun isBlockedContent(content: String): Boolean {
        val blockedRegexps = Realm.getDefaultInstance()
            .where(BlockedRegex::class.java)
            .findAll()
        for (blockedRegex in blockedRegexps){
            val regex = Regex(blockedRegex.regex)
            if (regex.containsMatchIn(content)) return true
        }
        return false
    }

    override fun unblockNumber(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(BlockedNumber::class.java)
                        .equalTo("id", id)
                        .findAll()
                        .deleteAllFromRealm()
            }
        }
    }

    override fun unblockNumbers(vararg addresses: String) {
        Realm.getDefaultInstance().use { realm ->
            val ids = realm.where(BlockedNumber::class.java)
                    .findAll()
                    .filter { number ->
                        addresses.any { address -> phoneNumberUtils.compare(number.address, address) }
                    }
                    .map { number -> number.id }
                    .toLongArray()

            realm.executeTransaction {
                realm.where(BlockedNumber::class.java)
                        .anyOf("id", ids)
                        .findAll()
                        .deleteAllFromRealm()
            }
        }
    }

    override fun unblockRegex(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(BlockedRegex::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun unblockRegexps(vararg regexps: String) {
        Realm.getDefaultInstance().use { realm ->
            val ids = realm.where(BlockedRegex::class.java)
                .findAll()
                .filter { blockedRegex ->
                    regexps.any { address -> phoneNumberUtils.compare(blockedRegex.regex, address) }
                }
                .map { number -> number.id }
                .toLongArray()

            realm.executeTransaction {
                realm.where(BlockedRegex::class.java)
                    .anyOf("id", ids)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

}
