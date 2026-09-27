// Tolerant parsers for the Samsung touch factory interface (/sys/devices/virtual/sec/tsp/cmd_result).
// Pure functions, no I/O: unit-tested on the host by sec_parse_test.c.
//
// cmd_result has been seen in several shapes across sec_ts driver versions, none verified on the
// Pixel 5 yet:  "get_x_num:16", "16", "run_delta_read_all:OK", "1,-2,3,...", "0 1 2\n3 4 5",
// "run_delta_read_all:1,2,...", "NG". The parsers therefore skip an optional leading "name:" and
// accept any mix of commas, spaces, tabs, semicolons and newlines between numbers.
#ifndef GRAFFUX_SEC_PARSE_H
#define GRAFFUX_SEC_PARSE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Skips a leading "identifier:" (letters, digits, '_' only) if present. */
const char *sec_skip_prefix(const char *s);

/**
 * Parses up to [max] signed integers from [s] (after sec_skip_prefix), clamped to int16. A '-' is a
 * sign only when a digit follows. Digits inside a word (e.g. "x16y") are ignored so that status
 * words never become numbers. Returns how many were found; keeps counting past [max] so the caller
 * can tell a truncated grid from a short one.
 */
size_t sec_parse_ints(const char *s, int16_t *out, size_t max);

/** The last integer in [s] (after sec_skip_prefix); 0 on success, -1 when there is none. */
int sec_parse_scalar(const char *s, int *value);

/** 1 when [s] reads as a failure: "NG", "FAIL", "NOT_APPLICABLE", "not support" (any case). */
int sec_is_failure(const char *s);

#ifdef __cplusplus
}
#endif

#endif
