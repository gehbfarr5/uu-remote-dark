# Compatibility matrix

| Target | Status | Notes |
|---|---|---|
| UU Remote 4.35.0 (`435000`) | Implemented, real-device verification pending | Exact base APK and signing certificate are checked; early resource, View/Compose, ActivityThread scan and settings-entry paths are implemented |
| Other UU versions | Refused by gate | Add a reviewed adapter before enabling hooks |
| Android 16 / API 36 | Development baseline | Compile with SDK 37; use local `PLK110_API_36` emulator for ordinary app/package checks |
| OnePlus 15 / ColorOS 16 / Vector-SR | Final integration target | Required for LSPosed and OEM behavior |

Each adapter must eventually record:

- package name;
- long version code and version name;
- base APK SHA-256 and signing certificate digest;
- unique theme/palette hook anchors;
- settings injection anchor;
- native/rendering exclusions;
- screenshot and lifecycle evidence.

An unknown or ambiguous adapter fails closed and leaves the original UU UI unchanged.
