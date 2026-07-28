package com.pennywiseai.ynab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
                    var granted by remember { mutableStateOf(hasSmsPermission()) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { granted = hasSmsPermission() }
                    val permissions = remember {
                        buildList {
                            add(Manifest.permission.RECEIVE_SMS)
                            add(Manifest.permission.READ_SMS)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                    }
                    PennyWiseApp(
                        onRequestPermissions = { launcher.launch(permissions) },
                        permissionsGranted = granted,
                    )
                }
            }
        }
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
}
