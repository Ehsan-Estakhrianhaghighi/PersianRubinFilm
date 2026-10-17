package com.persianrubinfilm.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.webkit.WebSettings
import android.os.Message
import android.view.ViewGroup
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import java.net.URLDecoder
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI

private const val BASE = "https://www.diycraftsguide.com"
private const val HOME = "$BASE/safeasli"
private val red = Color(0xFFE63946)
private val bg = Color(0xFF101116)
private data class Film(val title: String, val url: String, val poster: String?, val type: String)
private data class WatchEntry(val url: String, val title: String, val poster: String?, val type: String,
    val lastPage: String, val visitedAt: Long, val completed: Boolean = false)

private fun readHistory(prefs: android.content.SharedPreferences): Map<String, WatchEntry> = try {
    val root = JSONObject(prefs.getString("watch_history", "{}") ?: "{}")
    root.keys().asSequence().mapNotNull { key ->
        try {
            val o = root.getJSONObject(key)
            key to WatchEntry(key, o.optString("title"), o.optString("poster").ifBlank { null },
                o.optString("type", "movie"), o.optString("lastPage", key),
                o.optLong("visitedAt"), o.optBoolean("completed"))
        } catch (_: Exception) { null }
    }.toMap()
} catch (_: Exception) { emptyMap() }

private fun writeHistory(prefs: android.content.SharedPreferences, entries: Map<String, WatchEntry>) {
    val root = JSONObject()
    entries.values.forEach { e -> root.put(e.url, JSONObject().apply {
        put("title", e.title); put("poster", e.poster ?: ""); put("type", e.type)
        put("lastPage", e.lastPage); put("visitedAt", e.visitedAt); put("completed", e.completed)
    }) }
    prefs.edit().putString("watch_history", root.toString()).apply()
}

private fun imageUrl(raw: String?, base: String): String? {
    val value = raw?.trim()?.takeUnless { it.isBlank() || it.startsWith("data:") || it.startsWith("blob:") } ?: return null
    return try { URI(base).resolve(value).toString().takeIf { it.startsWith("https://") || it.startsWith("http://") } }
    catch (_: Exception) { null }
}
private fun posterFrom(img: org.jsoup.nodes.Element?, base: String): String? {
    if (img == null) return null
    for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-url", "src")) {
        imageUrl(img.attr(attr), base)?.let { return it }
    }
    for (attr in listOf("data-srcset", "srcset")) {
        val candidates = img.attr(attr).split(",").mapNotNull { chunk ->
            val parts = chunk.trim().split(Regex("\\s+"))
            val url = imageUrl(parts.firstOrNull(), base)
            val size = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
            if (url != null) size to url else null
        }
        candidates.maxByOrNull { it.first }?.second?.let { return it }
    }
    return null
}
private fun posterModel(context: android.content.Context, film: Film): Any? = film.poster?.let {
    ImageRequest.Builder(context).data(it).crossfade(true)
        .addHeader("Referer", HOME)
        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        .build()
}
private fun isAllowedNavigation(target: Uri, originalHost: String): Boolean {
    if (target.scheme != "https") return false
    val host = target.host?.lowercase() ?: return false
    // Allow the source site and embedded video-player hosts, but block unsolicited top-level ad redirects.
    return host == originalHost || host.endsWith(".$originalHost")
}


