package org.operatorfoundation.audiocoder

import android.content.Context
import android.content.Intent
import android.icu.text.SimpleDateFormat
import androidx.core.content.FileProvider
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Date
import java.util.Locale

class WSPRFileManager(private val context: Context)
{
    companion object
    {
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
    }

    /**
     * Saves WSPR Audio data as a WAV file.
     *
     * @param audioData Raw PCM audio data from WSPR encoder
     * @param callsign Callsign used in the WSPR message
     * @param gridSquare Grid square used in the WSPR message
     * @param power Power level used in the WSPR message
     * @return The saved wave file, or null if this process failed
     */
    fun saveWsprAsWav(
        audioData: ByteArray,
        callsign: String,
        gridSquare: String,
        power: Int
    ): File?
    {
        return try
        {
            // Create filename with timestamp and WSPR parameters
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "WSPR_${callsign}_${gridSquare}_${power}dBm_$timestamp.wav"

            // Create file in app's external files
            val file = File(context.getExternalFilesDir(null), filename)

            // Write a WAV file
            writeWavFile(file, audioData)

            Timber.i("WSPR WAV file saved: ${file.absolutePath}")
            file
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to save WSPR WAV file")
            null
        }
    }

    /**
     * Writes PCM audio data as a WAV file.
     *
     * @param file The File to save the audio data to
     * @audioData The audio data to save to the file
     */
    fun writeWavFile(file: File, audioData: ByteArray)
    {
        FileOutputStream(file).use { fos ->
            // Calculate sizes
            val audioDataSize = audioData.size
            val fileSize = 36 + audioDataSize

            // Create WAV header
            val header = ByteBuffer.allocate(44).apply {

                // RIFF Header
                put("RIFF".toByteArray())
                putInt(fileSize)
                put("WAV".toByteArray())

                // Format chunk
                put("fmt ".toByteArray())
                putInt(16) // PCM format chunk size
                putShort(1) // PCM format
                putShort(CHANNELS.toShort())
                putInt(WSPR_REQUIRED_SAMPLE_RATE)
                putInt(WSPR_REQUIRED_SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8) // Byte rate
                putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // Block align
                putShort(BITS_PER_SAMPLE.toShort())

                // Data chunk
                put("data".toByteArray())
                putInt(audioDataSize)
            }

            // Write header and audio data
            fos.write(header.array())
            fos.write(audioData)
        }
    }


    /**
     * Shares a WSPR WAV file using Android's share intent.
     *
     * @param file The file to share
     * @return The share intent, or null if the operation fails
     */
    fun shareWsprFile(file: File): Intent?
    {
        return try
        {
            // Create content URI using FileProvider
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "WSPR Signal - ${file.nameWithoutExtension}")
                putExtra(Intent.EXTRA_TEXT, "Generated WSPR signal file")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Intent.createChooser(shareIntent, "Share WSPR Signal")
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to create share intent for WSPR file")
            null
        }
    }

    /**
     * Gets a list of saved WSPR files.
     *
     * @return A list of saved WSPR files
     */
    fun getSavedWsprFiles(): List<File>
    {
        val directory = context.getExternalFilesDir(null) ?: return emptyList()

        return directory.listFiles { file ->
            file.name.startsWith("WSPR") && file.name.endsWith(".wav")
        }?.toList() ?: emptyList()
    }

    /**
     * Deletes a given WSPR file.
     *
     * @param file The file to delete
     */
    fun deleteWsprFile(file: File): Boolean
    {
        return try
        {
            val deleted = file.delete()

            if (deleted) {
                Timber.i("Deleted WSPR file: ${file.name}")
            }
            deleted
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to delete WSPR file: ${file.name}")
            false
        }
    }
}