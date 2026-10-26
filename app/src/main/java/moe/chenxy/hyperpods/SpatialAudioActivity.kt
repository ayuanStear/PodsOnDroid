package moe.chenxy.hyperpods

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.ui.AppTheme
import moe.chenxy.hyperpods.ui.components.PageScaffold
import moe.chenxy.hyperpods.ui.components.SpatialAudioPage

class SpatialAudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkMode = isSystemInDarkTheme()
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )
                onDispose {}
            }
            AppTheme(colorMode = 2) {
                PageScaffold(
                    title = stringResource(R.string.spatial_audio_title),
                    padding = WindowInsets.safeDrawing.asPaddingValues(),
                    onBack = ::finish,
                ) { bodyPadding ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = bodyPadding.calculateBottomPadding())) {
                        SpatialAudioPage(isActive = true)
                    }
                }
            }
        }
    }
}
