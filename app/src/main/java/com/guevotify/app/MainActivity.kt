package com.guevotify.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

val Green = Color(0xFF22E06A)
val PanelBg = Color(0xFF181818)

data class Item(val name: String, val sub: String, val uri: String, val type: String, val img: String?)
class ApiError(msg: String, val code: Int = 0) : Exception(msg)
data class Now(val name: String, val artists: String, val img: String?, val dur: Long, val pos: Long, val playing: Boolean)

object Spotify {
    private val http = OkHttpClient()
    private var prefs: SharedPreferences? = null
    var token by mutableStateOf<String?>(null)

    fun init(c: Context) {
        prefs = c.getSharedPreferences("gv", Context.MODE_PRIVATE)
        val t = prefs!!.getString("t", null)
        if (t != null && System.currentTimeMillis() < prefs!!.getLong("e", 0)) token = t
    }

    fun logout() { prefs?.edit()?.clear()?.apply(); token = null }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun loginIntent(): Intent {
        val v = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        prefs?.edit()?.putString("v", v)?.apply()
        val ch = b64(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", "user-modify-playback-state user-read-playback-state playlist-read-private user-top-read")
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", ch).build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    suspend fun exchange(code: String) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", REDIRECT).add("client_id", CLIENT_ID)
            .add("code_verifier", prefs?.getString("v", "") ?: "").build()
        http.newCall(Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build())
            .execute().use {
                val j = JSONObject(it.body!!.string())
                val t = j.optString("access_token")
                if (t.isNotEmpty()) {
                    prefs?.edit()?.putString("t", t)
                        ?.putLong("e", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60000)?.apply()
                    token = t
                }
            }
    }

    private fun req(path: String, method: String, body: String? = null): Pair<Int, String> {
        val rb = when {
            body != null -> body.toRequestBody("application/json".toMediaType())
            method == "PUT" || method == "POST" -> "".toRequestBody()
            else -> null
        }
        val r = Request.Builder().url("https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer $token").method(method, rb).build()
        http.newCall(r).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    private fun explain(c: Int, b: String = ""): String {
        if (c == 401) logout()
        val e = runCatching { JSONObject(b).optJSONObject("error") }.getOrNull()
        val msg = e?.optString("message").orEmpty()
        val reason = e?.optString("reason").orEmpty()
        return when {
            c == 401 -> "Tu sesión expiró. Conecta Spotify otra vez."
            c == 404 -> "No hay dispositivo activo de Spotify."
            reason == "PREMIUM_REQUIRED" -> "Necesitas Spotify Premium para reproducir."
            c == 403 -> "Spotify rechazó esto: " + msg.ifEmpty { "sin detalle" }
            c == 429 -> "Demasiadas peticiones. Espera un minuto."
            else -> "Error de Spotify ($c) $msg"
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val (c, b) = req(path, "GET")
        if (c !in 200..299) throw ApiError(explain(c, b), c)
        JSONObject(b)
    }

    private fun JSONObject.img(): String? = optJSONArray("images")?.optJSONObject(0)?.optString("url")

    private fun parse(a: JSONArray?, type: String): List<Item> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
            val sub = when (type) {
                "artist" -> "Artista"
                "playlist" -> "Playlist"
                "album" -> "Álbum · $artist"
                else -> artist
            }
            Item(o.optString("name"), sub, o.optString("uri"), type, o.img() ?: o.optJSONObject("album")?.img())
        }
    }

    suspend fun search(q: String): Map<String, List<Item>> {
        val r = getJson("/search?type=track,artist,album,playlist&limit=10&q=" + Uri.encode(q))
        return mapOf(
            "Artistas" to parse(r.optJSONObject("artists")?.optJSONArray("items"), "artist"),
            "Canciones" to parse(r.optJSONObject("tracks")?.optJSONArray("items"), "track"),
            "Álbumes" to parse(r.optJSONObject("albums")?.optJSONArray("items"), "album"),
            "Playlists" to parse(r.optJSONObject("playlists")?.optJSONArray("items"), "playlist")
        )
    }

    suspend fun topArtists() = parse(getJson("/me/top/artists?limit=10").optJSONArray("items"), "artist")
    suspend fun topTracks() = parse(getJson("/me/top/tracks?limit=10").optJSONArray("items"), "track")
    suspend fun playlists() = parse(getJson("/me/playlists?limit=50").optJSONArray("items"), "playlist")
    suspend fun me() = getJson("/me")

