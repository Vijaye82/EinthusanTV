package tv.einthusan.app

import android.content.Intent
import android.os.Bundle
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

private val Background = Color(0xFF090711)
private val SurfaceDark = Color(0xFF171323)
private val SurfaceRaised = Color(0xFF241B35)
private val Purple = Color(0xFF9226D6)
private val Magenta = Color(0xFFE00091)
private val TextMuted = Color(0xFFBDB5C9)

internal data class CatalogMovie(
    val title: String, val language: String, val year: String, val genre: String,
    val leadActor: String, val director: String, val description: String,
    val id: String = title, val posterUrl: String = "", val backdropUrl: String = ""
)

// Live catalogue data is loaded by EinthusanCatalogRepository.

class MainActivity : ComponentActivity() {
    private val crashFile by lazy { File(filesDir, "startup-crash.txt") }

    override fun onResume() {
        super.onResume()
        if (!crashFile.exists()) resumePendingApkInstall(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val details = StringWriter().also { writer ->
                    error.printStackTrace(PrintWriter(writer))
                }.toString()
                crashFile.writeText("EinthusanTV startup diagnostic\\nThread: ${thread.name}\\n\\n${details.take(24000)}")
            }
            previousHandler?.uncaughtException(thread, error)
        }

        if (crashFile.exists()) {
            val report = runCatching { crashFile.readText().take(24000) }.getOrDefault("A previous startup error was recorded, but the report could not be read.")
            setContent { StartupDiagnosticScreen(report, onClear = { crashFile.delete(); recreate() }) }
        } else {
            setContent { EinthusanTvApp() }
        }
    }
}

