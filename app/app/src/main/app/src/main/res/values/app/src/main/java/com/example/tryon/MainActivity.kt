package com.example.tryon

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

// ======================= UI =======================

@Composable
fun App() {
    val context = LocalContext.current
    var personUri by remember { mutableStateOf<Uri?>(null) }
    var garmentUri by remember { mutableStateOf<Uri?>(null) }
    var aiMode by remember { mutableStateOf(true) }

    val pickPerson = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { personUri = it ?: personUri }
    val pickGarment = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { garmentUri = it ?: garmentUri }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Virtual Try-On", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickBox("আপনার ছবি", personUri, Modifier.weight(1f)) { pickPerson.launch(imageOnly) }
            PickBox("ড্রেসের ছবি", garmentUri, Modifier.weight(1f)) { pickGarment.launch(imageOnly) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = aiMode, onClick = { aiMode = true }, label = { Text("🤖 AI ট্রাই-অন") })
            FilterChip(selected = !aiMode, onClick = { aiMode = false }, label = { Text("✋ কুইক") })
        }

        if (aiMode) AiMode(context, personUri, garmentUri) else QuickMode(context, personUri, garmentUri)
    }
}

@Composable
fun PickBox(label: String, uri: Uri?, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, uri) {
        value = uri?.let { withContext(Dispatchers.IO) { runCatching { decodeScaled(context, it, 512) }.getOrNull() } }
    }
    Box(
        modifier.aspectRatio(0.75f)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val t = thumb
        if (t != null) Image(t.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("$label\n(ট্যাপ করুন)", Modifier.padding(8.dp))
    }
}

// ---------- AI মোড ----------

@Composable
fun AiMode(context: Context, person: Uri?, garment: Uri?) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }

    Button(
        enabled = person != null && garment != null && !loading,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            loading = true; error = null; result = null
            scope.launch {
                try {
                    result = GradioTryOn.run(context, person!!, garment!!) { status = it }
                } catch (e: Exception) {
                    error = e.message ?: "কিছু একটা ভুল হয়েছে"
                } finally { loading = false }
            }
        }
    ) { Text("Try On") }

    if (loading) { CircularProgressIndicator(); Text(status) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let { ResultView(context, it) }
    Text(
        "⚠️ ছবি দুটো Hugging Face-এর পাবলিক ডেমো সার্ভারে যায়। ভিড় থাকলে ১-৩ মিনিট বা বেশি লাগতে পারে।",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
fun ResultView(context: Context, bmp: Bitmap) {
    Image(bmp.asImageBitmap(), "Result", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    OutlinedButton(
        onClick = {
            val ok = saveToGallery(context, bmp)
            Toast.makeText(context, if (ok) "গ্যালারিতে সেভ হয়েছে" else "সেভ করা যায়নি", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("গ্যালারিতে সেভ করুন") }
}

// ---------- কুইক মোড (পুরোপুরি ফোনে) ----------

private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

fun renderTryOn(c: android.graphics.Canvas, person: Bitmap, cut: Bitmap?, cx: Float, cy: Float, gs: Float, rot: Float) {
    c.drawBitmap(person, 0f, 0f, null)
    if (cut != null) {
        c.save()
        c.translate(cx, cy); c.rotate(rot); c.scale(gs, gs)
        c.drawBitmap(cut, -cut.width / 2f, -cut.height / 2f, bmpPaint)
        c.restore()
    }
}

fun removeLightBackground(src: Bitmap, threshold: Int): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    if (threshold >= 255) return out
    val px = IntArray(out.width * out.height)
    out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
    for (i in px.indices) {
        val p = px[i]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        if (min(r, min(g, b)) >= threshold) px[i] = 0
    }
    out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
    return out
}

@Composable
fun QuickMode(context: Context, personUri: Uri?, garmentUri: Uri?) {
    val person by produceState<Bitmap?>(null, personUri) {
        value = personUri?.let { withContext(Dispatchers.IO) { runCatching { decodeScaled(context, it, 1200) }.getOrNull() } }
    }
    val garment by produceState<Bitmap?>(null, garmentUri) {
        value = garmentUri?.let { withContext(Dispatchers.IO) { runCatching { decodeScaled(context, it, 800) }.getOrNull() } }
    }
    var threshold by remember { mutableFloatStateOf(235f) }
    val cut by produceState<Bitmap?>(null, garment, threshold) {
        value = garment?.let { g -> withContext(Dispatchers.Default) { removeLightBackground(g, threshold.toInt()) } }
    }

    var cx by remember { mutableFloatStateOf(0f) }
    var cy by remember { mutableFloatStateOf(0f) }
    var gs by remember { mutableFloatStateOf(1f) }
    var rot by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(person, garment) {
        val p = person; val g = garment
        if (p != null && g != null) {
            cx = p.width / 2f; cy = p.height * 0.4f
            gs = (p.width * 0.5f) / g.width; rot = 0f
        }
    }

    val p = person
    if (p == null) { Text("আগে আপনার ছবি দিন।"); return }

    var k by remember { mutableFloatStateOf(1f) }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(p.width.toFloat() / p.height)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    cx += pan.x / k; cy += pan.y / k
                    gs *= zoom; rot += rotation
                }
            }
    ) {
        k = size.width / p.width
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save(); nc.scale(k, k)
            renderTryOn(nc, p, cut, cx, cy, gs, rot)
            nc.restore()
        }
    }
    Text("এক আঙুলে টেনে সরান, দুই আঙুলে বড়/ছোট ও ঘোরান।", style = MaterialTheme.typography.bodySmall)

    Text("সাদা ব্যাকগ্রাউন্ড মোছা (ডানে নিলে বন্ধ)", style = MaterialTheme.typography.bodySmall)
    Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 150f..255f)

    OutlinedButton(
        enabled = cut != null,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            val out = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
            renderTryOn(android.graphics.Canvas(out), p, cut, cx, cy, gs, rot)
            val ok = saveToGallery(context, out)
            Toast.makeText(context, if (ok) "গ্যালারিতে সেভ হয়েছে" else "সেভ করা যায়নি", Toast.LENGTH_SHORT).show()
        }
    ) { Text("গ্যালারিতে সেভ করুন") }
    Text("এটা শুধু ছবির ওপর ছবি বসানো, ভাঁজ বা শরীরের গড়ন মানবে না। কিছু আপলোড হয় না।", style = MaterialTheme.typography.bodySmall)
}