    private fun playBody(i: Item) =
        if (i.type == "track") "{\"uris\":[\"${i.uri}\"]}" else "{\"context_uri\":\"${i.uri}\"}"

    suspend fun play(i: Item) = withContext(Dispatchers.IO) {
        var (c, b) = req("/me/player/play", "PUT", playBody(i))
        if (c == 404) {
            // No active device: try to wake any device that Spotify knows about (e.g. this phone).
            val d = req("/me/player/devices", "GET")
            val arr = if (d.first == 200) JSONObject(d.second).optJSONArray("devices") else null
            var id: String? = null
            if (arr != null) for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                if (id == null || o.optString("type") == "Smartphone") id = o.optString("id")
            }
            if (id != null) {
                val r = req("/me/player/play?device_id=$id", "PUT", playBody(i))
                c = r.first; b = r.second
            }
        }
        if (c !in 200..299) throw ApiError(explain(c, b), c)
    }

    suspend fun state(): Now? = withContext(Dispatchers.IO) {
        val (c, b) = req("/me/player", "GET")
        if (c != 200 || b.isBlank()) return@withContext null
        val j = JSONObject(b)
        val t = j.optJSONObject("item") ?: return@withContext null
        val names = t.optJSONArray("artists")?.let { a ->
            (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") }
        } ?: ""
        Now(t.optString("name"), names, t.optJSONObject("album")?.img(),
            t.optLong("duration_ms"), j.optLong("progress_ms"), j.optBoolean("is_playing"))
    }

    suspend fun toggle(play: Boolean) = withContext(Dispatchers.IO) {
        req(if (play) "/me/player/play" else "/me/player/pause", "PUT")
    }

    suspend fun skip(next: Boolean) = withContext(Dispatchers.IO) {
        req(if (next) "/me/player/next" else "/me/player/previous", "POST")
    }

    suspend fun seek(ms: Long) = withContext(Dispatchers.IO) { req("/me/player/seek?position_ms=$ms", "PUT") }
}

// Android's built-in audio effects. Limited on purpose (max about +6 dB) to protect hearing and speakers.
object Fx {
    private var loud: LoudnessEnhancer? = null
    private var bass: BassBoost? = null
    var ok by mutableStateOf(true)

    fun apply(volume: Int, bassLevel: Int) {
        ok = try {
            if (loud == null) loud = LoudnessEnhancer(0)
            loud!!.setTargetGain(volume * 6)
            loud!!.setEnabled(true)
            if (bass == null) bass = BassBoost(0, 0)
            bass!!.setStrength((bassLevel * 10).toShort())
            bass!!.setEnabled(true)
            true
        } catch (e: Throwable) { false }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Spotify.init(this)
        handle(intent)
        setContent { App { startActivity(Spotify.loginIntent()) } }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun handle(i: Intent?) {
        val code = i?.data?.takeIf { it.scheme == "guevotify" }?.getQueryParameter("code") ?: return
        lifecycleScope.launch { runCatching { Spotify.exchange(code) } }
    }
}

@Composable
fun App(onLogin: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Green, onPrimary = Color.Black,
        background = Color.Black, surface = Color.Black, onSurface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            if (Spotify.token == null) Login(onLogin) else Main()
        }
    }
}

@Composable
fun Login(onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Green), Alignment.Center) {
            Text("G", color = Color.Black, fontSize = 52.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("Guevotify", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Tu música de Spotify, sin anuncios de nuestra parte.", color = Color.Gray)
        Spacer(Modifier.height(28.dp))
        GreenButton("Conectar Spotify", onLogin)
    }
}

@Composable
fun GreenButton(label: String, onClick: () -> Unit) = Button(
    onClick, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
    shape = CircleShape
) { Text(label, fontWeight = FontWeight.SemiBold) }

fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { h < 12 -> "Buenos días"; h < 19 -> "Buenas tardes"; else -> "Buenas noches" }
}

