package com.persianrubinfilm.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.FrameLayout
import android.webkit.WebView.HitTestResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.ByteArrayInputStream
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.webkit.WebSettings
import android.os.Message
import android.view.ViewGroup
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import coil.request.ImageRequest
import coil.request.CachePolicy
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
private data class Episode(val season: Int, val number: Int, val title: String, val url: String)
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
    val value = raw?.trim()?.replace("&amp;", "&")?.takeUnless {
        it.isBlank() || it.startsWith("data:") || it.startsWith("blob:") ||
            it.startsWith("javascript:") || it.startsWith("//invalid") ||
            it.contains("placeholder", true) || it.contains("no-image", true) ||
            it.contains("loading.gif", true) || it.contains("blank.gif", true) ||
            it.contains("spacer.gif", true) || it.contains("pixel.gif", true) ||
            it.contains("1x1", true) || it.endsWith(".svg", true)
    } ?: return null
    return try {
        URI(base).resolve(value).toString().takeIf { it.startsWith("https://") || it.startsWith("http://") }
    } catch (_: Exception) { null }
}
private fun cssPoster(element: org.jsoup.nodes.Element?, base: String): String? {
    if (element == null) return null
    val style = element.attr("style")
    val match = Regex("""url\(\s*['"]?([^'")]+)""", RegexOption.IGNORE_CASE).find(style)
    return imageUrl(match?.groupValues?.getOrNull(1), base)
        ?: imageUrl(element.attr("data-bg"), base)
        ?: imageUrl(element.attr("data-background"), base)
        ?: imageUrl(element.attr("data-bg-src"), base)
}
private fun posterFrom(img: org.jsoup.nodes.Element?, base: String): String? {
    if (img == null) return null
    // Reject site branding and tiny tracking images, not legitimate poster thumbnails.
    val dimensions = listOf("width", "height").map { img.attr(it).toIntOrNull() }
    if (dimensions.any { it != null && it in 1..4 }) return null
    val urls = mutableListOf<String>()
    // Lazy-loading libraries may put the real poster in a source/srcset or a data-* field.
    for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-url", "data-echo", "data-thumb", "src")) {
        imageUrl(img.attr(attr), base)?.let(urls::add)
    }
    for (attr in listOf("data-srcset", "srcset")) {
        val candidates = img.attr(attr).split(",").mapNotNull { part ->
            val bits = part.trim().split(Regex("""\s+"""))
            val url = imageUrl(bits.firstOrNull(), base)
            val size = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
            if (url != null) size to url else null
        }
        candidates.maxByOrNull { it.first }?.second?.let { urls.add(0, it) }
    }
    cssPoster(img, base)?.let { urls.add(0, it) }
    return urls.firstOrNull { url ->
        !Regex("""(?:logo|favicon|avatar|icon|spinner|loader|banner|ads?[./_-])""", RegexOption.IGNORE_CASE)
            .containsMatchIn(URI(url).path.orEmpty())
    } ?: urls.firstOrNull()
}
private fun posterFromDetail(doc: org.jsoup.nodes.Document, title: String): String? {
    val base = doc.baseUri()
    for (selector in listOf("meta[property=og:image]", "meta[property=og:image:url]",
        "meta[name=twitter:image]", "meta[name=twitter:image:src]", "link[rel=image_src]")) {
        val e = doc.selectFirst(selector) ?: continue
        imageUrl(e.attr("content").ifBlank { e.attr("href") }, base)?.let { return it }
    }
    val imageNodes = doc.select(".mvic-desc img, .mvi-cover img, .poster img, .thumb img, .detail img, .film-poster img, article img, img")
    val name = title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
    val ranked = imageNodes.mapNotNull { img ->
        val url = posterFrom(img, base) ?: return@mapNotNull null
        val alt = (img.attr("alt") + img.attr("title")).lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
        val score = (if (name.length > 3 && alt.contains(name)) 100 else 0) +
            (if (img.closest(".poster,.thumb,.mvi-cover,.movie-poster,.film-poster") != null) 30 else 0) +
            (if ((img.attr("width").toIntOrNull() ?: 0) >= 120) 10 else 0)
        score to url
    }
    return ranked.maxByOrNull { it.first }?.second
}
private fun posterModel(context: android.content.Context, film: Film): Any? = film.poster?.let {
    ImageRequest.Builder(context).data(it).crossfade(true)
        .addHeader("Referer", BASE + "/")
        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        .build()
}
private val blockedAdHosts = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adnxs.com", "popads.net", "popcash.net", "propellerads.com",
    "onclickads.net", "exoclick.com", "trafficjunky.net",
    "adsterra.com", "ad-maven.com", "hilltopads.net", "clickadu.com",
    "richads.com", "monetag.com", "popmyads.com", "revenuehits.com",
    "adclick.g.doubleclick.net", "popunder.net", "adexchangeclear.com",
    "onclickalgo.com", "pushame.com", "adskeeper.co.uk", "mgid.com",
    "revcontent.com", "taboola.com", "outbrain.com"
)
private fun isAdHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return blockedAdHosts.any { h == it || h.endsWith(".$it") }
}
private fun suppressPageOverlays(view: WebView) {
    // Keep all iframe/video elements: third-party video hosts are needed for playback.
    // The MutationObserver removes newly inserted advertising banners/dialogs as well.
    view.evaluateJavascript("""(function(){
      if(window.__prfGuardInstalled)return;window.__prfGuardInstalled=true;
      const css=document.createElement('style');css.textContent=
        '[id*="popunder" i],[class*="popunder" i],[id*="popup-ad" i],'+
        '[class*="popup-ad" i],[class*="ad-overlay" i],[id*="ad-overlay" i],'+
        '[class*="adsbygoogle" i],[id*="floating-ad" i],[class*="floating-ad" i],'+
        '.adsbygoogle,ins.adsbygoogle,[data-ad-client],[data-ad-slot],'+
        '[class*="adsterra" i],[id*="adsterra" i],[class*="monetag" i],'+
        '[class*="popads" i],[id*="popads" i],'+
        'iframe[src*="doubleclick"],iframe[src*="adsterra"],iframe[src*="monetag"] {display:none!important}';
      (document.head||document.documentElement).appendChild(css);
      function clean(){
        document.querySelectorAll('aside,div,section,dialog').forEach(function(el){
          if(el.closest('video, .jwplayer, .plyr'))return;
          const txt=(el.innerText||'').trim().toLowerCase();
          if(txt.length>230||txt.length<12)return;
          // Only hide recognisable scam/finance ad overlays, not video player UI.
          if((txt.includes('congratulations')&&
              (txt.includes('transfer money')||txt.includes('withdraw money')||txt.includes('$35,000')))||
             (txt.includes('withdraw money')&&txt.includes('transfer money'))||
             (txt.includes('whatsapp')&&txt.includes('hacked')&&txt.includes('learn more'))){
            let box=el;
            for(let i=0;i<3&&box.parentElement;i++){
              const p=box.parentElement;
              if((p.innerText||'').trim().length<350 &&
                 (getComputedStyle(p).position==='fixed'||getComputedStyle(p).position==='absolute')){box=p;break;}
              break;
            }
            box.style.setProperty('display','none','important');
          }
        });
      }
      clean();let queued=false;
      const observer=new MutationObserver(function(){if(queued)return;queued=true;
        requestAnimationFrame(function(){queued=false;clean();});});
      observer.observe(document.documentElement,{childList:true,subtree:true});
      document.addEventListener('click',function(e){
        const a=e.target.closest&&e.target.closest('a[href]');if(!a)return;
        try{const dest=new URL(a.href,location.href);
          const source=location.hostname.replace(/^www\./,'');
          if(dest.hostname!==location.hostname&&dest.hostname!==source&&
             !dest.hostname.endsWith('.'+source)){
             e.preventDefault();e.stopImmediatePropagation();}
        }catch(_){}
      },true);
      document.querySelectorAll('a[target="_blank"]').forEach(a=>a.removeAttribute('target'));
    })();""", null)
}

