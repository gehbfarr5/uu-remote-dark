# Research record

## Local target profile

- Package: `com.netease.uuremote`
- Observed version: `4.35.0` / `versionCode 435000`
- `minSdk 26`, `targetSdk 35`
- UI stack: classic Android Views/Material plus Jetpack Compose 1.7.2
- The app declares `uiMode` in several Activity `configChanges` entries.
- UU's own night resources are sparse; dependency dark resources do not form a complete UU palette.

The target APK and decompiled sources were used only as local, ignored analysis inputs. They are not part of this repository.

## GitHub reuse decisions

- [DarQ Reborn](https://github.com/Arora-Sir/DarQ-Reborn): use only as a Force Dark coverage control. Its FAQ documents restart requirements and algorithmic color/image limitations, so it is not the product architecture.
- [AmznKiller](https://github.com/hxreborn/amznkiller): reuse the idea of package-scoped modern libxposed hooks, explicit mode parsing and preference listeners. The repository is GPL-3.0; its source is not copied.
- [SpotifyPlus](https://github.com/LeNerd46/SpotifyPlus): use as a comparable app-specific Compose palette and refresh reference. Its MIT license permits reuse with attribution, but this project keeps an independent implementation.
- [libxposed/api](https://github.com/libxposed/api): compile against the current 102 artifact while using the stable API 101 surface in the MVP. API 100 is not used; see [final-stable API PR #51](https://github.com/libxposed/api/pull/51).
- [libxposed/example](https://github.com/libxposed/example): packaging, static scope and Remote Preferences reference.
- [DexKit](https://github.com/LuckyPray/DexKit): semantic lookup for obfuscated UU methods, cached by target version only after a unique match is verified.

## Deliberately excluded

- Native/Skia/libhwui renderer hooks.
- Global `HardwareRenderer` or `View.setForceDarkAllowed` hooks.
- WebView CSS/JavaScript injection.
- Repacking or re-signing the UU APK.
- Uploading proprietary APKs, resources, screenshots or account data.
