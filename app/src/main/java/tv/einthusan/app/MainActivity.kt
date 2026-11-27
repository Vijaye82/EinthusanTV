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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import tv.einthusan.app.catalog.CatalogResult
import tv.einthusan.app.catalog.EinthusanCatalogRepository
import tv.einthusan.app.account.AccountState
import tv.einthusan.app.account.SignInRequest
import tv.einthusan.app.account.EinthusanAccountRepository
import tv.einthusan.app.playback.EinthusanPlaybackRepository
import tv.einthusan.app.playback.PlaybackRequest
import tv.einthusan.app.playback.PlaybackResult

private val Background = Color(0xFF080808)
private val SurfaceDark = Color(0xFF181818)
private val SurfaceRaised = Color(0xFF292929)
private val Purple = Color(0xFFE50914)
private val Magenta = Color(0xFFE50914)
private val TextMuted = Color(0xFFBDB5C9)

private data class CatalogMovie(
    val title: String, val language: String, val year: String, val genre: String,
    val leadActor: String, val director: String, val description: String,
    val id: String = title, val einthusanId: String? = null, val posterUrl: String? = null, val backgroundUrl: String? = null,
    val rating: Double? = null, val cast: List<String> = emptyList()
)


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
    val scope = rememberCoroutineScope()
    val accountRepository = remember { EinthusanAccountRepository() }
    var accountState by remember { mutableStateOf<AccountState>(AccountState.SignedOut) }
    val languages = listOf("All", "Tamil", "Telugu", "Hindi", "Malayalam", "Kannada", "Bengali", "Punjabi", "Marathi")
    val genres = listOf("All", "Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure")
    var catalogueMovies by remember { mutableStateOf(emptyList<CatalogMovie>()) }
    var catalogStatus by remember { mutableStateOf("Loading the latest Einthusan catalogue…") }
    var page by remember { mutableStateOf("HOME") }
    var language by remember { mutableStateOf("All") }
    var genre by remember { mutableStateOf("All") }
    var year by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf("") }
    var director by remember { mutableStateOf("") }
    var alphabetical by remember { mutableStateOf(false) }
    var selectedMovie by remember { mutableStateOf<CatalogMovie?>(null) }
    val movieFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val trendingEntryFocus = remember { FocusRequester() }
    val recentEntryFocus = remember { FocusRequester() }
    val recommendationsEntryFocus = remember { FocusRequester() }
    var returnFocusKey by remember { mutableStateOf<String?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var introVisible by remember { mutableStateOf(true) }
    val homePlayFocusRequester = remember { FocusRequester() }
    var initialHomeFocusRequested by remember { mutableStateOf(false) }
    var favourites by remember {
        mutableStateOf(
            context.getSharedPreferences("user_preferences", android.content.Context.MODE_PRIVATE)
                .getStringSet("favourites", emptySet())?.toSet() ?: emptySet()
        )
    }


    LaunchedEffect(Unit) {
        accountState = accountRepository.currentState()
        when (val result = EinthusanCatalogRepository(tv.einthusan.app.BuildConfig.EINTHUSAN_CATALOG_BASE_URL).loadHome()) {
            is CatalogResult.Success -> {
                catalogueMovies = result.snapshot.sections.flatMap { it.movies }.map { movie ->
                    CatalogMovie(
                        title = movie.title, language = movie.language, year = movie.year?.toString() ?: "—",
                        genre = movie.genres.firstOrNull() ?: "Cinema", leadActor = movie.cast.firstOrNull() ?: "",
                        director = movie.director.orEmpty(), description = movie.synopsis.orEmpty(), id = movie.id,
                        einthusanId = movie.einthusanId,
                        posterUrl = movie.posterUrl, backgroundUrl = movie.backgroundUrl, rating = movie.rating, cast = movie.cast
                    )
                }
                catalogStatus = "${catalogueMovies.size} movies loaded"
            }
            is CatalogResult.Failure -> catalogStatus = result.message
        }
    }

    LaunchedEffect(favourites) {
        context.getSharedPreferences("user_preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet("favourites", favourites.toSet())
            .apply()
    }

    LaunchedEffect(introVisible, catalogueMovies, page) {
        if (!introVisible && !initialHomeFocusRequested && page == "HOME" && catalogueMovies.isNotEmpty()) {
            delay(350)
            runCatching { homePlayFocusRequester.requestFocus() }
            initialHomeFocusRequested = true
        }
    }

    LaunchedEffect(selectedMovie, playerOpen, returnFocusKey) {
        if (selectedMovie == null && !playerOpen && returnFocusKey != null) {
            delay(220)
            movieFocusRequesters[returnFocusKey!!]?.let { runCatching { it.requestFocus() } }
        }
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
            if (introVisible) {
                CinematicIntro(onFinished = { introVisible = false })
            } else {
                AppUpdateChecker()
            if (playerOpen && selectedMovie != null) {
                PlayerScreen(selectedMovie!!, accountRepository, onBack = { playerOpen = false })
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
                            filtered, favourites, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie },
                            { title -> favourites = if (title in favourites) favourites - title else favourites + title },
                            movieFocusRequesters, returnFocusKey
                        )
                        "MY LIST" -> {
                            PageHeading("My List", "Your saved Einthusan films")
                            val saved = catalogueMovies.filter { it.title in favourites }
                            if (saved.isEmpty()) EmptyState("Your list is empty", "Open a title and choose + My List to save it here.")
                            else MovieShelf(saved, favourites, "my-list", movieFocusRequesters, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title -> favourites = favourites - title })
                        }
                        "SIGN IN" -> SignInPage(
                            state = accountState,
                            onSignIn = { email, password ->
                                accountState = AccountState.SigningIn
                                scope.launch { accountState = accountRepository.signIn(SignInRequest(email, password.toCharArray())) }
                            },
                            onSignOut = { scope.launch { accountRepository.signOut(); accountState = AccountState.SignedOut } }
                        )
                        "MOVIES" -> {
                            PageHeading("Explore Movies", "Browse the catalogue by language, genre and release year")
                            Text("LANGUAGE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            FilterChips(languages, language) { language = it }
                            Text("GENRE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            FilterChips(genres, genre) { genre = it }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("RELEASE YEAR", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                FilterChips(listOf("All", "2026", "2025", "2024", "2023", "2022", "2021", "2020"), year) { year = it }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${filtered.size} titles", color = TextMuted, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                SecondaryButton(if (alphabetical) "Sort: A–Z" else "Sort: Featured", onClick = { alphabetical = !alphabetical })
                            }
                            MovieShelf(filtered, favourites, "movies", movieFocusRequesters, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                favourites = if (title in favourites) favourites - title else favourites + title
                            })
                        }
                        else -> {
                            if (catalogueMovies.isEmpty()) {
                                PageHeading("Welcome to EinthusanTV", "Discover South Asian cinema")
                                EmptyState("Your cinema is getting ready", catalogStatus)
                            } else {
                                val hero = filtered.firstOrNull() ?: catalogueMovies.first()
                                FeaturedHero(
                                    movie = hero,
                                    onPlay = { returnFocusKey = null; selectedMovie = hero; playerOpen = true },
                                    onDetails = { returnFocusKey = null; selectedMovie = hero },
                                    playFocusRequester = homePlayFocusRequester
                                )
                                Text("Browse by language", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                FilterChips(languages, language) { language = it }
                                Text("Trending now", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                MovieShelf(filtered.take(16), favourites, "home-trending", movieFocusRequesters, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                }, downEntryFocus = recentEntryFocus, firstItemFocusRequester = trendingEntryFocus)
                                Text("Recently added", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                MovieShelf(filtered.sortedByDescending { it.year }.take(18), favourites, "home-recent", movieFocusRequesters, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                }, upEntryFocus = trendingEntryFocus, downEntryFocus = recommendationsEntryFocus, firstItemFocusRequester = recentEntryFocus)
                                Text("Explore by genre", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                FilterChips(genres, genre) { genre = it; page = "MOVIES" }
                                Text("Your next favourite film", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                MovieShelf(filtered.drop(8).take(18), favourites, "home-recommendations", movieFocusRequesters, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                }, upEntryFocus = recentEntryFocus, firstItemFocusRequester = recommendationsEntryFocus)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).background(
            Brush.linearGradient(listOf(Color(0xFF7427E8), Color(0xFFE51CB7), Color(0xFFFF8A18))),
            RoundedCornerShape(size * 0.28f)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text("▶", color = Color.White, fontSize = (size.value * 0.43f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BrandWordmark(fontSize: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Text("Einthusan", color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = (-0.7).sp)
        Text("TV", color = Color(0xFFFF5B79), fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = (-0.7).sp)
    }
}

@Composable
private fun CinematicIntro(onFinished: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "logoGlow")
    val glow by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Reverse),
        label = "glow"
    )
    LaunchedEffect(Unit) {
        delay(2800)
        onFinished()
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(Color(0xFF24102F), Color(0xFF0D0818), Color(0xFF05040A)),
                radius = 1000f
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            BrandMark(104.dp)
            BrandWordmark(39.sp)
            Box(
                Modifier.width(190.dp).height(2.dp).background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF8D28E8), Color(0xFFFF4E87), Color.Transparent))
                ).graphicsLayer(alpha = glow)
            )
        }
        Text(
            "A WORLD OF CINEMA",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp),
            color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp
        )
    }
}

