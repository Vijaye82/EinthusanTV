package tv.einthusan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101014)
private val Panel = Color(0xFF1B1B22)
private val PanelLight = Color(0xFF292932)
private val Gold = Color(0xFFFFC857)
private val Muted = Color(0xFFB8B8C2)

private data class DemoMovie(
    val title: String,
    val language: String,
    val year: String,
    val genre: String,
    val description: String
)

// Fictional sample entries: replace with Einthusan-approved catalogue data.
private val demoMovies = listOf(
    DemoMovie("Monsoon Letters", "Tamil", "2024", "Drama", "A quiet family drama set across two generations."),
    DemoMovie("The Last Mango Tree", "Telugu", "2023", "Drama", "A village comes together to save its oldest orchard."),
    DemoMovie("Midnight Expressway", "Hindi", "2024", "Thriller", "A late-night journey takes an unexpected turn."),
    DemoMovie("Coastal Stories", "Malayalam", "2022", "Family", "Four neighbours discover what home means to them."),
    DemoMovie("The Blue Kite", "Kannada", "2023", "Adventure", "A young inventor follows a mysterious map.")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EinthusanTvApp() }
    }
}

@Composable
private fun EinthusanTvApp() {
    val languages = listOf("All", "Tamil", "Telugu", "Hindi", "Malayalam", "Kannada", "Bengali", "Punjabi", "Marathi")
    var selectedLanguage by remember { mutableStateOf("All") }
    var page by remember { mutableStateOf("HOME") }
    var query by remember { mutableStateOf("") }
    var selectedMovie by remember { mutableStateOf<DemoMovie?>(null) }
    var favourites by remember { mutableStateOf(setOf<String>()) }

    val visibleMovies = demoMovies.filter { movie ->
        (selectedLanguage == "All" || movie.language == selectedLanguage) &&
            (query.isBlank() || movie.title.contains(query, ignoreCase = true) ||
                movie.genre.contains(query, ignoreCase = true) ||
                movie.language.contains(query, ignoreCase = true))
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 52.dp, end = 52.dp, top = 28.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EINTHUSAN", color = Gold, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("TV", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    listOf("HOME", "SEARCH", "MY LIST", "SIGN IN").forEach { item ->
                        NavButton(item, active = page == item, onClick = {
                            page = item
                            selectedMovie = null
                        })
                        Spacer(Modifier.width(7.dp))
                    }
                }

                if (selectedMovie != null) {
                    MovieDetails(
                        movie = selectedMovie!!,
                        saved = favourites.contains(selectedMovie!!.title),
                        onBack = { selectedMovie = null },
                        onToggleSaved = {
                            val title = selectedMovie!!.title
                            favourites = if (title in favourites) favourites - title else favourites + title
                        }
                    )
                } else {
                    when (page) {
                        "SEARCH" -> {
                            Text("SEARCH MOVIES", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search titles, languages or genres") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(0.72f)
                            )
                            MovieShelf(visibleMovies, favourites, onSelect = { selectedMovie = it }, onToggleSaved = {
                                favourites = if (it in favourites) favourites - it else favourites + it
                            })
                        }
                        "MY LIST" -> {
                            Text("MY LIST", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                            val saved = demoMovies.filter { it.title in favourites }
                            if (saved.isEmpty()) {
                                InfoPanel("Your list is empty", "Open a movie and choose Add to My List to save it here.")
                            } else {
                                MovieShelf(saved, favourites, onSelect = { selectedMovie = it }, onToggleSaved = {
                                    favourites = if (it in favourites) favourites - it else favourites + it
                                })
                            }
                        }
                        "SIGN IN" -> SignInPlaceholder()
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("YOUR CINEMA, ON THE BIG SCREEN", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text("Discover movies across languages", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                Text("Choose a language and explore the sample collection.", color = Muted, fontSize = 15.sp)
                            }
                            Text("BROWSE BY LANGUAGE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(languages) { language ->
                                    TvChoice(language, selectedLanguage == language) { selectedLanguage = language }
                                }
                            }
                            Text(if (selectedLanguage == "All") "SAMPLE PICKS" else "SAMPLE PICKS • ${selectedLanguage.uppercase()}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            MovieShelf(visibleMovies, favourites, onSelect = { selectedMovie = it }, onToggleSaved = {
                                favourites = if (it in favourites) favourites - it else favourites + it
                            })
                            InfoPanel("Integration status", "This is a visual prototype with fictional sample titles. Official catalogue, existing-account sign-in and playback will be connected only through Einthusan-approved interfaces.")
                        }
                    }
                }

                Spacer(Modifier.weight(1f, fill = false))
                Text("DEVELOPMENT PREVIEW  •  SAMPLE CONTENT ONLY  •  OFFICIAL INTEGRATION PENDING", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun NavButton(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Gold else Color.Transparent,
            contentColor = if (active) Ink else Muted,
            focusedContainerColor = Gold,
            focusedContentColor = Ink
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvChoice(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Gold else Panel,
            contentColor = if (active) Ink else Color.White,
            focusedContainerColor = Gold,
            focusedContentColor = Ink
        ),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun MovieShelf(
    movies: List<DemoMovie>,
    favourites: Set<String>,
    onSelect: (DemoMovie) -> Unit,
    onToggleSaved: (String) -> Unit
) {
    if (movies.isEmpty()) {
        InfoPanel("No sample titles found", "Try another language or a different search.")
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(15.dp), contentPadding = PaddingValues(vertical = 7.dp)) {
            items(movies) { movie ->
                MovieCard(movie, movie.title in favourites, onSelect, onToggleSaved)
            }
        }
    }
}

@Composable
private fun MovieCard(movie: DemoMovie, saved: Boolean, onSelect: (DemoMovie) -> Unit, onToggleSaved: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(205.dp)
            .background(Panel, RoundedCornerShape(14.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Gold else PanelLight, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(112.dp)
                .background(PanelLight, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(movie.language.uppercase(), color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        Text(movie.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("${movie.year}  •  ${movie.genre}", color = Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onSelect(movie) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)) {
                Text("Details", fontSize = 11.sp)
            }
            Button(
                onClick = { onToggleSaved(movie.title) },
                colors = ButtonDefaults.buttonColors(containerColor = PanelLight, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(if (saved) "Saved" else "+ List", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MovieDetails(movie: DemoMovie, saved: Boolean, onBack: () -> Unit, onToggleSaved: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("MOVIE DETAILS", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(movie.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("${movie.language}  •  ${movie.year}  •  ${movie.genre}", color = Muted, fontSize = 15.sp)
        Text(movie.description, color = Color.White, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text("Back to browsing") }
            Button(onClick = onToggleSaved) { Text(if (saved) "Remove from My List" else "Add to My List") }
        }
        InfoPanel("Playback not connected", "The Play action will be added when Einthusan supplies an approved playback integration. No streams are scraped or embedded in this prototype.")
    }
}

@Composable
private fun SignInPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SIGN IN", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        InfoPanel("Existing Einthusan account", "Account sign-in is not connected yet. We need Einthusan's approved authentication flow before handling credentials.")
        Text("Please do not enter your password into this preview app.", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoPanel(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp)).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