// A cursor layered over a real WebView. D-pad moves it; OK dispatches a real touch.
internal class TvCursorFrame(context: android.content.Context) : FrameLayout(context) {
    val browser = WebView(context)
    private var cursorX = 0f
    private var cursorY = 0f
    private var enabled = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val dot = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!enabled) return
            canvas.drawCircle(cursorX, cursorY, 11f * resources.displayMetrics.density, paint)
            canvas.drawLine(cursorX - 18f, cursorY, cursorX + 18f, cursorY, paint)
            canvas.drawLine(cursorX, cursorY - 18f, cursorX, cursorY + 18f, paint)
        }
    }
    init {
        addView(browser, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dot.isClickable = false
        dot.isFocusable = false
    }
    fun mouseMode(value: Boolean) {
        enabled = value
        if (cursorX == 0f) cursorX = width / 2f
        if (cursorY == 0f) cursorY = height / 2f
        dot.invalidate()
    }
    fun move(direction: Int): Boolean {
        if (!enabled) return false
        val step = 32f * resources.displayMetrics.density
        when (direction) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
            KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
            else -> return false
        }
        cursorX = cursorX.coerceIn(0f, (width-1).coerceAtLeast(0).toFloat())
        cursorY = cursorY.coerceIn(0f, (height-1).coerceAtLeast(0).toFloat())
        dot.invalidate()
        return true
    }
    fun click(): Boolean {
        if (!enabled) return false
        val now = android.os.SystemClock.uptimeMillis()
        browser.dispatchTouchEvent(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,cursorX,cursorY,0))
        browser.dispatchTouchEvent(MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,cursorX,cursorY,0))
        return true
    }
}

