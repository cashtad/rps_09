#include "../include/commands.h"
#include "../include/network.h"
#include "../include/room.h"
#include "../include/client.h"
#include <string.h>
#include <pthread.h>
#include <sys/socket.h>

extern pthread_mutex_t global_lock;

void mark_invalid_message(client_t *c) {
    if (!c) return;
    c->invalid_msg_streak++;
    if (c->invalid_msg_streak >= MAX_INVALID_MSG_STREAK) {
        shutdown(c->fd, SHUT_RDWR);
    }
}

void mark_valid_message(client_t *c) {
    if (c) c->invalid_msg_streak = 0;
}

void handle_hello(client_t *c, char *args) {
    if (!args) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_nick");
        mark_invalid_message(c);
        return;
    }
    char *nick = strtok(args, " ");
    if (!nick) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_nick");
        mark_invalid_message(c);
        return;
    }
    if (strlen(nick) > NICK_MAX) {
        send_line(c->fd, "ERR 100 BAD_FORMAT nick_too_long");
        mark_invalid_message(c);
        return;
    }

    if (c->state != ST_CONNECTED) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
        mark_invalid_message(c);
        return;
    }

    mark_valid_message(c);

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

void handle_list(client_t *c) {
    if (c->state != ST_AUTH) {
        send_line(c->fd, "ERR 101 INVALID_STATE not_auth");
        mark_invalid_message(c);
        return;
    }
    mark_valid_message(c);
    send_room_list(c->fd);
}

void handle_create(client_t *c, char *args) {
    if (!args) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_name");
        mark_invalid_message(c);
        return;
    }
    char *rname = strtok(args, " ");
    if (!rname) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_name");
        mark_invalid_message(c);
        return;
    }

    if (strlen(rname) > ROOM_NAME_MAX) {
        send_line(c->fd, "ERR 100 BAD_FORMAT room_name_too_long");
        mark_invalid_message(c);
        return;
    }

    if (c->state != ST_AUTH) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
        mark_invalid_message(c);
        return;
    }

    mark_valid_message(c);

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
    if (!args) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_id");
        mark_invalid_message(c);
        return;
    }
    char *idstr = strtok(args, " ");
    if (!idstr) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_id");
        mark_invalid_message(c);
        return;
    }

    if (c->state != ST_AUTH) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
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

    mark_valid_message(c);

    add_player_to_room(c, r);
    send_line(c->fd, "ROOM_JOINED %d", r->id);

    if (r->player_count == 2) {
        client_t *other = (r->player1 == c) ? r->player2 : r->player1;
        send_line(other->fd, "PLAYER_JOINED %s", c->nick);
    }
}

void handle_ready(client_t *c) {
    if (c->state != ST_IN_LOBBY) {
        send_line(c->fd, "ERR 101 INVALID_STATE not_in_lobby");
        mark_invalid_message(c);
        return;
    }

    mark_valid_message(c);
    c->state = ST_READY;
    send_line(c->fd, "OK you_are_ready");

    room_t *r = find_room_by_id(c->room_id);
    if (!r || r->player_count == 1) return;

    client_t *opponent = (r->player1 == c) ? r->player2 : r->player1;

    send_line(opponent->fd, "PLAYER_READY %s", c->nick);

    if (opponent->state == ST_READY) {
        // Start the match once both participants are ready
        start_game(r);
    }
}

void handle_leave(client_t *c) {
    room_t *r = find_room_by_id(c->room_id);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        mark_invalid_message(c);
        return;
    }

    if (r->state != RM_FULL && r->state != RM_OPEN) {
        send_line(c->fd, "ERR 101 INVALID_STATE cannot_leave_now");
        mark_invalid_message(c);
        return;
    }

    if (c->state != ST_IN_LOBBY && c->state != ST_READY) {
        send_line(c->fd, "ERR 101 INVALID_STATE cannot_leave_now");
        mark_invalid_message(c);
        return;
    }

    mark_valid_message(c);
    remove_player_from_room(c, r);
    send_line(c->fd, "OK left_room %d", r->id);
}