@Composable
private fun TopNavigation(page: String, onNavigate: (String) -> Unit) {
    Row(Modifier.focusGroup(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark(34.dp)
        BrandWordmark(25.sp)
        Spacer(Modifier.weight(1f))
        listOf("HOME", "MOVIES", "SEARCH", "MY LIST", "SIGN IN").forEach { item ->
            NavButton(item, page == item) { onNavigate(item) }
        }
    }
}


@Composable
private fun FeaturedHero(movie: CatalogMovie, onPlay: () -> Unit, onDetails: () -> Unit, playFocusRequester: FocusRequester? = null) {
    Box(Modifier.fillMaxWidth().height(310.dp).background(SurfaceDark, RoundedCornerShape(8.dp))) {
        if (!movie.backgroundUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(movie.backgroundUrl).crossfade(true).build(),
                contentDescription = movie.title + " featured artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PosterArtwork(movie, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            listOf(Color(0xFF080808), Color(0xEE080808), Color(0x99080808), Color.Transparent)
        )))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f).padding(start = 28.dp, bottom = 25.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("EINTHUSAN COLLECTION", color = Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
            Text(movie.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Text("${movie.year}  •  ${movie.language}  •  ${movie.genre}", color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp)
            Text(movie.description, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp)
            Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("▶  Play", onPlay, focusRequester = playFocusRequester)
                SecondaryButton("ⓘ  More Info", onDetails)
            }
        }
    }
}

