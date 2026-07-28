package com.pennywiseai.ynab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.pennywiseai.ynab.ui.theme.PennyWiseTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PennyWiseTheme {
                Surface {
                    Text("PennyWise → YNAB") // replaced by PennyWiseApp() in Task 12
                }
            }
        }
    }
}
