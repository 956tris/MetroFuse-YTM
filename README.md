# MetroFuse YouTube Music

An optional YouTube Music provider for MetroFuse plugin API 1.1.0. The native JAR and APK use InnerTubeX v0.11.7 and QuackJS v0.1.4 for search, catalog, metadata, Home, account authentication and audio, including SABR. Each activation owns its network client and account session.

Download compiled packages from [GitHub Releases](https://github.com/956tris/MetroFuse-YouTube-Music/releases). Import `youtube-music.jar` on desktop or `youtube-music.apk` on Android through MetroFuse's Plugins settings, enable it, and select YouTube Music as your music and Home source. All implementations use `metrofuse.youtube-music`, so install one implementation at a time.

The universal `youtube-music.mfp` provides sign-in, Home, catalog and direct audio in JavaScript. It supports a limited set of signature transforms. It does not implement SABR or the full YouTube `n` transform. Use the native package for InnerTubeX playback.

## Account sign-in

Open MetroFuse's profile menu and select Login. YouTube sign-in opens in an embedded WebView on Android or a private WebView2 window on Windows. MetroFuse captures the session after sign-in and the provider verifies the active YouTube account. App Settings remains available for every provider, including providers without login support.

Use a MetroFuse build containing the plugin WebView sign-in support. Enter your Google account details directly in Google's sign-in page. The host passes the captured session only to this plugin's current activation. A failed verification preserves the previous valid session.

The host stores this provider's accepted session with Windows DPAPI or Android Keystore encryption. Log out from the same profile menu to clear it. Linux and macOS encrypted session persistence is currently unavailable.

## Build from source

Use Java 25, Gradle 9.8.2, Kotlin 2.4.21 and the matching MetroFuse API and plugin SDK JARs. Export them with `:plugin-api:desktopJar :plugin-sdk:desktopJar` from the MetroFuse KMP source. The SDK's Windows browser build requires .NET SDK 10; set `-PdotnetExecutable` if it is outside PATH. Set `ANDROID_HOME` to an SDK containing platform `android-37.0` and build tools `37.0.0` for APK packaging.

```powershell
./gradlew.bat :native:desktopTest :native:packagePluginJar :native:packagePluginApk "-PmetrofuseApiJar=C:\path\to\plugin-api-desktop-1.1.0.jar" "-PmetrofuseSdkJar=C:\path\to\plugin-sdk-desktop-1.1.0.jar" --no-configuration-cache
python tools/package-mfp.py
node native/tests/javascript.mjs
```

Native outputs are in `native/build/outputs/plugins/`. The MFP is in `build/outputs/plugins/`. Compiled downloads and SHA256SUMS belong in Releases and are ignored by Git.

The native packages bundle InnerTubeX and QuackJS. The host supplies the MetroFuse API and plugin SDK, Kotlin, coroutines, serialization, Ktor and Compose. SDK classes stay on the compile and test classpaths and are excluded from plugin artifacts. The native plugin uses Ktor's platform engine discovery and does not reference desktop-only CIO classes in production Android code.

## Verification and limits

Provider checks cover catalog parsing, quality selection, direct and SABR descriptors, account verification, failed account replacement, logout and Home locale handling. JavaScript checks exercise production callbacks, authentication hashes, account state, Home, catalog, direct audio and supported and unsupported signature transforms.

The live native check on 14 November 2026 returned 15 search tracks, three Home shelves and 140,231 bytes of SABR media for Adele's Hello. This verifies public service access and audio delivery. It does not prove device playback or account authentication. Actual browser-account sign-in, personalized Home, Android device playback and the desktop audio output still need manual verification.

Live tests are opt-in and do not run in ordinary builds or CI:

```powershell
$env:METROFUSE_LIVE_YTM = "1"
./gradlew.bat :native:desktopTest --tests dev.metrofuse.youtube.YouTubeLiveTest "-PmetrofuseApiJar=C:\path\to\plugin-api-desktop-1.1.0.jar" "-PmetrofuseSdkJar=C:\path\to\plugin-sdk-desktop-1.1.0.jar"
```

Only the initial search and catalog browse pages are exposed. Pagination, account/channel switching, music videos and library mutation are not implemented. Home uses service-provided shelf titles and excludes shelves linked to `FEmusic_library`.

YTM owns its entire account screen, Google navigation rules, labels, verification and account session. The native plugin implements `NativeLoginUi` and uses `dev.metrofuse.sdk.WebViewLoginScreen`. MFP supplies the SDK screen through `host.login({request, ui, login, logout})`; `ui()` returns `{request, labels}`. The reusable Android WebView, desktop WebView and Windows helper live in `plugin-sdk`. MetroFuse hosts the contributed screen and stores accepted credentials through the generic plugin lifecycle. Its platform account menus contain no YTM browser implementation. Existing `LoginPlugin`, `LoginRequest` and legacy browser callbacks retain compatibility.

Use a host containing `plugin-sdk` and the SABR transport adapter. The SABR adapter reconstructs the session using host-owned InnerTubeX classes. Plugin-owned InnerTubeX objects never cross the plugin API.

## Attribution

Catalog parsing and initial provider integration were adapted from MetroList at commit `e97197786c2a7ad56279cc9bb4ce3233ef6c99ff`. This standalone implementation and its account, Home and transport work are maintained by 956tris. See [LICENSE](LICENSE), [native provenance](native/SOURCE) and [JavaScript provenance](universal/SOURCE).

Plugin API documentation is in [MetroFuse-Plugin-Docs](https://github.com/956tris/MetroFuse-Plugin-Docs).
