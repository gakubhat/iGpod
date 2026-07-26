package com.igeeta.igpod.ui

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.igeeta.igpod.logic.enableEdgeToEdgeProperly

abstract class BaseComposeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeProperly()
    }
}

@Composable
fun BaseComposeActivity.GramophoneTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // iGpod: pure dark theme removed; always use the normal color scheme.
    GramophoneTheme(useDarkTheme, content)
}

@Composable
fun GramophoneTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = (if (useDarkTheme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                dynamicDarkColorScheme(LocalContext.current)
            else
                darkColorScheme()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                dynamicLightColorScheme(LocalContext.current)
            else
                lightColorScheme()
        }), content = {
            CompositionLocalProvider(
                LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.surface),
            ) {
                content()
            }
        }
    )
}
