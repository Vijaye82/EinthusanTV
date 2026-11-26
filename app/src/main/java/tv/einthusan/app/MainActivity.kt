package tv.einthusan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val Background = Color(0xFF090711)
private val SurfaceDark = Color(0xFF171323)
private val SurfaceRaised = Color(0xFF241B35)
private val Purple = Color(0xFF9226D6)
private val Magenta = Color(0xFFE00091)
private val TextMuted = Color(0xFFBDB5C9)

private data class CatalogMovie(
    val title: String, val language: String, val year: String, val genre: String,
    val leadActor: String, val director: String, val description: String
)

// The catalogue starts empty and will be populated through an approved Einthusan integration.
private val catalogueMovies = emptyList<CatalogMovie>()

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        resumePendingApkInstall(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EinthusanTvApp() }
    }
}

@Composable
private fun EinthusanTvApp() {
    val languages = listOf("All", "Tamil", "Telugu", "Hindi", "Malayalam", "Kannada", "Bengali", "Punjabi", "Marathi")
    val genres = listOf("All", "Action", "Drama", "Comedy", "Romance", "Thriller", "Family", "Adventure")
    var page by remember { mutableStateOf("HOME") }
    var language by remember { mutableStateOf("All") }
    var genre by remember { mutableStateOf("All") }
    var year by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf("") }
    var director by remember { mutableStateOf("") }
    var alphabetical by remember { mutableStateOf(false) }
    var selectedMovie by remember { mutableStateOf<CatalogMovie?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var favourites by remember { mutableStateOf(setOf<String>()) }

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
            AppUpdateChecker()
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
                        "MOVIES" -> {
                            PageHeading("Explore Movies", "Einthusan movie catalogue")
                            FilterChips(languages, language) { language = it }
                            MovieShelf(filtered, favourites, { selectedMovie = it }, { title ->
                                favourites = if (title in favourites) favourites - title else favourites + title
                            })
                        }
                        else -> {
                            PageHeading("Welcome to EinthusanTV", "Your Einthusan catalogue will appear here")
                            Text("Browse by language", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            FilterChips(languages, language) { language = it }
                            EmptyState(
                                "Catalogue connection pending",
                                "No sample films or placeholder posters are shown. Real Einthusan titles, artwork and details will appear here when an approved catalogue connection is available."
                            )
                        }
                    }
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
        listOf("HOME", "MOVIES", "SEARCH", "MY LIST", "SIGN IN").forEach { item ->
            NavButton(item, page == item) { onNavigate(item) }
        }
    }
}

@Composable
private fun HeroBanner(onPlay: () -> Unit, onDetails: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(242.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFF25152F), Color(0xFF512044), Color(0xFF171323))), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(horizontal = 30.dp, vertical = 23.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart).width(440.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("FEATURED • TAMIL • 2024", color = Color(0xFFFFA9E5), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text("Monsoon Letters", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("Drama  •  HD concept", color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp)
            Text("A quiet family drama set across two generations.", color = TextMuted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("▶  Play preview", onPlay)
                SecondaryButton("More info", onDetails)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ET", color = Color.White.copy(alpha = 0.12f), fontSize = 92.sp, fontWeight = FontWeight.Black)
            Text("EINTHUSAN TV", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, letterSpacing = 2.sp)
        }
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
    Box(modifier.background(Brush.linearGradient(colors), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(movie.language.uppercase(), Modifier.align(Alignment.TopStart), color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text(movie.title.uppercase(), Modifier.align(Alignment.Center), color = Color.White, fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black)
        Text("${movie.genre.uppercase()}  •  ${movie.year}", Modifier.align(Alignment.BottomStart), color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp)
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
private fun SignInPage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(SurfaceDark, RoundedCornerShape(20.dp))
                .border(1.dp, Purple.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text("EinthusanTV", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
            Text("Sign in to your Einthusan account", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Your favourite South Asian cinema, on the big screen.", color = TextMuted, fontSize = 13.sp)
            EmptyState("Account connection pending", "We need Einthusan's approved sign-in method before connecting existing accounts. Please do not enter your password in this development preview.")
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
    val player = remember(context) { ExoPlayer.Builder(context).build().apply { playWhenReady = false } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    keepScreenOn = true
                    contentDescription = "Video player for ${movie.title}"
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
        }
        Text(
            "PLAYBACK CONNECTION PENDING",
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC10091A)).padding(16.dp),
            color = Color(0xFFFFA9E5), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
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
