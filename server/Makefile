CC = gcc
CFLAGS = -std=c11 -Wall -Wextra -pthread -Iinclude -O2
SRCS = src/main.c src/server.c src/acceptor.c src/client.c src/room.c src/game.c src/protocol.c src/parser.c src/logger.c src/config.c src/utils.c src/janitor.c
OBJS = $(SRCS:.c=.o)
TARGET = server

all: $(TARGET)

$(TARGET): $(SRCS)
	$(CC) $(CFLAGS) $(SRCS) -o $(TARGET)

clean:
	rm -f $(TARGET) *.o src/*.o