@Composable
private fun StartupDiagnosticScreen(report: String, onClear: () -> Unit) {
    val context = LocalContext.current
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(
                Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("EinthusanTV startup report", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("The previous launch stopped unexpectedly. This report was saved on the Fire Stick.", color = TextMuted, fontSize = 14.sp)
                Text(report, color = Color.White, fontSize = 12.sp, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.filesDir, "startup-crash.txt"))
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_TEXT, report)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(share, "Share startup report"))
                        }
                    }) { Text("Share report") }
                    OutlinedButton(onClick = onClear) { Text("Clear report and retry") }
                }
                Text("If sharing is unavailable on your Fire Stick, take a photo of this screen and send it here.", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EinthusanTvApp() {
    val context = LocalContext.current
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2400)
        showSplash = false
    }
    val languages = listOf("All", "Tamil", "Telugu", "Hindi", "Malayalam", "Kannada", "Bengali", "Punjabi", "Marathi")
    val genres = listOf("All", "Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure")
    var page by remember { mutableStateOf("HOME") }
    var manualUpdateCheck by remember { mutableIntStateOf(0) }
    var updateCheckStatus by remember { mutableStateOf("Updates are checked automatically when EinthusanTV starts.") }
    var language by remember { mutableStateOf("All") }
    var genre by remember { mutableStateOf("All") }
    var year by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf("") }
    var director by remember { mutableStateOf("") }
    var alphabetical by remember { mutableStateOf(false) }
    var selectedMovie by remember { mutableStateOf<CatalogMovie?>(null) }
    var catalogueMovies by remember { mutableStateOf<List<CatalogMovie>>(emptyList()) }
    var catalogueLoading by remember { mutableStateOf(true) }
    var catalogueError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedMovie?.id) {
        val current = selectedMovie ?: return@LaunchedEffect
        try {
            val enriched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EinthusanCatalogRepository().details(current)
            }
            if (selectedMovie?.id == current.id) selectedMovie = enriched
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the browse-page details if Einthusan's watch page is temporarily unavailable.
        }
    }

    LaunchedEffect(language) {
        catalogueLoading = true
        catalogueError = null
        try {
            val requestedLanguage = if (language == "All") "tamil" else language.lowercase()
            catalogueMovies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EinthusanCatalogRepository().featured(requestedLanguage)
            }
            if (catalogueMovies.isEmpty()) catalogueError = "Einthusan returned no movie cards. The page layout may have changed or temporarily blocked this request."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Language changes or leaving this screen cancel the old request; don't show that as an error.
            throw cancelled
        } catch (error: Exception) {
            catalogueError = error.message?.take(220) ?: "Could not connect to Einthusan. Check your internet connection and try again."
        } finally {
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                catalogueLoading = false
            }
        }
    }
    var playerOpen by remember { mutableStateOf(false) }
    var favourites by remember {
        mutableStateOf(
            context.getSharedPreferences("user_preferences", android.content.Context.MODE_PRIVATE)
                .getStringSet("favourites", emptySet())?.toSet() ?: emptySet()
        )
    }

    LaunchedEffect(favourites) {
        context.getSharedPreferences("user_preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet("favourites", favourites.toSet())
            .apply()
    }

    BackHandler(enabled = page != "HOME" || selectedMovie != null || playerOpen) {
        when {
            playerOpen -> playerOpen = false
            selectedMovie != null -> selectedMovie = null
            page != "HOME" -> page = "HOME"
        }
    }

    val filtered = catalogueMovies.filter { movie ->
        (language == "All" || movie.language == language) &&
            (genre == "All" || movie.genre.equals(genre, true)) &&
            (year == "All" || movie.year == year) &&
            (query.isBlank() || movie.title.contains(query, true) || movie.language.contains(query, true)) &&
            (actor.isBlank() || movie.leadActor.contains(actor, true)) &&
            (director.isBlank() || movie.director.contains(director, true))
    }.let { list -> if (alphabetical) list.sortedBy { it.title.lowercase() } else list }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Background) {
            if (showSplash) {
                EinthusanSplashScreen()
            } else {
                AppUpdateChecker(manualCheckRequest = manualUpdateCheck, onStatusChanged = { updateCheckStatus = it })
            if (playerOpen && selectedMovie != null) {
                PlayerScreen(selectedMovie!!, onBack = { playerOpen = false })
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 42.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    TopNavigation(page) { destination ->
                        page = destination
                        selectedMovie = null
                        playerOpen = false
                    }
                    if (selectedMovie != null) {
                        MovieDetails(
                            selectedMovie!!, selectedMovie!!.title in favourites,
                            onBack = { selectedMovie = null },
                            onPlay = { playerOpen = true },
                            onToggleSaved = {
                                val title = selectedMovie!!.title
                                favourites = if (title in favourites) favourites - title else favourites + title
                            }
                        )
                    } else when (page) {
                        "SEARCH" -> SearchPage(
                            query, { query = it }, actor, { actor = it }, director, { director = it },
                            genre, { genre = it }, year, { year = it }, alphabetical, { alphabetical = it },
                            filtered, favourites, { selectedMovie = it },
                            { title -> favourites = if (title in favourites) favourites - title else favourites + title }
                        )
                        "MY LIST" -> {
                            PageHeading("My List", "Your saved Einthusan films")
                            val saved = catalogueMovies.filter { it.title in favourites }
                            if (saved.isEmpty()) EmptyState("Your list is empty", "Open a title and choose + My List to save it here.")
                            else MovieShelf(saved, favourites, { selectedMovie = it }, { title -> favourites = favourites - title })
                        }
                        "SIGN IN" -> SignInPage()
                        "SETTINGS" -> SettingsPage(updateStatus = updateCheckStatus, onCheckUpdates = { manualUpdateCheck++ })
                        "MOVIES" -> {
                            PageHeading("Explore Movies", "Live Einthusan movie catalogue")
                            FilterChips(languages, language) { language = it }
                            when {
                                catalogueLoading -> EmptyState("Loading " + language + " movies…", "Fetching live titles and poster artwork from Einthusan.")
                                catalogueMovies.isNotEmpty() -> MovieShelf(filtered, favourites, { selectedMovie = it }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                })
                                else -> EmptyState("Catalogue unavailable", catalogueError ?: "No titles were returned.")
                            }
                        }
                        else -> {
                            HomeShowcase(
                                movies = catalogueMovies,
                                favourites = favourites,
                                loading = catalogueLoading,
                                error = catalogueError,
                                onBrowse = { page = "MOVIES" },
                                onSelect = { selectedMovie = it },
                                onToggleSaved = { title -> favourites = if (title in favourites) favourites - title else favourites + title }
                            )
                        }
                    }
                }
            }
            }
        }
    }
}


@Composable
private fun ArtworkImage(url: String, contentDescription: String?, contentScale: ContentScale, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url)
            .addHeader("Referer", "https://einthusan.tv/")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            .crossfade(true).build()
    }
    AsyncImage(model = request, contentDescription = contentDescription, contentScale = contentScale, modifier = modifier)
}

