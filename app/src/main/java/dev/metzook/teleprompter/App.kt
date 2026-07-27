package dev.metzook.teleprompter

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.metzook.teleprompter.settings.SettingsRepository
import dev.metzook.teleprompter.ui.edit.EditScreen
import dev.metzook.teleprompter.ui.library.LibraryScreen
import dev.metzook.teleprompter.ui.prompter.PrompterScreen
import dev.metzook.teleprompter.ui.settings.SettingsScreen
import dev.metzook.teleprompter.ui.theme.TeleprompterTheme
import dev.metzook.teleprompter.vault.ScriptRepository
import dev.metzook.teleprompter.vault.ScriptStore
import dev.metzook.teleprompter.vault.VaultStore
import java.io.File

@Composable
fun TeleprompterApp() {
    val context = LocalContext.current.applicationContext
    val repo = remember {
        ScriptRepository(
            local = ScriptStore(File(context.filesDir, "scripts")),
            vault = VaultStore(context),
        )
    }
    val settingsRepo = remember { SettingsRepository(context) }
    val nav = rememberNavController()

    TeleprompterTheme {
        NavHost(navController = nav, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    repo = repo,
                    settingsRepo = settingsRepo,
                    onOpen = { id -> nav.navigate("prompter/${Uri.encode(id)}") },
                    onEditLocal = { name ->
                        nav.navigate(if (name == null) "edit" else "edit?id=${Uri.encode(name)}")
                    },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable(
                route = "edit?id={id}",
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                EditScreen(
                    store = repo.local,
                    scriptId = entry.arguments?.getString("id"),
                    onDone = { nav.popBackStack() },
                )
            }
            composable("prompter/{id}") { entry ->
                PrompterScreen(
                    repo = repo,
                    settingsRepo = settingsRepo,
                    scriptId = entry.arguments?.getString("id").orEmpty(),
                    onExit = { nav.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(settingsRepo = settingsRepo, onBack = { nav.popBackStack() })
            }
        }
    }
}
