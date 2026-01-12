package com.aeoncorex.streamx.ui.admin

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeoncorex.streamx.data.MovieRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAppScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var tmdbId by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("STREAMX COMMAND", color = Color(0xFF00E5FF), fontWeight = FontWeight.Black, fontSize = 20.sp)
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, "Exit", tint = Color.Red)
            }
        }

        Spacer(Modifier.height(40.dp))

        // Info Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.05f)),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("AUTOMATED DEPLOYMENT", color = Color.Gray, fontSize = 12.sp)
                Text("Enter TMDB ID and Stream Link. Metadata will be fetched automatically.", color = Color.White, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(30.dp))

        // Input Fields
        OutlinedTextField(
            value = tmdbId,
            onValueChange = { tmdbId = it },
            label = { Text("TMDB ID (e.g., 550)", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = Color(0xFF00E5FF), unfocusedBorderColor = Color.DarkGray)
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = streamUrl,
            onValueChange = { streamUrl = it },
            label = { Text("Stream URL (Direct Link)", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = Color(0xFF00E5FF), unfocusedBorderColor = Color.DarkGray)
        )

        Spacer(Modifier.height(40.dp))

        // Action Button
        Button(
            onClick = {
                if (tmdbId.isNotEmpty() && streamUrl.isNotEmpty()) {
                    isLoading = true
                    scope.launch {
                        val success = MovieRepository.postMovie(tmdbId, streamUrl)
                        isLoading = false
                        if (success) {
                            Toast.makeText(context, "DEPLOYED SUCCESSFULLY", Toast.LENGTH_SHORT).show()
                            tmdbId = ""; streamUrl = ""
                        } else {
                            Toast.makeText(context, "DEPLOYMENT FAILED", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            shape = RoundedCornerShape(8.dp),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
            else Text("EXECUTE PUSH", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
