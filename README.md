# AudioCoder Android Library

A standalone Android library for WSPR (Weak Signal Propagation Reporter) encoding and decoding, extracted from the [LoudBang](https://github.com/TheMetallists/LoudBang) application to provide WSPR functionality for other Android applications.

## Overview

AudioCoder provides a complete WSPR implementation for Android, including:
- **WSPR Signal Encoding**: Generate WSPR audio signals from callsign, grid locator, and power data
- **WSPR Signal Decoding**: Decode WSPR messages from audio recordings
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

### Basic WSPR Encoding

```java
import org.operatorfoundation.audiocoder.CJarInterface;

// Generate WSPR signal
byte[] audioData = CJarInterface.WSPREncodeToPCM(
    "K1JT",      // Callsign
    "FN20",      // Grid locator  
    30,          // Power (dBm)
    0,           // Frequency offset (Hz)
    false        // LSB mode
);

// audioData contains 16-bit PCM samples at 12kHz sample rate
// Ready for audio playback or file output
```

### Basic WSPR Decoding

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

### Core Interface: `CJarInterface`

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

#### Database Classes
- `DBHelper`: SQLite database management for WSPR contacts
- `WSPRNetSender`: Upload decoded spots to WSPRNet
- `Bandplan`: Ham radio band plan definitions

## Technical Specifications

- **Audio Format**: 16-bit PCM, 12 kHz sample rate
- **WSPR Mode**: WSPR-2 (2-minute transmission periods)
- **Frequency**: 1500 Hz center frequency with ±200 Hz range
- **Signal Processing**: Native C/C++ with FFTW for performance
- **Android Support**: API 26+ (Android 8.0+)
- **Architectures**: armeabi-v7a, arm64-v8a, x86, x86_64

## Examples

### Distance Calculation
```java
// Calculate great circle distance between grid squares
double distance = CJarInterface.WSPRGetDistanceBetweenLocators("FN20", "JO65");
System.out.println("Distance: " + Math.round(distance) + " km");
```

### Grid Square Conversion
```java
// Convert latitude/longitude to Maidenhead grid square
String gridSquare = CJarInterface.WSPRLatLonToGSQ(40.7128, -74.0060); // NYC
System.out.println("Grid Square: " + gridSquare); // "FN30as"
```

### Database Integration
```java
// Store decoded messages in local database
DBHelper dbHelper = new DBHelper(context);
// Database operations for managing WSPR contacts
```

## Requirements

- Android API 26+ (Android 8.0+)
- NDK support for native libraries
- Approximately 10MB additional APK size

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