// Series pages list each episode under a separate Season heading. Episode links are
// often just "E01", "E02", etc., and point to the SAME /watch/...html page with
// a different ?key= value. Do not try to read season numbers out of those URLs.
private fun westernDigits(raw: String): String = buildString(raw.length) {
    for (ch in raw) append(when (ch) {
        in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
        in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
        else -> ch
    })
}

private val seasonHeadingPattern = Regex(
    """^(?:season|فصل)\s*[:#\-–.]?\s*(\d{1,2})\s*$""",
    RegexOption.IGNORE_CASE
)
private val episodeLabelPattern = Regex(
    """^(?:(?:playing\s*\.*|now\s+playing)\s*)?(?:episode|ep\.?|e|قسمت)\s*[:#\-.\s]*(\d{1,3})(?:\s*[-–—]\s*(?:end|final|آخر))?\s*$""",
    RegexOption.IGNORE_CASE
)

private fun seasonHeading(raw: String): Int? = seasonHeadingPattern
    .matchEntire(westernDigits(raw).replace(Regex("""\s+"""), " ").trim())
    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..50 }

private fun episodeLabel(raw: String): Int? = episodeLabelPattern
    .matchEntire(westernDigits(raw).replace(Regex("""\s+"""), " ").trim())
    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..300 }