@Composable
private fun EinthusanSplashScreen() {
    val pulse by rememberInfiniteTransition(label = "splash-pulse").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "logo-pulse"
    )
    val glow by rememberInfiniteTransition(label = "splash-glow").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "glow-alpha"
    )
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF32105A), Background))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).background(Purple.copy(alpha = glow * 0.22f), RoundedCornerShape(38.dp)))
                Image(
                    painter = painterResource(id = R.drawable.einthusantv_icon),
                    contentDescription = "EinthusanTV logo",
                    modifier = Modifier.size(112.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                )
            }
            Text("YOUR CINEMA. YOUR SCREEN.", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(3) { index ->
                    Box(Modifier.size(7.dp).background(if (index == 1) Magenta else Purple.copy(alpha = 0.65f), RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable
private fun TopNavigation(page: String, onNavigate: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Einthusan", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text("TV", color = Magenta, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.weight(1f))
        listOf("HOME", "MOVIES", "SEARCH", "MY LIST", "SIGN IN", "SETTINGS").forEach { item ->
            NavButton(item, page == item) { onNavigate(item) }
        }
    }
}


@Composable
private fun HomeShowcase(
    movies: List<CatalogMovie>, favourites: Set<String>, loading: Boolean, error: String?,
    onBrowse: () -> Unit, onSelect: (CatalogMovie) -> Unit, onToggleSaved: (String) -> Unit
) {
    val featured = movies.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (featured != null) {
            Box(Modifier.fillMaxWidth().height(260.dp).background(SurfaceDark, RoundedCornerShape(18.dp))) {
                val heroImage = featured.backdropUrl.ifBlank { featured.posterUrl }
                if (heroImage.isNotBlank()) ArtworkImage(
                    url = heroImage, contentDescription = featured.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Background.copy(alpha = 0.98f), Background.copy(alpha = 0.78f), Color.Transparent))))
                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.62f).padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("FEATURED MOVIE", color = Color(0xFFFFA9E5), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(featured.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(listOf(featured.language, featured.year, featured.genre).filter { it.isNotBlank() }.joinToString("  •  "), color = TextMuted, fontSize = 12.sp)
                    if (featured.description.isNotBlank()) Text(featured.description, color = Color.White, fontSize = 13.sp, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("▶  Details", { onSelect(featured) })
                        SecondaryButton("Browse Movies", onBrowse)
                    }
                }
            }
            Text("New Releases", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            MovieShelf(movies, favourites, onSelect, onToggleSaved)
        } else if (loading) {
            EmptyState("Loading Einthusan catalogue…", "Fetching live movies and artwork.")
        } else {
            EmptyState("Catalogue unavailable", error ?: "No titles were returned.")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Browse by Language", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            SecondaryButton("All Movies  →", onBrowse)
        }
        Text("Choose a language from Movies to explore its catalogue.", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SearchPage(
    query: String, onQuery: (String) -> Unit, actor: String, onActor: (String) -> Unit,
    director: String, onDirector: (String) -> Unit, genre: String, onGenre: (String) -> Unit,
    year: String, onYear: (String) -> Unit, alphabetical: Boolean, onAlphabetical: (Boolean) -> Unit,
    movies: List<CatalogMovie>, favourites: Set<String>, onSelect: (CatalogMovie) -> Unit, onToggleSaved: (String) -> Unit
) {
    PageHeading("Search", "Find films by title, cast, director or genre")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchField("Movie title or language", query, onQuery, Modifier.weight(1.4f))
        SearchField("Actor", actor, onActor, Modifier.weight(1f))
        SearchField("Director", director, onDirector, Modifier.weight(1f))
    }
    Text("Browse by genre", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    FilterChips(listOf("All", "Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure"), genre, onGenre)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Year", color = TextMuted, fontSize = 12.sp)
        FilterChips(listOf("All", "2024", "2023", "2022"), year, onYear)
        Spacer(Modifier.width(15.dp))
        SecondaryButton(if (alphabetical) "A–Z ✓" else "Sort A–Z", onClick = { onAlphabetical(!alphabetical) })
        Text("${movies.size} catalogue results", color = TextMuted, fontSize = 12.sp)
    }
    MovieShelf(movies, favourites, onSelect, onToggleSaved)
}

@Composable
private fun SearchField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValue,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        singleLine = true, modifier = modifier, shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun FilterChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
        items(options) { option -> ChoiceChip(option, selected == option) { onSelect(option) } }
    }
}

@Composable
private fun MovieShelf(movies: List<CatalogMovie>, favourites: Set<String>, onSelect: (CatalogMovie) -> Unit, onToggleSaved: (String) -> Unit) {
    if (movies.isEmpty()) EmptyState("No catalogue titles yet", "The real Einthusan catalogue will appear here once connected.")
    else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 7.dp)) {
        items(movies) { movie ->
            MovieCard(movie, movie.title in favourites, { onSelect(movie) }, { onToggleSaved(movie.title) })
        }
    }
}

