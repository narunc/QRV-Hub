#APP_OPTIM := debug
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_STL := c++_static
APP_PLATFORM := android-21
# Record source paths relative to the project, not the build machine's folders
# (assert messages and debug info otherwise embed the absolute path).
APP_CFLAGS += -ffile-prefix-map=$(abspath $(NDK_PROJECT_PATH))=.
