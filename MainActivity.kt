package com.persianrubinfilm.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import android.webkit.WebView
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.ByteArrayInputStream
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
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
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
private fun cssPoster(element: org.jsoup.nodes.Element?, base: String): String? {
    if (element == null) return null
    val style = element.attr("style")
    val match = Regex("""url\(\s*['"]?([^'")]+)""", RegexOption.IGNORE_CASE).find(style)
    return imageUrl(match?.groupValues?.getOrNull(1), base)
        ?: imageUrl(element.attr("data-bg"), base)
        ?: imageUrl(element.attr("data-background"), base)
}
private fun posterFrom(img: org.jsoup.nodes.Element?, base: String): String? {
    if (img == null) return null
    for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-url", "data-fallback-src", "src")) {
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
    return cssPoster(img, base)
}
private fun posterModel(context: android.content.Context, film: Film): Any? = film.poster?.let {
    ImageRequest.Builder(context).data(it).crossfade(true)
        .addHeader("Referer", HOME)
        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        .build()
}
private val blockedAdHosts = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adnxs.com", "popads.net", "popcash.net", "propellerads.com",
    "onclickads.net", "exoclick.com", "trafficjunky.net",
    "adsterra.com", "ad-maven.com", "hilltopads.net", "clickadu.com",
    "richads.com", "monetag.com", "popmyads.com", "revenuehits.com"
)
private fun isAdHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return blockedAdHosts.any { h == it || h.endsWith(".$it") }
}
private fun suppressPageOverlays(view: WebView) {
    // Only modify the top-level document; do not remove video frames or playback controls.
    view.evaluateJavascript("""(function(){
      if(window.__prfGuardInstalled)return; window.__prfGuardInstalled=true;
      const css=document.createElement('style'); css.textContent=
        '[id*="popunder" i],[class*="popunder" i],[id*="popup-ad" i],'+
        '[class*="popup-ad" i],[class*="ad-overlay" i],[id*="ad-overlay" i],'+
        '[class*="adsbygoogle" i] {display:none!important}';
      (document.head||document.documentElement).appendChild(css);
      // Capture unwanted external top-page link clicks, leaving same-site navigation alone.
      document.addEventListener('click',function(e){
        const a=e.target.closest&&e.target.closest('a[href]'); if(!a)return;
        try {const dest=new URL(a.href,location.href);
          const source=location.hostname.replace(/^www\./,'');
          if(dest.hostname!==location.hostname && dest.hostname!==source &&
             !dest.hostname.endsWith('.'+source)) { e.preventDefault();e.stopImmediatePropagation(); }
        } catch(_){}
      },true);
      document.querySelectorAll('a[target="_blank"]').forEach(a=>a.removeAttribute('target'));
    })();""", null)
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
        val doc = Jsoup.connect(HOME)
            .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            .timeout(20000).get()
        val results = linkedMapOf<String, Film>()

        // Movie and TV cards have a *separate* poster link and h3 title link.
        // Never treat the generic "Play" action as the title of a catalog item.
        fun meaningfulTitle(raw: String?): String? {
            val t = raw?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            if (t.isBlank() || t.length > 160) return null
            if (t.lowercase() in setOf("play", "watch", "watch now", "read more", "view more", "more", "hd", "sd", "4k", "all")) return null
            if (t.matches(Regex("""[0-9]+\s*episodes?""", RegexOption.IGNORE_CASE))) return null
            return t
        }

        fun cardImage(link: org.jsoup.nodes.Element, baseUri: String): String? {
            // Start inside the title link, then inspect nearby siblings within its card.
            // Do not take a random image from an entire section or from the page header.
            var node: org.jsoup.nodes.Element? = link
            for (depth in 0..4) {
                val n = node ?: break
                val img = n.selectFirst("img[data-src], img[data-original], img[src], picture img, source[srcset]")
                val candidate = posterFrom(img, baseUri)
                    ?: cssPoster(n.selectFirst("[style*=background]"), baseUri)
                if (candidate != null) return candidate
                if (n.tagName() in listOf("section", "main", "body", "html")) break
                node = n.parent()
            }
            return null
        }

        fun save(link: org.jsoup.nodes.Element, category: String, baseUri: String) {
            val href = link.absUrl("href")
            if (!href.startsWith("$BASE/") || href == HOME || '#' in href) return
            val heading = link.closest("h2, h3, h4")
                ?: link.selectFirst("h2, h3, h4")
            val title = meaningfulTitle(heading?.text())
                ?: meaningfulTitle(link.attr("title"))
                ?: meaningfulTitle(link.text())
                ?: meaningfulTitle(link.selectFirst("img")?.attr("alt"))
                ?: return
            val poster = cardImage(link, baseUri)
            val current = results[href]
            // Better title and poster replace an earlier weak card for the same URL.
            // This is important when the site has both an image link and a title link.
            val finalTitle = if (current == null || current.title.equals("Play", true) ||
                (heading != null && current.title != title)) title else current.title
            val finalPoster = poster ?: current?.poster
            val finalType = if (category == "series" || current?.type == "series") "series" else "movie"
            results[href] = Film(finalTitle, href, finalPoster, finalType)
        }

        // Prefer the site's real card heading links. Their text is the movie/series name;
        // "Play" links in the hero carousel are not catalog names.
        var activeSection: String? = null
        for (element in doc.getAllElements()) {
            if (element.tagName().matches(Regex("h[1-4]"))) {
                when (element.text().trim().lowercase()) {
                    "latest tv series", "latest series" -> activeSection = "series"
                    "latest movies", "movie suggestion" -> activeSection = "movie"
                    "all tv channels", "about" -> activeSection = null
                }
            }
            if (activeSection != null && element.tagName() == "a" &&
                (element.closest("h2, h3, h4") != null ||
                    element.selectFirst("h2, h3, h4") != null)) {
                save(element, activeSection, doc.baseUri())
            }
        }
        // Structural fallback, again restricted to actual title links instead of players.
        for (link in doc.select("article h2 a[href], article h3 a[href], article h4 a[href], .post h2 a[href], .post h3 a[href], .item h3 a[href], .card h3 a[href], h2 a[href], h3 a[href], h4 a[href]")) {
            val container = link.closest("article, .post, .item, .card, li") ?: link.parent()
            val context = generateSequence(container) { it.parent() }.take(5)
                .joinToString(" ") { it.className().lowercase() + " " + it.id().lowercase() }
            val nearby = container?.text().orEmpty()
            val category = if (context.contains("serial") || context.contains("series") ||
                nearby.contains("episodes", true) || nearby.contains("season", true) ||
                nearby.contains("سریال")) "series" else "movie"
            save(link, category, doc.baseUri())
        }
        // Separate catalog page provides extra series not visible on the homepage.
        for ((page, category) in listOf("$BASE/serial.html" to "series", "$BASE/movie.html" to "movie")) {
            try {
                val extra = Jsoup.connect(page).userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                    .referrer(HOME).timeout(12000).get()
                for (link in extra.select("article h2 a[href], article h3 a[href], .item h3 a[href], .post h3 a[href], .card h3 a[href], h2 a[href], h3 a[href]")) {
                    save(link, category, extra.baseUri())
                }
            } catch (_: Exception) { /* Keep homepage results if catalog is unavailable. */ }
        }
        // Enrich missing images from individual public movie/series metadata.
        val initial = results.values.toList()
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            initial.map { film ->
                async(Dispatchers.IO) {
                    if (film.poster != null) film else gate.withPermit {
                        try {
                            val detail = Jsoup.connect(film.url)
                                .userAgent("Mozilla/5.0 (Android TV)")
                                .referrer(HOME).timeout(8500).get()
                            val meta = detail.selectFirst("meta[property=og:image], meta[name=twitter:image], link[rel=image_src]")
                            val candidate = imageUrl(meta?.attr("content")?.ifBlank { meta.attr("href") }, detail.baseUri())
                                ?: posterFrom(detail.selectFirst(".poster img, .thumb img, article img, img"), detail.baseUri())
                            film.copy(poster = candidate)
                        } catch (_: Exception) { film }
                    }
                }
            }.map { it.await() }
        }
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
    val isTv = remember { context.packageManager.hasSystemFeature("android.software.leanback") ||
        context.packageManager.hasSystemFeature("android.hardware.type.television") }
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var persian by remember { mutableStateOf(prefs.getBoolean("persian", false)) }
    var tab by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Film?>(null) }
    var resumeUrl by remember { mutableStateOf<String?>(null) }
    BackHandler(selected != null) { selected = null; resumeUrl = null }
    var catalog by remember { mutableStateOf<List<Film>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(readHistory(prefs)) }
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
                Text("PersianRubinFilm", color = red, fontWeight = FontWeight.Bold, fontSize = if (isTv) 29.sp else 22.sp)
                TextButton(modifier = Modifier.tvFocus(), onClick = { persian = !persian; prefs.edit().putBoolean("persian", persian).apply() }) {
                    Text(if (persian) "English" else "فارسی")
                }
            }
            if (selected != null) {
                val film = selected!!
                TextButton(modifier = Modifier.tvFocus(), onClick = { selected = null; resumeUrl = null }) { Text(label("← Back", "بازگشت ←")) }
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
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.mediaPlaybackRequiresUserGesture = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(view: WebView?, isDialog: Boolean,
                                    isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val url = request?.url ?: return null
                                    if (isAdHost(url.host)) {
                                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return null
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val requestUri = request?.url ?: return true
                                    if (!request.isForMainFrame) return false // embedded players can load their own frames
                                    return !isAllowedNavigation(requestUri, Uri.parse(BASE).host ?: "www.diycraftsguide.com")
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (view != null) suppressPageOverlays(view)
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).tvFocus(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("home" to label("Home", "خانه"), "history" to label("History", "تاریخچه"), "movie" to label("Movies", "فیلم‌ها"),
                        "series" to label("Series", "سریال‌ها"), "favorites" to label("My List", "فهرست من"))
                        .forEach { (key, title) -> TextButton(modifier = Modifier.tvFocus(), onClick = { tab = key }) {
                            Text(title, color = if (tab == key) red else Color.LightGray, fontSize = if (isTv) 18.sp else 12.sp)
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
                                Poster(film, Modifier.size(width = 76.dp, height = 105.dp).tvFocus().clickable { openFilm(film, true) })
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
                                Column(Modifier.fillMaxWidth().tvFocus().clickable { openFilm(film) }.padding(horizontal = 16.dp)) {
                                    Poster(film, Modifier.fillMaxWidth().height(190.dp))
                                    Text(film.title, Modifier.padding(vertical = 8.dp), color = Color.White, fontSize = 20.sp)
                                    Button(modifier = Modifier.tvFocus(), onClick = { openFilm(film) }) { Text(label("Watch", "تماشا")) }
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

// Focus outline makes D-pad selection visible from a sofa distance.
@Composable private fun Modifier.tvFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this.onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent,
            RoundedCornerShape(8.dp))
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
    val ctx = LocalContext.current
    val tv = ctx.packageManager.hasSystemFeature("android.software.leanback") ||
        ctx.packageManager.hasSystemFeature("android.hardware.type.television")
    Column(Modifier.width(if (tv) 212.dp else 145.dp).padding(if (tv) 8.dp else 5.dp)) {
        Poster(film, Modifier.fillMaxWidth().height(if (tv) 275.dp else 195.dp).tvFocus().clickable(onClick = onOpen))
        Text(film.title, Modifier.tvFocus().clickable(onClick = onOpen).padding(top = 5.dp), color = Color.White, fontSize = if (tv) 18.sp else 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(modifier = Modifier.tvFocus(), onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
    }
}
@Composable private fun FilmRow(film: Film, saved: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().tvFocus().clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(film, Modifier.size(width = 90.dp, height = 125.dp))
        Text(film.title, Modifier.weight(1f).padding(12.dp), color = Color.White)
        TextButton(modifier = Modifier.tvFocus(), onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
    }
}
