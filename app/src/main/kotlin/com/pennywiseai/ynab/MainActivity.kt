package com.pennywiseai.ynab

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import com.pennywiseai.ynab.ui.PennyWiseApp
import com.pennywiseai.ynab.ui.theme.PennyWiseTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PennyWiseTheme {
                Surface {
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { /* results surface via the in-app banner/history; no action needed here */ }
                    val permissions = remember {
                        buildList {
                            add(Manifest.permission.RECEIVE_SMS)
                            add(Manifest.permission.READ_SMS)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                    }
                    PennyWiseApp(onRequestPermissions = { launcher.launch(permissions) })
                }
            }
        }
    }
}
