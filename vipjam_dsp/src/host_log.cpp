#include <stdio.h>
#include <stdarg.h>

extern "C" int __android_log_print(int prio, const char *tag, const char *fmt,
                                   ...) {
    (void)prio;
    (void)tag;
    (void)fmt;
    return 0;
}
