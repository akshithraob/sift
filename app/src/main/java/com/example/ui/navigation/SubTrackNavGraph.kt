package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ImportScreen
import com.example.ui.screens.LockScreen
import com.example.ui.screens.ManualAddEditScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ReviewScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SubscriptionDetailScreen
import com.example.ui.viewmodel.SubTrackViewModel

object Destinations {
    const val ONBOARDING = "onboarding"
    const val LOCK = "lock"
    const val DASHBOARD = "dashboard"
    const val IMPORT = "import"
    const val REVIEW = "review"
    const val DETAIL = "detail/{id}"
    const val MANUAL_ADD = "manual_add"
    const val MANUAL_EDIT = "manual_edit/{id}"
    const val SETTINGS = "settings"

    fun detail(id: Long) = "detail/$id"
    fun edit(id: Long) = "manual_edit/$id"
}

@Composable
fun SubTrackNavGraph(
    viewModel: SubTrackViewModel,
    navController: NavHostController = rememberNavController()
) {
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val isAppLocked by viewModel.isAppLocked.collectAsState()

    val startDestination = when {
        !isOnboardingCompleted -> Destinations.ONBOARDING
        isAppLocked -> Destinations.LOCK
        else -> Destinations.DASHBOARD
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Destinations.ONBOARDING) {
            OnboardingScreen(
                onComplete = { enableAppLock ->
                    viewModel.completeOnboarding(enableAppLock)
                    navController.navigate(Destinations.DASHBOARD) {
                        popUpTo(Destinations.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Destinations.LOCK) {
            LockScreen(
                onUnlocked = {
                    viewModel.unlockApp()
                    navController.navigate(Destinations.DASHBOARD) {
                        popUpTo(Destinations.LOCK) { inclusive = true }
                    }
                }
            )
        }

        composable(Destinations.DASHBOARD) {
            DashboardScreen(
                viewModel = viewModel,
                onNavigateToImport = { navController.navigate(Destinations.IMPORT) },
                onNavigateToManualAdd = { navController.navigate(Destinations.MANUAL_ADD) },
                onNavigateToDetail = { id -> navController.navigate(Destinations.detail(id)) },
                onNavigateToSettings = { navController.navigate(Destinations.SETTINGS) }
            )
        }

        composable(Destinations.IMPORT) {
            ImportScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToReview = { navController.navigate(Destinations.REVIEW) },
                onNavigateToManualAdd = { navController.navigate(Destinations.MANUAL_ADD) }
            )
        }

        composable(Destinations.REVIEW) {
            ReviewScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onSavedSuccessfully = {
                    navController.navigate(Destinations.DASHBOARD) {
                        popUpTo(Destinations.DASHBOARD) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = Destinations.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            SubscriptionDetailScreen(
                subscriptionId = id,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { editId -> navController.navigate(Destinations.edit(editId)) }
            )
        }

        composable(Destinations.MANUAL_ADD) {
            ManualAddEditScreen(
                subscriptionId = null,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Destinations.MANUAL_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            ManualAddEditScreen(
                subscriptionId = id,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Destinations.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onDataWiped = {
                    navController.navigate(Destinations.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
