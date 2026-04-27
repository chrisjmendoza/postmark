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
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.plusorminustwo.postmark.ui.conversations.ConversationsScreen
import com.plusorminustwo.postmark.ui.search.SearchScreen
import com.plusorminustwo.postmark.ui.settings.BackupSettingsScreen
import com.plusorminustwo.postmark.ui.settings.DevOptionsScreen
import com.plusorminustwo.postmark.ui.settings.SettingsScreen
import com.plusorminustwo.postmark.ui.stats.StatsScreen
import com.plusorminustwo.postmark.ui.thread.ThreadScreen

sealed class Screen(val route: String) {
    data object Conversations : Screen("conversations")
    data object Thread : Screen("thread/{threadId}?scrollToMessageId={scrollToMessageId}") {
        fun route(threadId: Long, scrollToMessageId: Long = -1L) =
            "thread/$threadId?scrollToMessageId=$scrollToMessageId"
    }
    data object Search : Screen("search")
    data object Stats : Screen("stats")
    data object Settings : Screen("settings")
    data object BackupSettings : Screen("settings/backup")
    data object DevOptions : Screen("settings/dev")
}

private val SLIDE_IN  = tween<IntOffset>(280)
private val SLIDE_OUT = tween<IntOffset>(220)
private val FADE_IN   = tween<Float>(280)
private val FADE_OUT  = tween<Float>(220)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Conversations.route,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        enterTransition    = { slideInHorizontally(SLIDE_IN) { it } + fadeIn(FADE_IN) },
        exitTransition     = { slideOutHorizontally(SLIDE_OUT) { -it / 4 } + fadeOut(FADE_OUT) },
        popEnterTransition = { slideInHorizontally(SLIDE_IN) { -it / 4 } + fadeIn(FADE_IN) },
        popExitTransition  = { slideOutHorizontally(SLIDE_OUT) { it } + fadeOut(FADE_OUT) }
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
            arguments = listOf(
                navArgument("threadId") { type = NavType.LongType },
                navArgument("scrollToMessageId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val threadId = backStackEntry.arguments!!.getLong("threadId")
            val scrollToMessageId = backStackEntry.arguments!!.getLong("scrollToMessageId")
            ThreadScreen(
                threadId = threadId,
                scrollToMessageId = scrollToMessageId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onMessageClick = { threadId, messageId ->
                    navController.navigate(Screen.Thread.route(threadId, messageId))
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
                onDevOptionsClick = { navController.navigate(Screen.DevOptions.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.BackupSettings.route) {
            BackupSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.DevOptions.route) {
            DevOptionsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