// ======================= AI: Hugging Face Gradio API =======================

object GradioTryOn {
    private const val BASE = "https://yisol-idm-vton.hf.space"
    private const val API_NAME = "tryon"

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    suspend fun run(context: Context, person: Uri, garment: Uri, onStatus: (String) -> Unit): Bitmap =
        withContext(Dispatchers.IO) {
            onStatus("ছবি আপলোড হচ্ছে…")
            val (apiBase, personPath) = upload(toJpeg(context, person))
            val (_, garmentPath) = upload(toJpeg(context, garment), apiBase)

            fun fileData(path: String) = JSONObject()
                .put("path", path).put("meta", JSONObject().put("_type", "gradio.FileData"))

            val data = JSONArray()
                .put(JSONObject().put("background", fileData(personPath)).put("layers", JSONArray()).put("composite", JSONObject.NULL))
                .put(fileData(garmentPath))
                .put("a garment")
                .put(true)
                .put(false)
                .put(30)
                .put(42)

            onStatus("প্রসেস হচ্ছে… অ্যাপ বন্ধ করবেন না।")
            val callReq = Request.Builder().url("$apiBase/call/$API_NAME")
                .post(JSONObject().put("data", data).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val eventId = http.newCall(callReq).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("সার্ভার এরর (${r.code}): ${body.take(200)}")
                JSONObject(body).getString("event_id")
            }

            val streamReq = Request.Builder().url("$apiBase/call/$API_NAME/$eventId").build()
            val resultJson = http.newCall(streamReq).execute().use { r ->
                if (!r.isSuccessful) error("সার্ভার এরর (${r.code})")
                val src = r.body!!.source()
                var event = ""
                var out: String? = null
                while (out == null) {
                    val line = src.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val d = line.removePrefix("data:").trim()
                            if (event == "complete") out = d
                            if (event == "error") error("AI সার্ভার এরর: ${d.take(200)}")
                        }
                    }
                }
                out ?: error("রেজাল্ট পাওয়া যায়নি (ডেমো ব্যস্ত বা বন্ধ হতে পারে)")
            }

            val first = JSONArray(resultJson).getJSONObject(0)
            val url = first.optString("url").ifEmpty { "$apiBase/file=${first.getString("path")}" }
            onStatus("রেজাল্ট নামানো হচ্ছে…")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("ছবি নামানো যায়নি (${r.code})")
                BitmapFactory.decodeStream(r.body!!.byteStream()) ?: error("রেজাল্ট ছবি পড়া যায়নি")
            }
        }

    private fun upload(jpeg: ByteArray, knownBase: String? = null): Pair<String, String> {
        val candidates = if (knownBase != null) listOf(knownBase) else listOf("$BASE/gradio_api", BASE)
        var lastCode = 0
        for (api in candidates) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("files", "image.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                .build()
            http.newCall(Request.Builder().url("$api/upload").post(body).build()).execute().use { r ->
                lastCode = r.code
                if (r.isSuccessful) return api to JSONArray(r.body!!.string()).getString(0)
            }
        }
        error("আপলোড ব্যর্থ ($lastCode) — ডেমো বন্ধ বা ব্যস্ত থাকতে পারে")
    }
}

// ======================= ছবির হেল্পার =======================

fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (max(opts.outWidth, opts.outHeight) / sample > maxSide * 2) sample *= 2
    val src = context.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }!!
    val scale = min(1f, maxSide / max(src.width, src.height).toFloat())
    return if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
}

fun toJpeg(context: Context, uri: Uri, maxSide: Int = 1024): ByteArray {
    val out = ByteArrayOutputStream()
    decodeScaled(context, uri, maxSide).compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

fun saveToGallery(context: Context, bmp: Bitmap): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "tryon_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TryOn")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return true
}
