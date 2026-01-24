# FLoader

A root Android application for managing Frida server on rooted devices.

## Features

- Download Frida server directly from GitHub releases
- Support for multiple Frida versions (17.x, 16.x, 15.x)
- Automatic architecture detection (arm64, arm, x86_64, x86)
- Binary name randomization for anti-detection
- USB and Remote connection modes
- Random port generation for remote mode
- Local version caching
- Dark theme UI

## Requirements

- Rooted Android device
- Android 5.0+ (API 21+)
- Magisk or similar root solution

## Usage

1. Launch the app
2. Choose binary naming option (random recommended for anti-detection)
3. Select Frida version from dropdown
4. Click "Check & Download Frida" to download
5. Click "Start Server" to run Frida server

## Connection Modes

**USB Mode**: Default mode for ADB connection
```
frida -U
```

**Remote Mode**: Network-based connection with custom port
```
frida -H <device_ip>:<port>
```

## Version Compatibility

- Android 11+ (API 30+): Frida 16.x recommended
- Android 10 and below: Latest version supported

## Anti-Detection

The app supports binary name randomization to avoid detection by apps that check for "frida-server" process. A random 5-character alphabetic name is generated on each app launch if enabled.

## Build

```
./gradlew assembleDebug
```

## License

MIT License