private suspend fun fetchEpisodes(film: Film): List<Episode> = withContext(Dispatchers.IO) {
    if (film.type != "series") return@withContext emptyList()
    try {
        val doc = Jsoup.connect(film.url)
            .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .referrer(HOME).timeout(16000).get()
        val baseUri = URI(film.url)
        val siteHost = URI(BASE).host
        val found = linkedMapOf<String, Episode>()
        var currentSeason: Int? = null

        // Jsoup's getAllElements() visits the DOM in document order. Reading the
        // explicit Season headings as we pass them associates E01...E20 with
        // their actual group, including when the same episode number is reused
        // by two different seasons.
        for (element in doc.getAllElements()) {
            // ownText() avoids merging a complete season's episode links into
            // the parent heading, which could produce spurious numbers.
            val heading = seasonHeading(element.ownText())
                ?: element.attr("data-season").toIntOrNull()?.takeIf {
                    it in 1..50 && element.tagName() != "a"
                }
            if (heading != null) currentSeason = heading

            if (element.tagName() != "a") continue
            // "Playing.. E12", "E09", "E13 - End", "Episode 4" and Persian
            // episode labels all mean the same thing, but "25 EPISODES" does not.
            val episodeNumber = episodeLabel(element.text())
                ?: episodeLabel(element.attr("title"))
                ?: episodeLabel(element.attr("aria-label"))
                ?: continue
            val link = element.absUrl("href")
            val uri = try { URI(link) } catch (_: Exception) { continue }
            if (uri.scheme != "https" || !uri.host.equals(siteHost, true) ||
                !uri.path.orEmpty().startsWith("/watch/") ||
                !uri.path.orEmpty().endsWith(".html")) continue
            // Episode links can differ ONLY by ?key=... . Keep this query: it
            // is what makes the selected episode play instead of the default one.
            if (uri.path == baseUri.path && uri.rawQuery.isNullOrBlank() &&
                uri.rawFragment != null) continue

            // When no explicit season heading exists, keep the episode under a
            // neutral "Episodes" group (season=0), rather than invent Season 1.
            val season = currentSeason ?: 0
            val key = "$season:$episodeNumber"
            found.putIfAbsent(key, Episode(season, episodeNumber,
                if (season == 0) "Episode $episodeNumber" else "Season $season Episode $episodeNumber", link))
        }
        found.values.sortedWith(compareBy<Episode> { it.season }.thenBy { it.number })
    } catch (_: Exception) { emptyList() }
}

// The catalog/detail pages contain advertising and sometimes scam overlays.
// Open ONLY an identified, HTTPS video embed in the player WebView instead.
// Allowlisted provider paths are intentionally narrow; never accept an arbitrary iframe.
private fun isSupportedVideoEmbed(rawUrl: String): Boolean = try {
    val uri = URI(rawUrl)
    if (uri.scheme != "https" || uri.userInfo != null) false
    else {
        val host = uri.host?.lowercase()?.removePrefix("www.").orEmpty()
        val path = uri.path.orEmpty()
        when (host) {
            "ok.ru", "m.ok.ru" -> path.matches(Regex("/videoembed/[0-9]+/?"))
            "vk.com", "vkvideo.ru" -> path == "/video_ext.php"
            "youtube.com", "youtube-nocookie.com" -> path.startsWith("/embed/")
            "player.vimeo.com" -> path.startsWith("/video/")
            "rutube.ru" -> path.startsWith("/play/embed/")
            "www.aparat.com", "aparat.com" -> path.startsWith("/video/video/embed/")
            "dailymotion.com" -> path.startsWith("/embed/video/")
            else -> false
        }
    }
} catch (_: Exception) { false }

private fun autoplayEmbedUrl(rawUrl: String): String = try {
    val uri = URI(rawUrl)
    val query = uri.rawQuery.orEmpty()
    // Pass a standard autoplay hint to the provider; some providers still require a tap.
    if (Regex("(?:^|&)autoplay=", RegexOption.IGNORE_CASE).containsMatchIn(query)) rawUrl
    else URI(uri.scheme, uri.rawAuthority, uri.rawPath,
        (if (query.isEmpty()) "" else "$query&") + "autoplay=1", uri.rawFragment).toASCIIString()
} catch (_: Exception) { rawUrl }

private suspend fun findSafeVideoEmbed(sourcePage: String): String? = withContext(Dispatchers.IO) {
    // Never allow this extraction request to fetch an advertising host or arbitrary URL.
    val origin = URI(BASE)
    val source = try { URI(sourcePage) } catch (_: Exception) { return@withContext null }
    if (source.scheme != "https" || !source.host.equals(origin.host, true) ||
        !source.path.orEmpty().startsWith("/watch/")) return@withContext null
    try {
        val doc = Jsoup.connect(sourcePage)
            .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .referrer(HOME).timeout(15000).get()
        val page = doc.baseUri()
        for (frame in doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url]")) {
            for (attr in listOf("src", "data-src", "data-lazy-src", "data-url")) {
                val raw = frame.attr(attr).trim()
                if (raw.isEmpty() || raw.startsWith("javascript:", true)) continue
                val url = try { URI(page).resolve(raw).toASCIIString() } catch (_: Exception) { continue }
                if (isSupportedVideoEmbed(url)) return@withContext autoplayEmbedUrl(url)
            }
        }
        // If the site temporarily removes its player, report failure rather than opening ads.
        null
    } catch (_: Exception) { null }
}

