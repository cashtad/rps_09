# Protocol for RPS bo9 (TCP)

## Overview

Text-based, line-oriented protocol over TCP. Lines terminated by `\r\n` (CRLF).
Fields separated by single spaces. Nicknames and room names MUST not contain spaces.
All messages originate from either Client or Server and are plain ASCII/UTF-8.

## General rules

* Server always responds to the client's message.
* For each client request the server sends at least one response (ACK/ERR/etc.).
* Heartbeat: `PING` / `PONG` — server may send `PING` periodically; client replies `PONG`.
* If nothing happens, nothing is sent (except optional periodic PING).
* Max nickname length: 32 chars; allowed chars: printable non-space.
* Max room name length: 32 chars.
* Message terminator: CRLF (`\r\n`).

## Client -> Server commands

* `HELLO <nickname>`

    * initial identification. Server responds `WELCOME <token>`. Token should be stored locally to allow reconnection.
    * example: `HELLO Alice\r\n`

* `LIST`

    * requests room list. Server responds:

        * `R_LIST <count>`
        * `ROOM <id> <name> <players>/<max> <state>` (repeated <count> times)
    * example:

        * `ROOM_LIST 1\r\nROOM 42 room1 1/2 OPEN\r\n`

* `CREATE <room_name>`

    * create room (max players 2). Server: `R_CREATED <room_id>` or `ERR`.

* `JOIN <room_id>`

    * join room id. Server: `R_JOINED <room_id>` and broadcast `P_JOINED <nickname>` to room.

* `LEAVE`

    * leave current room and return to the lobby. Server: `OK left_room <id_room>` and broadcast `P_LEFT <nickname>`.

* `GET_OPP`

    * ask for the opponent's nickname in the current room. Server: `OPP_INF <nickname> <status>` if present or `OPP_INF NONE`.

* `READY`

    * mark ready in room. Server: `OK you_are_ready` and broadcast `P_READY <nickname>`. When both ready server sends `G_ST`.

* `MOVE <R|P|S>`

    * send choice for current round. Server: `MOVE_ACCEPTED` or `ERR`.

* `PING`

    * heartbeat. Server: `PONG`.

* `REC <token>`

    * attempt to reattach to old session. Server: `REC_OK <state>`

## Server -> Client messages

* `WELCOME <token>`

    * token is opaque session id (string). Keep locally to allow `RECONNECT`.

* `ERR <code> <message>`

    * error message. Code numeric.

* `OK <message>`

    * general positive acknowledgement.

* `R_LIST <n>` and `ROOM ...` entries

* `R_CREATED <room_id>`

* `R_JOINED <room_id>` 

* `OPP_INF <nickname> <status>`

* `P_JOINED <nickname>`

* `P_LEFT <nickname>`

* `LEAVE <room_id>`

* `G_ST`

* `R_ST <round_number>`

* `M_ACC`

* `R_RE <0 if lost|1 if won|D if draw> <move_yours> <move_opponents> <score_yours> <score_opponents>`

    * examples:

        * `ROUND_RESULT 1 R S 3 2`
        * `ROUND_RESULT D R R 2 2`

* `G_END <winner>`

* `PONG`

* `REC_OK <state>`

* `G_PAUSE`

* `G_RESUME <round_number> <score_yours> <score_opponents> <performed move>`

## Error codes

* `100` BAD_FORMAT — syntax error
* `101` INVALID_STATE — command invalid in current state
* `102` ROOM_FULL
* `103` AUTH_FAIL
* `104` UNKNOWN_ROOM
* `105` NOT_IN_ROOM
* `106` ROOM_WRONG_STATE
* `107` NICKNAME_TAKEN
* `200` TOO_MANY_INVALID_MSGS

## State machines (ASCII)

### Client state (per session)

```
ST_CONNECTED
  | Sending: HELLO, REC
  | Receiving: WELCOME, REC_OK
  v
ST_AUTH (in lobby)
  | Sending: R_CREATE, R_JOIN, LIST
  | Receiving: ROOM, R_LIST, R_CREATED, R_JOINED
  v
ST_IN_LOBBY (waiting)
  | Sending: READY, LEAVE, GET_OPP
  | Recieving: P_JOINED, P_READY, P_LEFT
  v
ST_READY
  | Sending: LEAVE, GET_OPP
  | Recieving: P_JOINED, P_READY, G_ST, P_LEFT
  v
ST_PLAYING
  | Sending: MOVE
  | Receiving: R_RE, G_END, R_ST, G_PAUSE
  v
AUTHENTICATED
```

### Room state

```
OPEN (0..1 players)
  | players join
  v
FULL (2 players)
  | both READY
  v
PLAYING
  | player DISCONNECTED (short) -> PAUSED
  | player DISCONNECTED (long) -> FINISHED
  v
FINISHED
```

## Round rules and timeouts

* bo9: first to reach 5 wins.
* Round flow:

    * Server sends `R_ST <n>`
    * Each player sends `MOVE <R|P|S>`
    * Server waits for both moves or `MOVE_TIMEOUT` (default 10s).
    * If a player fails to send a move within the timeout, that player LOSES the round.
    * If both send move, standard RPS rules apply; if same — DRAW (no score change).

## Reconnection policy

* `KEEPALIVE` interval: client may send `PING` every 3s.
* If server receives no data for `KEEPALIVE`(6s): marks `PLAYER_UNAVAILABLE short` and pauses game.
* `RECONNECT_WINDOW` default 45s — server keeps session state. Client attempts reconnect using `RECONNECT <token>`.
* If reconnect within window: `REC_OK` and game resumes.
* If not: server treats as long disconnect and ends the game; opponent wins the match by default.


*This document is the canonical protocol specification for the project.*
