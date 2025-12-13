#include "../include/commands.h"
#include "../include/network.h"
#include "../include/room.h"
#include "../include/client.h"
#include <string.h>
#include <pthread.h>

extern pthread_mutex_t global_lock;

void handle_hello(client_t *c, char *args) {
    char *nick = strtok(args, " ");
    if (!nick) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_nick");
        return;
    }
    if (find_client_by_name(nick) != NULL) {
        send_line(c->fd, "ERR 107 NICKNAME_TAKEN");
        return;
    }

    strncpy(c->nick, nick, NICK_MAX);
    c->nick[NICK_MAX] = '\0';
    gen_token(c->token);
    c->state = ST_AUTH;
    send_line(c->fd, "WELCOME %s", c->token);
}

void handle_list(const client_t *c) {
    if (c->state != ST_AUTH) {
        send_line(c->fd, "ERR 101 INVALID_STATE not_auth");
        return;
    }
    send_room_list(c->fd);
}

void handle_create(client_t *c, char *args) {
    if (c->state != ST_AUTH) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
        return;
    }
    char *rname = strtok(args, " ");
    if (!rname) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_name");
        return;
    }
    int rid = create_room(rname);
    if (rid < 0) {
        send_line(c->fd, "ERR 200 SERVER_FULL");
        return;
    }
    char buf[LINE_BUF];
    snprintf(buf, LINE_BUF, "ROOM_CREATED %d", rid);
    send_broadcast_about_new_room(buf);
}

void handle_join(client_t *c, char *args) {
    if (c->state != ST_AUTH) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
        return;
    }
    char *idstr = strtok(args, " ");
    if (!idstr) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_id");
        return;
    }
    int rid = atoi(idstr);

    room_t *r = find_room_by_id(rid);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        return;
    }

    if (r->state != RM_OPEN) {
        send_line(c->fd, "ERR 106 ROOM_WRONG_STATE");
        return;
    }

    add_player_to_room(c, r);

    if (r->player_count == 2) {
        client_t *other = (r->player1 == c) ? r->player2 : r->player1;
        send_line(other->fd, "PLAYER_JOINED %s", c->nick);
    }
}

void handle_ready(client_t *c) {
    c->state = ST_READY;
    send_line(c->fd, "OK you_are_ready");

    room_t *r = find_room_by_id(c->room_id);
    if (!r || r->player_count == 1) return;

    client_t *opponent = (r->player1 == c) ? r->player2 : r->player1;

    send_line(opponent->fd, "PLAYER_READY %s", c->nick);

    if (opponent->state == ST_READY) {
        // Оба игрока готовы, начинаем игру
        send_line(r->player1->fd, "GAME_START");
        send_line(r->player2->fd, "GAME_START");
        c->state = ST_PLAYING;
        opponent->state = ST_PLAYING;
        r->state = RM_PLAYING;

        // Инициализируем игру
        r->round_number = 0;
        r->score_p1 = 0;
        r->score_p2 = 0;

        start_next_round(r);  // запускаем первый раунд
    }
}

void handle_leave(client_t *c) {
    room_t *r = find_room_by_id(c->room_id);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        return;
    }

    if (r->state != RM_FULL && r->state != RM_OPEN) {
        send_line(c->fd, "ERR 101 INVALID_STATE cannot_leave_now");
        return;
    }

    if (c->state != ST_IN_LOBBY && c->state != ST_READY) {
        send_line(c->fd, "ERR 101 INVALID_STATE cannot_leave_now");
        return;
    }

    remove_player_from_room(c, r);
    send_line(c->fd, "LEFT_ROOM %d", r->id);
}

void handle_move(client_t *c, char *args) {
    if (c->state != ST_PLAYING) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
        return;
    }

    room_t *r = find_room_by_id(c->room_id);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        return;
    }

    // Проверяем состояние комнаты
    if (r->state != RM_PLAYING) {
        send_line(c->fd, "ERR 101 INVALID_STATE room_not_playing");
        return;
    }

    if (!r->awaiting_moves) {
        send_line(c->fd, "ERR 101 INVALID_STATE not_accepting_moves");
        return;
    }

    char *move = strtok(args, " ");
    if (!move || (move[0] != 'R' && move[0] != 'P' && move[0] != 'S')) {
        send_line(c->fd, "ERR 100 BAD_FORMAT invalid_move");
        return;
    }

    // Сохраняем ход
    if (r->player1 == c) {
        if (r->move_p1 != '\0') {
            send_line(c->fd, "ERR 101 INVALID_STATE move_already_sent");
            return;
        }
        r->move_p1 = move[0];
    } else {
        if (r->move_p2 != '\0') {
            send_line(c->fd, "ERR 101 INVALID_STATE move_already_sent");
            return;
        }
        r->move_p2 = move[0];
    }

    send_line(c->fd, "MOVE_ACCEPTED");

    // Проверяем, получены ли оба хода
    if (r->move_p1 != '\0' && r->move_p2 != '\0') {
        fprintf(stderr, "Both moves received, processing round\n");
        r->awaiting_moves = 0;
        process_round_result(r);
    } else {
        fprintf(stderr, "Waiting for opponent move: p1=%c p2=%c\n",
               r->move_p1 ? r->move_p1 : '-',
               r->move_p2 ? r->move_p2 : '-');
    }
}

