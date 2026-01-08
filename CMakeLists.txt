cmake_minimum_required(VERSION 4.0)
project(rps-09 C)

set(CMAKE_C_STANDARD 11)

add_executable(rps-09
        server/src/server.c
        server/include/server.h
        server/src/client.c
        server/src/commands.c
        server/src/send_line.c
        server/src/room.c
        server/src/game.c
        server/include/game.h)