@Composable
private fun SearchPage(
    query: String, onQuery: (String) -> Unit, actor: String, onActor: (String) -> Unit,
    director: String, onDirector: (String) -> Unit, genre: String, onGenre: (String) -> Unit,
    year: String, onYear: (String) -> Unit, alphabetical: Boolean, onAlphabetical: (Boolean) -> Unit,
    movies: List<CatalogMovie>, favourites: Set<String>, onSelect: (CatalogMovie, String) -> Unit, onToggleSaved: (String) -> Unit,
    focusRequesters: MutableMap<String, FocusRequester>, restoreFocusKey: String?
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
    MovieShelf(movies, favourites, "search", focusRequesters, restoreFocusKey, onSelect, onToggleSaved)
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
    LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
        items(options) { option -> ChoiceChip(option, selected == option) { onSelect(option) } }
    }
}

@Composable
private fun MovieShelf(movies: List<CatalogMovie>, favourites: Set<String>, shelfId: String, focusRequesters: MutableMap<String, FocusRequester>, restoreFocusKey: String?, onSelect: (CatalogMovie, String) -> Unit, onToggleSaved: (String) -> Unit, upEntryFocus: FocusRequester? = null, downEntryFocus: FocusRequester? = null, firstItemFocusRequester: FocusRequester? = null) {
    if (movies.isEmpty()) EmptyState("No catalogue titles yet", "The real Einthusan catalogue will appear here once connected.")
    else {
        LaunchedEffect(restoreFocusKey, movies, shelfId) {
            if (restoreFocusKey?.startsWith(shelfId + "|") == true) {
                delay(120)
                focusRequesters[restoreFocusKey]?.let { runCatching { it.requestFocus() } }
            }
        }
        LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 7.dp)) {
            items(movies, key = { it.id }) { index, movie ->
                val focusKey = shelfId + "|" + movie.id
                val requester = remember(focusKey) { focusRequesters.getOrPut(focusKey) { FocusRequester() } }
                val shelfEntryRequester = if (index == 0) firstItemFocusRequester else null
                MovieCard(movie, movie.title in favourites, requester, shelfEntryRequester, upEntryFocus, downEntryFocus, { onSelect(movie, focusKey) }, { onToggleSaved(movie.title) })
            }
        }
    }
}

