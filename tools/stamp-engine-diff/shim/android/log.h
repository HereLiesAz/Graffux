#pragma once
#include <cstdio>
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_INFO 4
#define __android_log_print(p, tag, ...) (std::fprintf(stderr, "[%s] ", tag), std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
