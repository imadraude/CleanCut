package com.cleancut.bgremover.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cleancut.bgremover.R
import com.cleancut.bgremover.ui.components.BackgroundSelector
import com.cleancut.bgremover.ui.components.ImagePreviewArea
import com.cleancut.bgremover.ui.viewmodel.MainUiState
import com.cleancut.bgremover.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // System Photo Picker launcher for primary subject
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.processImageUri(context, it) }
    }

    // System Photo Picker launcher for custom background replacement
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.setCustomBackgroundUri(context, it) }
    }

    val updateState by viewModel.updateState.collectAsState()
    val segmentationMode by viewModel.segmentationMode.collectAsState()
    val modelDownloadState by viewModel.modelDownloadState.collectAsState()

    // Show snackbar when there is a user message from segmentation
    val userMessage = (uiState as? MainUiState.Success)?.userMessage
    LaunchedEffect(userMessage) {
        if (userMessage != null) {
            snackbarHostState.showSnackbar(userMessage)
            viewModel.clearMessage()
        }
    }

    // Show snackbar when there is an update message
    LaunchedEffect(updateState.infoMessage) {
        updateState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUpdateMessage()
        }
    }

    // In-app Update Dialog
    updateState.availableUpdate?.let { updateInfo ->
        com.cleancut.bgremover.ui.components.UpdateDialog(
            updateInfo = updateInfo,
            isDownloading = updateState.isDownloading,
            downloadProgress = updateState.downloadProgress,
            onConfirmUpdate = { viewModel.startUpdateDownload() },
            onDismiss = { viewModel.dismissUpdateDialog() }
        )
    }

    // Model Download Dialog (RMBG-1.4 or BiRefNet)
    if (modelDownloadState.showDialog) {
        com.cleancut.bgremover.ui.components.DownloadModelDialog(
            targetMode = modelDownloadState.targetMode,
            isDownloading = modelDownloadState.isDownloading,
            progress = modelDownloadState.downloadProgress,
            onConfirmDownload = { viewModel.downloadRequiredModel() },
            onDismiss = { viewModel.dismissModelDownloadDialog() }
        )
    }

    val isEditingMask by viewModel.isEditingMask.collectAsState()

    if (isEditingMask && uiState is MainUiState.Success) {
        val successState = uiState as MainUiState.Success
        MaskEditorScreen(
            originalBitmap = successState.originalBitmap,
            initialCutoutBitmap = successState.foregroundCutout,
            onApply = { refinedCutout ->
                viewModel.applyRefinedMask(refinedCutout)
            },
            onCancel = {
                viewModel.closeMaskEditor()
            }
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cleancut_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "CleanCut",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                navigationIcon = {
                    if (uiState !is MainUiState.Idle) {
                        IconButton(
                            onClick = { viewModel.reset() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ArrowBack,
                                contentDescription = "Назад до вибору фото"
                            )
                        }
                    }
                },
                actions = {
                    if (uiState is MainUiState.Success) {
                        val state = uiState as MainUiState.Success
                        IconButton(
                            onClick = { viewModel.openMaskEditor() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoFixHigh,
                                contentDescription = "Підправити краї",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = "${state.processingTimeMs} мс",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Check for updates button
                    IconButton(
                        onClick = { viewModel.checkForUpdates(silent = false) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Перевірити оновлення"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is MainUiState.Idle -> {
                    IdleStateContent(
                        currentMode = segmentationMode,
                        onModeSelected = { viewModel.setSegmentationMode(it) },
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }

                is MainUiState.Processing -> {
                    ProcessingStateContent(message = state.message)
                }

                is MainUiState.Success -> {
                    SuccessStateContent(
                        state = state,
                        currentMode = segmentationMode,
                        onModeSelected = { viewModel.setSegmentationMode(it) },
                        onSelectBackground = { viewModel.selectBackground(it) },
                        onPickCustomBg = {
                            bgPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onSave = { viewModel.saveToGallery(context) },
                        onShare = { viewModel.shareImage(context) },
                        onOpenEditor = { viewModel.openMaskEditor() }
                    )
                }

                is MainUiState.Error -> {
                    ErrorStateContent(
                        errorMessage = state.errorMessage,
                        onRetry = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleStateContent(
    currentMode: com.cleancut.bgremover.domain.model.SegmentationMode,
    onModeSelected: (com.cleancut.bgremover.domain.model.SegmentationMode) -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(88.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_cleancut_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(54.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Миттєве видалення фону",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Локальна сегментація об'єктів на пристрої без передачі даних на сервер. Оберіть режим якості та фотографію.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        com.cleancut.bgremover.ui.components.QualityModeSelector(
            currentMode = currentMode,
            onModeSelected = onModeSelected
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onPickPhoto,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Вибрати зображення",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun ProcessingStateContent(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            modifier = Modifier.size(54.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SuccessStateContent(
    state: MainUiState.Success,
    currentMode: com.cleancut.bgremover.domain.model.SegmentationMode,
    onModeSelected: (com.cleancut.bgremover.domain.model.SegmentationMode) -> Unit,
    onSelectBackground: (com.cleancut.bgremover.data.util.BackgroundOption) -> Unit,
    onPickCustomBg: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Mode selector allowing immediate comparison between Fast and Studio
        com.cleancut.bgremover.ui.components.QualityModeSelector(
            currentMode = currentMode,
            onModeSelected = onModeSelected,
            isSwitching = state.isSwitchingMode
        )

        // Main canvas with zoom, pan, and compare
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            ImagePreviewArea(
                displayBitmap = state.foregroundCutout,
                originalBitmap = state.originalBitmap,
                backgroundOption = state.backgroundOption,
                modifier = Modifier.fillMaxSize()
            )

            if (state.isSwitchingMode) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Background presets selector
        BackgroundSelector(
            selectedOption = state.backgroundOption,
            onOptionSelected = onSelectBackground,
            onPickCustomImage = onPickCustomBg
        )

        // Button to open interactive mask editor
        OutlinedButton(
            onClick = onOpenEditor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .height(48.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoFixHigh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Підправити вручну (Ластик / Відновлення)",
                style = MaterialTheme.typography.labelLarge
            )
        }

        // Bottom action buttons: Save PNG & Share (height 52dp, tap targets >= 44dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onShare,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Поділитися",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Button(
                onClick = onSave,
                enabled = !state.isSaving,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(52.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Зберегти PNG",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorStateContent(
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Виникла помилка",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Спробувати інше фото",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
