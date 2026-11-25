package com.vtlo.unrestrict3d

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vtlo.unrestrict3d.ui.ChatActions
import com.vtlo.unrestrict3d.ui.ChatScreen
import com.vtlo.unrestrict3d.ui.ChatViewModel
import com.vtlo.unrestrict3d.ui.SettingsActions
import com.vtlo.unrestrict3d.ui.SettingsSheet
import com.vtlo.unrestrict3d.ui.SplashScreen
import com.vtlo.unrestrict3d.ui.VtloTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            VtloTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var splash by rememberSaveable { mutableStateOf(true) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.importModel(uri)
                }
                val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val pick = { picker.launch(arrayOf("*/*")) }

                val chat = remember {
                    ChatActions(
                        onSend = vm::send, onStop = vm::stop,
                        onOpenSettings = { settingsOpen = true },
                        onPickModel = pick, onDownloadDefault = vm::downloadDefault,
                        onMode = vm::setMode, onStopPipeline = vm::stopPipeline,
                        onDismissPipeline = vm::dismissPipeline, onApproval = vm::answerApproval,
                        onResume = vm::resumePipeline, onDismissDiag = vm::dismissDiag,
                        onCancelBusy = vm::cancelBusy,
                    )
                }

                if (splash) {
                    SplashScreen { splash = false }
                } else {
                    ChatScreen(state, chat)
                    if (settingsOpen) {
                        SettingsSheet(
                            state,
                            SettingsActions(
                                onDismiss = { settingsOpen = false },
                                onPickModel = { settingsOpen = false; pick() },
                                onDownloadDefault = { settingsOpen = false; vm.downloadDefault() },
                                onLoad = vm::loadModel, onLoadPath = vm::loadFromPath,
                                onDelete = vm::deleteModel, onApply = vm::applySettings,
                                onNewChat = vm::newChat, onCompress = vm::compressManually,
                                onRootFree = { vm.rootFreeNow() }, onRootProtect = { vm.rootProtectNow() },
                                onResources = { vm.showResources() }, onClearResources = vm::clearResources,
                            ),
                        )
                    }
                }
            }
        }
    }
}
