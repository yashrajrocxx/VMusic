/**
 * Music Recognition Feature
 * 
 * This feature is based on the original MusicRecognizer project by Aleksey Saenko.
 * Original project: https://github.com/aleksey-saenko/MusicRecognizer
 * 
 * Special thanks to Aleksey Saenko for the music recognition implementation.
 */

package app.pulse.android.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.pulse.android.shazamkit.Shazam
import app.pulse.android.shazamkit.models.RecognitionResult
import app.pulse.android.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Service for recognizing music using audio fingerprinting.
 * Records audio from the microphone, generates a Shazam-compatible fingerprint,
 * and sends it to the Shazam API for recognition.
 */
object MusicRecognitionService {
    
    // Recording parameters
    private const val PREFERRED_SAMPLE_RATE = 16000
    private const val FALLBACK_SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    // Fast, responsive recording duration: 7.5 seconds is optimal for Shazam peak generation
    private const val RECORDING_DURATION_MS = 7500L
    private const val TAG = "MusicRecognitionService"

    private val _recognitionStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val recognitionStatus: StateFlow<RecognitionStatus> = _recognitionStatus.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Set to true by the widget service after it has already persisted the result to the
     * database, so that [RecognitionScreen] skips the duplicate insert.
     * Reset to false by [reset].
     */
    var resultSavedExternally: Boolean = false
    
    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start the music recognition process.
     * Records audio, generates fingerprint, and queries Shazam API.
     */
    @SuppressLint("MissingPermission")
    suspend fun recognize(context: Context): RecognitionStatus = withContext(Dispatchers.IO) {
        if (!hasRecordPermission(context)) {
            Timber.tag(TAG).w("Microphone permission not granted, aborting recognition")
            return@withContext RecognitionStatus.Error("Microphone permission not granted")
        }

        _recognitionStatus.value = RecognitionStatus.Listening
        Timber.tag(TAG).d("Starting music recognition")
        
        try {
            // Step 1: Record audio (prefers 16kHz directly to avoid interpolation distortion)
            val recorded = recordAudio()
            Timber.tag(TAG).d("Audio recorded: %d bytes at %dHz", recorded.bytes.size, recorded.sampleRate)

            _recognitionStatus.value = RecognitionStatus.Processing
            
            // Step 2: Ensure 16kHz mono PCM
            val pcmData = if (recorded.sampleRate == VibraSignature.REQUIRED_SAMPLE_RATE) {
                recorded.bytes
            } else {
                Timber.tag(TAG).d("Resampling audio from %dHz to %dHz", recorded.sampleRate, VibraSignature.REQUIRED_SAMPLE_RATE)
                val decodedAudio = DecodedAudio(
                    data = recorded.bytes,
                    channelCount = 1,
                    sampleRate = recorded.sampleRate,
                    pcmEncoding = AUDIO_FORMAT
                )
                val resampled = AudioResampler.resample(
                    decodedAudio,
                    VibraSignature.REQUIRED_SAMPLE_RATE
                ).getOrElse { error ->
                    Timber.tag(TAG).e(error, "Audio resampling failed")
                    _recognitionStatus.value = RecognitionStatus.Error("Failed to resample audio: ${error.message}")
                    return@withContext _recognitionStatus.value
                }
                resampled.data
            }
            
            // Verify format
            require(
                pcmData.isNotEmpty() && 
                pcmData.size % 2 == 0
            ) { "Invalid audio format for fingerprint generation" }
            
            // Step 3: Generate fingerprint
            val signature = try {
                VibraSignature.fromI16(pcmData)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Fingerprint generation failed")
                _recognitionStatus.value = RecognitionStatus.Error("Failed to generate fingerprint: ${e.message}")
                return@withContext _recognitionStatus.value
            }
            
            // Step 4: Send to Shazam API
            val sampleDurationMs = (pcmData.size / 2) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE
            Timber.tag(TAG).d("Fingerprint generated, sampleDurationMs=%d", sampleDurationMs)
            
            val result = Shazam.recognize(signature, sampleDurationMs)
            
            result.fold(
                onSuccess = { recognitionResult ->
                    Timber.tag(TAG).i("Recognition successful: '%s' by %s", recognitionResult.title, recognitionResult.artist)
                    _recognitionStatus.value = RecognitionStatus.Success(recognitionResult)
                },
                onFailure = { error ->
                    val message = error.message ?: "Unknown error"
                    Timber.tag(TAG).w(error, "Recognition API returned failure: %s", message)
                    _recognitionStatus.value = if (message.contains("No match", ignoreCase = true)) {
                        RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
                    } else {
                        RecognitionStatus.Error(message)
                    }
                }
            )
            
            _recognitionStatus.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Recognition failed with exception")
            _recognitionStatus.value = RecognitionStatus.Error(e.message ?: "Recognition failed")
            _recognitionStatus.value
        }
    }

    private data class RecordedAudioData(
        val bytes: ByteArray,
        val sampleRate: Int
    )
    
    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): RecordedAudioData = withContext(Dispatchers.IO) {
        val sampleRates = intArrayOf(PREFERRED_SAMPLE_RATE, FALLBACK_SAMPLE_RATE)
        var selectedRate = PREFERRED_SAMPLE_RATE
        var audioRecord: AudioRecord? = null
        var bufferSize = 0

        for (rate in sampleRates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf > 0) {
                val candidateBufSize = maxOf(minBuf * 4, 8192)
                try {
                    val candidateRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        rate,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        candidateBufSize
                    )
                    if (candidateRecord.state == AudioRecord.STATE_INITIALIZED) {
                        selectedRate = rate
                        bufferSize = candidateBufSize
                        audioRecord = candidateRecord
                        break
                    } else {
                        candidateRecord.release()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Could not initialize AudioRecord at %dHz", rate)
                }
            }
        }

        val record = audioRecord ?: throw IllegalStateException("AudioRecord initialization failed")
        Timber.tag(TAG).d("Recording audio at %dHz with buffer size %d", selectedRate, bufferSize)

        val outputStream = ByteArrayOutputStream()
        val readBuffer = ByteArray(bufferSize / 2)
        val startTime = System.currentTimeMillis()
        
        try {
            record.startRecording()
            Timber.tag(TAG).d("AudioRecord started, recording for %dms", RECORDING_DURATION_MS)

            while (System.currentTimeMillis() - startTime < RECORDING_DURATION_MS && isActive) {
                val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                if (bytesRead > 0) {
                    outputStream.write(readBuffer, 0, bytesRead)
                } else if (bytesRead < 0) {
                    Timber.tag(TAG).w("AudioRecord read error: %d", bytesRead)
                }
            }
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error stopping AudioRecord")
            }
            record.release()
        }

        val totalBytes = outputStream.size()
        Timber.tag(TAG).d("Audio recording complete: %d bytes collected at %dHz", totalBytes, selectedRate)
        RecordedAudioData(outputStream.toByteArray(), selectedRate)
    }
    
    fun startRecognition(scope: CoroutineScope, context: Context) {
        cancelRecognition()
        activeJob = scope.launch {
            recognize(context)
        }
    }

    fun cancelRecognition() {
        activeJob?.cancel()
        activeJob = null
        reset()
    }

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        Timber.tag(TAG).d("Recognition state reset")
        _recognitionStatus.value = RecognitionStatus.Ready
        resultSavedExternally = false
    }
}