@Composable
private fun MovieCard(movie: CatalogMovie, saved: Boolean, onSelect: () -> Unit, onToggleSaved: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(184.dp).background(SurfaceDark, RoundedCornerShape(13.dp))
            .border(if (focused) 3.dp else 1.dp, if (focused) Magenta else Color.White.copy(alpha = 0.08f), RoundedCornerShape(13.dp))
            .onFocusChanged { focused = it.isFocused }.focusable().padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PosterArtwork(movie, Modifier.fillMaxWidth().height(145.dp))
        Text(movie.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("${movie.language}  •  ${movie.year}", color = TextMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PrimaryButton("Details", onSelect, compact = true)
            SecondaryButton(if (saved) "♥" else "+ List", onToggleSaved, compact = true)
        }
    }
}

@Composable
private fun PosterArtwork(movie: CatalogMovie, modifier: Modifier = Modifier) {
    val colors = when (movie.genre.lowercase()) {
        "thriller" -> listOf(Color(0xFF25152F), Color(0xFF8C315A))
        "family" -> listOf(Color(0xFF174B4B), Color(0xFF96633D))
        "adventure" -> listOf(Color(0xFF173D60), Color(0xFF398B83))
        else -> listOf(Color(0xFF55234F), Color(0xFFB45A73))
    }
    Box(modifier.background(Brush.linearGradient(colors), RoundedCornerShape(10.dp))) {
        if (movie.posterUrl.isNotBlank()) ArtworkImage(movie.posterUrl, movie.title, ContentScale.Crop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC090711)))).padding(10.dp)) {
            Text(movie.language.uppercase(), Modifier.align(Alignment.TopStart), color = Color.White.copy(alpha = 0.95f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(movie.title.uppercase(), Modifier.align(Alignment.Center), color = Color.White, fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black)
            val meta = listOf(movie.genre, movie.year).filter { it.isNotBlank() }.joinToString(" • ")
            if (meta.isNotBlank()) Text(meta.uppercase(), Modifier.align(Alignment.BottomStart), color = Color.White.copy(alpha = 0.9f), fontSize = 8.sp)
        }
    }
}

@Composable
private fun MovieDetails(movie: CatalogMovie, saved: Boolean, onBack: () -> Unit, onPlay: () -> Unit, onToggleSaved: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(440.dp).padding(top = 10.dp)) {
        if (movie.posterUrl.isNotBlank()) {
            ArtworkImage(movie.backdropUrl.ifBlank { movie.posterUrl }, null, ContentScale.Crop, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Background.copy(alpha = 0.98f), Background.copy(alpha = 0.82f), Color.Transparent))))
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.72f).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MOVIE DETAILS", color = Color(0xFFFFA9E5), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(movie.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(listOf(movie.language, movie.year, movie.genre).filter { it.isNotBlank() }.joinToString("  •  "), color = Color(0xFFFFA9E5), fontSize = 14.sp)
            if (movie.description.isNotBlank()) Text(movie.description, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4)
            if (movie.leadActor.isNotBlank()) Text("CAST  •  ${movie.leadActor}", color = Color.White, fontSize = 13.sp)
            if (movie.director.isNotBlank()) Text("DIRECTOR  •  ${movie.director}", color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PrimaryButton("▶  Play", onPlay)
                SecondaryButton(if (saved) "♥  My List" else "+  My List", onToggleSaved)
                SecondaryButton("Back", onBack)
            }
        }
    }
}

