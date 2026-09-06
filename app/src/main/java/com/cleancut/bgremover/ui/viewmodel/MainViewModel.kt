package com.cleancut.bgremover.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cleancut.bgremover.BuildConfig
import com.cleancut.bgremover.data.ml.HybridSubjectSegmenter
import com.cleancut.bgremover.data.update.GitHubUpdateManager
import com.cleancut.bgremover.data.util.BackgroundOption
import com.cleancut.bgremover.data.util.BitmapUtils
import com.cleancut.bgremover.domain.model.AppUpdate
import com.cleancut.bgremover.domain.model.SegmentationMode
import com.cleancut.bgremover.domain.repository.UpdateManager
import com.cleancut.bgremover.domain.usecase.SegmentImageUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MainUiState {
    object Idle : MainUiState
    data class Processing(val message: String = "Вирізання об'єкта...") : MainUiState
    data class Success(
        val originalBitmap: Bitmap,
        val foregroundCutout: Bitmap,
        val backgroundOption: BackgroundOption,
        val processingTimeMs: Long,
        val isSaving: Boolean = false,
        val userMessage: String? = null,
        val isSwitchingMode: Boolean = false
    ) : MainUiState
    data class Error(val errorMessage: String) : MainUiState
}

data class UpdateUiState(
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdate? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val infoMessage: String? = null
)

