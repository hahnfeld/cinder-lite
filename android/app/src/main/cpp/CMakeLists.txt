# libtermux: the PTY / subprocess JNI half of Termux's terminal-emulator. termux.c is verbatim from
# termux-app v0.118.0 (terminal-emulator/src/main/jni/termux.c, Apache-2.0) — the same version as
# the terminal-emulator classes that load it.
#
# Built here instead of using the AAR's copy because the published libtermux.so is 4 KB
# page-aligned, and devices with 16 KB memory pages would need compatibility mode to load it.
cmake_minimum_required(VERSION 3.22.1)
project(termux C)
add_library(termux SHARED termux.c)
target_link_options(termux PRIVATE "-Wl,-z,max-page-size=16384")
