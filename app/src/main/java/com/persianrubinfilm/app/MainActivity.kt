package com.persianrubinfilm.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
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

// Only public catalog metadata is read. Video URLs, DRM and cookies are not extracted.
private suspend fun fetchCatalog(): List<Film> = withContext(Dispatchers.IO) {
    try {
        val doc = Jsoup.connect(HOME).userAgent("PersianRubinFilm/0.1 (Android catalog client)")
            .timeout(15000).get()
        val results = linkedMapOf<String, Film>()
        for (heading in doc.select("h3")) {
            val link = heading.selectFirst("a[href]") ?: continue
            val href = link.absUrl("href")
            if (!href.startsWith("$BASE/") || href == HOME) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val container = heading.parent()
            val image = container?.selectFirst("img") ?: heading.previousElementSibling()?.selectFirst("img")
            val poster = image?.absUrl("data-src")?.takeIf { it.startsWith("http") }
                ?: image?.absUrl("src")?.takeIf { it.startsWith("http") }
            val section = heading.parents().firstOrNull { el ->
                el.selectFirst("h2")?.text()?.contains("Series", ignoreCase = true) == true ||
                el.selectFirst("h2")?.text()?.contains("Movies", ignoreCase = true) == true
            }
            val sectionText = section?.selectFirst("h2")?.text().orEmpty()
            val type = if (sectionText.contains("Series", true) ||
                heading.parent()?.text()?.contains("EPISODES", true) == true) "series" else "movie"
            results[href] = Film(title, href, poster, type)
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
                TextButton(onClick = { selected = null }) { Text(label("← Back", "بازگشت ←")) }
                Text(film.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 22.sp)
                Text(label("Playback uses the source site's own page/player. Availability and permissions depend on the provider.",
                    "پخش از صفحه و پخش‌کنندهٔ سایت اصلی انجام می‌شود."), Modifier.padding(16.dp), color = Color.LightGray)
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val url = request?.url ?: return true
                                // Allow ordinary HTTPS navigation only; do not intercept media or bypass site protections.
                                return url.scheme != "https"
                            }
                        }
                        loadUrl(film.url)
                    }
                }, update = { if (it.url != film.url && it.url == null) it.loadUrl(film.url) },
                    modifier = Modifier.fillMaxSize())
            } else {
                OutlinedTextField(query, { query = it }, label = { Text(label("Search titles", "جستجوی فیلم و سریال")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("home" to label("Home", "خانه"), "movie" to label("Movies", "فیلم‌ها"),
                        "series" to label("Series", "سریال‌ها"), "favorites" to label("My List", "فهرست من"))
                        .forEach { (key, title) -> TextButton(onClick = { tab = key }) {
                            Text(title, color = if (tab == key) red else Color.LightGray, fontSize = 12.sp)
                        } }
                }
                if (loading) { CircularProgressIndicator(Modifier.padding(24.dp)) }
                else if (catalog.isEmpty()) {
                    Column(Modifier.padding(24.dp)) {
                        Text(label("Catalog unavailable. Check your connection or the source website.",
                            "فهرست در دسترس نیست. اتصال یا سایت اصلی را بررسی کنید."), color = Color.White)
                        TextButton(onClick = { selected = Film("Source website", HOME, null, "movie") }) {
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
                                Column(Modifier.fillMaxWidth().clickable { selected = film }.padding(horizontal = 16.dp)) {
                                    AsyncImage(model = film.poster, contentDescription = film.title,
                                        modifier = Modifier.fillMaxWidth().height(190.dp).background(Color.DarkGray, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                    Text(film.title, Modifier.padding(vertical = 8.dp), color = Color.White, fontSize = 20.sp)
                                    Button(onClick = { selected = film }) { Text(label("Watch", "تماشا")) }
                                }
                            } }
                            for ((type, en, fa) in listOf(Triple("movie", "Movies", "فیلم‌ها"), Triple("series", "Series", "سریال‌ها"))) {
                                item { Text(label(en, fa), Modifier.padding(16.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                                item { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    items(catalog.filter { it.type == type }) { film -> FilmCard(film, film.url in favorites,
                                        { selected = film }, { favorites = if (film.url in favorites) favorites - film.url else favorites + film.url
                                            prefs.edit().putStringSet("favorites", favorites).apply() }) }
                                } }
                            }
                        } else {
                            items(filtered) { film -> FilmRow(film, film.url in favorites, { selected = film }, {
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

@Composable private fun FilmCard(film: Film, saved: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Column(Modifier.width(145.dp).padding(5.dp)) {
        AsyncImage(model = film.poster, contentDescription = film.title,
            modifier = Modifier.fillMaxWidth().height(195.dp).background(Color.DarkGray, RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
            contentScale = ContentScale.Crop)
        Text(film.title, Modifier.clickable(onClick = onOpen).padding(top = 5.dp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
    }
}
@Composable private fun FilmRow(film: Film, saved: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = film.poster, contentDescription = film.title,
            modifier = Modifier.size(width = 90.dp, height = 125.dp).background(Color.DarkGray), contentScale = ContentScale.Crop)
        Text(film.title, Modifier.weight(1f).padding(12.dp), color = Color.White)
        TextButton(onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
    }
}
