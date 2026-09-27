// Host test for sec_parse.c (not part of the Android build):
//   cc -std=c11 -Wall -Werror -o /tmp/sec_parse_test sec_parse_test.c sec_parse.c && /tmp/sec_parse_test
#include "sec_parse.h"

#include <stdio.h>
#include <string.h>

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

int main(void) {
    int v = 0;
    CHECK(sec_parse_scalar("get_x_num:16", &v) == 0 && v == 16);
    CHECK(sec_parse_scalar("34\n", &v) == 0 && v == 34);
    CHECK(sec_parse_scalar("get_y_num:NG", &v) == -1);
    CHECK(sec_is_failure("get_y_num:NG"));
    CHECK(sec_is_failure("run_delta_read_all:FAIL"));
    CHECK(sec_is_failure("NOT_APPLICABLE"));
    CHECK(!sec_is_failure("run_delta_read_all:OK"));
    CHECK(!sec_is_failure("1,2,3"));

    int16_t g[16];
    // Status words and the command name must never become numbers.
    CHECK(sec_parse_ints("run_delta_read_all:OK", g, 16) == 0);
    // Commas, spaces, newlines, negative values, trailing separator.
    size_t n = sec_parse_ints("run_delta_read_all:1,-2, 3\n4 5\t-6;7,8,", g, 16);
    CHECK(n == 8);
    CHECK(g[0] == 1 && g[1] == -2 && g[2] == 3 && g[5] == -6 && g[7] == 8);
    // Bare grid rows, CRLF, clamping.
    n = sec_parse_ints("  0 1 2\r\n3 99999 -99999\r\n", g, 16);
    CHECK(n == 6 && g[4] == 32767 && g[5] == -32768);
    // A '-' separator ("1 - 2") is not a sign; digits in words ("x16y") are not numbers.
    n = sec_parse_ints("1 - 2 x16y 3", g, 16);
    CHECK(n == 3 && g[0] == 1 && g[1] == 2 && g[2] == 3);
    // Counts past max, so a truncated read is visible.
    n = sec_parse_ints("1,2,3,4,5", g, 2);
    CHECK(n == 5 && g[1] == 2);
    // A numeric "prefix" is data, not a command name.
    n = sec_parse_ints("16:1,2", g, 16);
    CHECK(n == 3 && g[0] == 16);

    printf(failures ? "%d failure(s)\n" : "sec_parse: all passed\n", failures);
    return failures ? 1 : 0;
}
