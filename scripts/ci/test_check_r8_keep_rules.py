#!/usr/bin/env python3
"""Regression tests for check_r8_keep_rules.py: one passing tree, then one broken tree per rule."""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_r8_keep_rules as check  # noqa: E402

KEEP_NATIVE = "-keepclasseswithmembernames class * {\n    native <methods>;\n}\n"

LIB_GRADLE = 'plugins { id("com.android.library") }\nandroid { defaultConfig { consumerProguardFiles("proguard-rules.pro") } }\n'
APP_GRADLE = (
    'plugins { id("com.android.application") }\n'
    'android { buildTypes { release { proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } } }\n'
)
KOTLIN = "package com.example.nb\n\nclass Engine {\n    external fun init(): Long\n}\n"
CPP = 'extern "C" JNIEXPORT jlong JNICALL Java_com_example_nb_Engine_init(JNIEnv*, jobject) { return 0; }\n'


def tree(overrides: dict[str, str] | None = None) -> Path:
    root = Path(tempfile.mkdtemp())
    files = {
        "app/build.gradle.kts": APP_GRADLE,
        "app/proguard-rules.pro": "-dontwarn foo.Bar\n",
        "lib/build.gradle.kts": LIB_GRADLE,
        "lib/proguard-rules.pro": KEEP_NATIVE,
        "lib/src/main/java/com/example/nb/Engine.kt": KOTLIN,
        "lib/src/main/cpp/engine.cpp": CPP,
    }
    files.update(overrides or {})
    for rel, text in files.items():
        if text is None:
            continue
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return root


def problems(root: Path) -> list[str]:
    return check.check(root)


def expect_clean(root: Path, label: str) -> None:
    found = problems(root)
    assert not found, f"{label}: unexpected problems {found}"


def expect_problem(root: Path, needle: str, label: str) -> None:
    found = problems(root)
    assert any(needle in item for item in found), f"{label}: expected a problem containing {needle!r}, got {found}"


expect_clean(tree(), "baseline")

LIB = "lib/build.gradle.kts"
RULES = "lib/proguard-rules.pro"
CPP_PATH = "lib/src/main/cpp/engine.cpp"

# 1. A library's rules under proguardFiles are never read; an unreferenced rules file is dead.
expect_problem(
    tree({LIB: 'plugins { id("com.android.library") }\nandroid { buildTypes { release { proguardFiles("proguard-rules.pro") } } }\n'}),
    "never reads it", "library proguardFiles",
)
expect_problem(tree({LIB: 'plugins { id("com.android.library") }\n'}), "dead rules", "unreferenced rules")
expect_problem(tree({"other/consumer-rules.pro": "-keep class a.B\n"}), "no build file", "rules without a build file")

# 2. Native methods must be kept by a rules file that is read.
expect_problem(tree({RULES: "-dontwarn foo.Bar\n"}), "keeps native methods", "no native keep")
expect_problem(
    tree({RULES: "# -keepclasseswithmembernames class * { native <methods>; }\n"}),
    "keeps native methods", "commented native keep",
)

# 3. A JNI symbol for a class that has no native declaration.
expect_problem(
    tree({CPP_PATH: CPP.replace("com_example_nb_Engine", "com_example_nb_Renamed")}),
    "com.example.nb.Renamed", "stale JNI symbol",
)
# ...and an underscore in a class name is mangled as _1.
expect_clean(
    tree({
        "lib/src/main/java/com/example/nb/Gpu_Engine.kt": "package com.example.nb\n\nclass Gpu_Engine { external fun go() }\n",
        CPP_PATH: CPP + "JNIEXPORT void JNICALL Java_com_example_nb_Gpu_1Engine_go(JNIEnv*, jobject) {}\n",
    }),
    "mangled underscore",
)

# 4. A class the native code looks up by name needs its own keep.
lookup = CPP + 'static const char* kCallback = "com/example/nb/Callback";\n'
expect_problem(tree({CPP_PATH: lookup}), "com.example.nb.Callback", "lookup without keep")
expect_clean(
    tree({CPP_PATH: lookup, RULES: KEEP_NATIVE + "-keep class com.example.nb.Callback { *; }\n"}),
    "lookup with keep",
)
expect_clean(
    tree({CPP_PATH: lookup, RULES: KEEP_NATIVE + "-keep class com.example.nb.** { *; }\n"}),
    "lookup with package keep",
)

print("check_r8_keep_rules tests passed")
