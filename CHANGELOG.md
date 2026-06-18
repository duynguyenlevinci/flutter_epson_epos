## 1.1.3

- [iOS] Return human-readable error descriptions for API failures (connect, discovery, sendData). Raw SDK code remains in `code`; `message` shows description only.

## 1.1.2

- Add unified `status_code: int` + `message: string` to print response.
  - New `EpsonStatusCode` constants exposed on Dart side; `EpsonEPOS.onPrint()` now returns a typed `EpsonPrinterResponse`.
  - [Android] All error paths in `onPrint`, `onPtrReceive`, `getPrinterSetting`, `setPrinterSetting` now populate `status_code` based on `PrinterStatusInfo` / `Epos2Exception`.
  - [iOS] All error paths in `onPrint`, `printData`, `connectPrinter`, `onPtrReceive` now populate `status_code` based on `Epos2PrinterStatusInfo` / Epos2 API result.
- Strip stray newlines from error messages so loggers (e.g. `logger`) no longer split them across multiple lines.
  - [Android] `getErrorMessage()` no longer appends `"\n"` by default; trim trailing whitespace; multi-segment messages joined by space.
  - [iOS] Sanitize messages from `ePOS2Localizable.strings` and `MessageHelper` before returning to Flutter.
- Backward compatible: existing `success`, `message`, `code`, `content` fields are preserved.

## 1.1.0

- Upgrade to Flutter 3.35.7 / Dart 3.9+.
- [Android] Bump Android Gradle Plugin to 8.7.0, Kotlin to 2.1.0, Gradle wrapper to 8.10.2.
- [Android] Raise `compileSdk` to 35 and `minSdk` to 21, modernize plugin Kotlin code.
- [Android] Example project migrated to declarative `plugins {}` block in `settings.gradle`.
- [iOS] Bump deployment target to iOS 13.0, podspec version to 1.1.0.
- [iOS] Modernize Swift code (Swift 5.0), fix `addCut`/`addTextStyle`/`addBarcode` edge cases.
- [iOS] Always send Flutter result after `onPtrReceive` (fixed bug where result could be dropped on successful disconnect).

## 0.0.2

- [Addroid] added USB Print. Special thanks @brasizza
## 0.0.1+7

- [Addroid] Added: Get error messages via Printer Status

## 0.0.1+6

- [Addroid] Added: setPrinterSetting
- [Addroid] Fixed: Disconnect Printer

## 0.0.1+5

- [Addroid] Fixed: Clear commands
## 0.0.1+4

- [Addroid] Fixed: Cannot connect when printing multiple times.
## 0.0.1+3

- Add commands: addLineSpace, addFeedLine, addCut, addTextAlign
## 0.0.1+2

- Typo
## 0.0.1+1

- Initial release.
