#include "sec_parse.h"

#include <ctype.h>
#include <string.h>
#include <strings.h>

const char *sec_skip_prefix(const char *s) {
    const char *p = s;
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    const char *q = p;
    while (isalnum((unsigned char)*q) || *q == '_') q++;
    // Only a word containing a letter is a command name; "16:" is not a prefix.
    int letter = 0;
    for (const char *r = p; r < q; r++) if (isalpha((unsigned char)*r)) letter = 1;
    return (q > p && *q == ':' && letter) ? q + 1 : s;
}

static int is_word_char(int c) { return isalpha(c) || c == '_'; }

size_t sec_parse_ints(const char *s, int16_t *out, size_t max) {
    const char *p = sec_skip_prefix(s);
    size_t n = 0;
    while (*p) {
        unsigned char c = (unsigned char)*p;
        int neg = 0;
        const char *start = p;
        if (c == '-' && isdigit((unsigned char)p[1])) { neg = 1; p++; c = (unsigned char)*p; }
        if (isdigit(c)) {
            int in_word = (start > s && is_word_char((unsigned char)start[-1]));
            long v = 0;
            while (isdigit((unsigned char)*p)) {
                if (v < 1000000) v = v * 10 + (*p - '0');
                p++;
            }
            if (is_word_char((unsigned char)*p)) in_word = 1;
            if (in_word) continue;
            if (neg) v = -v;
            if (v > INT16_MAX) v = INT16_MAX;
            if (v < INT16_MIN) v = INT16_MIN;
            if (n < max) out[n] = (int16_t)v;
            n++;
            continue;
        }
        p++;
    }
    return n;
}

int sec_parse_scalar(const char *s, int *value) {
    int16_t buf[64];
    size_t n = sec_parse_ints(s, buf, 64);
    if (n == 0 || n > 64) return -1;
    *value = buf[n - 1];
    return 0;
}

int sec_is_failure(const char *s) {
    const char *p = sec_skip_prefix(s);
    while (*p == ' ' || *p == '\t') p++;
    if (strncasecmp(p, "NG", 2) == 0 && !isalnum((unsigned char)p[2])) return 1;
    static const char *const words[] = {"FAIL", "NOT_APPLICABLE", "not support", "unknown cmd"};
    for (size_t i = 0; i < sizeof(words) / sizeof(words[0]); i++) {
        size_t len = strlen(words[i]);
        for (const char *q = s; *q; q++) if (strncasecmp(q, words[i], len) == 0) return 1;
    }
    return 0;
}
