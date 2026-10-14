cmake_minimum_required(VERSION 3.22.1)
project("snolc_android")

add_library(snolc_android SHARED
    snolc_jni.c
)

find_library(log-lib log)

target_link_libraries(snolc_android
    ${log-lib}
    dl
)
