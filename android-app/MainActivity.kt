package com.whispercpp.transcriber

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max

private const val REQ_MODEL = 1
private const val REQ_AUDIO = 2
private const val REQ_SAVE = 3
private const val SR = 16000
private const val CHUNK_SEC = 30
private const val CHUNK = CHUNK_SEC * SR
private val TS_REGEX = Regex("^\\[[^\\]]*\\]:?\\s*")

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var cancelled = false
    private val transcript = StringBuilder()

    private lateinit var statusView: TextView
    private lateinit var outView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var bar: ProgressBar
    private lateinit var btnModel: Button
    private lateinit var btnAudio: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button

    private val modelFile: File get() = File(filesDir, "model.bin")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        if (modelFile.exists()) status("Saved model: " + describeModel())
        else status("Step 1: choose your model file.")
        setBusy(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        scope.cancel()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        root.addView(TextView(this).apply {
            text = "Whisper Offline"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        statusView = TextView(this).apply { setTextColor(Color.DKGRAY); setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(statusView)
        btnModel = button("1. Choose model file") { pick(REQ_MODEL) }
        btnAudio = button("2. Choose audio & transcribe") { pick(REQ_AUDIO) }
        btnStop = button("Stop") { cancelled = true }
        btnSave = button("Save text") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "transcript.txt")
                }, REQ_SAVE
            )
        }
        root.addView(btnModel); root.addView(btnAudio); root.addView(btnStop); root.addView(btnSave)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)
        outView = TextView(this).apply {
            setTextIsSelectable(true)
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(0, pad / 2, 0, 0)
        }
        scroll = ScrollView(this).apply { addView(outView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun status(msg: String) { statusView.text = msg }
    private fun ui(block: () -> Unit) { runOnUiThread { block() } }

    private fun setBusy(busy: Boolean) {
        btnModel.isEnabled = !busy
        btnAudio.isEnabled = !busy && modelFile.exists()
        btnStop.isEnabled = busy
        btnSave.isEnabled = !busy && transcript.isNotEmpty()
    }

    private fun pick(req: Int) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, req
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_MODEL -> copyModel(uri)
            REQ_AUDIO -> startTranscription(uri)
            REQ_SAVE -> try {
                contentResolver.openOutputStream(uri)!!.use { it.write(transcript.toString().toByteArray()) }
                status("Saved.")
            } catch (e: Exception) { status("Save failed: ${e.message}") }
        }
    }

    // ---------- model ----------
    private fun sizeOf(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0) return it.getLong(i)
            }
        }
        return -1L
    }

    private fun copyModel(uri: Uri) {
        setBusy(true)
        job = scope.launch {
            try {
                val total = sizeOf(uri)
                val tmp = File(filesDir, "model.tmp")
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > (8L shl 20)) {
                                last = done
                                val p = if (total > 0) (done * 100 / total).toInt() else 0
                                ui { status("Copying model… ${done shr 20} MB"); bar.progress = p }
                            }
                        }
                    }
                }
                modelFile.delete()
                tmp.renameTo(modelFile)
                val d = describeModel()
                ui { status("Model ready: $d"); bar.progress = 0 }
            } catch (e: Throwable) {
                ui { status("Model copy failed: ${e.message}") }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun describeModel(): String {
        return try {
            val f = modelFile
            val mb = f.length() shr 20
            val head = ByteArray(65536)
            val n = FileInputStream(f).use { it.read(head) }
            val magic = String(head, 0, 4, Charsets.ISO_8859_1)
            when {
                magic == "GGUF" -> {
                    val bb = ByteBuffer.wrap(head, 0, max(n, 0)).order(ByteOrder.LITTLE_ENDIAN)
                    "$mb MB, GGUF format (architecture: ${ggufArch(bb) ?: "unknown"}). " +
                        "whisper.cpp normally needs a ggml-*.bin Whisper file, so loading may fail."
                }
                magic == "lmgg" -> "$mb MB, whisper.cpp ggml format OK"
                else -> "$mb MB, unrecognised format"
            }
        } catch (e: Exception) { "could not inspect file" }
    }

    private fun ggufArch(bb: ByteBuffer): String? = try {
        bb.position(4)
        bb.getInt()          // version
        bb.getLong()         // tensor count
        val kvCount = bb.getLong()
        if (kvCount <= 0) null else {
            val kl = bb.getLong().toInt()
            if (kl !in 0..256) null else {
                val kb = ByteArray(kl); bb.get(kb)
                val type = bb.getInt()
                if (String(kb) == "general.architecture" && type == 8) {
                    val vl = bb.getLong().toInt()
                    if (vl !in 0..256) null else { val vb = ByteArray(vl); bb.get(vb); String(vb) }
                } else null
            }
        }
    } catch (e: Exception) { null }

    // ---------- transcription ----------
    private fun startTranscription(uri: Uri) {
        transcript.setLength(0)
        outView.text = ""
        cancelled = false
        bar.progress = 0
        setBusy(true)
        job = scope.launch {
            var ctx: WhisperContext? = null
            try {
                ui { status("Loading model…") }
                ctx = WhisperContext.createContextFromFile(modelFile.absolutePath)
                transcribeAudio(uri, ctx)
                ui {
                    status(if (cancelled) "Stopped. Partial text can be saved." else "Done. Tap Save text.")
                    if (!cancelled) bar.progress = 100
                }
            } catch (e: Throwable) {
                var m = e.message ?: e.javaClass.simpleName
                if (m.contains("Couldn't create context"))
                    m += "\nThe model is probably not in whisper.cpp ggml format (needs a ggml-*.bin Whisper file)."
                ui { status("Error: $m") }
            } finally {
                try { ctx?.release() } catch (e2: Throwable) { }
                ui { setBusy(false) }
            }
        }
    }

    private class Resampler(srcRate: Int) {
        private val step = srcRate / SR.toDouble()
        private var pos = 1.0
        private var last = 0f
        fun process(inp: FloatArray): FloatArray {
            val n = inp.size
            if (n == 0) return FloatArray(0)
            val out = FloatArray(max(((n - pos) / step).toInt() + 2, 0))
            var k = 0
            while (pos < n) {
                val i = floor(pos).toInt()
                val fr = (pos - i).toFloat()
                val a = if (i == 0) last else inp[i - 1]
                val b = inp[i]
                out[k++] = a * (1f - fr) + b * fr
                pos += step
            }
            pos -= n
            last = inp[n - 1]
            return out.copyOf(k)
        }
    }

    private suspend fun transcribeAudio(uri: Uri, ctx: WhisperContext) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(this, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw RuntimeException("No audio track found in this file")
            ex.selectTrack(track)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            var pcmFloat = false
            var lastTimeUs = 0L
            var chunkIndex = 0
            val chunk = FloatArray(CHUNK)
            var fill = 0

            suspend fun flush(len: Int) {
                val data = if (len == chunk.size) chunk else chunk.copyOf(len)
                val raw = ctx.transcribeData(data)
                val text = raw.lines().joinToString(" ") { it.replace(TS_REGEX, "").trim() }.trim()
                val t = chunkIndex * CHUNK_SEC
                chunkIndex++
                if (text.isNotEmpty()) {
                    val line = "[%02d:%02d:%02d] %s\n\n".format(t / 3600, (t % 3600) / 60, t % 60, text)
                    transcript.append(line)
                    ui { outView.append(line); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
                }
                val pct = if (durationUs > 0) (lastTimeUs * 100 / durationUs).toInt().coerceIn(0, 99) else 0
                ui { bar.progress = pct; status("Transcribing… $pct%") }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            ui { status("Transcribing… 0%") }
            while (!outputDone && !cancelled) {
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            lastTimeUs = ex.sampleTime
                            dec.queueInputBuffer(idx, 0, n, lastTimeUs, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val ob = dec.getOutputBuffer(o)!!
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val ch = max(channels, 1)
                        val mono: FloatArray
                        if (pcmFloat) {
                            val fb = ob.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            mono = FloatArray(frames) { i ->
                                var s = 0f
                                for (c in 0 until ch) s += fb.get(i * ch + c)
                                s / ch
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            val frames = sb.remaining() / ch
                            mono = FloatArray(frames) { i ->
                                var s = 0f
                                for (c in 0 until ch) s += sb.get(i * ch + c)
                                s / ch / 32768f
                            }
                        }
                        val res = resampler.process(mono)
                        var p = 0
                        while (p < res.size) {
                            val take = minOf(CHUNK - fill, res.size - p)
                            System.arraycopy(res, p, chunk, fill, take)
                            fill += take
                            p += take
                            if (fill == CHUNK) { flush(CHUNK); fill = 0 }
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler = Resampler(nf.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    pcmFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            if (!cancelled && fill >= SR / 2) flush(fill)
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Throwable) { }
            ex.release()
        }
    }
}
