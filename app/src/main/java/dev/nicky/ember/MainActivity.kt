package dev.nicky.ember

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.nicky.ember.ui.EmberApp
import dev.nicky.ember.ui.theme.EmberTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EmberTheme {
                EmberApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppVisible(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) viewModel.setAppVisible(false)
        super.onStop()
    }
}