@Composable
fun Main() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var np by remember { mutableStateOf<Now?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf(false) }
    suspend fun refresh() {
        val n = runCatching { Spotify.state() }.getOrNull() ?: return
        np = n
        if (!drag) pos = n.pos
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(3000) } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val n = np
            if (n != null && n.playing && !drag) pos = minOf(pos + 1000, n.dur)
        }
    }
    val play: (Item) -> Unit = { i ->
        scope.launch {
            try { Spotify.play(i); delay(1200); refresh() }
            catch (e: ApiError) {
                if (e.code == 404) {
                    val l = ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")
                    if (l != null) {
                        Toast.makeText(ctx, "Abriendo Spotify… vuelve a Guevotify y toca la canción otra vez.", Toast.LENGTH_LONG).show()
                        ctx.startActivity(l)
                    } else Toast.makeText(ctx, "Instala la app de Spotify para reproducir.", Toast.LENGTH_LONG).show()
                } else Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) { Toast.makeText(ctx, "Sin conexión", Toast.LENGTH_SHORT).show() }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F3D24), Color.Black), endY = 1100f))) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = if (np != null) 220.dp else 100.dp)) {
            when (tab) {
                0 -> HomeTab(play)
                1 -> SearchTab(play)
                2 -> PlaylistsTab(play)
                3 -> ProfileTab()
                else -> EqTab()
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
            np?.let { n ->
                PlayerCard(n, pos,
                    onSeek = { pos = it; drag = true },
                    onSeekDone = { scope.launch { Spotify.seek(pos); delay(500); drag = false } },
                    onPrev = { scope.launch { Spotify.skip(false); delay(800); refresh() } },
                    onToggle = { scope.launch { Spotify.toggle(!n.playing); np = n.copy(playing = !n.playing) } },
                    onNext = { scope.launch { Spotify.skip(true); delay(800); refresh() } })
                Spacer(Modifier.height(8.dp))
            }
            NavBar(tab) { tab = it }
        }
    }
}

@Composable
fun RoundBtn(label: String, main: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(if (main) Green else Color(0xFF2A2A2A)).clickable(onClick = onClick), Alignment.Center) {
        Text(label, color = if (main) Color.Black else Color.White, fontSize = 14.sp)
    }
}

@Composable
fun PlayerCard(n: Now, pos: Long, onSeek: (Long) -> Unit, onSeekDone: () -> Unit,
               onPrev: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val max = maxOf(n.dur, 1L).toFloat()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF161616)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(n.img, 52.dp, false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.artists, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundBtn("⏮", false, onPrev)
            Spacer(Modifier.width(6.dp))
            RoundBtn(if (n.playing) "❚❚" else "▶", true, onToggle)
            Spacer(Modifier.width(6.dp))
            RoundBtn("⏭", false, onNext)
        }
        Slider(value = pos.toFloat().coerceIn(0f, max), onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..max, onValueChangeFinished = onSeekDone,
            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Color(0xFF333333)))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(fmt(pos), color = Color.Gray, fontSize = 11.sp)
            Text(fmt(n.dur), color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf("Inicio" to Icons.Default.Home, "Buscar" to Icons.Default.Search,
        "Playlists" to Icons.Default.Menu, "Perfil" to Icons.Default.Person, "EQ" to Icons.Default.Settings)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF111111)).padding(8.dp), Arrangement.SpaceAround) {
        tabs.forEachIndexed { i, (label, icon) ->
            val sel = tab == i
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Green else Color.Transparent)
                .clickable { onTab(i) }.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, label, tint = if (sel) Color.Black else Color.Gray)
                Text(label, fontSize = 10.sp, color = if (sel) Color.Black else Color.Gray)
            }
        }
    }
}

