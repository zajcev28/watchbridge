package pl.etoo.watchbridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon

/**
 * Ikony aplikacji z iPhone'a. ANCS nie przesyła obrazków, więc:
 *  1. jeśli w res/drawable jest plik "app_<bundle id>" (np. app_net_whatsapp_whatsapp.png) – używamy go,
 *  2. w przeciwnym razie rysujemy kolorowe kółko z symbolem kategorii (czat, telefon, mail…).
 */
object AppIcons {
    private const val SIZE = 128

    private val COLORS = mapOf(
        "net.whatsapp.WhatsApp" to 0xFF25D366.toInt(),
        "net.whatsapp.WhatsAppSMB" to 0xFF25D366.toInt(),
        "com.apple.MobileSMS" to 0xFF34C759.toInt(),
        "com.apple.mobilephone" to 0xFF34C759.toInt(),
        "com.apple.facetime" to 0xFF34C759.toInt(),
        "com.apple.mobilemail" to 0xFF1E88E5.toInt(),
        "com.apple.mobilecal" to 0xFFE53935.toInt(),
        "com.apple.reminders" to 0xFFFB8C00.toInt(),
        "com.facebook.Messenger" to 0xFF0A7CFF.toInt(),
        "ph.telegra.Telegraph" to 0xFF29A9EB.toInt(),
        "org.whispersystems.signal" to 0xFF3A76F0.toInt(),
        "com.google.Gmail" to 0xFFD93025.toInt(),
        "com.microsoft.skype.teams" to 0xFF5B5FC7.toInt(),
    )

    private val PALETTE = intArrayOf(
        0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(),
    )

    private val cache = HashMap<String, Bitmap>()

    fun color(appId: String): Int =
        COLORS[appId] ?: PALETTE[(appId.hashCode() and 0x7FFFFFFF) % PALETTE.size]

    /** Monochromatyczna ikona na pasek powiadomień. */
    fun small(appId: String, category: Int): Int = when {
        appId == "com.apple.mobilephone" || appId == "com.apple.facetime" -> R.drawable.ic_call
        appId == "com.apple.mobilemail" || appId == "com.google.Gmail" -> R.drawable.ic_mail
        appId == "com.apple.mobilecal" -> R.drawable.ic_event
        category == Ancs.CAT_INCOMING_CALL || category == Ancs.CAT_MISSED_CALL ||
            category == Ancs.CAT_VOICEMAIL -> R.drawable.ic_call
        category == Ancs.CAT_SOCIAL -> R.drawable.ic_chat
        category == Ancs.CAT_EMAIL -> R.drawable.ic_mail
        category == Ancs.CAT_SCHEDULE -> R.drawable.ic_event
        appId == "com.apple.MobileSMS" -> R.drawable.ic_chat
        else -> R.drawable.ic_bell
    }

    /** Kolorowa ikona (duża) – Twoja z res/drawable albo wygenerowana. */
    fun bitmap(ctx: Context, appId: String, category: Int): Bitmap {
        val key = "$appId|${small(appId, category)}"
        cache[key]?.let { return it }
        val bmp = custom(ctx, appId) ?: generated(ctx, appId, category)
        cache[key] = bmp
        return bmp
    }

    fun large(ctx: Context, appId: String, category: Int): Icon =
        Icon.createWithBitmap(bitmap(ctx, appId, category))

    @SuppressLint("DiscouragedApi")
    private fun custom(ctx: Context, appId: String): Bitmap? {
        if (appId.isEmpty()) return null
        val name = "app_" + appId.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id == 0) return null
        val d = ctx.getDrawable(id) ?: return null
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // przycięcie do koła, żeby pasowało do okrągłego UI Wear OS
        val clip = Path().apply { addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Path.Direction.CW) }
        c.clipPath(clip)
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(c)
        return bmp
    }

    private fun generated(ctx: Context, appId: String, category: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(appId) }
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p)
        val g = ctx.getDrawable(small(appId, category))?.mutate()
        if (g != null) {
            g.setTint(Color.WHITE)
            val inset = SIZE / 4
            g.setBounds(inset, inset, SIZE - inset, SIZE - inset)
            g.draw(c)
        }
        return bmp
    }
}