private fun isAllowedPlayerNavigation(target: Uri, initialPlayerUrl: String): Boolean {
    if (target.toString() == "about:blank") return true
    if (target.scheme != "https" || isAdHost(target.host)) return false
    val main = Uri.parse(initialPlayerUrl)
    val host = target.host?.lowercase()?.removePrefix("www.") ?: return false
    val initial = main.host?.lowercase()?.removePrefix("www.") ?: return false
    // Prevent a popup ad or script from replacing the video with a scam website.
    // Same-provider redirects are permitted, but cross-provider top-level pages are not.
    return host == initial || host.endsWith(".$initial")
}


// Read only public title, poster and page-link metadata, never playback video URLs.
private suspend fun fetchCatalog(): List<Film> = withContext(Dispatchers.IO) {
    val results = linkedMapOf<String, Film>()
    val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    fun cleanTitle(raw: String?): String? {
        val value = raw?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        val forbidden = setOf("play", "watch", "watch now", "read more", "view more",
            "more", "hd", "sd", "4k", "all", "home", "movie", "series", "view more »")
        if (value.isEmpty() || value.length > 150 || value.lowercase() in forbidden ||
            value.startsWith("Watch now:", true) ||
            value.matches(Regex("""[0-9]+\s*episodes?""", RegexOption.IGNORE_CASE))) return null
        return value
    }
    fun watchUrl(link: org.jsoup.nodes.Element): String? {
        val url = link.absUrl("href").substringBefore('#')
        val uri = try { URI(url) } catch (_: Exception) { return null }
        // Prevent menu, carousel controls, ads, and other pages from being imported.
        return url.takeIf { uri.host?.equals(URI(BASE).host, true) == true &&
            (uri.path ?: "").startsWith("/watch/") && (uri.path ?: "").endsWith(".html") }
    }
    fun matchingPoster(anchor: org.jsoup.nodes.Element, url: String, base: String): String? {
        // The title <h3><a> and poster <a><img> are separate sibling links.
        // Search only within their shared card, NEVER a whole category section.
        var node: org.jsoup.nodes.Element? = anchor
        repeat(7) {
            val card = node ?: return@repeat
            val watchLinks = card.select("a[href]").count { watchUrl(it) != null }
            if (watchLinks <= 3) {
                for (link in card.select("a[href]")) {
                    if (watchUrl(link) != url) continue
                    for (img in link.select("img, source")) {
                        posterFrom(img, base)?.let { return it }
                    }
                    posterFrom(link.selectFirst("picture source"), base)?.let { return it }
                    cssPoster(link, base)?.let { return it }
                }
                for (img in card.select("img, source")) {
                    // Cards may contain an image with no wrapping anchor.
                    posterFrom(img, base)?.let { return it }
                }
                cssPoster(card.selectFirst("[style*=background], [data-bg], [data-background]"), base)?.let { return it }
            }
            if (card.tagName() in setOf("body", "html", "main", "section")) return@repeat
            node = card.parent()
        }
        return null
    }
    fun parse(doc: org.jsoup.nodes.Document, fixedCategory: String? = null) {
        // Determine category from section headings while scanning the page in document order.
        var section = fixedCategory
        for (element in doc.getAllElements()) {
            if (fixedCategory == null && element.tagName() in setOf("h1", "h2", "h3")) {
                when (element.ownText().ifBlank { element.text() }.trim().lowercase()) {
                    "latest movies", "movie suggestion", "movies" -> section = "movie"
                    "latest tv series", "latest series", "tv series" -> section = "series"
                    "all tv channels", "about" -> section = null
                }
            }
            // Only accept heading links to /watch/*.html; never use "Play" CTA links.
            if (element.tagName() != "a" || element.closest("h2, h3, h4") == null) continue
            val url = watchUrl(element) ?: continue
            val title = cleanTitle(element.text())
                ?: cleanTitle(element.attr("title"))
                ?: cleanTitle(element.closest("h2, h3, h4")?.text())
                ?: continue
            val category = section ?: fixedCategory ?: continue
            val poster = matchingPoster(element, url, doc.baseUri())
            val old = results[url]
            // Never replace a known title or poster with a generic action or null.
            results[url] = Film(title, url, poster ?: old?.poster,
                if (category == "series" || old?.type == "series") "series" else "movie")
        }
    }
    val pages = listOf(HOME to null, "$BASE/movies.html" to "movie", "$BASE/tv-series.html" to "series")
    for ((page, category) in pages) {
        try {
            val doc = Jsoup.connect(page).userAgent(ua).referrer(HOME)
                .timeout(16000).get()
            parse(doc, category)
        } catch (_: Exception) { /* Other catalog pages may still load. */ }
    }
    // Individual detail pages contain poster metadata even when a list card does not.
    // Limit concurrent connections to avoid overwhelming the site or TV device.
    kotlinx.coroutines.coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        results.values.map { film ->
            async(Dispatchers.IO) {
                if (film.poster != null) film else gate.withPermit {
                    try {
                        val detail = Jsoup.connect(film.url).userAgent(ua)
                            .referrer(HOME).timeout(9000).get()
                        val poster = posterFromDetail(detail, film.title)
                        film.copy(poster = poster)
                    } catch (_: Exception) { film }
                }
            }
        }.map { it.await() }
    }
}

