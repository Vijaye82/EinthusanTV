package tv.einthusan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101014)
private val Panel = Color(0xFF1B1B22)
private val Gold = Color(0xFFFFC857)
private val Muted = Color(0xFFB8B8C2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EinthusanTvApp() }
    }
}

@Composable
private fun EinthusanTvApp() {
    val sections = listOf(
        "Tamil", "Telugu", "Hindi", "Malayalam", "Kannada",
        "Bengali", "Punjabi", "Marathi"
    )
    val selected = remember { mutableStateOf("Tamil") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EINTHUSAN", color = Gold, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.width(14.dp))
                    Text("TV", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("HOME     SEARCH     MY LIST     SIGN IN", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YOUR CINEMA, ON THE BIG SCREEN", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Discover movies across languages", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Browse the collection and choose a language to get started.", color = Muted, fontSize = 17.sp)
                }

                Text("LANGUAGES", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(sections) { language ->
                        TvChoice(
                            label = language,
                            active = selected.value == language,
                            onClick = { selected.value = language }
                        )
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .background(Panel, RoundedCornerShape(18.dp))
                        .padding(28.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("FEATURED IN ${selected.value.uppercase()}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text("Your next favourite film is waiting.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "This starter screen uses placeholder content. Official catalogue, account sign-in and playback will be connected only through Einthusan-authorised interfaces.",
                            color = Muted, fontSize = 15.sp, lineHeight = 22.sp
                        )
                    }
                }
                Text("Preview build • Catalogue and playback integration pending authorised API details", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TvChoice(label: String, active: Boolean, onClick: () -> Unit) {
    val focused = remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier.height(54.dp).onFocusChanged { focused.value = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Gold else Panel,
            contentColor = if (active) Ink else Color.White,
            focusedContainerColor = Gold,
            focusedContentColor = Ink
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