@Composable
private fun SignInPage() {
    var webError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Sign in to Einthusan", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Loading the official Einthusan sign-in page…", color = TextMuted, fontSize = 12.sp)
        AndroidView(
            factory = { viewContext ->
                android.webkit.WebView(viewContext).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = android.webkit.WebChromeClient()
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) {
                            webError = null
                        }
                        override fun onPageFinished(view: android.webkit.WebView, url: String) {
                            android.webkit.CookieManager.getInstance().flush()
                        }
                        override fun onReceivedError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) webError = "Login page couldn't load: " + error.description + " (code " + error.errorCode + ")"
                        }
                        override fun onReceivedHttpError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                            if (request.isForMainFrame) webError = "Einthusan returned HTTP " + response.statusCode + " " + response.reasonPhrase
                        }
                    }
                    loadUrl("https://einthusan.tv/login/?lang=tamil")
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        if (webError != null) {
            Text(webError!!, color = Color(0xFFFFB4AB), fontSize = 13.sp)
            Button(onClick = { webError = null; webViewRef?.reload() }) { Text("Retry login page") }
        }
    }
}
@Composable
private fun EmptyState(title: String, message: String) {
    Column(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(13.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(message, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun PlayerScreen(movie: CatalogMovie, onBack: () -> Unit) {
    val context = LocalContext.current
    var source by remember(movie.id) { mutableStateOf<PlaybackSource?>(null) }
    var status by remember(movie.id) { mutableStateOf("Checking for an authorised video stream…") }
    var loading by remember(movie.id) { mutableStateOf(true) }
    var retry by remember(movie.id) { mutableIntStateOf(0) }
    var playerError by remember(movie.id) { mutableStateOf<String?>(null) }
    var isBuffering by remember(movie.id) { mutableStateOf(false) }

    LaunchedEffect(movie.id, retry) {
        loading = true
        source = null
        playerError = null
        status = "Checking for an authorised video stream…"
        try {
            val result = WebViewPlaybackResolver(context).resolve(movie)
            source = result.source
            status = result.message ?: "Preparing native playback…"
        } catch (error: Exception) {
            status = "Couldn't resolve the video stream. " +
                (error.message?.take(180) ?: "Please try again.")
        } finally {
            loading = false
        }
    }

    val player = remember(context, movie.id) {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isBuffering = false
                var cause: Throwable? = error
                var httpCode: Int? = null
                var responseMessage: String? = null
                while (cause != null) {
                    if (cause is HttpDataSource.InvalidResponseCodeException) {
                        httpCode = cause.responseCode
                        responseMessage = cause.message
                        break
                    }
                    cause = cause.cause
                }
                playerError = if (httpCode != null) {
                    "HTTP ${httpCode}: ${responseMessage ?: "The video server rejected the request."} (${error.errorCodeName})"
                } else {
                    "Video error: ${error.errorCodeName}. ${error.message ?: "The stream was rejected or is not supported."}"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(source, retry) {
        val stream = source ?: return@LaunchedEffect
        playerError = null
        try {
            val item = MediaItem.Builder()
                .setUri(stream.url)
                .apply { stream.mimeType?.let { setMimeType(it) } }
                .build()
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(stream.requestHeaders["User-Agent"] ?: "EinthusanTV Android TV")
                .setDefaultRequestProperties(stream.requestHeaders)
                .setAllowCrossProtocolRedirects(true)
            val mediaSource = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpFactory)
                .createMediaSource(item)
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        } catch (error: Exception) {
            playerError = "Could not prepare video: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (source != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        isFocusable = true
                        requestFocus()
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            if (isBuffering && playerError == null) {
                Row(Modifier.align(Alignment.Center).background(Color(0xCC10091A), RoundedCornerShape(12.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Magenta, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Connecting to video…", color = Color.White, fontSize = 14.sp)
                }
            }
            if (playerError != null) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.78f)
                        .background(Color(0xE8171323), RoundedCornerShape(14.dp)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("THIS VIDEO COULDN'T START", color = Color(0xFFFFA9E5), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(playerError.orEmpty().take(260), color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                    PrimaryButton("Try again", { retry++ })
                    SecondaryButton("‹ Back to movie", onBack)
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth(0.78f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (loading) CircularProgressIndicator(color = Magenta)
                else Text("NATIVE PLAYBACK NOT AVAILABLE", color = Color(0xFFFFA9E5), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Color.White, fontSize = 14.sp, lineHeight = 21.sp)
                if (!loading) {
                    PrimaryButton("Try again", { retry++ })
                    SecondaryButton("‹ Back to movie", onBack)
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0xCC10091A))
                .padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryButton("‹ Back", onBack)
            Spacer(Modifier.width(18.dp))
            Text(movie.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NavButton(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Purple else Color.Transparent,
            contentColor = if (active) Color.White else TextMuted
        ),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)
    ) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ChoiceChip(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.height(39.dp), shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Purple else SurfaceRaised, contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, compact: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Magenta, contentColor = Color.White),
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 9.dp else 17.dp, vertical = if (compact) 3.dp else 8.dp)
    ) { Text(label, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, compact: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = Color.White),
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 9.dp else 15.dp, vertical = if (compact) 3.dp else 8.dp)
    ) { Text(label, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold) }
}
