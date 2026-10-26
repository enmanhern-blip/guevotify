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
class ApiError(msg: String) : Exception(msg)

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
            method == "PUT" -> "".toRequestBody()
            else -> null
        }
        val r = Request.Builder().url("https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer $token").method(method, rb).build()
        http.newCall(r).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    private fun explain(c: Int): String {
        if (c == 401) logout()
        return when (c) {
            401 -> "Tu sesión expiró. Conecta Spotify otra vez."
            403 -> "Spotify no permite esto con tu cuenta (se necesita Premium)."
            404 -> "No hay dispositivo activo. Abre Spotify, pon una canción y vuelve."
            429 -> "Demasiadas peticiones. Espera un minuto."
            else -> "Error de Spotify ($c)."
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val (c, b) = req(path, "GET")
        if (c !in 200..299) throw ApiError(explain(c))
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

    suspend fun play(i: Item) = withContext(Dispatchers.IO) {
        val body = if (i.type == "track") "{\"uris\":[\"${i.uri}\"]}" else "{\"context_uri\":\"${i.uri}\"}"
        val (c, _) = req("/me/player/play", "PUT", body)
        if (c !in 200..299) throw ApiError(explain(c))
    }

    suspend fun toggle(play: Boolean) = withContext(Dispatchers.IO) {
        req(if (play) "/me/player/play" else "/me/player/pause", "PUT")
    }
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

@Composable
fun Main() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf<Item?>(null) }
    var playing by remember { mutableStateOf(false) }
    val play: (Item) -> Unit = { i ->
        scope.launch {
            try { Spotify.play(i); now = i; playing = true }
            catch (e: ApiError) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
            catch (e: Exception) { Toast.makeText(ctx, "Sin conexión", Toast.LENGTH_SHORT).show() }
        }
    }
    Scaffold(containerColor = Color.Black, bottomBar = {
        Column {
            now?.let { n ->
                Row(Modifier.fillMaxWidth().background(PanelBg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(n.img, 44.dp, false)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.sub, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                    }
                    GreenButton(if (playing) "❚❚" else "▶") {
                        scope.launch { runCatching { Spotify.toggle(!playing) }; playing = !playing }
                    }
                }
            }
            NavBar(tab) { tab = it }
        }
    }) { pad ->
        Box(Modifier.padding(pad).statusBarsPadding()) {
            when (tab) {
                0 -> HomeTab(play)
                1 -> SearchTab(play)
                2 -> PlaylistsTab(play)
                3 -> ProfileTab()
                else -> EqTab()
            }
        }
    }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf("Inicio" to Icons.Default.Home, "Buscar" to Icons.Default.Search,
        "Playlists" to Icons.Default.Menu, "Perfil" to Icons.Default.Person, "EQ" to Icons.Default.Settings)
    NavigationBar(containerColor = Color(0xFF0A0A0A)) {
        tabs.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = tab == i, onClick = { onTab(i) },
                icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Black, selectedTextColor = Green,
                    indicatorColor = Green, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray))
        }
    }
}

@Composable
fun Cover(url: String?, size: Dp, round: Boolean) {
    Box(Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))) {
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
        Title("Inicio")
        if (loading) CircularProgressIndicator(color = Green, modifier = Modifier.padding(top = 24.dp))
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
