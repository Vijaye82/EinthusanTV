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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
    val movieShelfStates = remember { mutableMapOf<String, LazyListState>() }
    val trendingEntryFocus = remember { FocusRequester() }
    val recentEntryFocus = remember { FocusRequester() }
    val recommendationsEntryFocus = remember { FocusRequester() }
    val languageFilterEntryFocus = remember { FocusRequester() }
    val genreFilterEntryFocus = remember { FocusRequester() }
    val exploreLanguageEntryFocus = remember { FocusRequester() }
    val exploreGenreEntryFocus = remember { FocusRequester() }
    val exploreYearEntryFocus = remember { FocusRequester() }
    val exploreMoviesEntryFocus = remember { FocusRequester() }
    val searchTitleFocus = remember { FocusRequester() }
    val searchActorFocus = remember { FocusRequester() }
    val searchDirectorFocus = remember { FocusRequester() }
    val searchGenreFocus = remember { FocusRequester() }
    val searchYearFocus = remember { FocusRequester() }
    val searchResultsFocus = remember { FocusRequester() }
    val myListEntryFocus = remember { FocusRequester() }
    var returnFocusKey by remember { mutableStateOf<String?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var introVisible by remember { mutableStateOf(true) }
    val homePlayFocusRequester = remember { FocusRequester() }
    val homeNavFocus = remember { FocusRequester() }
    val moviesNavFocus = remember { FocusRequester() }
    val searchNavFocus = remember { FocusRequester() }
    val myListNavFocus = remember { FocusRequester() }
    val signInNavFocus = remember { FocusRequester() }
    val signInEmailFocus = remember { FocusRequester() }
    val signInPasswordFocus = remember { FocusRequester() }
    val signInSubmitFocus = remember { FocusRequester() }
    val detailsBackFocus = remember { FocusRequester() }
    val detailsPlayFocus = remember { FocusRequester() }
    val detailsSaveFocus = remember { FocusRequester() }
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

    LaunchedEffect(selectedMovie) {
        if (selectedMovie != null && !playerOpen) {
            delay(180)
            runCatching { detailsPlayFocus.requestFocus() }
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
                    TopNavigation(page, { destination ->
                        page = destination
                        selectedMovie = null
                        playerOpen = false
                    }, homeNavFocus, moviesNavFocus, searchNavFocus, myListNavFocus, signInNavFocus,
                        homePlayFocusRequester, exploreLanguageEntryFocus, searchTitleFocus, myListEntryFocus, signInEmailFocus)
                    if (selectedMovie != null) {
                        MovieDetails(
                            selectedMovie!!, selectedMovie!!.title in favourites,
                            onBack = { selectedMovie = null },
                            onPlay = { playerOpen = true },
                            onToggleSaved = {
                                val title = selectedMovie!!.title
                                favourites = if (title in favourites) favourites - title else favourites + title
                            },
                            backFocus = detailsBackFocus,
                            playFocus = detailsPlayFocus,
                            saveFocus = detailsSaveFocus
                        )
                    } else when (page) {
                        "SEARCH" -> SearchPage(
                            query, { query = it }, actor, { actor = it }, director, { director = it },
                            genre, { genre = it }, year, { year = it }, alphabetical, { alphabetical = it },
                            filtered, favourites, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie },
                            { title -> favourites = if (title in favourites) favourites - title else favourites + title },
                            movieFocusRequesters, movieShelfStates, returnFocusKey,
                            searchTitleFocus, searchActorFocus, searchDirectorFocus, searchGenreFocus, searchYearFocus, searchResultsFocus
                        )
                        "MY LIST" -> {
                            val saved = catalogueMovies.filter { it.title in favourites }
                            PageHeading("My List", "Your personal collection of saved Einthusan films")
                            Box(
                                Modifier.fillMaxWidth().background(
                                    Brush.horizontalGradient(listOf(Color(0xFF251116), Color(0xFF171717), SurfaceDark)),
                                    RoundedCornerShape(16.dp)
                                ).border(1.dp, Color(0xFFE50914).copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 22.dp, vertical = 18.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("YOUR COLLECTION", color = Color(0xFFFF6972), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                        Text(
                                            if (saved.isEmpty()) "Build your movie night list" else "Ready when you are",
                                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            if (saved.isEmpty()) "Save films from any movie card to keep them here." else "Your saved films, all in one place.",
                                            color = TextMuted, fontSize = 12.sp
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(saved.size.toString(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                                        Text(if (saved.size == 1) "SAVED FILM" else "SAVED FILMS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    }
                                }
                            }
                            if (saved.isEmpty()) {
                                EmptyState(
                                    "Your watchlist is waiting",
                                    "Explore the catalogue and tap + List on a film to add it to your collection.",
                                    actionLabel = "Explore movies",
                                    onAction = { page = "MOVIES" },
                                    actionFocusRequester = myListEntryFocus
                                )
                            } else {
                                ShelfHeading("Saved films", "Pick up where your next movie night begins")
                                MovieShelf(
                                    saved, favourites, "my-list", movieFocusRequesters, movieShelfStates, returnFocusKey,
                                    { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie },
                                    { title -> favourites = favourites - title },
                                    firstItemFocusRequester = myListEntryFocus
                                )
                            }
                        }
                        "SIGN IN" -> SignInPage(
                            state = accountState,
                            onSignIn = { email, password ->
                                accountState = AccountState.SigningIn
                                scope.launch { accountState = accountRepository.signIn(SignInRequest(email, password.toCharArray())) }
                            },
                            onSignOut = { scope.launch { accountRepository.signOut(); accountState = AccountState.SignedOut } },
                            emailFocus = signInEmailFocus,
                            passwordFocus = signInPasswordFocus,
                            submitFocus = signInSubmitFocus
                        )
                        "MOVIES" -> {
                            PageHeading("Explore Movies", "Browse the catalogue by language, genre and release year")
                            Text("LANGUAGE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            FilterChips(languages, language, downEntryFocus = exploreGenreEntryFocus, firstChipFocusRequester = exploreLanguageEntryFocus) { language = it }
                            Text("GENRE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            FilterChips(genres, genre, upEntryFocus = exploreLanguageEntryFocus, downEntryFocus = exploreYearEntryFocus, firstChipFocusRequester = exploreGenreEntryFocus) { genre = it }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("RELEASE YEAR", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                FilterChips(listOf("All", "2026", "2025", "2024", "2023", "2022", "2021", "2020"), year, upEntryFocus = exploreGenreEntryFocus, downEntryFocus = exploreMoviesEntryFocus, firstChipFocusRequester = exploreYearEntryFocus) { year = it }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${filtered.size} titles", color = TextMuted, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                SecondaryButton(if (alphabetical) "Sort: A–Z" else "Sort: Featured", onClick = { alphabetical = !alphabetical })
                            }
                            MovieShelf(filtered, favourites, "movies", movieFocusRequesters, movieShelfStates, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                favourites = if (title in favourites) favourites - title else favourites + title
                            }, upEntryFocus = exploreYearEntryFocus, firstItemFocusRequester = exploreMoviesEntryFocus)
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
                                ShelfHeading("Browse by language", "Find cinema from across the region")
                                FilterChips(languages, language, upEntryFocus = homePlayFocusRequester, downEntryFocus = trendingEntryFocus, firstChipFocusRequester = languageFilterEntryFocus) { language = it }
                                ShelfHeading("Trending now", "Popular picks from the catalogue")
                                MovieShelf(filtered.take(16), favourites, "home-trending", movieFocusRequesters, movieShelfStates, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                }, upEntryFocus = languageFilterEntryFocus, downEntryFocus = genreFilterEntryFocus, firstItemFocusRequester = trendingEntryFocus)
                                ShelfHeading("Recently added", "Fresh additions and recent releases")
                                MovieShelf(filtered.sortedByDescending { it.year }.take(18), favourites, "home-recent", movieFocusRequesters, movieShelfStates, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                }, upEntryFocus = trendingEntryFocus, downEntryFocus = genreFilterEntryFocus, firstItemFocusRequester = recentEntryFocus)
                                ShelfHeading("Explore by genre", "Choose a mood for your next movie")
                                FilterChips(genres, genre, upEntryFocus = recentEntryFocus, downEntryFocus = recommendationsEntryFocus, firstChipFocusRequester = genreFilterEntryFocus) { genre = it; page = "MOVIES" }
                                ShelfHeading("Your next favourite film", "More films selected from the catalogue")
                                MovieShelf(filtered.drop(8).take(18), favourites, "home-recommendations", movieFocusRequesters, movieShelfStates, returnFocusKey, { movie, focusKey -> returnFocusKey = focusKey; selectedMovie = movie }, { title ->
                                    favourites = if (title in favourites) favourites - title else favourites + title
                                }, upEntryFocus = genreFilterEntryFocus, firstItemFocusRequester = recommendationsEntryFocus)
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
private fun TopNavigation(
    page: String, onNavigate: (String) -> Unit,
    homeNav: FocusRequester, moviesNav: FocusRequester, searchNav: FocusRequester,
    myListNav: FocusRequester, signInNav: FocusRequester,
    homeEntry: FocusRequester, moviesEntry: FocusRequester, searchEntry: FocusRequester, myListEntry: FocusRequester, signInEntry: FocusRequester
) {
    val items = listOf(
        Triple("HOME", homeNav, homeEntry), Triple("MOVIES", moviesNav, moviesEntry),
        Triple("SEARCH", searchNav, searchEntry), Triple("MY LIST", myListNav, myListEntry),
        Triple("SIGN IN", signInNav, signInEntry)
    )
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF191216), Color(0xFF151515), Color(0xFF101010))),
                RoundedCornerShape(16.dp)
            )
            .border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(Modifier.fillMaxWidth().focusGroup(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandMark(38.dp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BrandWordmark(23.sp)
                Text("A WORLD OF CINEMA", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.45.sp)
            }
            Spacer(Modifier.weight(1f))
            items.forEachIndexed { index, (item, requester, entry) ->
                NavButton(item, page == item, { onNavigate(item) }, requester,
                    items.getOrNull(index - 1)?.second, items.getOrNull(index + 1)?.second, entry)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(2.dp))) {
            Box(
                Modifier.fillMaxWidth(when (page) {
                    "HOME" -> 0.12f
                    "MOVIES" -> 0.31f
                    "SEARCH" -> 0.50f
                    "MY LIST" -> 0.69f
                    else -> 1f
                }).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE50914), Color(0xFFFF6972))), RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun FeaturedHero(movie: CatalogMovie, onPlay: () -> Unit, onDetails: () -> Unit, playFocusRequester: FocusRequester? = null) {
    Box(Modifier.fillMaxWidth().height(350.dp).background(SurfaceDark, RoundedCornerShape(13.dp)).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(13.dp))) {
        if (!movie.backgroundUrl.isNullOrBlank()) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(movie.backgroundUrl).crossfade(true).build(), contentDescription = movie.title + " featured artwork", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else PosterArtwork(movie, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF080808).copy(alpha = 0.98f), Color(0xFF080808).copy(alpha = 0.88f), Color(0xFF080808).copy(alpha = 0.34f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF080808).copy(alpha = 0.12f), Color(0xFF080808).copy(alpha = 0.62f)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.76f).padding(start = 34.dp, end = 12.dp, bottom = 29.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("FEATURED ON EINTHUSAN", color = Color(0xFFFF6972), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Text(movie.title, color = Color.White, fontSize = 36.sp, lineHeight = 41.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(movie.language.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("•", color = TextMuted)
                Text(movie.year, color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp)
                Text("•", color = TextMuted)
                Text(movie.genre, color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp)
                movie.rating?.let { Text("★ " + String.format("%.1f", it), color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            if (movie.description.isNotBlank()) Text(movie.description, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp, maxLines = 2, lineHeight = 19.sp)
            Row(Modifier.focusGroup().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                PrimaryButton("▶  Play", onPlay, focusRequester = playFocusRequester)
                SecondaryButton("ⓘ  More Info", onDetails)
            }
        }
    }
}

@Composable
private fun ShelfHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextMuted, fontSize = 11.sp)
    }
}
@Composable
private fun SearchPage(
    query: String, onQuery: (String) -> Unit, actor: String, onActor: (String) -> Unit,
    director: String, onDirector: (String) -> Unit, genre: String, onGenre: (String) -> Unit,
    year: String, onYear: (String) -> Unit, alphabetical: Boolean, onAlphabetical: (Boolean) -> Unit,
    movies: List<CatalogMovie>, favourites: Set<String>, onSelect: (CatalogMovie, String) -> Unit, onToggleSaved: (String) -> Unit,
    focusRequesters: MutableMap<String, FocusRequester>, shelfStates: MutableMap<String, LazyListState>, restoreFocusKey: String?,
    titleFocus: FocusRequester, actorFocus: FocusRequester, directorFocus: FocusRequester,
    genreFocus: FocusRequester, yearFocus: FocusRequester, resultsFocus: FocusRequester
) {
    PageHeading("Search", "Find films by title, cast, director or genre")
    Column(
        Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SEARCH YOUR WAY", color = Color(0xFFFF6972), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchField("Movie title or language", query, onQuery, Modifier.weight(1.4f), titleFocus, genreFocus)
            SearchField("Actor", actor, onActor, Modifier.weight(1f), actorFocus, genreFocus)
            SearchField("Director", director, onDirector, Modifier.weight(1f), directorFocus, genreFocus)
        }
        Text("FILTER BY GENRE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        FilterChips(listOf("All", "Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure"), genre, upEntryFocus = titleFocus, downEntryFocus = yearFocus, firstChipFocusRequester = genreFocus, onSelect = onGenre)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("YEAR", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            FilterChips(listOf("All", "2024", "2023", "2022"), year, upEntryFocus = genreFocus, downEntryFocus = resultsFocus, firstChipFocusRequester = yearFocus, onSelect = onYear)
            Spacer(Modifier.weight(1f))
            SecondaryButton(if (alphabetical) "A–Z ✓" else "Sort A–Z", onClick = { onAlphabetical(!alphabetical) })
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Search results", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Browse matching films", color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(movies.size.toString() + " titles", color = Color(0xFFFF6972), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    MovieShelf(movies, favourites, "search", focusRequesters, shelfStates, restoreFocusKey, onSelect, onToggleSaved, upEntryFocus = yearFocus, firstItemFocusRequester = resultsFocus)
}

@Composable
private fun SearchField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null, downEntryFocus: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.015f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "searchFieldFocusScale"
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, color = if (focused) Color.White else TextMuted, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { if (downEntryFocus != null) down = downEntryFocus }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(11.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFFFF5962),
            unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color(0xFFFF5962),
            focusedContainerColor = Color(0xFF202020),
            unfocusedContainerColor = Color(0xFF202020)
        )
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
private fun FilterChips(options: List<String>, selected: String, upEntryFocus: FocusRequester? = null, downEntryFocus: FocusRequester? = null, firstChipFocusRequester: FocusRequester? = null, onSelect: (String) -> Unit) {
    LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
        itemsIndexed(options) { index, option ->
            val requester = if (index == 0) firstChipFocusRequester else null
            ChoiceChip(option, selected == option, onClick = { onSelect(option) }, focusRequester = requester, upEntryFocus = upEntryFocus, downEntryFocus = downEntryFocus)
        }
    }
}

@Composable
private fun MovieShelf(movies: List<CatalogMovie>, favourites: Set<String>, shelfId: String, focusRequesters: MutableMap<String, FocusRequester>, shelfStates: MutableMap<String, LazyListState>, restoreFocusKey: String?, onSelect: (CatalogMovie, String) -> Unit, onToggleSaved: (String) -> Unit, upEntryFocus: FocusRequester? = null, downEntryFocus: FocusRequester? = null, firstItemFocusRequester: FocusRequester? = null) {
    if (movies.isEmpty()) EmptyState("No catalogue titles yet", "The real Einthusan catalogue will appear here once connected.")
    else {
        val listState = remember(shelfId) { shelfStates.getOrPut(shelfId) { LazyListState() } }
        LaunchedEffect(restoreFocusKey, movies, shelfId) {
            if (restoreFocusKey?.startsWith(shelfId + "|") == true) {
                val targetIndex = movies.indexOfFirst { shelfId + "|" + it.id == restoreFocusKey }
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                    delay(100)
                    focusRequesters[restoreFocusKey]?.let { runCatching { it.requestFocus() } }
                }
            }
        }
        LazyRow(state = listState, modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)) {
            itemsIndexed(movies, key = { _, movie -> movie.id }) { index, movie ->
                val focusKey = shelfId + "|" + movie.id
                val requester = remember(focusKey) {
                    focusRequesters.getOrPut(focusKey) {
                        if (index == 0 && firstItemFocusRequester != null) firstItemFocusRequester else FocusRequester()
                    }
                }
                MovieCard(movie, movie.title in favourites, requester, upEntryFocus, downEntryFocus, { onSelect(movie, focusKey) }, { onToggleSaved(movie.title) })
            }
        }
    }
}

@Composable
private fun MovieCard(movie: CatalogMovie, saved: Boolean, focusRequester: FocusRequester, upEntryFocus: FocusRequester?, downEntryFocus: FocusRequester?, onSelect: () -> Unit, onToggleSaved: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.075f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 340f),
        label = "posterFocusScale"
    )
    Column(
        Modifier.width(174.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                if (upEntryFocus != null) up = upEntryFocus
                if (downEntryFocus != null) down = downEntryFocus
            }
            .focusGroup()
             .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 20f else 0f
                shape = RoundedCornerShape(13.dp)
                clip = false
            }
            .background(if (focused) Color(0xFF242424) else Color.Transparent, RoundedCornerShape(13.dp))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color(0xFFFF3944) else Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(13.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PosterArtwork(movie, Modifier.fillMaxWidth().height(230.dp))
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
private fun MovieDetails(
    movie: CatalogMovie, saved: Boolean, onBack: () -> Unit, onPlay: () -> Unit,
    onToggleSaved: () -> Unit, backFocus: FocusRequester, playFocus: FocusRequester, saveFocus: FocusRequester
) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = 455.dp)
            .background(SurfaceDark, RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
    ) {
        if (!movie.backgroundUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(movie.backgroundUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(455.dp)
            )
            Box(
                Modifier.fillMaxWidth().height(455.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f to Color(0xFF101010).copy(alpha = 0.97f),
                            0.48f to Color(0xFF101010).copy(alpha = 0.91f),
                            1f to Color(0xFF101010).copy(alpha = 0.66f)
                        )
                    )
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(26.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterArtwork(movie, Modifier.width(285.dp).height(400.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("EINTHUSAN  •  MOVIE DETAILS", color = Color(0xFFFF6972), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                Text(movie.title, color = Color.White, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(movie.language.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("•", color = TextMuted)
                    Text(movie.year, color = TextMuted, fontSize = 13.sp)
                    Text("•", color = TextMuted)
                    Text(movie.genre, color = TextMuted, fontSize = 13.sp)
                    movie.rating?.let {
                        Text("•", color = TextMuted)
                        Text("★ ${"%.1f".format(it)}", color = Color(0xFFFFD166), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (movie.description.isNotBlank()) {
                    Text(movie.description, color = Color(0xFFE0DCE2), fontSize = 16.sp, lineHeight = 23.sp, maxLines = 4)
                }
                if (movie.cast.isNotEmpty() || movie.leadActor.isNotBlank()) {
                    Text("CAST", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Text((movie.cast.ifEmpty { listOf(movie.leadActor) }).take(4).joinToString("  •  "), color = Color.White, fontSize = 13.sp, maxLines = 2)
                }
                if (movie.director.isNotBlank()) {
                    Text("DIRECTOR  •  ${movie.director}", color = Color(0xFFD0CBD3), fontSize = 13.sp, maxLines = 1)
                }
                Row(
                    Modifier.focusGroup().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    SecondaryButton("Back", onBack, focusRequester = backFocus, rightFocus = playFocus)
                    PrimaryButton("▶  Play", onPlay, focusRequester = playFocus, leftFocus = backFocus, rightFocus = saveFocus)
                    SecondaryButton(if (saved) "♥  In My List" else "+  My List", onToggleSaved, focusRequester = saveFocus, leftFocus = playFocus)
                }
            }
        }
    }
}

@Composable
private fun SignInPage(
    state: AccountState,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit,
    emailFocus: FocusRequester,
    passwordFocus: FocusRequester,
    submitFocus: FocusRequester
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1040.dp).heightIn(min = 390.dp)
                .background(SurfaceDark, RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
        ) {
            Column(
                Modifier.weight(0.9f).fillMaxHeight()
                    .background(Brush.linearGradient(listOf(Color(0xFF42131D), Color(0xFF211018), Color(0xFF171717))))
                    .padding(30.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BrandMark(54.dp)
                    Text("YOUR CINEMA. YOUR ACCOUNT.", color = Color(0xFFFF8790), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text("A world of films,\nall in one place.", color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
                    Text("Sign in with your existing Einthusan account to access available playback features.", color = Color(0xFFD7C9CE), fontSize = 13.sp, lineHeight = 19.sp)
                }
                BrandWordmark(25.sp)
            }
            Column(
                Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 34.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("WELCOME BACK", color = Color(0xFFFF6972), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text("Sign in to Einthusan", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                when (state) {
                    is AccountState.SignedIn -> {
                        Text("You’re signed in" + (state.displayName?.let { " as $it" } ?: ""), color = Color(0xFF8DE6B2), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Your account session is held in memory for this app session.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                        SecondaryButton("Sign out", onSignOut, focusRequester = emailFocus)
                    }
                    else -> {
                        Text("Use your existing Einthusan credentials. Your password is sent only to Einthusan over HTTPS and is not saved by the app.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                        OutlinedTextField(
                            value = email, onValueChange = { email = it },
                            label = { Text("Email address") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(emailFocus).focusProperties { down = passwordFocus },
                            shape = RoundedCornerShape(11.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF5962), unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFFF5962), focusedContainerColor = Color(0xFF202020),
                                unfocusedContainerColor = Color(0xFF202020)
                            )
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text("Password") }, singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).focusProperties { up = emailFocus; down = submitFocus },
                            shape = RoundedCornerShape(11.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF5962), unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFFF5962), focusedContainerColor = Color(0xFF202020),
                                unfocusedContainerColor = Color(0xFF202020)
                            )
                        )
                        if (state is AccountState.Error) Text(state.message, color = Color(0xFFFF8A9A), fontSize = 12.sp, lineHeight = 17.sp)
                        PrimaryButton(
                            if (state is AccountState.SigningIn) "Signing in…" else "Sign in",
                            { if (state !is AccountState.SigningIn && email.isNotBlank() && password.isNotBlank()) onSignIn(email.trim(), password) },
                            focusRequester = submitFocus
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun EmptyState(
    title: String, message: String, actionLabel: String? = null,
    onAction: (() -> Unit)? = null, actionFocusRequester: FocusRequester? = null
) {
    Column(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(13.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(message, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
        if (actionLabel != null && onAction != null) SecondaryButton(actionLabel, onAction, focusRequester = actionFocusRequester)
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
private fun NavButton(
    label: String, active: Boolean, onClick: () -> Unit,
    focusRequester: FocusRequester? = null, leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null, downFocus: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.075f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "navFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocus != null) left = leftFocus
                if (rightFocus != null) right = rightFocus
                if (downFocus != null) down = downFocus
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (focused) 9f else 0f }
            .border(
                width = if (focused) 2.dp else if (active) 1.dp else 1.dp,
                color = if (focused) Color.White else if (active) Color(0xFFFF5962).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.035f),
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused },
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                active && focused -> Color(0xFFFF303B)
                active -> Color(0xFF8F111B)
                focused -> Color(0xFF393939)
                else -> Color(0xFF242020)
            },
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(label, fontSize = 10.sp, fontWeight = if (active || focused) FontWeight.ExtraBold else FontWeight.Bold, letterSpacing = 0.35.sp) }
}

@Composable
private fun ChoiceChip(label: String, active: Boolean, onClick: () -> Unit, focusRequester: FocusRequester? = null, upEntryFocus: FocusRequester? = null, downEntryFocus: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.085f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 360f),
        label = "filterFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier.height(42.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (upEntryFocus != null) up = upEntryFocus
                if (downEntryFocus != null) down = downEntryFocus
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 13f else 0f
                shape = RoundedCornerShape(11.dp)
                clip = false
            }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    active -> Color(0xFFFF5962)
                    else -> Color.White.copy(alpha = 0.035f)
                },
                shape = RoundedCornerShape(11.dp)
            )
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                active && focused -> Color(0xFFFF303B)
                active -> Purple
                focused -> Color(0xFF484848)
                else -> SurfaceRaised
            },
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    ) { Text(label, fontSize = 11.sp, fontWeight = if (active || focused) FontWeight.Bold else FontWeight.SemiBold) }
}
@Composable
private fun PrimaryButton(
    label: String, onClick: () -> Unit, compact: Boolean = false,
    focusRequester: FocusRequester? = null, leftFocus: FocusRequester? = null, rightFocus: FocusRequester? = null
) {
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
            .focusProperties {
                if (leftFocus != null) left = leftFocus
                if (rightFocus != null) right = rightFocus
            }
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
private fun SecondaryButton(
    label: String, onClick: () -> Unit, compact: Boolean = false,
    focusRequester: FocusRequester? = null, leftFocus: FocusRequester? = null, rightFocus: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
        label = "secondaryButtonFocusScale"
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (leftFocus != null) left = leftFocus
                if (rightFocus != null) right = rightFocus
            }
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