void handle_get_opponent(client_t *c) {
    if (c->state != ST_IN_LOBBY && c->state != ST_READY) {
        send_line(c->fd, "ERR 101 INVALID_STATE not_in_lobby");
        return;
    }

    room_t *r = find_room_by_id(c->room_id);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        return;
    }

    if (r->player_count == 1) {
        send_line(c->fd, "OPPONENT_INFO NONE");
        return;
    }

    client_t *opponent = (r->player1 == c) ? r->player2 : r->player1;
    const char *status = (opponent->state == ST_READY) ? "READY" : "NOT_READY";
    send_line(c->fd, "OPPONENT_INFO %s %s", opponent->nick, status);
}

void handle_reconnect(client_t *c, char* args) {
    char *token = strtok(args, " ");
    if (!token) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_token");
        return;
    }

    client_t *old_client = find_client_by_token(token);
    if (!old_client) {
        send_line(c->fd, "ERR XXX INVALID_TOKEN");
        return;
    }

    // Копируем данные старого клиента
    strncpy(c->nick, old_client->nick, NICK_MAX);
    c->nick[NICK_MAX] = '\0';
    strncpy(c->token, old_client->token, TOKEN_LEN);
    c->token[TOKEN_LEN - 1] = '\0';
    c->state = old_client->state;
    c->room_id = old_client->room_id;
    c->timeout_state = CONNECTED;
    c->last_seen = time(NULL);

    // Обновляем указатели в комнате
    if (c->room_id != -1) {
        room_t *r = find_room_by_id(c->room_id);
        if (r) {
            if (r->player1 == old_client) {
                r->player1 = c;
            } else if (r->player2 == old_client) {
                r->player2 = c;
            }

            // Уведомляем оппонента
            client_t *opponent = (r->player1 == c) ? r->player2 : r->player1;
            if (opponent) {
                send_line(opponent->fd, "PLAYER_RECONNECTED %s", c->nick);
            }

            // Отправляем информацию о состоянии
            if (c->state == ST_PLAYING && (r->state == RM_PLAYING || r->state == RM_PAUSED)) {
                //Получаем информацию, был ли совершен ход до отключения
                char performed_move = (r->player1 == c) ? r->move_p1 : r->move_p2;
                send_line(c->fd, "RECONNECT_OK GAME %d %d %d %d %c",
                         r->score_p1, r->score_p2, r->round_number, performed_move);

                // Возобновляем игру если была пауза
                if (r->state == RM_PAUSED) {
                    r->state = RM_PLAYING;
                    r->round_start_time = time(NULL);

                    if (opponent) {
                        send_line(opponent->fd, "GAME_RESUMED %d %d %d",
                                 r->round_number, r->score_p1, r->score_p2);
                    }
                    send_line(c->fd, "GAME_RESUMED %d %d %d",
                             r->round_number, r->score_p1, r->score_p2);

                    fprintf(stderr, "Game resumed in room %d\n", r->id);

                    // ВАЖНО: Сохраняем состояние awaiting_moves при реконнекте
                    // Если оба хода уже были сделаны до дисконнекта
                    if (r->move_p1 != '\0' && r->move_p2 != '\0') {
                        fprintf(stderr, "Both moves present, processing round immediately\n");
                        r->awaiting_moves = 0;
                        process_round_result(r);
                    } else {
                        // Ходов еще нет или есть только один - ждем
                        r->awaiting_moves = 1;
                        fprintf(stderr, "Waiting for moves: p1=%c p2=%c\n",
                               r->move_p1 ? r->move_p1 : '-',
                               r->move_p2 ? r->move_p2 : '-');
                    }
                }
            } else if (c->state == ST_IN_LOBBY || c->state == ST_READY) {
                if (opponent) {
                    send_line(c->fd, "RECONNECT_OK LOBBY %s %s", opponent->nick,
                             (opponent->state == ST_READY) ? "READY" : "NOT_READY");
                } else {
                    send_line(c->fd, "RECONNECT_OK LOBBY");
                }
            } else {
                send_line(c->fd, "RECONNECT_OK CONNECTED");
            }
        } else {
            send_line(c->fd, "RECONNECT_OK CONNECTED");
        }
    } else {
        send_line(c->fd, "RECONNECT_OK CONNECTED");
    }

    // Закрываем старое соединение
    close(old_client->fd);
    unregister_client_without_lock(old_client);
    free(old_client);
}

void handle_line(client_t *c, char *line) {
    trim_crlf(line);
    if (strlen(line) == 0) return;

    char *cmd = strtok(line, " ");
    if (!cmd) return;

    char *args = strtok(NULL, "");

    if (strcmp(line, "PONG") != 0) {
        printf("Recieved from client %s: %s\n", c->nick, line);
    }

    pthread_mutex_lock(&global_lock);
    c->last_seen = time(NULL);
    pthread_mutex_unlock(&global_lock);

    if (strcmp(cmd, "HELLO") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_hello(c, args);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "LIST") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_list(c);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "CREATE") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_create(c, args);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "JOIN") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_join(c, args);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "READY") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_ready(c);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "LEAVE") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_leave(c);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "MOVE") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_move(c, args);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "GET_OPPONENT") == 0) {
        pthread_mutex_lock(&global_lock);
        handle_get_opponent(c);
        pthread_mutex_unlock(&global_lock);
    } else if (strcmp(cmd, "QUIT") == 0) {
        send_line(c->fd, "OK bye");
    } else if (strcmp(cmd, "PONG") == 0) {
        return;
    } else if (strcmp(cmd, "RECONNECT") == 0){
        pthread_mutex_lock(&global_lock);
        handle_reconnect(c, args);
        pthread_mutex_unlock(&global_lock);
    } else {
        send_line(c->fd, "ERR 100 BAD_FORMAT unknown_command");
    }
}
