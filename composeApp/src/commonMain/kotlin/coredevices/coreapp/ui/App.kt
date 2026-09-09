package coredevices.coreapp.ui

import CommonRoutes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import coredevices.pebble.signal.SignalStation
import org.koin.compose.getKoin
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.coreapp.ui.navigation.AppNavHost
import coredevices.coreapp.ui.screens.SHOWN_ONBOARDING
import coredevices.pebble.ui.PebbleRoutes
import coredevices.ui.dismissKeyboardOnTapOutside
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.koinInject
import theme.AppTheme

@Composable
@Preview
fun App() {
    val navHostController = rememberNavController()
    DisposableEffect(navHostController) {
        val listener = NavController.OnDestinationChangedListener { controller, destination, arguments ->
            val route = destination.route
            Logger.d("Nav: Destination Changed to route='$route'")
        }
        navHostController.addOnDestinationChangedListener(listener)
        onDispose {
            navHostController.removeOnDestinationChangedListener(listener)
        }
    }
    AppTheme {
        val settings: Settings = koinInject()
        val koin = getKoin()
        val signal = remember(koin) { koin.getOrNull<SignalStation>() }
        val startDestination = if (signal?.available == true) {
            PebbleRoutes.SignalHomeRoute
        } else if (settings.getBoolean(SHOWN_ONBOARDING, false)) {
            PebbleRoutes.WatchHomeRoute
        } else {
            CommonRoutes.OnboardingRoute
        }
        Box(Modifier.fillMaxSize().dismissKeyboardOnTapOutside()) {
            AppNavHost(navHostController, startDestination)
            if (signal?.available != true) SttModelUpdatePrompt()
        }
    }
}