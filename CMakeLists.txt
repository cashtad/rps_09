cmake_minimum_required(VERSION 4.0)
project(rps_09 C)

set(CMAKE_C_STANDARD 11)

add_executable(rps_09
        server/src/server.c
        server/include/server.h)
