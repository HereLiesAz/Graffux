#include "include/GlesStampEngine.h"
#ifndef OUTDIR
#define OUTDIR "out_gl"
#endif
#include "scenarios.h"
int main() { return runAll<GlesStampEngine>(); }