data class ModelDownloadState(
    val showDialog: Boolean = false,
    val targetMode: SegmentationMode = SegmentationMode.STUDIO,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0
)

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val segmentUseCase: SegmentImageUseCase = SegmentImageUseCase(HybridSubjectSegmenter(application))
    private val updateManager: UpdateManager = GitHubUpdateManager(application)

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _segmentationMode = MutableStateFlow(SegmentationMode.FAST)
    val segmentationMode: StateFlow<SegmentationMode> = _segmentationMode.asStateFlow()

    private val _modelDownloadState = MutableStateFlow(ModelDownloadState())
    val modelDownloadState: StateFlow<ModelDownloadState> = _modelDownloadState.asStateFlow()

    private val _isEditingMask = MutableStateFlow(false)
    val isEditingMask: StateFlow<Boolean> = _isEditingMask.asStateFlow()

    private var currentInputBitmap: Bitmap? = null
    private val segmentationCache = mutableMapOf<SegmentationMode, com.cleancut.bgremover.domain.model.SegmentationResult>()

    init {
        // Automatically check for newer releases in background on startup
        checkForUpdates(silent = true)
    }

    fun setSegmentationMode(mode: SegmentationMode) {
        if (_segmentationMode.value == mode && _uiState.value is MainUiState.Success) {
            return
        }

        if (!segmentUseCase.isModelReady(mode)) {
            _modelDownloadState.update {
                it.copy(showDialog = true, targetMode = mode)
            }
            return
        }

        _segmentationMode.value = mode
        val bitmap = currentInputBitmap
        val currentSuccess = _uiState.value as? MainUiState.Success
        if (bitmap != null && currentSuccess != null) {
            val cachedResult = segmentationCache[mode]
            if (cachedResult != null) {
                // Instant switch from in-memory cache without re-processing or extra bitmap allocations
                _uiState.value = currentSuccess.copy(
                    foregroundCutout = cachedResult.foregroundCutout,
                    processingTimeMs = cachedResult.processingTimeMs,
                    isSwitchingMode = false
                )
            } else {
                // Not cached yet: execute segmentation for the new mode
                processBitmap(bitmap, mode)
            }
        }
    }

    fun downloadRequiredModel() {
        val target = _modelDownloadState.value.targetMode
        _modelDownloadState.update { it.copy(isDownloading = true, downloadProgress = 0) }

        viewModelScope.launch {
            val result = segmentUseCase.downloadModel(target) { progress ->
                _modelDownloadState.update { it.copy(downloadProgress = progress) }
            }

            result.onSuccess {
                _modelDownloadState.update { it.copy(showDialog = false, isDownloading = false) }
                _segmentationMode.value = target
                currentInputBitmap?.let { bitmap ->
                    processBitmap(bitmap, target)
                }
            }.onFailure { error ->
                _modelDownloadState.update { it.copy(isDownloading = false) }
                _updateState.update {
                    it.copy(infoMessage = "Помилка завантаження моделі: ${error.localizedMessage}")
                }
            }
        }
    }

    fun dismissModelDownloadDialog() {
        _modelDownloadState.update { it.copy(showDialog = false) }
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _updateState.update { it.copy(isChecking = true) }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val result = updateManager.checkForUpdate(currentVersion)

            result.onSuccess { update ->
                if (update.isUpdateAvailable) {
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = update
                        )
                    }
                } else {
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            infoMessage = if (!silent) "У вас встановлена найновіша версія (v$currentVersion)" else null
                        )
                    }
                }
            }.onFailure { error ->
                _updateState.update {
                    it.copy(
                        isChecking = false,
                        infoMessage = if (!silent) "Помилка перевірки оновлень: ${error.localizedMessage}" else null
                    )
                }
            }
        }
    }

    fun startUpdateDownload() {
        val update = _updateState.value.availableUpdate ?: return
        _updateState.update { it.copy(isDownloading = true, downloadProgress = 0) }

        viewModelScope.launch {
            val downloadResult = updateManager.downloadApk(update.apkDownloadUrl) { progress ->
                _updateState.update { it.copy(downloadProgress = progress) }
            }

            downloadResult.onSuccess { apkFile ->
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        availableUpdate = null
                    )
                }
                updateManager.installApk(apkFile)
            }.onFailure { error ->
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        infoMessage = "Помилка завантаження оновлення: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateState.update { it.copy(availableUpdate = null) }
    }

    fun clearUpdateMessage() {
        _updateState.update { it.copy(infoMessage = null) }
    }

    fun processImageUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            segmentationCache.clear()
            _uiState.value = MainUiState.Processing("Завантаження та оптимізація фотографії...")

            val inputBitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapUtils.loadBitmapFromUri(context, uri)
                } catch (e: Exception) {
                    null
                }
            }

            if (inputBitmap == null) {
                _uiState.value = MainUiState.Error("Не вдалося відкрити вибране зображення.")
                return@launch
            }

            currentInputBitmap = inputBitmap
            processBitmap(inputBitmap, _segmentationMode.value)
        }
    }

    private fun processBitmap(bitmap: Bitmap, mode: SegmentationMode) {
        viewModelScope.launch {
            val currentSuccess = _uiState.value as? MainUiState.Success
            val prevOption = currentSuccess?.backgroundOption ?: BackgroundOption.Transparent

            if (currentSuccess != null) {
                _uiState.value = currentSuccess.copy(isSwitchingMode = true)
            } else {
                val message = when (mode) {
                    SegmentationMode.FAST -> "Оптимізація країв Guided Filter..."
                    SegmentationMode.STUDIO -> "Студійна нейросегментація RMBG-1.4..."
                    SegmentationMode.ULTRA -> "Ультра-прецизійна сегментація BiRefNet..."
                    SegmentationMode.ANIME -> "Аніме-сегментація IS-Net..."
                }
                _uiState.value = MainUiState.Processing(message)
            }

            val result = segmentUseCase(bitmap, mode)
            result.onSuccess { segResult ->
                segmentationCache[mode] = segResult

                _uiState.value = MainUiState.Success(
                    originalBitmap = segResult.originalBitmap,
                    foregroundCutout = segResult.foregroundCutout,
                    backgroundOption = prevOption,
                    processingTimeMs = segResult.processingTimeMs,
                    isSwitchingMode = false
                )
            }.onFailure { error ->
                if (currentSuccess != null) {
                    _uiState.value = currentSuccess.copy(
                        isSwitchingMode = false,
                        userMessage = error.localizedMessage ?: "Помилка при виконанні сегментації."
                    )
                } else {
                    _uiState.value = MainUiState.Error(
                        error.localizedMessage ?: "Помилка при виконанні сегментації."
                    )
                }
            }
        }
    }

    fun selectBackground(backgroundOption: BackgroundOption) {
        val currentState = _uiState.value as? MainUiState.Success ?: return
        if (currentState.backgroundOption == backgroundOption) return

        // Instant 0ms background switch without heavy CPU compositing or extra bitmap allocations
        _uiState.update { state ->
            if (state is MainUiState.Success) {
                state.copy(backgroundOption = backgroundOption)
            } else state
        }
    }

    fun setCustomBackgroundUri(context: Context, bgUri: Uri) {
        val currentState = _uiState.value as? MainUiState.Success ?: return

        viewModelScope.launch {
            val bgBitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapUtils.loadBitmapFromUri(context, bgUri)
                } catch (e: Exception) {
                    null
                }
            }

            if (bgBitmap != null) {
                selectBackground(BackgroundOption.Image(bgBitmap))
            } else {
                _uiState.update {
                    currentState.copy(userMessage = "Не вдалося завантажити фонове зображення.")
                }
            }
        }
    }

    fun saveToGallery(context: Context) {
        val currentState = _uiState.value as? MainUiState.Success ?: return
        if (currentState.isSaving) return

        _uiState.update { currentState.copy(isSaving = true) }

        viewModelScope.launch {
            val saveResult = withContext(Dispatchers.IO) {
                val composite = BitmapUtils.compositeWithBackground(
                    currentState.foregroundCutout,
                    currentState.backgroundOption
                )
                val result = BitmapUtils.saveBitmapToGallery(context, composite)
                if (composite != currentState.foregroundCutout) {
                    composite.recycle()
                }
                result
            }

            _uiState.update {
                currentState.copy(
                    isSaving = false,
                    userMessage = if (saveResult.isSuccess) {
                        "Зображення збережено в папку Галереї CleanCut"
                    } else {
                        "Помилка збереження: ${saveResult.exceptionOrNull()?.localizedMessage}"
                    }
                )
            }
        }
    }

    fun shareImage(context: Context) {
        val currentState = _uiState.value as? MainUiState.Success ?: return

        viewModelScope.launch {
            val shareUri = withContext(Dispatchers.IO) {
                val composite = BitmapUtils.compositeWithBackground(
                    currentState.foregroundCutout,
                    currentState.backgroundOption
                )
                val uri = BitmapUtils.saveBitmapForSharing(context, composite)
                if (composite != currentState.foregroundCutout) {
                    composite.recycle()
                }
                uri
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(sendIntent, "Поділитися зображенням")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    fun openMaskEditor() {
        if (_uiState.value is MainUiState.Success) {
            _isEditingMask.value = true
        }
    }

    fun closeMaskEditor() {
        _isEditingMask.value = false
    }

    fun applyRefinedMask(refinedCutout: Bitmap) {
        val currentSuccess = _uiState.value as? MainUiState.Success ?: return
        val currentMode = _segmentationMode.value

        _uiState.value = currentSuccess.copy(
            foregroundCutout = refinedCutout,
            userMessage = "Маску успішно відкориговано"
        )

        val cached = segmentationCache[currentMode]
        if (cached != null) {
            segmentationCache[currentMode] = cached.copy(foregroundCutout = refinedCutout)
        }

        _isEditingMask.value = false
    }

    fun reset() {
        segmentationCache.clear()
        currentInputBitmap = null
        _isEditingMask.value = false
        _uiState.value = MainUiState.Idle
    }

    fun clearMessage() {
        val currentState = _uiState.value as? MainUiState.Success ?: return
        _uiState.update { currentState.copy(userMessage = null) }
    }
}
