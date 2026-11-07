package com.example.tasmee

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var surahIn: EditText
    private lateinit var fromIn: EditText
    private lateinit var toIn: EditText
    private lateinit var hideSwitch: Switch
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var textView: TextView

    private var words = listOf<String>()
    private var norm = listOf<String>()
    private var state = IntArray(0)          // 0 = لسه، 1 = صح، 2 = غلط
    private var pos = 0                      // الكلمة الجاية المطلوبة
    private var running = false
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var matched = 0                  // كلمات اتحسبت في الجلسة الحالية

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun num(hint: String, def: String) = EditText(this).apply {
            this.hint = hint; setText(def)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        surahIn = num("السورة (1-114)", "1")
        fromIn = num("من آية", "1")
        toIn = num("إلى آية (فاضي = للآخر)", "")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(surahIn); row.addView(fromIn); row.addView(toIn)
        root.addView(row)

        hideSwitch = Switch(this).apply {
            text = "اخفي الكلام واظهر الكلمة بعد ما أقولها"
            setOnCheckedChangeListener { _, _ -> render() }
        }
        root.addView(hideSwitch)

        startBtn = Button(this).apply { text = "ابدأ"; setOnClickListener { toggle() } }
        root.addView(startBtn)

        status = TextView(this).apply { textSize = 16f; text = "اختار السورة واضغط ابدأ" }
        root.addView(status)

        textView = TextView(this).apply {
            textSize = 28f
            setLineSpacing(0f, 1.4f)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(ScrollView(this).apply { addView(textView) })
        setContentView(root)
    }

    private fun toggle() {
        if (running) { stop("وقفت"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "خدمة التعرف على الصوت مش موجودة (ثبّت Google)"; return
        }
        load()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggle()
        else status.text = "لازم تسمح بالميكروفون"
    }

    private fun load() {
        val s = (surahIn.text.toString().toIntOrNull() ?: 1).coerceIn(1, 114)
        status.text = "جاري تحميل السورة…"
        thread {
            try {
                val json = URL("https://api.alquran.cloud/v1/surah/$s/quran-simple").readText()
                val ay = JSONObject(json).getJSONObject("data").getJSONArray("ayahs")
                val from = (fromIn.text.toString().toIntOrNull() ?: 1).coerceIn(1, ay.length())
                val to = (toIn.text.toString().toIntOrNull() ?: ay.length()).coerceIn(from, ay.length())
                val w = ArrayList<String>()
                for (i in from - 1 until to)
                    w += ay.getJSONObject(i).getString("text").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                ui.post {
                    words = w; norm = w.map { normalize(it) }
                    state = IntArray(w.size); pos = 0
                    running = true; startBtn.text = "وقف"
                    status.text = "اقرأ…"; render(); listen()
                }
            } catch (e: Exception) {
                ui.post { status.text = "فشل التحميل (النت؟)" }
            }
        }
    }

    private fun stop(msg: String) {
        running = false; session++
        recognizer?.destroy(); recognizer = null
        startBtn.text = "ابدأ"; status.text = msg
    }

    private fun listen() {
        if (!running) return
        recognizer?.destroy()
        val sid = ++session
        matched = 0
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(b: Bundle?) { if (sid == session) handle(b, false) }
                override fun onResults(b: Bundle?) {
                    if (sid != session) return
                    handle(b, true)
                    if (sid == session) ui.postDelayed({ listen() }, 200)
                }
                override fun onError(e: Int) {
                    if (sid == session) ui.postDelayed({ listen() }, 400)
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(r: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(i)
    }

    private fun handle(b: Bundle?, final: Boolean) {
        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        val toks = t.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { normalize(it) }
        var i = matched
        while (i < toks.size && pos < words.size) {
            if (i == toks.size - 1 && !final) break      // آخر كلمة لسه ممكن تتغير
            if (same(toks[i], norm[pos])) {
                state[pos] = 1; pos++; matched = i + 1
            } else {
                state[pos] = 2
                bell(); render()
                status.text = "غلط في الكلمة الحمرا، أعدها"
                ui.postDelayed({ listen() }, 300)        // جلسة جديدة من نفس الكلمة
                session++                                 // يلغي نتايج الجلسة القديمة
                return
            }
            i++
        }
        render()
        if (pos >= words.size) stop("ما شاء الله، خلصت ✅")
    }

    private fun render() {
        val hide = hideSwitch.isChecked
        val sb = SpannableStringBuilder()
        for (i in words.indices) {
            val shown = if (state[i] == 0 && hide) "•••" else words[i]
            val color = when (state[i]) {
                1 -> Color.rgb(0, 140, 0)
                2 -> Color.RED
                else -> if (hide) Color.LTGRAY else Color.DKGRAY
            }
            val st = sb.length
            sb.append(shown).append(" ")
            sb.setSpan(ForegroundColorSpan(color), st, st + shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = sb
    }

    private fun bell() {
        try {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
        } catch (_: Exception) {}
    }

    // شيل التشكيل ووحّد أشكال الحروف
    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val k = c.code
            if (k in 0x064B..0x065F || k == 0x0670 || k in 0x06D6..0x06ED || k == 0x0640) continue
            sb.append(when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                else -> c
            })
        }
        return sb.toString()
    }

    // تساهل بسيط في الإملاء: فرق حرف واحد مسموح للكلمات الطويلة
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        return lev(a, b) <= 1
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length)
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    override fun onDestroy() { super.onDestroy(); recognizer?.destroy() }
}
