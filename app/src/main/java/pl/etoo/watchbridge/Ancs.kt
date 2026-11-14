package pl.etoo.watchbridge

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) – stałe i (de)serializacja.
 * Specyfikacja: Apple "ANCS Specification".
 */
object Ancs {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // EventID
    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    // EventFlags
    const val FLAG_SILENT = 1 shl 0
    const val FLAG_IMPORTANT = 1 shl 1
    const val FLAG_PRE_EXISTING = 1 shl 2
    const val FLAG_POSITIVE_ACTION = 1 shl 3
    const val FLAG_NEGATIVE_ACTION = 1 shl 4

    // CategoryID
    const val CAT_OTHER = 0
    const val CAT_INCOMING_CALL = 1
    const val CAT_MISSED_CALL = 2
    const val CAT_VOICEMAIL = 3
    const val CAT_SOCIAL = 4
    const val CAT_SCHEDULE = 5
    const val CAT_EMAIL = 6

    // CommandID
    const val CMD_GET_NOTIFICATION_ATTRIBUTES = 0
    const val CMD_GET_APP_ATTRIBUTES = 1
    const val CMD_PERFORM_NOTIFICATION_ACTION = 2

    // NotificationAttributeID
    const val ATTR_APP_ID = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_POSITIVE_LABEL = 6
    const val ATTR_NEGATIVE_LABEL = 7

    // AppAttributeID
    const val APP_ATTR_DISPLAY_NAME = 0

    // ActionID
    const val ACTION_POSITIVE = 0
    const val ACTION_NEGATIVE = 1

    private val REQUESTED = intArrayOf(
        ATTR_APP_ID, ATTR_TITLE, ATTR_SUBTITLE, ATTR_MESSAGE,
        ATTR_POSITIVE_LABEL, ATTR_NEGATIVE_LABEL,
    )

    fun getNotificationAttributes(uid: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(CMD_GET_NOTIFICATION_ATTRIBUTES)
        writeUid(o, uid)
        for (a in REQUESTED) {
            o.write(a)
            when (a) {
                ATTR_TITLE, ATTR_SUBTITLE -> le16(o, 64)
                ATTR_MESSAGE -> le16(o, 400)
            }
        }
        return o.toByteArray()
    }

    fun getAppAttributes(appId: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(CMD_GET_APP_ATTRIBUTES)
        o.write(appId.toByteArray(Charsets.UTF_8))
        o.write(0)
        o.write(APP_ATTR_DISPLAY_NAME)
        return o.toByteArray()
    }

    fun performAction(uid: Int, actionId: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(CMD_PERFORM_NOTIFICATION_ACTION)
        writeUid(o, uid)
        o.write(actionId)
        return o.toByteArray()
    }

    /** Zwraca null, jeśli odpowiedź jeszcze nie dotarła w całości (fragmentacja po MTU). */
    fun parseNotificationAttributes(buf: ByteArray): NotificationAttributes? {
        if (buf.size < 5) return null
        require(buf[0].toInt() == CMD_GET_NOTIFICATION_ATTRIBUTES) { "Nieoczekiwana komenda ${buf[0]}" }
        val uid = readUid(buf, 1)
        val map = HashMap<Int, String>()
        var i = 5
        repeat(REQUESTED.size) {
            if (i + 3 > buf.size) return null
            val id = buf[i].toInt() and 0xFF
            val len = u16(buf, i + 1)
            if (i + 3 + len > buf.size) return null
            map[id] = String(buf, i + 3, len, Charsets.UTF_8)
            i += 3 + len
        }
        return NotificationAttributes(uid, map)
    }

    /** Zwraca (appId, nazwa) albo null, jeśli niekompletne. */
    fun parseAppAttributes(buf: ByteArray): Pair<String, String>? {
        if (buf.isEmpty()) return null
        require(buf[0].toInt() == CMD_GET_APP_ATTRIBUTES) { "Nieoczekiwana komenda ${buf[0]}" }
        var z = -1
        for (k in 1 until buf.size) if (buf[k].toInt() == 0) { z = k; break }
        if (z < 0) return null
        val appId = String(buf, 1, z - 1, Charsets.UTF_8)
        val i = z + 1
        if (i + 3 > buf.size) return null
        val len = u16(buf, i + 1)
        if (i + 3 + len > buf.size) return null
        return appId to String(buf, i + 3, len, Charsets.UTF_8)
    }

    private fun writeUid(o: ByteArrayOutputStream, uid: Int) {
        o.write(uid and 0xFF)
        o.write((uid shr 8) and 0xFF)
        o.write((uid shr 16) and 0xFF)
        o.write((uid shr 24) and 0xFF)
    }

    private fun le16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun readUid(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}

data class NotificationAttributes(val uid: Int, val attrs: Map<Int, String>) {
    operator fun get(id: Int): String = attrs[id].orEmpty()
}

data class SourceEvent(
    val eventId: Int,
    val flags: Int,
    val category: Int,
    val count: Int,
    val uid: Int,
) {
    val silent get() = flags and Ancs.FLAG_SILENT != 0
    val preExisting get() = flags and Ancs.FLAG_PRE_EXISTING != 0
    val hasPositive get() = flags and Ancs.FLAG_POSITIVE_ACTION != 0
    val hasNegative get() = flags and Ancs.FLAG_NEGATIVE_ACTION != 0

    companion object {
        fun parse(b: ByteArray): SourceEvent? {
            if (b.size < 8) return null
            return SourceEvent(
                eventId = b[0].toInt() and 0xFF,
                flags = b[1].toInt() and 0xFF,
                category = b[2].toInt() and 0xFF,
                count = b[3].toInt() and 0xFF,
                uid = Ancs.readUid(b, 4),
            )
        }
    }
}
