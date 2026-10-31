package com.example.steamgrab

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SteamGrabApp()
                }
            }
        }
    }
}

@Composable
fun SteamGrabApp(vm: SteamViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    // Notifikasi dibutuhkan agar progres download terlihat di Android 13 ke atas
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (val s = state) {
        is UiState.LoggedOut -> LoginScreen(
            loading = s.loading,
            error = s.error,
            needsGuardCode = s.needsGuardCode,
            status = s.status,
            onLogin = { user, pass, guard -> vm.login(user, pass, guard) }
        )
        is UiState.GameList -> GameListScreen(
            games = s.games,
            onSelect = { appId -> vm.startDownload(appId) }
        )
        is UiState.Downloading -> DownloadScreen(
            progress = s.progress,
            gameName = s.gameName,
            status = s.status,
            onCancel = { vm.cancelDownload() }
        )
        is UiState.Done -> DoneScreen(
            path = s.savedPath,
            onBack = { vm.backToList() }
        )
        is UiState.Error -> ErrorScreen(
            message = s.message,
            onBack = { vm.backToList() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    loading: Boolean,
    error: String?,
    needsGuardCode: Boolean,
    status: String?,
    onLogin: (String, String, String?) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var guardCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Login Steam", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Username") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (needsGuardCode) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = guardCode, onValueChange = { guardCode = it },
                label = { Text("Kode Steam Guard") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            // SelectionContainer supaya teks error bisa disalin dan dikirim ke pengembang
            SelectionContainer {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { onLogin(username, password, guardCode.ifBlank { null }) },
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Text(if (needsGuardCode) "Kirim kode" else "Login")
        }
    }
}

@Composable
fun GameListScreen(games: List<GameInfo>, onSelect: (Int) -> Unit) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pilih Game", style = MaterialTheme.typography.headlineSmall)

        if (!Storage.hasAllFilesAccess()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Izin akses semua file belum aktif. Tanpa izin ini hasil download tidak bisa dibuka dari PC.",
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }) { Text("Buka pengaturan izin") }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(games) { game ->
                ListItem(
                    headlineContent = { Text(game.name) },
                    supportingContent = { Text("App ID: ${game.appId}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(game.appId) }
                )
                Divider()
            }
        }
    }
}

@Composable
fun DownloadScreen(progress: Float, gameName: String, status: String, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(gameName, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${(progress * 100).toInt()}% — $status")
        Spacer(Modifier.height(8.dp))
        Text("Download tetap berjalan walau layar dikunci atau aplikasi ditutup. Pantau dari notifikasi.")
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text("Batal") }
    }
}

@Composable
fun DoneScreen(path: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Download selesai!", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Tersimpan di:\n$path")
        Spacer(Modifier.height(16.dp))
        Text(
            "Hubungkan HP ke PC via USB. Salin isi folder steamapps (di Download/SteamGames/steamapps) " +
                "ke folder steamapps Steam di PC. Gabungkan dengan yang sudah ada, jangan menimpa seluruh folder. " +
                "Lalu buka Steam dan pilih Verify integrity pada game tersebut."
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Download game lain") }
    }
}

@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Terjadi kesalahan", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Kembali ke daftar") }
    }
}