// Only public catalog metadata is read. Video URLs, DRM and cookies are not extracted.
private suspend fun fetchCatalog(): List<Film> = withContext(Dispatchers.IO) {
    try {
        val doc = Jsoup.connect(HOME).userAgent("PersianRubinFilm/0.1 (Android catalog client)")
            .timeout(15000).get()
        val results = linkedMapOf<String, Film>()
        // Catalog layouts differ: the title link may wrap the image, or sit beside it.
        for (link in doc.select("h2 a[href], h3 a[href], .post a[href], article a[href], .entry-title a[href]")) {
            val href = link.absUrl("href")
            if (!href.startsWith("$BASE/") || href == HOME || href.contains("#")) continue
            val container = link.closest("article, .post, .post-item, .item, .entry, .card")
                ?: link.parent()?.parent() ?: link.parent()
            val heading = link.closest("h2, h3, h4")
            val title = (heading?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: container?.selectFirst("img[alt]")?.attr("alt")?.trim()).orEmpty()
            if (title.isBlank()) continue
            val img = link.selectFirst("img") ?: container?.selectFirst("img")
            val poster = posterFrom(img, doc.baseUri())
                ?: imageUrl(container?.selectFirst("meta[property=og:image]")?.attr("content"), doc.baseUri())
            val nearby = container?.text().orEmpty()
            val type = if (nearby.contains("episode", true) || nearby.contains("season", true) ||
                nearby.contains("سریال") || title.contains("سریال")) "series" else "movie"
            val old = results[href]
            if (old == null || (old.poster == null && poster != null)) results[href] = Film(title, href, poster, type)
        }
        results.values.toList()
    } catch (_: Exception) { emptyList() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var persian by remember { mutableStateOf(prefs.getBoolean("persian", false)) }
    var tab by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Film?>(null) }
    var catalog by remember { mutableStateOf<List<Film>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(readHistory(prefs)) }
    var resumeUrl by remember { mutableStateOf<String?>(null) }
    fun openFilm(film: Film, resume: Boolean = false) {
        resumeUrl = if (resume) history[film.url]?.lastPage else null
        selected = film
        val previous = history[film.url]
        history = history + (film.url to WatchEntry(film.url, film.title, film.poster, film.type,
            resumeUrl ?: film.url, System.currentTimeMillis(), previous?.completed ?: false))
        writeHistory(prefs, history)
    }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet()) }
    LaunchedEffect(Unit) { catalog = fetchCatalog(); loading = false }
    fun label(en: String, fa: String) = if (persian) fa else en
    MaterialTheme(colorScheme = darkColorScheme(primary = red, background = bg, surface = Color(0xFF1D1E25))) {
        Column(Modifier.fillMaxSize().background(bg)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PersianRubinFilm", color = red, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                TextButton(onClick = { persian = !persian; prefs.edit().putBoolean("persian", persian).apply() }) {
                    Text(if (persian) "English" else "فارسی")
                }
            }
            if (selected != null) {
                val film = selected!!
                TextButton(onClick = { selected = null; resumeUrl = null }) { Text(label("← Back", "بازگشت ←")) }
                Text(film.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 22.sp)
                Text(label("Playback uses the source site's own page/player. Availability and permissions depend on the provider.",
                    "پخش از صفحه و پخش‌کنندهٔ سایت اصلی انجام می‌شود."), Modifier.padding(16.dp), color = Color.LightGray)
                val lastPage = history[film.url]?.lastPage
                if (lastPage != null && lastPage != film.url) {
                    TextButton(onClick = { resumeUrl = lastPage }) {
                        Text(label("Open last visited episode/page", "باز کردن آخرین قسمت / صفحه"))
                    }
                }
                TextButton(onClick = {
                    val old = history[film.url]
                    if (old != null) { history = history + (film.url to old.copy(completed = !old.completed)); writeHistory(prefs, history) }
                }) { Text(if (history[film.url]?.completed == true) label("✓ Watched (tap to undo)", "✓ دیده‌شده")
                    else label("Mark watched", "علامت‌گذاری به عنوان دیده‌شده")) }
                key(film.url, resumeUrl) {
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(view: WebView?, isDialog: Boolean,
                                    isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val requestUri = request?.url ?: return true
                                    if (!request.isForMainFrame) return false // embedded players can load their own frames
                                    return !isAllowedNavigation(requestUri, Uri.parse(BASE).host ?: "www.diycraftsguide.com")
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url != null && isAllowedNavigation(Uri.parse(url), Uri.parse(BASE).host ?: "www.diycraftsguide.com")) {
                                        val old = history[film.url]
                                        if (old != null && old.lastPage != url) {
                                            history = history + (film.url to old.copy(lastPage = url, visitedAt = System.currentTimeMillis()))
                                            writeHistory(prefs, history)
                                        }
                                    }
                                }
                            }
                            loadUrl(resumeUrl ?: film.url)
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
            } else {
                OutlinedTextField(query, { query = it }, label = { Text(label("Search titles", "جستجوی فیلم و سریال")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("home" to label("Home", "خانه"), "history" to label("History", "تاریخچه"), "movie" to label("Movies", "فیلم‌ها"),
                        "series" to label("Series", "سریال‌ها"), "favorites" to label("My List", "فهرست من"))
                        .forEach { (key, title) -> TextButton(onClick = { tab = key }) {
                            Text(title, color = if (tab == key) red else Color.LightGray, fontSize = 12.sp)
                        } }
                }
                if (tab == "history") {
                    val recent = history.values.sortedByDescending { it.visitedAt }
                    if (recent.isEmpty()) Text(label("No watch history yet.", "هنوز سابقه‌ای وجود ندارد."),
                        Modifier.padding(20.dp), color = Color.White)
                    else LazyColumn {
                        item { Text(label("Continue watching / Recently opened", "ادامه تماشا / اخیراً بازشده"),
                            Modifier.padding(16.dp), color = Color.White, fontSize = 19.sp) }
                        items(recent) { entry ->
                            val film = Film(entry.title, entry.url, entry.poster, entry.type)
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Poster(film, Modifier.size(width = 76.dp, height = 105.dp).clickable { openFilm(film, true) })
                                Column(Modifier.weight(1f).padding(8.dp)) {
                                    Text(entry.title, color = Color.White, maxLines = 2)
                                    Text(if (entry.completed) label("Watched", "دیده‌شده") else label("Recently opened", "اخیراً بازشده"),
                                        color = Color.LightGray, fontSize = 12.sp)
                                    TextButton(onClick = { openFilm(film, true) }) { Text(label("Continue", "ادامه")) }
                                }
                                TextButton(onClick = { history = history - entry.url; writeHistory(prefs, history) }) {
                                    Text(label("Remove", "حذف"), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else if (loading) { CircularProgressIndicator(Modifier.padding(24.dp)) }
                else if (catalog.isEmpty()) {
                    Column(Modifier.padding(24.dp)) {
                        Text(label("Catalog unavailable. Check your connection or the source website.",
                            "فهرست در دسترس نیست. اتصال یا سایت اصلی را بررسی کنید."), color = Color.White)
                        TextButton(onClick = { openFilm(Film("Source website", HOME, null, "movie")) }) {
                            Text(label("Open source website", "باز کردن سایت"))
                        }
                    }
                } else {
                    val filtered = catalog.filter { it.title.contains(query, ignoreCase = true) &&
                        (tab == "home" || tab == it.type || (tab == "favorites" && it.url in favorites)) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (tab == "home" && query.isBlank()) {
                            item { Text(label("Featured", "ویژه"), Modifier.padding(16.dp), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                            item { catalog.firstOrNull()?.let { film ->
                                Column(Modifier.fillMaxWidth().clickable { openFilm(film) }.padding(horizontal = 16.dp)) {
                                    Poster(film, Modifier.fillMaxWidth().height(190.dp))
                                    Text(film.title, Modifier.padding(vertical = 8.dp), color = Color.White, fontSize = 20.sp)
                                    Button(onClick = { openFilm(film) }) { Text(label("Watch", "تماشا")) }
                                }
                            } }
                            for ((type, en, fa) in listOf(Triple("movie", "Movies", "فیلم‌ها"), Triple("series", "Series", "سریال‌ها"))) {
                                item { Text(label(en, fa), Modifier.padding(16.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                                item { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    items(catalog.filter { it.type == type }) { film -> FilmCard(film, film.url in favorites,
                                        { openFilm(film) }, { favorites = if (film.url in favorites) favorites - film.url else favorites + film.url
                                            prefs.edit().putStringSet("favorites", favorites).apply() }) }
                                } }
                            }
                        } else {
                            items(filtered) { film -> FilmRow(film, film.url in favorites, { openFilm(film) }, {
                                favorites = if (film.url in favorites) favorites - film.url else favorites + film.url
                                prefs.edit().putStringSet("favorites", favorites).apply()
                            }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Poster(film: Film, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier.background(Color(0xFF30313B), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text("🎬", fontSize = 30.sp, color = Color.LightGray)
        AsyncImage(model = posterModel(ctx, film), contentDescription = film.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable private fun FilmCard(film: Film, saved: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Column(Modifier.width(145.dp).padding(5.dp)) {
        Poster(film, Modifier.fillMaxWidth().height(195.dp).clickable(onClick = onOpen))
        Text(film.title, Modifier.clickable(onClick = onOpen).padding(top = 5.dp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
    }
}
@Composable private fun FilmRow(film: Film, saved: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(film, Modifier.size(width = 90.dp, height = 125.dp))
        Text(film.title, Modifier.weight(1f).padding(12.dp), color = Color.White)
        TextButton(onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
    }
}
