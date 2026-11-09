#!/usr/bin/env python3
"""Checks that R8 can neither strip nor rename anything the native code reaches by name.

A release build minifies the app and every library module into one R8 run. JNI binds by name, so a
class that R8 renames or removes fails at runtime with UnsatisfiedLinkError/NoClassDefFoundError, and
no unit test or debug build sees it (debug builds do not minify). Four rules are checked, across the
app and every module:

1. Every rules file is actually read. A library module's rules reach R8 only through
   `consumerProguardFiles(...)`; listed under `proguardFiles(...)` in a library they are never read.
   A rules file that no build file names at all is dead.
2. Some rules file that is read keeps native methods (`native <methods>` in a -keep* rule).
3. Every JNI symbol the native code exports (`Java_<package>_<Class>_<method>`) belongs to a class
   that has a Kotlin/Java source declaring a native method. A symbol for a class that no longer
   exists (renamed, moved) is a silent break.
4. Every class the native code looks up by name (a "com/..." string literal) has an explicit keep rule.

Usage: check_r8_keep_rules.py [repository-root]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

SKIP_DIRS = {"build", ".git", ".gradle", ".cxx", "target", "node_modules", ".idea"}
NATIVE_SUFFIXES = {".c", ".cc", ".cpp", ".h", ".hpp", ".rs"}
SOURCE_SUFFIXES = {".kt", ".java"}
# Call arguments up to the rules file name, allowing one level of nested calls such as
# getDefaultProguardFile("...").
ARGS = r"(?:[^()]|\([^()]*\))*?"


def walk(root: Path):
    for path in root.rglob("*"):
        if any(part in SKIP_DIRS for part in path.relative_to(root).parts):
            continue
        if path.is_file():
            yield path


def decode_jni_class(symbol: str) -> tuple[str, str] | None:
    """Java_com_x_Foo_bar -> ("com.x", "Foo"). Returns None when it cannot be split."""
    body = symbol[len("Java_"):].split("__", 1)[0]  # drop an overload signature
    body = body.replace("_00024", "$").replace("_1", "\0")
    parts = body.split("_")
    if len(parts) < 3:
        return None
    *path, cls, _method = parts
    pkg = ".".join(p.replace("\0", "_") for p in path)
    return pkg, cls.replace("\0", "_")


def check(root: Path) -> list[str]:
    problems: list[str] = []
    files = list(walk(root))

    rules = [p for p in files if p.suffix == ".pro" and ("proguard" in p.name or "consumer" in p.name)]
    build_files = [p for p in files if p.name in ("build.gradle.kts", "build.gradle")]
    build_text = {p: p.read_text(encoding="utf-8", errors="ignore") for p in build_files}

    # 1. every rules file is read.
    read_rules: list[Path] = []
    for rule in rules:
        owner = next((b for b in build_files if b.parent == rule.parent), None)
        rel = rule.relative_to(root)
        if owner is None:
            problems.append(f"{rel}: no build file next to it names this rules file (dead rules)")
            continue
        text = build_text[owner]
        is_library = "com.android.library" in text or "android.library" in text or "com.android.kotlin.multiplatform.library" in text
        consumer = re.search(r"consumerProguardFiles?\s*\(" + ARGS + r"[\"']" + re.escape(rule.name) + r"[\"']", text)
        plain = re.search(r"(?<!consumer)proguardFiles?\s*\(" + ARGS + r"[\"']" + re.escape(rule.name) + r"[\"']", text, re.I)
        if consumer or (plain and not is_library):
            read_rules.append(rule)
        elif plain and is_library:
            problems.append(
                f"{rel}: listed under proguardFiles in a library module; R8 never reads it there "
                f"(use consumerProguardFiles)"
            )
        else:
            problems.append(f"{rel}: {owner.relative_to(root)} does not reference this rules file (dead rules)")

    rule_text = "\n".join(r.read_text(encoding="utf-8", errors="ignore") for r in read_rules)
    rule_text = "\n".join(line for line in rule_text.splitlines() if not line.lstrip().startswith("#"))

    # 2. native methods are kept.
    if not re.search(r"-keep\w*\s+[^{}]*\{[^}]*native\s*<methods>", rule_text, re.S):
        problems.append("no rules file that R8 reads keeps native methods (-keepclasseswithmembernames class * { native <methods>; })")

    native_text = {p: p.read_text(encoding="utf-8", errors="ignore") for p in files if p.suffix in NATIVE_SUFFIXES}

    # 3. exported JNI symbols belong to a class that still declares native methods.
    declared: set[tuple[str, str]] = set()
    for src in (p for p in files if p.suffix in SOURCE_SUFFIXES):
        text = src.read_text(encoding="utf-8", errors="ignore")
        if not re.search(r"\bexternal\s+fun\b|\bnative\s+[\w<>\[\]]+\s+\w+\s*\(", text):
            continue
        pkg = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        for name in re.findall(r"\b(?:class|object|interface)\s+(\w+)", text):
            declared.add((pkg.group(1), name))
    exported: dict[tuple[str, str], Path] = {}
    for path, text in native_text.items():
        for symbol in set(re.findall(r"\bJava_[A-Za-z0-9_$]+", text)):
            decoded = decode_jni_class(symbol)
            if decoded:
                exported.setdefault((decoded[0], decoded[1].split("$")[0]), path)
    for (pkg, cls), path in sorted(exported.items()):
        if (pkg, cls) not in declared:
            problems.append(
                f"{path.relative_to(root)}: exports JNI symbols for {pkg}.{cls}, "
                f"but no source declares native methods on that class"
            )

    # 4. classes looked up by name need an explicit keep.
    looked_up = {
        m.replace("/", ".")
        for text in native_text.values()
        for m in re.findall(r"\"(com/[A-Za-z0-9_/$]+)\"", text)
    }
    for name in sorted(looked_up):
        package = name.rsplit(".", 1)[0]
        covered = bool(
            re.search(
                r"-keep\w*[^\n{]*(?<![\w.])(" + re.escape(name) + r"|" + re.escape(package) + r"\.\*\*?)(?=[\s{,;]|$)",
                rule_text,
                re.M,
            )
        )
        if not covered:
            problems.append(f"native code looks up {name} by name but no rules file that R8 reads keeps it")
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".").resolve()
    problems = check(root)
    for problem in problems:
        print(f"FAIL  {problem}", file=sys.stderr)
    if problems:
        print(f"{len(problems)} R8 keep-rule problem(s).", file=sys.stderr)
        return 1
    print("R8 keep rules cover the native code.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
