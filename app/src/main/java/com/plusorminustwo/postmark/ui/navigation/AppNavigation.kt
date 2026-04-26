package com.plusorminustwo.postmark.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.plusorminustwo.postmark.ui.conversations.ConversationsScreen
import com.plusorminustwo.postmark.ui.search.SearchScreen
import com.plusorminustwo.postmark.ui.settings.BackupSettingsScreen
import com.plusorminustwo.postmark.ui.settings.SettingsScreen
import com.plusorminustwo.postmark.ui.stats.StatsScreen
import com.plusorminustwo.postmark.ui.thread.ThreadScreen

sealed class Screen(val route: String) {
    data object Conversations : Screen("conversations")
    data object Thread : Screen("thread/{threadId}") {
        fun route(threadId: Long) = "thread/$threadId"
    }
    data object Search : Screen("search")
    data object Stats : Screen("stats")
    data object Settings : Screen("settings")
    data object BackupSettings : Screen("settings/backup")
}

private val ENTER = tween<Float>(280)
private val EXIT  = tween<Float>(220)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Conversations.route,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        enterTransition    = { slideInHorizontally(ENTER) { it } + fadeIn(ENTER) },
        exitTransition     = { slideOutHorizontally(EXIT) { -it / 4 } + fadeOut(EXIT) },
        popEnterTransition = { slideInHorizontally(ENTER) { -it / 4 } + fadeIn(ENTER) },
        popExitTransition  = { slideOutHorizontally(EXIT) { it } + fadeOut(EXIT) }
    ) {
        composable(Screen.Conversations.route) {
            ConversationsScreen(
                onThreadClick = { threadId ->
                    navController.navigate(Screen.Thread.route(threadId))
                },
                onSearchClick = { navController.navigate(Screen.Search.route) },
                onStatsClick = { navController.navigate(Screen.Stats.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Thread.route,
            arguments = listOf(navArgument("threadId") { type = NavType.LongType })
        ) { backStackEntry ->
            val threadId = backStackEntry.arguments!!.getLong("threadId")
            ThreadScreen(
                threadId = threadId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onMessageClick = { threadId, _ ->
                    navController.navigate(Screen.Thread.route(threadId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(
                onThreadClick = { threadId ->
                    navController.navigate(Screen.Thread.route(threadId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackupSettingsClick = { navController.navigate(Screen.BackupSettings.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.BackupSettings.route) {
            BackupSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
