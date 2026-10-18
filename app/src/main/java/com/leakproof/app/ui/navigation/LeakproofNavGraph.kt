package com.leakproof.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.leakproof.app.ui.screens.DashboardScreen
import com.leakproof.app.ui.screens.ImportScreen
import com.leakproof.app.ui.screens.ConsentScreen
import com.leakproof.app.ui.screens.LockScreen
import com.leakproof.app.ui.screens.PrivacyPolicyScreen
import com.leakproof.app.ui.screens.ManualAddEditScreen
import com.leakproof.app.ui.screens.OnboardingScreen
import com.leakproof.app.ui.screens.ReviewScreen
import com.leakproof.app.ui.screens.SettingsScreen
import com.leakproof.app.ui.screens.SubscriptionDetailScreen
import com.leakproof.app.ui.viewmodel.LeakproofViewModel

object Destinations {
    const val CONSENT = "consent"
    const val PRIVACY_POLICY = "privacy_policy"
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
fun LeakproofNavGraph(
    viewModel: LeakproofViewModel,
    navController: NavHostController = rememberNavController()
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val isConsentGiven by viewModel.isConsentGiven.collectAsState()
    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    val isAppLocked by viewModel.isAppLocked.collectAsState()

    val startDestination = when {
        !isConsentGiven -> Destinations.CONSENT
        !isOnboardingCompleted -> Destinations.ONBOARDING
        isAppLocked -> Destinations.LOCK
        else -> Destinations.DASHBOARD
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Destinations.CONSENT) {
            val activity = androidx.activity.compose.LocalActivity.current
            ConsentScreen(
                onAgree = {
                    viewModel.giveConsent()
                    val next = when {
                        !isOnboardingCompleted -> Destinations.ONBOARDING
                        isAppLocked -> Destinations.LOCK
                        else -> Destinations.DASHBOARD
                    }
                    navController.navigate(next) { popUpTo(Destinations.CONSENT) { inclusive = true } }
                },
                onDecline = { activity?.finishAffinity() },
                onReadPolicy = { navController.navigate(Destinations.PRIVACY_POLICY) }
            )
        }

        composable(Destinations.PRIVACY_POLICY) {
            PrivacyPolicyScreen(onNavigateBack = { navController.popBackStack() })
        }

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
                onOpenPrivacyPolicy = { navController.navigate(Destinations.PRIVACY_POLICY) },
                onDataWiped = { com.leakproof.app.util.restartApp(appContext) }
            )
        }
    }
}
