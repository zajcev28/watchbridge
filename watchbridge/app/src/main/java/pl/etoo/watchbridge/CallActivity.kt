package pl.etoo.watchbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Pełnoekranowy ekran połączenia przychodzącego: Odrzuć / Odbierz. */
class CallActivity : Activity() {

    companion object {
        private const val EXTRA_UID = "uid"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_APP = "app"
        private const val EXTRA_POS = "pos"
        private const val EXTRA_NEG = "neg"

        private var current: WeakReference<CallActivity>? = null
        private val main = Handler(Looper.getMainLooper())

        fun intent(ctx: Context, uid: Int, name: String, app: String, pos: Boolean, neg: Boolean): Intent =
            Intent(ctx, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_UID, uid)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_APP, app)
                .putExtra(EXTRA_POS, pos)
                .putExtra(EXTRA_NEG, neg)

        /** Wywoływane, gdy iPhone zgłosi koniec dzwonienia. */
        fun dismiss(uid: Int) {
            main.post {
                val a = current?.get() ?: return@post
                if (a.uid == uid) a.finish()
            }
        }
    }

    private var uid = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = WeakReference(this)
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun bind(i: Intent) {
        uid = i.getIntExtra(EXTRA_UID, 0)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val p = (24 * dp).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = i.getStringExtra(EXTRA_APP).orEmpty()
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = i.getStringExtra(EXTRA_NAME).orEmpty()
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (6 * dp).toInt(), 0, (14 * dp).toInt())
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun circle(color: Int, label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val s = (64 * dp).toInt()
            val m = (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(m, 0, m, 0) }
            setOnClickListener { onClick() }
        }
        if (i.getBooleanExtra(EXTRA_NEG, true)) {
            row.addView(circle(Color.rgb(0xE5, 0x39, 0x35), "✕") { act(Ancs.ACTION_NEGATIVE) })
        }
        if (i.getBooleanExtra(EXTRA_POS, true)) {
            row.addView(circle(Color.rgb(0x43, 0xA0, 0x47), "✓") { act(Ancs.ACTION_POSITIVE) })
        }
        root.addView(row)
        setContentView(root)
    }

    private fun act(actionId: Int) {
        AncsService.instance?.performAction(uid, actionId)
        if (actionId == Ancs.ACTION_NEGATIVE) Notifier.cancel(this, uid)
        finish()
    }
}
