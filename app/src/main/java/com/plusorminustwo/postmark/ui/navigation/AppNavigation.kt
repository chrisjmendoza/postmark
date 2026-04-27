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
    data object Thread : Screen("thread/{threadId}?scrollToMessageId={scrollToMessageId}&scrollToDate={scrollToDate}") {
        fun route(threadId: Long, scrollToMessageId: Long = -1L, scrollToDate: String = "") = buildString {
            append("thread/$threadId")
            val params = buildList<String> {
                if (scrollToMessageId != -1L) add("scrollToMessageId=$scrollToMessageId")
                if (scrollToDate.isNotEmpty()) add("scrollToDate=$scrollToDate")
            }
            if (params.isNotEmpty()) append("?${params.joinToString("&")}")
        }
    }
    data object Search : Screen("search")
    data object Stats : Screen("stats?threadId={threadId}") {
        fun navRoute(threadId: Long? = null) =
            if (threadId != null) "stats?threadId=$threadId" else "stats"
    }
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
                onStatsClick = { navController.navigate(Screen.Stats.navRoute()) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Thread.route,
            arguments = listOf(
                navArgument("threadId") { type = NavType.LongType },
                navArgument("scrollToMessageId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("scrollToDate") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val threadId          = backStackEntry.arguments!!.getLong("threadId")
            val scrollToMessageId = backStackEntry.arguments!!.getLong("scrollToMessageId")
            val scrollToDate      = backStackEntry.arguments!!.getString("scrollToDate") ?: ""
            ThreadScreen(
                threadId          = threadId,
                scrollToMessageId = scrollToMessageId,
                scrollToDate      = scrollToDate,
                onBack            = { navController.popBackStack() },
                onViewStats       = { navController.navigate(Screen.Stats.navRoute(threadId)) },
                onBackupSettingsClick = { navController.navigate(Screen.BackupSettings.route) }
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

        composable(
            route = Screen.Stats.route,
            arguments = listOf(navArgument("threadId") {
                type = NavType.LongType
                defaultValue = -1L
            })
        ) {
            StatsScreen(
                onOpenThreadAt = { threadId, scrollToMessageId, scrollToDate ->
                    navController.navigate(Screen.Thread.route(threadId, scrollToMessageId, scrollToDate))
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
