package com.aeoncorex.streamx.ui.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(navController: NavController) {
    // Privacy Policy ফাইলের URL
    val privacyPolicyUrl = "https://raw.githubusercontent.com/cybernahid-dev/StreamX-Ultra/main/PRIVACY_POLICY.md"
    
    // Markdown টেক্সট সেভ করার জন্য State
    val policyText = remember { mutableStateOf<String?>(null) }
    val isLoading = remember { mutableStateOf(true) }

    // নেটওয়ার্ক থেকে Markdown ফাইলটি লোড করার জন্য LaunchedEffect
    LaunchedEffect(Unit) {
        isLoading.value = true
        try {
            val text = withContext(Dispatchers.IO) {
                URL(privacyPolicyUrl).readText()
            }
            policyText.value = text
        } catch (e: Exception) {
            policyText.value = "Failed to load Privacy Policy. Please check your internet connection."
        } finally {
            isLoading.value = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading.value) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                policyText.value?.let {
                    // Markdown টেক্সট দেখানোর জন্য একটি সাধারণ স্ক্রলেবল কলাম
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Markdown-এর মতো করে দেখানোর জন্য আমরা টেক্সটকে লাইন বাই লাইন ভাগ করব
                        it.lines().forEach { line ->
                            when {
                                line.startsWith("# ") -> {
                                    Text(
                                        text = line.removePrefix("# "),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                                    )
                                }
                                line.startsWith("## ") -> {
                                    Text(
                                        text = line.removePrefix("## "),
                                        style = MaterialTheme.typogr