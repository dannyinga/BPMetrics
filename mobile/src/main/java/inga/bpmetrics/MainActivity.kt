package inga.bpmetrics

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import inga.bpmetrics.ui.theme.BPMetricsTheme

class MainActivity : ComponentActivity() {
    private val tag = "BPMetrics Main Activity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            BPMetricsTheme {
                BpmLibraryView()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