@Composable
private fun MovieCard(movie: CatalogMovie, saved: Boolean, focusRequester: FocusRequester, shelfEntryRequester: FocusRequester?, upEntryFocus: FocusRequester?, downEntryFocus: FocusRequester?, onSelect: () -> Unit, onToggleSaved: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.055f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "posterFocusScale"
    )
    Column(
        Modifier.width(166.dp)
            .focusRequester(focusRequester)
            .then(if (shelfEntryRequester != null) Modifier.focusRequester(shelfEntryRequester) else Modifier)
            .focusProperties {
                if (upEntryFocus != null) up = upEntryFocus
                if (downEntryFocus != null) down = downEntryFocus
            }
            .focusGroup()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (focused) Color(0xFF202020) else Color.Transparent, RoundedCornerShape(13.dp))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color(0xFFE50914) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(13.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PosterArtwork(movie, Modifier.fillMaxWidth().height(218.dp))
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
    if (!movie.posterUrl.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(movie.posterUrl).crossfade(true).build(),
            contentDescription = movie.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(SurfaceRaised, RoundedCornerShape(10.dp))
        )
    } else {
        Box(modifier.background(Brush.linearGradient(colors), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(movie.language.uppercase(), Modifier.align(Alignment.TopStart), color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(movie.title.uppercase(), Modifier.align(Alignment.Center), color = Color.White, fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black)
            Text("${movie.genre.uppercase()}  •  ${movie.year}", Modifier.align(Alignment.BottomStart), color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp)
        }
    }
}

@Composable
private fun MovieDetails(movie: CatalogMovie, saved: Boolean, onBack: () -> Unit, onPlay: () -> Unit, onToggleSaved: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
        PosterArtwork(movie, Modifier.width(245.dp).height(340.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("MOVIE DETAILS", color = Color(0xFFFFA9E5), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(movie.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("${movie.language}  •  ${movie.year}  •  ${movie.genre}", color = Color(0xFFFFA9E5), fontSize = 15.sp)
            Text(movie.description, color = TextMuted, fontSize = 17.sp, lineHeight = 25.sp)
            Text("CAST  •  ${movie.leadActor}", color = Color.White, fontSize = 14.sp)
            Text("DIRECTOR  •  ${movie.director}", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PrimaryButton("▶  Play", onPlay)
                SecondaryButton(if (saved) "♥  In My List" else "+  My List", onToggleSaved)
                SecondaryButton("Back", onBack)
            }
            Text("No authorised stream is connected yet.", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SignInPage(
    state: AccountState,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(SurfaceDark, RoundedCornerShape(20.dp))
                .border(1.dp, Purple.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(42.dp)
                BrandWordmark(27.sp)
            }
            Text("Sign in to your Einthusan account", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            when (state) {
                is AccountState.SignedIn -> {
                    Text("Signed in" + (state.displayName?.let { " as $it" } ?: ""), color = Color(0xFF8DE6B2), fontSize = 15.sp)
                    Text("Your session is held in memory for this app session.", color = TextMuted, fontSize = 12.sp)
                    SecondaryButton("Sign out", onSignOut)
                }
                else -> {
                    Text("Use your existing Einthusan account. Your password is sent only to Einthusan over HTTPS and is not saved by the app.", color = TextMuted, fontSize = 13.sp)
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (state is AccountState.Error) Text(state.message, color = Color(0xFFFF8A9A), fontSize = 12.sp)
                    PrimaryButton(if (state is AccountState.SigningIn) "Signing in…" else "Sign in", { if (state !is AccountState.SigningIn) onSignIn(email, password) })
                }
            }
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
private fun PlayerScreen(movie: CatalogMovie, accountRepository: EinthusanAccountRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(context) { ExoPlayer.Builder(context).build().apply { playWhenReady = false } }
    var playbackStatus by remember(movie.id) { mutableStateOf("Checking your Einthusan account and finding a stream…") }
    var quality by remember(movie.id) { mutableStateOf(PlaybackRequest.Quality.AUTO) }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(movie.id, quality) {
        player.stop()
        playbackStatus = "Checking Einthusan access…"
        when (val result = EinthusanPlaybackRepository(accountRepository)
            .resolve(PlaybackRequest(movie.einthusanId ?: movie.id.removePrefix("einthusan_"), movie.language.lowercase(), quality))) {
            is PlaybackResult.Ready -> {
                val source = result.sources.first()
                val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(source.headers)
                val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
                val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri(source.url))
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true
                playbackStatus = "Playing • " + source.label
            }
            is PlaybackResult.Unavailable -> playbackStatus = result.message
            is PlaybackResult.Failure -> playbackStatus = result.message
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    keepScreenOn = true
                    contentDescription = "Video player for " + movie.title
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0xCC10091A)).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryButton("‹ Back", onBack)
            Spacer(Modifier.width(18.dp))
            Text(movie.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SecondaryButton("Quality: " + quality.name, {
                quality = when (quality) {
                    PlaybackRequest.Quality.AUTO -> PlaybackRequest.Quality.HD
                    PlaybackRequest.Quality.HD -> PlaybackRequest.Quality.UHD
                    PlaybackRequest.Quality.UHD -> PlaybackRequest.Quality.AUTO
                }
            })
        }
        Text(
            playbackStatus,
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC10091A)).padding(16.dp),
            color = Color(0xFFFFA9E5), fontSize = 11.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NavButton(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 460f),
        label = "navFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else if (active) Color(0xFFFF5962) else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .onFocusChanged { focused = it.isFocused },
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                active -> Purple
                focused -> Color(0xFF383838)
                else -> Color.Transparent
            },
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)
    ) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ChoiceChip(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 480f),
        label = "filterFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier.height(39.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else if (active) Color(0xFFFF5962) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                active -> Purple
                focused -> Color(0xFF414141)
                else -> SurfaceRaised
            },
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, compact: Boolean = false, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 480f),
        label = "primaryButtonFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(9.dp)
            )
            .onFocusChanged { focused = it.isFocused },
        colors = ButtonDefaults.buttonColors(containerColor = Magenta, contentColor = Color.White),
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 9.dp else 17.dp, vertical = if (compact) 3.dp else 8.dp)
    ) { Text(label, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, compact: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
        label = "secondaryButtonFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(9.dp)
            )
            .onFocusChanged { focused = it.isFocused },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) Color(0xFF454545) else SurfaceRaised,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 9.dp else 15.dp, vertical = if (compact) 3.dp else 8.dp)
    ) { Text(label, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold) }
}
