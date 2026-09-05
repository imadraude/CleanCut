package com.cleancut.bgremover.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleancut.bgremover.data.ml.MlKitSubjectSegmenter
import com.cleancut.bgremover.data.util.BackgroundOption
import com.cleancut.bgremover.data.util.BitmapUtils
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
        val compositeBitmap: Bitmap,
        val backgroundOption: BackgroundOption,
        val processingTimeMs: Long,
        val isSaving: Boolean = false,
        val userMessage: String? = null
    ) : MainUiState
    data class Error(val errorMessage: String) : MainUiState
}

class MainViewModel(
    private val segmentUseCase: SegmentImageUseCase = SegmentImageUseCase(MlKitSubjectSegmenter())
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun processImageUri(context: Context, uri: Uri) {
        viewModelScope.launch {
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

            _uiState.value = MainUiState.Processing("Аналіз та сегментація об'єктів...")

            val result = segmentUseCase(inputBitmap)
            result.onSuccess { segResult ->
                _uiState.value = MainUiState.Success(
                    originalBitmap = segResult.originalBitmap,
                    foregroundCutout = segResult.foregroundCutout,
                    compositeBitmap = segResult.foregroundCutout,
                    backgroundOption = BackgroundOption.Transparent,
                    processingTimeMs = segResult.processingTimeMs
                )
            }.onFailure { error ->
                _uiState.value = MainUiState.Error(
                    error.localizedMessage ?: "Помилка при виконанні сегментації."
                )
            }
        }
    }

    fun selectBackground(backgroundOption: BackgroundOption) {
        val currentState = _uiState.value as? MainUiState.Success ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val updatedComposite = BitmapUtils.compositeWithBackground(
                currentState.foregroundCutout,
                backgroundOption
            )
            _uiState.update {
                currentState.copy(
                    compositeBitmap = updatedComposite,
                    backgroundOption = backgroundOption
                )
            }
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
                BitmapUtils.saveBitmapToGallery(context, currentState.compositeBitmap)
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
                BitmapUtils.saveBitmapForSharing(context, currentState.compositeBitmap)
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

    fun reset() {
        _uiState.value = MainUiState.Idle
    }

    fun clearMessage() {
        val currentState = _uiState.value as? MainUiState.Success ?: return
        _uiState.update { currentState.copy(userMessage = null) }
    }
}
