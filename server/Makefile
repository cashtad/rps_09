CC = gcc
CFLAGS = -std=c11 -Wall -Wextra -pthread -Iinclude -O2
SRCS = src/client.c src/commands.c src/game.c src/network.c src/room.c src/server.c
OBJS = $(SRCS:.c=.o)
TARGET = server

all: $(TARGET)

$(TARGET): $(SRCS)
	$(CC) $(CFLAGS) $(SRCS) -o $(TARGET)

clean:
	rm -f $(TARGET) *.o src/*.o