class MainActivity : ComponentActivity() {
    private var tvCursor: TvCursorFrame? = null
    private var mouseEnabled = false
    internal fun attachCursor(frame: TvCursorFrame?) {
        tvCursor = frame
        tvCursor?.mouseMode(mouseEnabled)
    }
    internal fun toggleCursor() {
        mouseEnabled = !mouseEnabled
        tvCursor?.mouseMode(mouseEnabled)
    }
    internal fun mouseModeEnabled() = mouseEnabled
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tvCursor != null) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                toggleCursor(); return true
            }
            // Long-press Play/Pause toggles cursor; regular Play/Pause is left to the player.
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.isLongPress) {
                toggleCursor(); return true
            }
            if (mouseEnabled) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    toggleCursor(); return true
                }
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if (event.action == KeyEvent.ACTION_DOWN) tvCursor?.move(event.keyCode)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_UP) tvCursor?.click()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
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
    BackHandler(selected != null) {
        (context as? MainActivity)?.attachCursor(null)
        selected = null; resumeUrl = null
    }
    var searchOpen by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var chosenSeason by remember { mutableIntStateOf(1) }
    var chosenEpisode by remember { mutableStateOf<Episode?>(null) }
    var episodeLoading by remember { mutableStateOf(false) }
    var playRequested by remember { mutableStateOf(false) }
    var playbackAttempt by remember { mutableIntStateOf(0) }
    var safePlayerUrl by remember { mutableStateOf<String?>(null) }
    var playerLoading by remember { mutableStateOf(false) }
    var playerFailed by remember { mutableStateOf(false) }
    LaunchedEffect(selected?.url, resumeUrl, playRequested, playbackAttempt) {
        safePlayerUrl = null
        playerFailed = false
        playerLoading = playRequested && selected != null
        if (playRequested && selected != null) {
            val requestedPage = resumeUrl ?: selected!!.url
            safePlayerUrl = findSafeVideoEmbed(requestedPage)
            playerFailed = safePlayerUrl == null
        }
        playerLoading = false
    }
    LaunchedEffect(selected?.url) {
        val film = selected
        episodes = emptyList(); chosenEpisode = null; chosenSeason = 1; playRequested = false
        if (film?.type == "series") {
            episodeLoading = true
            episodes = fetchEpisodes(film)
            chosenSeason = episodes.firstOrNull()?.season ?: 1
            episodeLoading = false
        }
    }
    var catalog by remember { mutableStateOf<List<Film>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(readHistory(prefs)) }
    fun openFilm(film: Film, resume: Boolean = false) {
        resumeUrl = if (resume) history[film.url]?.lastPage else null
        playRequested = false
        (context as? MainActivity)?.attachCursor(null)
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
                Text("PersianRubinFilm v0.10.2", color = red, fontWeight = FontWeight.Bold, fontSize = if (isTv) 29.sp else 22.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected == null) {
                        if (searchOpen) {
                            OutlinedTextField(query, { query = it },
                                modifier = Modifier.width(if (isTv) 280.dp else 145.dp).tvFocus(),
                                placeholder = { Text(label("Search", "جستجو")) }, singleLine = true)
                        }
                        TextButton(modifier = Modifier.tvFocus(), onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        }) { Text(if (searchOpen) "✕" else "⌕ " + label("Search", "جستجو")) }
                    }
                    TextButton(modifier = Modifier.tvFocus(), onClick = {
                        persian = !persian; prefs.edit().putBoolean("persian", persian).apply()
                    }) { Text(if (persian) "English" else "فارسی") }
                }
            }
            if (selected != null) {
                val film = selected!!
                TextButton(modifier = Modifier.tvFocus(), onClick = { selected = null; resumeUrl = null }) { Text(label("← Back", "بازگشت ←")) }
                Text(film.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 22.sp)
                Text(label("Play opens the video provider directly, without loading the advertising-filled website. The video host may still require one tap to start.",
                    "پخش مستقیماً از پخش‌کنندهٔ ویدئو باز می‌شود. ممکن است یک بار لمس برای شروع لازم باشد."),
                    Modifier.padding(16.dp), color = Color.LightGray)
                if (film.type == "series") {
                    if (episodeLoading) CircularProgressIndicator(Modifier.padding(12.dp))
                    else if (episodes.isEmpty()) {
                        Text(label("No selectable episode links were found on the source page for this series.",
                            "پیوند قابل‌استفاده برای قسمت‌های این سریال پیدا نشد."),
                            Modifier.padding(horizontal = 16.dp), color = Color.LightGray)
                    } else {
                        Text(label("Choose season", "انتخاب فصل"), Modifier.padding(horizontal = 16.dp), color = Color.White)
                        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            items(episodes.map { it.season }.distinct()) { season ->
                                TextButton(modifier = Modifier.tvFocus(), onClick = { chosenSeason = season; chosenEpisode = null }) {
                                    Text(if (season == 0) label("Episodes", "قسمت‌ها") else label("Season $season", "فصل $season"),
                                        color = if (chosenSeason == season) red else Color.White)
                                }
                            }
                        }
                        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            items(episodes.filter { it.season == chosenSeason }) { episode ->
                                TextButton(modifier = Modifier.tvFocus(), onClick = { chosenEpisode = episode }) {
                                    Text(label("Episode ${episode.number}", "قسمت ${episode.number}"),
                                        color = if (chosenEpisode?.url == episode.url) red else Color.White)
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(modifier = Modifier.padding(start = 16.dp).tvFocus(),
                        enabled = film.type != "series" || episodes.isEmpty() || chosenEpisode != null,
                        onClick = {
                            val episodePage = chosenEpisode?.url ?: film.url
                            resumeUrl = episodePage
                            val previous = history[film.url]
                            if (previous != null) {
                                history = history + (film.url to previous.copy(lastPage = episodePage,
                                    visitedAt = System.currentTimeMillis()))
                                writeHistory(prefs, history)
                            }
                            playRequested = true
                            playbackAttempt++
                        }) {
                        Text(label("▶ Play", "▶ پخش"))
                    }
                    TextButton(modifier = Modifier.tvFocus(), onClick = {
                        (context as? MainActivity)?.toggleCursor()
                    }) { Text(label("Mouse: toggle (MENU)", "ماوس: تغییر حالت")) }
                }
                val lastPage = history[film.url]?.lastPage
                if (lastPage != null && lastPage != film.url) {
                    TextButton(onClick = { resumeUrl = lastPage; playRequested = true; playbackAttempt++ }) {
                        Text(label("Open last visited episode/page", "باز کردن آخرین قسمت / صفحه"))
                    }
                }
                TextButton(onClick = {
                    val old = history[film.url]
                    if (old != null) { history = history + (film.url to old.copy(completed = !old.completed)); writeHistory(prefs, history) }
                }) { Text(if (history[film.url]?.completed == true) label("✓ Watched (tap to undo)", "✓ دیده‌شده")
                    else label("Mark watched", "علامت‌گذاری به عنوان دیده‌شده")) }
                if (!playRequested) {
                    Text(label("Press Play to load the video, not the source website.",
                        "برای بارگیری ویدئو، پخش را بزنید."),
                        Modifier.padding(16.dp), color = Color.LightGray)
                } else if (playerLoading) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(label("  Finding the video player…", "  در حال پیدا کردن پخش‌کننده…"),
                            color = Color.White)
                    }
                } else if (playerFailed) {
                    Column(Modifier.padding(16.dp)) {
                        Text(label("No supported video player was found for this title/episode. The unsafe source page was not opened.",
                            "پخش‌کنندهٔ قابل‌استفاده پیدا نشد. صفحهٔ ناامن باز نشد."),
                            color = Color.LightGray)
                        TextButton(modifier = Modifier.tvFocus(), onClick = { playbackAttempt++ }) {
                            Text(label("Retry video", "تلاش دوباره"))
                        }
                    }
                } else if (safePlayerUrl != null) key(film.url, safePlayerUrl, playbackAttempt) {
                    val embedUrl = safePlayerUrl!!
                    AndroidView(factory = { ctx ->
                        TvCursorFrame(ctx).apply {
                          (context as? MainActivity)?.attachCursor(this)
                          browser.apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            // Allows Play to start the embedded video without a second Android WebView gesture.
                            settings.mediaPlaybackRequiresUserGesture = false
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
                                    if (!request.isForMainFrame) return false // CDN/subframes needed for video.
                                    return !isAllowedPlayerNavigation(requestUri, embedUrl)
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    // The host player is loaded directly; do not inject the source-site DOM/ad scripts.
                                    view?.evaluateJavascript("""(function(){
                                        var v=document.querySelector('video');
                                        if(v){v.autoplay=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}
                                    })();""", null)
                                }
                            }
                            loadUrl(embedUrl, mapOf("Referer" to (resumeUrl ?: film.url)))
                          }
                        }
                    }, modifier = Modifier.fillMaxWidth().weight(1f),
                    update = { (context as? MainActivity)?.attachCursor(it) })
                }
            } else {
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
                                    Button(modifier = Modifier.tvFocus(), onClick = { openFilm(film) }) {
                                        Text(label(if (film.type == "series") "Episodes" else "▶ Play", if (film.type == "series") "قسمت‌ها" else "▶ پخش"))
                                    }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(modifier = Modifier.tvFocus(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                onClick = onOpen) { Text(if (film.type == "series") "Episodes" else "▶ Play", fontSize = 12.sp) }
            TextButton(modifier = Modifier.tvFocus(), onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
        }
    }
}
@Composable private fun FilmRow(film: Film, saved: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().tvFocus().clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(film, Modifier.size(width = 90.dp, height = 125.dp))
        Text(film.title, Modifier.weight(1f).padding(12.dp), color = Color.White)
        Column {
            Button(modifier = Modifier.tvFocus(), onClick = onOpen) {
                Text(if (film.type == "series") "Episodes" else "▶ Play", fontSize = 12.sp)
            }
            TextButton(modifier = Modifier.tvFocus(), onClick = onFavorite) { Text(if (saved) "♥" else "+", color = red) }
        }
    }
}
