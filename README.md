## VSCodeTermux
A development environment for coding and building apps on android, built with a _termux_ terminal view and _code-server_, running in a secure webview, with platform tweaks and optional android build tools.

### NOTES
#### Targets armeabi-v7a (32bit / 64bit)

Build on android with `sh ./dev/build.sh debug|release` and not `./dev/build.sh` directly as /sdcard isn't granted executable permission. 

The build will generate a self signed certificate if not already in /assets/tls
