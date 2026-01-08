#ifndef RPS_09_NETWORK_H
#define RPS_09_NETWORK_H

#include "room.h"
#include <string.h>
#include <stdarg.h>
#include <sys/socket.h>

/**
 * @brief Strips trailing CR/LF characters from a mutable string.
 * @param s Input/output buffer to sanitize in place.
 * @return void
 */
void trim_crlf(char *s);
/**
 * @brief Sends a formatted line terminated with CRLF to a socket.
 * @param fd Input destination descriptor.
 * @param fmt Input printf-style format string followed by variadic args.
 * @return int Output number of bytes transmitted or -1 on error.
 */
int send_line(int fd, const char *fmt, ...);


#endif //RPS_09_NETWORK_H