void handle_move(client_t *c, char *args) {
    if (c->state != ST_PLAYING) {
        send_line(c->fd, "ERR 101 INVALID_STATE");
        mark_invalid_message(c);
        return;
    }

    room_t *r = find_room_by_id(c->room_id);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        mark_invalid_message(c);
        return;
    }

    // Validate the room state before accepting the move
    if (r->state != RM_PLAYING) {
        send_line(c->fd, "ERR 101 INVALID_STATE room_not_playing");
        mark_invalid_message(c);
        return;
    }

    if (!r->awaiting_moves) {
        send_line(c->fd, "ERR 101 INVALID_STATE not_accepting_moves");
        mark_invalid_message(c);
        return;
    }

    if (!args) {
        send_line(c->fd, "ERR 100 BAD_FORMAT invalid_move");
        mark_invalid_message(c);
        return;
    }
    char *move = strtok(args, " ");
    if (!move || (move[0] != 'R' && move[0] != 'P' && move[0] != 'S')) {
        send_line(c->fd, "ERR 100 BAD_FORMAT invalid_move");
        mark_invalid_message(c);
        return;
    }
    mark_valid_message(c);

    // Remember the move for the player that just acted
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

    // Run the round resolution once both moves are present
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
        mark_invalid_message(c);
        return;
    }

    room_t *r = find_room_by_id(c->room_id);
    if (!r) {
        send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
        mark_invalid_message(c);
        return;
    }
    mark_valid_message(c);


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
    printf("Handling reconnect for fd%d token %s\n", c->fd, token);
    if (!token) {
        send_line(c->fd, "ERR 100 BAD_FORMAT missing_token");
        mark_invalid_message(c);
        shutdown(c->fd, SHUT_RDWR);
        return;
    }

    client_t *old_client = find_client_by_token(token);
    if (!old_client) {
        send_line(c->fd, "ERR 110 INVALID_TOKEN");
        mark_invalid_message(c);
        shutdown(c->fd, SHUT_RDWR);
        return;
    }

    mark_valid_message(c);

    // Copy the previous client's session data
    strncpy(c->nick, old_client->nick, NICK_MAX);
    c->nick[NICK_MAX] = '\0';
    strncpy(c->token, old_client->token, TOKEN_LEN);
    c->token[TOKEN_LEN - 1] = '\0';
    c->state = old_client->state;
    c->room_id = old_client->room_id;
    c->timeout_state = CONNECTED;
    c->last_seen = time(NULL);
    c->invalid_msg_streak = old_client->invalid_msg_streak;
    old_client->is_replaced = 1;


    switch (c->state) {
        case ST_AUTH:
            send_line(c->fd, "RECONNECT_OK CONNECTED");
            handle_list(c);
            break;
        case ST_IN_LOBBY:
            room_t *r = find_room_by_id(c->room_id);
            if (!r) {
                send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
                break;
            }
            // Replace the old client pointer with the reconnected instance
            if (r->player1 == old_client) {
                r->player1 = c;
                printf("Replaced player1 fd%d by fd%d\n", old_client->fd, c->fd);
            } else if (r->player2 == old_client){
                r->player2 = c;
                printf("Replaced player2 fd%d by fd%d\n", old_client->fd, c->fd);
            }

            send_line(c->fd, "RECONNECT_OK LOBBY");

            break;
        case ST_PLAYING:
            room_t *room = find_room_by_id(c->room_id);
            if (!room) {
                send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
                break;
            }
            // Replace the old client pointer with the reconnected instance
            if (room->player1 == old_client) {
                room->player1 = c;
            } else {
                room->player2 = c;
            }

            // Refresh the room metadata to resume gameplay
            room->state = RM_PLAYING;
            room->awaiting_moves = 1;
            room->round_start_time = time(NULL);
            client_t *opponent = get_opponent_in_room(room, c);
            // Check whether this player already sent a move before disconnecting
            char performed_move = (room->player1 == c) ? room->move_p1 : room->move_p2;
            if (performed_move != '\0') {
                performed_move = 'X';
            }
            send_line(c->fd, "RECONNECT_OK GAME %d %d %d %d %c",
                     room->score_p1, room->score_p2, room->round_number, performed_move);

            if (opponent) {
                performed_move = (room->player1 == c) ? room->move_p2 : room->move_p1;
                send_line(opponent->fd, "GAME_RESUMED %d %d %d %c",
                         room->round_number, room->score_p1, room->score_p2, performed_move);
            }

            break;
        default:
            send_line(c->fd, "RECONNECT_OK CONNECTED");
            break;
    }

    shutdown(old_client->fd, SHUT_RDWR);
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
    } else if (strcmp(cmd, "PONG") == 0) {
        return;
    } else if (strcmp(cmd, "RECONNECT") == 0){
        pthread_mutex_lock(&global_lock);
        handle_reconnect(c, args);
        pthread_mutex_unlock(&global_lock);
    } else {
        send_line(c->fd, "ERR 100 BAD_FORMAT unknown_command");
        mark_invalid_message(c);
    }
}
