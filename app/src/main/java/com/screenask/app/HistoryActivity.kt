package com.screenask.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = Prefs.load(this)
        if (prefs.secureWindow) window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "识屏历史"
            setTitleTextColor(Color.WHITE)
            setBackgroundColor(getColor(R.color.brand))
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@HistoryActivity, androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val clear = Button(this).apply {
            text = "清空历史"
            setOnClickListener {
                HistoryStore.clear(this@HistoryActivity)
                Prefs.saveLastAnswer(this@HistoryActivity, "")
                render()
                Toast.makeText(this@HistoryActivity, "已清空", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(28))
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun render() {
        listContainer.removeAllViews()
        val entries = HistoryStore.all(this)
        if (entries.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "还没有记录。去通知栏点“识屏”试试。"
                textSize = 14f
                setTextColor(Color.parseColor("#8A8F98"))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        for (entry in entries) {
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            val header = TextView(this).apply {
                text = kindLabel(entry.kind) + " · " + formatter.format(Date(entry.time)) +
                    (if (entry.model.isNotBlank()) " · " + entry.model else "")
                textSize = 12f
                setTextColor(Color.parseColor("#8A8F98"))
            }
            val question = TextView(this).apply {
                text = entry.question
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            }
            val answer = TextView(this).apply {
                text = entry.answer
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(0, dp(2), 0, dp(6))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(miniButton("复制") { AskEngine.copyToClipboard(this, entry.answer); toast("已复制") })
            row.addView(miniButton("分享") { share(entry) })
            content.addView(header)
            content.addView(question)
            content.addView(answer)
            content.addView(row)
            card.addView(content)
            listContainer.addView(card)
        }
    }

    private fun miniButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(38)).apply { marginStart = dp(6) }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun share(entry: HistoryEntry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "识屏问答记录")
            putExtra(Intent.EXTRA_TEXT, entry.question + "\n\n" + entry.answer)
        }
        runCatching { startActivity(Intent.createChooser(intent, "分享")) }
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "followup" -> "追问"
        "transcript" -> "听力转写"
        else -> "识屏"
    }
}
