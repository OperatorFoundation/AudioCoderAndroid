# AudioCoder Android Library

A standalone Android library for WSPR (Weak Signal Propagation Reporter) encoding and decoding, extracted from the [LoudBang](https://github.com/TheMetallists/LoudBang) application to provide WSPR functionality for other Android applications.

## Overview

AudioCoder provides a complete WSPR implementation for Android, including:
- **WSPR Signal Encoding**: Generate WSPR audio signals from callsign, grid locator, and power data
- **WSPR Signal Decoding**: Decode WSPR messages from audio recordings with intelligent buffering
- **Audio Processing**: Smart buffering and timing management for optimal WSPR decoding
- **File Management**: Save and share WSPR signals as WAV files
- **Band Plan Integration**: Complete WSPR frequency management with official band plans
- **Utility Functions**: Distance calculations, grid square conversions, and callsign hashing
- **Database Integration**: Store and manage decoded WSPR contacts

The library uses native C/C++ code with FFTW for high-performance signal processing, wrapped with a clean Java/Kotlin API.

## Installation

### Using JitPack

Add JitPack to your project's `settings.gradle`:

```gradle
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Add the dependency to your app's `build.gradle`:

```gradle
dependencies {
    implementation 'com.github.OperatorFoundation:AudioCoderAndroid:main-SNAPSHOT'
}
```

For specific versions, replace `main-SNAPSHOT` with a commit hash or release tag.

## Quick Start

### Using WSPRProcessor for Smart Audio Buffering

```kotlin
import org.operatorfoundation.audiocoder.WSPRProcessor
import org.operatorfoundation.audiocoder.WSPRBandplan

// Create processor with intelligent buffering
val processor = WSPRProcessor()

// Add audio samples as they arrive
val audioSamples = shortArrayOf(/* your 16-bit PCM samples at 12kHz */)
processor.addSamples(audioSamples)

// Check if ready for decode (120+ seconds of audio)
if (processor.isReadyForDecode()) {
    // Decode using 20m band (most popular)
    val messages = processor.decodeBufferedWSPR(
        WSPRBandplan.getDefaultFrequency(), // 14.0956 MHz
        false // USB mode
    )
    
    messages?.forEach { msg ->
        println("Decoded: ${msg.getMSG()}")
        println("SNR: ${msg.getSNR()} dB")
        println("Frequency: ${msg.getFREQ()} Hz")
    }
}

// Check buffer status
println("Buffer: ${processor.getBufferDurationSeconds()}s / ${processor.getRecommendedBufferSeconds()}s")
```

### WSPR Band Plan Management

```kotlin
import org.operatorfoundation.audiocoder.WSPRBandplan

// Get all available WSPR frequencies
val allBands = WSPRBandplan.ALL_BANDS
val popularBands = WSPRBandplan.getPopularBands() // Most active frequencies

// Use specific frequencies
val freq20m = WSPRBandplan.ALL_BANDS.find { it.name == "20m" }?.dialFrequencyMHz
val freq40m = WSPRBandplan.ALL_BANDS.find { it.name == "40m" }?.dialFrequencyMHz

// For UI - create frequency picker
val frequencyMap = WSPRBandplan.getFrequencyMap()
// Returns: {"20m" -> 14.0956, "40m" -> 7.0386, ...}

// Validate frequencies
if (WSPRBandplan.isValidWSPRFrequency(14.0956)) {
    // Process this frequency
}
```

### File Management and Sharing

```kotlin
import org.operatorfoundation.audiocoder.WSPRFileManager

val fileManager = WSPRFileManager(context)

// Save WSPR signal as WAV file
val audioData = CJarInterface.WSPREncodeToPCM("Q0QQQ", "FN20", 30, 0, false)
val savedFile = fileManager.saveWsprAsWav(
    audioData,
    "Q0QQQ",      // Callsign
    "FN20",      // Grid square  
    30           // Power (dBm)
)

// Share the file
savedFile?.let { file ->
    val shareIntent = fileManager.shareWsprFile(file)
    shareIntent?.let { intent ->
        context.startActivity(intent)
    }
}

// Manage saved files
val savedFiles = fileManager.getSavedWsprFiles()
savedFiles.forEach { file ->
    println("Saved: ${file.name}")
    // fileManager.deleteWsprFile(file) // Delete if needed
}
```

### Basic WSPR Encoding (Low-level API)

```java
import org.operatorfoundation.audiocoder.CJarInterface;

// Generate WSPR signal
byte[] audioData = CJarInterface.WSPREncodeToPCM(
    "Q0QQQ",      // Callsign
    "FN20",      // Grid locator  
    30,          // Power (dBm)
    0,           // Frequency offset (Hz)
    false        // LSB mode
);

// audioData contains 16-bit PCM samples at 12kHz sample rate
// Ready for audio playback or file output
```

### Basic WSPR Decoding (Low-level API)

```java
import org.operatorfoundation.audiocoder.WSPRMessage;

// Decode WSPR from audio data
WSPRMessage[] messages = CJarInterface.WSPRDecodeFromPcm(
    audioData,   // PCM audio data
    14.097,      // Dial frequency (MHz)
    false        // LSB mode
);

// Process decoded messages
for (WSPRMessage msg : messages) {
    System.out.println("Callsign: " + msg.call);
    System.out.println("Grid: " + msg.loc);
    System.out.println("Power: " + msg.power + " dBm");
    System.out.println("SNR: " + msg.getSNR() + " dB");
    System.out.println("Frequency: " + msg.getFREQ() + " Hz");
}
```

## API Reference

### High-Level Kotlin API

#### `WSPRProcessor` - Smart Audio Processing
```kotlin
class WSPRProcessor {
    // Audio buffer management
    fun addSamples(samples: ShortArray)
    fun isReadyForDecode(): Boolean
    fun getBufferDurationSeconds(): Float
    fun clearBuffer()
    
    // WSPR decoding with intelligent buffering
    fun decodeBufferedWSPR(
        dialFrequencyMHz: Double = WSPRBandplan.getDefaultFrequency(),
        useLowerSideband: Boolean = false
    ): Array<WSPRMessage>?
    
    // Timing information
    fun getRecommendedBufferSeconds(): Float        // 180 seconds
    fun getMinimumBufferSeconds(): Float           // 120 seconds  
    fun getWSPRTransmissionSeconds(): Float        // ~110.6 seconds
}
```

#### `WSPRBandplan` - Frequency Management
```kotlin
object WSPRBandplan {
    val ALL_BANDS: List<WSPRBand>                  // All official WSPR frequencies
    
    fun getPopularBands(): List<WSPRBand>          // Most active bands
    fun getBandsForRegion(region: WSPRRegion): List<WSPRBand>
    fun getFrequencyMap(): Map<String, Double>     // For UI dropdowns
    fun findBandByFrequency(frequencyMHz: Double): WSPRBand?
    fun getDefaultFrequency(): Double              // 20m band (14.0956 MHz)
    fun isValidWSPRFrequency(frequencyMHz: Double): Boolean
}

data class WSPRBand(
    val name: String,                              // "20m", "40m", etc.
    val dialFrequencyMHz: Double,                  // Exact frequency
    val wavelengthMeters: String,                  // "20m", "40m", etc.
    val region: WSPRRegion,                        // GLOBAL, REGION_1, REGION_2
    val isPopular: Boolean                         // High activity bands
)
```

#### `WSPRFileManager` - File Operations
```kotlin
class WSPRFileManager(context: Context) {
    // Save and share WSPR files
    fun saveWsprAsWav(audioData: ByteArray, callsign: String, gridSquare: String, power: Int): File?
    fun shareWsprFile(file: File): Intent?
    
    // File management
    fun getSavedWsprFiles(): List<File>
    fun deleteWsprFile(file: File): Boolean
    
    // Low-level WAV operations
    fun writeWavFile(file: File, audioData: ByteArray)
}
```

### Low-Level JNI Interface: `CJarInterface`

#### Encoding
```java
public static native byte[] WSPREncodeToPCM(String callsign, String locator, int power, int offset, boolean lsb)
```
Generates WSPR audio signal as PCM data.

**Parameters:**
- `callsign`: Ham radio call sign (e.g., "W1AW")
- `locator`: Maidenhead grid square (e.g., "FN31")
- `power`: Transmit power in dBm (0-60)
- `offset`: Frequency offset in Hz
- `lsb`: Use LSB mode (inverts symbol order)

**Returns:** 16-bit PCM audio data as byte array

#### Decoding
```java
public static native WSPRMessage[] WSPRDecodeFromPcm(byte[] sound, double dialfreq, boolean lsb)
```
Decodes WSPR messages from audio data.

**Parameters:**
- `sound`: PCM audio data as byte array
- `dialfreq`: Radio dial frequency in MHz
- `lsb`: Signal uses LSB mode

**Returns:** Array of decoded `WSPRMessage` objects

#### Utility Functions
```java
public static native int WSPRNhash(String call)
public static native double WSPRGetDistanceBetweenLocators(String a, String b)  
public static native String WSPRLatLonToGSQ(double lat, double lon)
public static native int radioCheck(int testvar)
```

### Data Classes

#### `WSPRMessage`
```java
public class WSPRMessage {
    public float getSNR()      // Signal-to-noise ratio (dB)
    public double getFREQ()    // Frequency offset (Hz)
    public String getMSG()     // Complete decoded message
    public float getDT()       // Time offset (seconds)  
    public float getDRIFT()    // Frequency drift (Hz)
    
    // Additional fields
    public String call;        // Callsign
    public String loc;         // Grid locator
    public int power;          // Power level (dBm)
}
```

## Technical Specifications

- **Audio Format**: 16-bit PCM, 12 kHz sample rate
- **WSPR Mode**: WSPR-2 (2-minute transmission periods)
- **Frequency**: 1500 Hz center frequency with ±200 Hz range
- **Signal Processing**: Native C/C++ with FFTW for performance
- **Android Support**: API 26+ (Android 8.0+)
- **Architectures**: armeabi-v7a, arm64-v8a, x86, x86_64

## Examples

### Real-time WSPR Processing

```kotlin
// Set up processor for real-time audio
val processor = WSPRProcessor()

// In your audio capture callback
fun onAudioData(audioSamples: ShortArray) {
    processor.addSamples(audioSamples)
    
    // Check if we have enough data
    if (processor.isReadyForDecode()) {
        // Try to decode on 20m band
        val messages = processor.decodeBufferedWSPR(
            WSPRBandplan.getDefaultFrequency()
        )
        
        messages?.forEach { msg ->
            // Process decoded WSPR message
            handleWSPRMessage(msg)
        }
        
        // Clear buffer for next decode cycle
        processor.clearBuffer()
    }
    
    // Show buffer status to user
    val progress = processor.getBufferDurationSeconds() / processor.getRecommendedBufferSeconds()
    updateBufferProgress(progress)
}
```

### Multi-band WSPR Monitoring

```kotlin
// Monitor multiple popular bands
val popularBands = WSPRBandplan.getPopularBands()

popularBands.forEach { band ->
    val messages = processor.decodeBufferedWSPR(
        band.dialFrequencyMHz,
        false // USB mode
    )
    
    messages?.forEach { msg ->
        println("${band.getDisplayName()}: ${msg.getMSG()}")
        
        // Calculate distance if we have grid squares
        if (msg.loc != null && myGridSquare != null) {
            val distance = CJarInterface.WSPRGetDistanceBetweenLocators(
                myGridSquare, msg.loc
            )
            println("Distance: ${distance.toInt()} km")
        }
    }
}
```

### Complete File Workflow

```kotlin
// Generate, save, and share a WSPR signal
fun generateAndShareWSPR(callsign: String, grid: String, power: Int) {
    // 1. Generate WSPR signal
    val audioData = CJarInterface.WSPREncodeToPCM(
        callsign, grid, power, 0, false
    )
    
    // 2. Save as WAV file with metadata
    val fileManager = WSPRFileManager(context)
    val savedFile = fileManager.saveWsprAsWav(audioData, callsign, grid, power)
    
    // 3. Share the file
    savedFile?.let { file ->
        val shareIntent = fileManager.shareWsprFile(file)
        shareIntent?.let { intent ->
            startActivity(Intent.createChooser(intent, "Share WSPR Signal"))
        }
        
        // 4. Log the file info
        println("Saved WSPR file: ${file.name} (${file.length()} bytes)")
    }
}
```

### Distance Calculation
### Grid Square and Distance Utilities

```java
// Calculate great circle distance between grid squares
double distance = CJarInterface.WSPRGetDistanceBetweenLocators("FN20", "JO65");
System.out.println("Distance: " + Math.round(distance) + " km");

// Convert latitude/longitude to Maidenhead grid square
String gridSquare = CJarInterface.WSPRLatLonToGSQ(40.7128, -74.0060); // NYC
System.out.println("Grid Square: " + gridSquare); // "FN30as"

// Hash callsign for database operations
int hash = CJarInterface.WSPRNhash("Q0QQQ");
System.out.println("Callsign hash: " + hash);
```

## Architecture

AudioCoder uses a layered architecture for optimal performance and ease of use:

### High-Level Kotlin API
- **WSPRProcessor**: Intelligent audio buffering and timing management
- **WSPRBandplan**: Complete WSPR frequency management with official band plans
- **WSPRFileManager**: File I/O operations with metadata support
- Clean, type-safe Kotlin interfaces with comprehensive documentation

### Low-Level JNI Interface
- **CJarInterface**: Direct access to native WSPR processing functions
- Minimal overhead between Java/Kotlin and native code
- Compatible with existing WSPR applications

### Native Signal Processing Engine
- **High-performance C/C++ implementation** using FFTW for FFT operations
- **Fano and Jelinek decoders** for robust message recovery
- **Complete WSPR protocol implementation** based on WSJT-X codebase
- **Multi-architecture support** (ARM, x86) with optimized builds

### Key Benefits
- **Performance**: Native signal processing with minimal overhead
- **Reliability**: Proven algorithms from the WSJT-X project
- **Flexibility**: Both high-level convenience APIs and low-level control
- **Compatibility**: Works with existing WSPR infrastructure and tools

## Requirements

- **Android API 26+** (Android 8.0+)
- **NDK support** for native libraries
- **Approximately 10-15MB** additional APK size (includes FFTW and signal processing libraries)
- **Audio permissions** for real-time processing (if using microphone input)
- **Storage permissions** for file operations (if saving/sharing WAV files)

### Recommended Usage Patterns

- **Real-time decoding**: Use WSPRProcessor for live audio processing
- **File-based processing**: Use CJarInterface directly for batch processing
- **UI applications**: Use WSPRBandplan for frequency selection interfaces
- **File sharing**: Use WSPRFileManager for saving and sharing WSPR signals

## License

This library is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Attribution

This library is a fork of the [LoudBang](https://github.com/TheMetallists/LoudBang) Android application, extracted and modified to serve as a standalone WSPR library. We gratefully acknowledge the original authors' work.

The WSPR protocol implementation is based on code by Joe Taylor (K1JT) and Steven Franke (K9AN) from the WSJT-X project.

## Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

## Support

- **Issues**: [GitHub Issues](https://github.com/OperatorFoundation/AudioCoderAndroid/issues)
- **Documentation**: This README and inline code documentation
- **Community**: WSPR and amateur radio communities

## Changelog

### Pre-release Development
- Initial library extraction from LoudBang
- JitPack publishing configuration
- Native library optimization
- API documentation

---

**Note**: This library is currently in pre-release development. APIs may change before the first stable release.