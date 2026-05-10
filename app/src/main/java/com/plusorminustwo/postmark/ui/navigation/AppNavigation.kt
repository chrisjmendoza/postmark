package com.plusorminustwo.postmark.ui.navigation

import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.edit
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.plusorminustwo.postmark.ui.conversations.ConversationsScreen
import com.plusorminustwo.postmark.ui.conversations.NewConversationScreen
import com.plusorminustwo.postmark.ui.contact.ContactDetailScreen
import com.plusorminustwo.postmark.ui.onboarding.OnboardingScreen
import com.plusorminustwo.postmark.ui.search.SearchScreen
import com.plusorminustwo.postmark.ui.settings.BackupSettingsScreen
import com.plusorminustwo.postmark.ui.settings.DevOptionsScreen
import com.plusorminustwo.postmark.ui.settings.SettingsScreen
import com.plusorminustwo.postmark.ui.settings.SyncLogScreen
import com.plusorminustwo.postmark.ui.stats.StatsScreen
import com.plusorminustwo.postmark.ui.thread.ThreadScreen

/**
 * Sealed class representing every navigation destination in the app.
 *
 * Each object carries its [route] template string. Objects with required or
 * optional arguments expose a typed `route(...)` / `navRoute(...)` helper so
 * callers never hand-code route strings.
 */
sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
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
    /** Search screen; optionally pre-filtered to a single thread via [navRoute]. */
    data object Search : Screen("search?threadId={threadId}") {
        // If threadId is provided, the search screen pre-filters to that conversation.
        fun navRoute(threadId: Long = -1L) =
            if (threadId != -1L) "search?threadId=$threadId" else "search"
    }
    /** Statistics screen; optionally pre-selected to a single thread via [navRoute]. */
    data object Stats : Screen("stats?threadId={threadId}") {
        fun navRoute(threadId: Long? = null) =
            if (threadId != null) "stats?threadId=$threadId" else "stats"
    }
    /** Top-level Settings screen. */
    data object Settings : Screen("settings")
    /** Backup & restore settings screen. */
    data object BackupSettings : Screen("settings/backup")
    /** Developer options screen (hidden). */
    data object DevOptions : Screen("settings/dev")
    /** Full-screen sync log viewer. */
    data object SyncLog : Screen("settings/dev/synclog")
    /** Compose a new message — recipient picker / phone number entry. */
    data object NewConversation : Screen("new_conversation")
    /** Contact detail page — opened by tapping the name/avatar in the thread TopAppBar. */
    data object ContactDetail : Screen("contact/{threadId}") {
        fun route(threadId: Long) = "contact/$threadId"
    }
}

private val SLIDE_IN  = tween<IntOffset>(280)
private val SLIDE_OUT = tween<IntOffset>(220)
private val FADE_IN   = tween<Float>(280)
private val FADE_OUT  = tween<Float>(220)

@Composable
fun AppNavigation(showOnboarding: Boolean) {
    val navController = rememberNavController()
    val startDestination = if (showOnboarding) Screen.Onboarding.route else Screen.Conversations.route

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        enterTransition    = { slideInHorizontally(SLIDE_IN) { it } + fadeIn(FADE_IN) },
        exitTransition     = { slideOutHorizontally(SLIDE_OUT) { -it / 4 } + fadeOut(FADE_OUT) },
        popEnterTransition = { slideInHorizontally(SLIDE_IN) { -it / 4 } + fadeIn(FADE_IN) },
        popExitTransition  = { slideOutHorizontally(SLIDE_OUT) { it } + fadeOut(FADE_OUT) }
    ) {
        composable(Screen.Onboarding.route) {
            val context = LocalContext.current
            OnboardingScreen(
                onComplete = {
                    context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)
                        .edit { putBoolean("onboarding_completed", true) }
                    navController.navigate(Screen.Conversations.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Conversations.route) {
            ConversationsScreen(
                onThreadClick = { threadId ->
                    navController.navigate(Screen.Thread.route(threadId))
                },
                onSearchClick  = { navController.navigate(Screen.Search.navRoute()) },
                onStatsClick   = { navController.navigate(Screen.Stats.navRoute()) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onNewConversationClick = { navController.navigate(Screen.NewConversation.route) }
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
                onViewContact     = { navController.navigate(Screen.ContactDetail.route(threadId)) },
                onViewStats       = { navController.navigate(Screen.Stats.navRoute(threadId)) },
                onBackupSettingsClick = { navController.navigate(Screen.BackupSettings.route) },
                onSearchInThread  = { id -> navController.navigate(Screen.Search.navRoute(id)) }
            )
        }

        composable(
            route = Screen.Search.route,
            arguments = listOf(navArgument("threadId") {
                type = NavType.LongType
                defaultValue = -1L
            })
        ) { backStackEntry ->
            SearchScreen(
                onMessageClick = { threadId, messageId ->
                    navController.navigate(Screen.Thread.route(threadId, messageId))
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
                onViewSyncLog = { navController.navigate(Screen.SyncLog.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SyncLog.route) {
            SyncLogScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NewConversation.route) {
            NewConversationScreen(
                onNavigateToThread = { threadId ->
                    // Pop the new-conversation screen so back from the thread
                    // returns to the conversations list, not the picker.
                    navController.navigate(Screen.Thread.route(threadId)) {
                        popUpTo(Screen.NewConversation.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ContactDetail.route,
            arguments = listOf(navArgument("threadId") { type = NavType.LongType })
        ) { backStackEntry ->
            val threadId = backStackEntry.arguments!!.getLong("threadId")
            ContactDetailScreen(
                threadId = threadId,
                onBack   = { navController.popBackStack() }
            )
        }
    }
}
