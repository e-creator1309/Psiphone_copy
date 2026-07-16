# Only arm64-v8a — user has Android 15, no need for old ABI bloat
APP_ABI := arm64-v8a
APP_PLATFORM := android-21

# Max speed optimization flags
APP_CFLAGS := -O3 -ffast-math -fomit-frame-pointer -funroll-loops
APP_CPPFLAGS := -O3 -ffast-math -fomit-frame-pointer -funroll-loops
APP_OPTIM := release