@Composable
fun Cover(url: String?, size: Dp, round: Boolean) {
    Box(Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(14.dp)).background(Color(0xFF2A2A2A))) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ItemRow(i: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(i.img, 56.dp, i.type == "artist")
        Spacer(Modifier.width(12.dp))
        Column {
            Text(i.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(i.sub, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun Shelf(title: String, list: List<Item>, play: (Item) -> Unit) {
    if (list.isEmpty()) return
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list) { i ->
            Column(Modifier.width(128.dp).clickable { play(i) }) {
                Cover(i.img, 128.dp, i.type == "artist")
                Text(i.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(i.sub, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Title(t: String) = Text(t, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

@Composable
fun HomeTab(play: (Item) -> Unit) {
    var artists by remember { mutableStateOf(listOf<Item>()) }
    var tracks by remember { mutableStateOf(listOf<Item>()) }
    var pls by remember { mutableStateOf(listOf<Item>()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { artists = Spotify.topArtists() } catch (e: Exception) { err = e.message }
        try { tracks = Spotify.topTracks() } catch (e: Exception) { err = e.message }
        try { pls = Spotify.playlists() } catch (e: Exception) { err = e.message }
        loading = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Title(greeting())
        if (loading) CircularProgressIndicator(color = Green, modifier = Modifier.padding(top = 24.dp))
        if (pls.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            pls.take(6).chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { p ->
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E1E1E)).clickable { play(p) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(p.img, 52.dp, false)
                            Text(p.name, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Shelf("Tus artistas favoritos", artists, play)
        Shelf("Tus canciones favoritas", tracks, play)
        Shelf("Tus playlists", pls, play)
        if (!loading && artists.isEmpty() && tracks.isEmpty() && pls.isEmpty()) {
            Text("Aquí aparecerán tus favoritos cuando escuches más música en Spotify. Mientras tanto, usa Buscar.",
                color = Color.Gray, modifier = Modifier.padding(top = 24.dp))
            err?.let { Text(it, color = Green, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
fun SearchTab(play: (Item) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<Map<String, List<Item>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val go: () -> Unit = {
        if (q.isNotBlank()) scope.launch {
            busy = true; err = null
            try { res = Spotify.search(q.trim()) } catch (e: Exception) { err = e.message }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Title("Buscar")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, shape = CircleShape,
                placeholder = { Text("Canciones, artistas, álbumes") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Color(0xFF444444),
                    cursorColor = Green, focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            Spacer(Modifier.width(8.dp))
            GreenButton("Buscar", go)
        }
        if (busy) CircularProgressIndicator(color = Green, modifier = Modifier.padding(top = 24.dp))
        err?.let { Text(it, color = Green, modifier = Modifier.padding(top = 16.dp)) }
        res?.let { r ->
            Shelf("Artistas", r["Artistas"].orEmpty(), play)
            val songs = r["Canciones"].orEmpty()
            if (songs.isNotEmpty()) {
                Text("Canciones", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                songs.forEach { s -> ItemRow(s) { play(s) } }
            }
            Shelf("Álbumes", r["Álbumes"].orEmpty(), play)
            Shelf("Playlists", r["Playlists"].orEmpty(), play)
            if (r.values.all { it.isEmpty() }) Text("Sin resultados.", color = Color.Gray, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
fun PlaylistsTab(play: (Item) -> Unit) {
    var list by remember { mutableStateOf(listOf<Item>()) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { list = Spotify.playlists() } catch (e: Exception) { err = e.message } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Title("Tus playlists"); Spacer(Modifier.height(8.dp)) }
        err?.let { m -> item { Text(m, color = Green) } }
        items(list) { p -> ItemRow(p) { play(p) } }
    }
}

@Composable
fun ProfileTab() {
    var name by remember { mutableStateOf("") }
    var img by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val m = Spotify.me()
            name = m.optString("display_name").ifEmpty { m.optString("id") }
            img = m.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        } catch (e: Exception) { }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Cover(img, 120.dp, true)
        Spacer(Modifier.height(16.dp))
        Text(name.ifEmpty { "Tu perfil" }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Cuenta de Spotify conectada", color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        GreenButton("Cerrar sesión") { Spotify.logout() }
    }
}

@Composable
fun EqTab() {
    var vol by remember { mutableFloatStateOf(10f) }
    var bass by remember { mutableFloatStateOf(10f) }
    LaunchedEffect(vol, bass) { Fx.apply(vol.toInt(), bass.toInt()) }
    val colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Title("Ecualizador")
        Spacer(Modifier.height(24.dp))
        Text("Volumen extra: ${vol.toInt()}", color = Color.White)
        Slider(vol, { vol = it }, valueRange = 0f..100f, colors = colors)
        Spacer(Modifier.height(12.dp))
        Text("Bajos: ${bass.toInt()}", color = Color.White)
        Slider(bass, { bass = it }, valueRange = 0f..100f, colors = colors)
        Spacer(Modifier.height(16.dp))
        Text("Para proteger tus oídos y la bocina, el efecto máximo (100) sube el volumen solo unas 2 veces. " +
            "Funciona según tu teléfono y la app de música.", color = Color.Gray, fontSize = 13.sp)
        if (!Fx.ok) Text("Tu teléfono no permite aplicar este efecto.", color = Green, modifier = Modifier.padding(top = 12.dp))
    }
}
