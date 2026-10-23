package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * A brush's stroke program: a small, sandboxed, deterministic script that runs once per dab step of
 * a live stroke and reshapes what that step paints. See `spec/stroke-program.md`.
 *
 * The three source fields are kept as text so a package stays human-auditable and the engine
 * compiles them itself; nothing here can touch files, the network, the clock, or any state outside
 * the stroke. [state] declares the persistent variables (and their starting values) that survive
 * from one dab step to the next within a stroke; [init] runs once, before the first step; [step]
 * runs for every dab step.
 */
@Serializable
data class StrokeProgramSpec(
    val state: Map<String, Float> = emptyMap(),
    val init: String = "",
    val step: String = "",
    /**
     * When positive, the program also runs every [tickMs] milliseconds of pen time that pass without
     * a distance step (a held pen, or a very slow one), so a brush can keep spraying where it is.
     */
    val tickMs: Float = 0f,
)

/** The result of compiling a [StrokeProgramSpec]: a runnable [StrokeProgram] or the reason it can't run. */
sealed interface StrokeProgramCompilation {
    data class Ok(val program: StrokeProgram) : StrokeProgramCompilation
    data class Error(val message: String) : StrokeProgramCompilation
}

/** What the engine tells a program about the dab step it is about to paint. */
class StrokeProgramFrame(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val speedPxPerMs: Float,
    val headingDeg: Float,
    val turnDeg: Float,
    val distancePx: Float,
    val timeMs: Float,
    val dtMs: Float,
    val index: Int,
    val diameterPx: Float,
    val tiltDeg: Float,
    val azimuthDeg: Float,
    val dwellMs: Float,
    val stepPx: Float,
    /** Layer px per millisecond over the last ~50 ms (not screen px: it follows the drawn line). */
    val layerSpeedPxPerMs: Float = 0f,
    /** 0 touchdown, 1 moving, 2 held (a time tick), 3 lift-off. */
    val phase: Int = 1,
    /** Pen clock in ms, the sample's uptime; ledger films dry against it. */
    val nowMs: Long = 0L,
)

/** A program's decision for one dab step: the dabs to paint, and how far to move before the next one. */
class StrokeProgramStep(val dabs: List<Dab>, val gapMultiplier: Float)

/**
 * A compiled stroke program. Immutable and shareable; each stroke runs it through its own
 * [StrokeProgramRunner], which owns the stroke's persistent state and random stream.
 */
class StrokeProgram private constructor(
    private val layout: Layout,
    private val stateDefaults: FloatArray,
    private val initCode: Array<Stmt>,
    private val stepCode: Array<Stmt>,
) {
    /**
     * A fresh runner for one stroke. [seed] is the stroke's seed, so a replay reproduces it exactly.
     * [ledger] is the stroke's view of the wet paint already on the layer; without one the stroke
     * starts on a dry wall.
     */
    fun newRunner(seed: Long, brush: AzphaltBrush, ledger: StrokeLedger? = null): StrokeProgramRunner =
        StrokeProgramRunner(this, seed, brush, ledger ?: PaintLedger().beginStroke())

    internal fun newMemory(): FloatArray {
        val mem = FloatArray(layout.memorySize)
        stateDefaults.copyInto(mem, layout.stateBase)
        return mem
    }

    internal fun runInit(vm: Vm) = runAll(initCode, vm)

    internal fun runStep(vm: Vm) = runAll(stepCode, vm)

    private fun runAll(code: Array<Stmt>, vm: Vm) {
        for (stmt in code) stmt.run(vm)
    }

    internal val stateBase get() = layout.stateBase
    internal val stateSize get() = layout.stateNames.size
    internal val outputBase get() = layout.outputBase
    internal val memorySize get() = layout.memorySize

    companion object {
        const val MAX_SOURCE_CHARS = 12_000
        const val MAX_STATE_VARS = 32
        const val MAX_LOOP_ITERATIONS = 64
        const val MAX_NESTING = 8
        const val MAX_EXPRESSION_DEPTH = 32
        const val MAX_LOCALS = 96
        const val MAX_OPS_PER_STEP = 40_000
        const val MAX_EMITS_PER_STEP = 256
        const val MAX_EMITS_PER_STROKE = 40_000
        const val MIN_GAP = 0.05f
        const val MIN_TICK_MS = 8f
        const val MAX_TICKS_PER_SAMPLE = 512
        const val MAX_GAP = 20f

        /** Names a program may read. */
        val INPUTS = listOf(
            "px", "py", "pressure", "speed", "heading", "turn", "dist", "time", "dt", "index",
            "diameter", "tilt", "azimuth", "dwell", "stepPx",
            "baseAlpha", "baseAngle", "baseTip", "baseHard",
            "layerSpeed", "phase",
        )

        /** Names a program may assign to, with the value each starts every step at. */
        val OUTPUTS = listOf(
            "shiftX" to 0f, "shiftY" to 0f, "sizeMul" to 1f, "alphaMul" to 1f, "tipMul" to 1f,
            "hardMul" to 1f, "rotate" to 0f, "hueShift" to 0f, "satMul" to 1f, "valMul" to 1f,
            "gapMul" to 1f, "hide" to 0f,
        )

        /** The fields an `emit { ... }` may set. */
        val EMIT_FIELDS = listOf(
            "x", "y", "size", "alpha", "angle", "tip", "hard", "hue", "sat", "val", "mix", "flow",
        )

        fun compile(spec: StrokeProgramSpec): StrokeProgramCompilation = try {
            StrokeProgramCompilation.Ok(build(spec))
        } catch (e: ProgramSyntaxException) {
            StrokeProgramCompilation.Error(e.message ?: "invalid stroke program")
        }

        private fun build(spec: StrokeProgramSpec): StrokeProgram {
            if (spec.init.length + spec.step.length > MAX_SOURCE_CHARS) {
                throw ProgramSyntaxException("program source is longer than $MAX_SOURCE_CHARS characters")
            }
            if (spec.state.size > MAX_STATE_VARS) {
                throw ProgramSyntaxException("more than $MAX_STATE_VARS state variables")
            }
            val stateNames = spec.state.keys.toList()
            for (name in stateNames) {
                if (!isIdentifier(name)) throw ProgramSyntaxException("state variable '$name' is not a valid name")
                if (name in RESERVED) throw ProgramSyntaxException("state variable '$name' is a reserved word")
                if (name in INPUTS || OUTPUTS.any { it.first == name }) {
                    throw ProgramSyntaxException("state variable '$name' shadows a built-in")
                }
            }
            val layout = Layout(stateNames)
            val init = Parser(Lexer(spec.init).tokens(), layout).parseProgram()
            val step = Parser(Lexer(spec.step).tokens(), layout).parseProgram()
            val defaults = FloatArray(stateNames.size) { i ->
                spec.state.getValue(stateNames[i]).takeIf { it.isFinite() } ?: 0f
            }
            return StrokeProgram(layout, defaults, init.toTypedArray(), step.toTypedArray())
        }

        private val RESERVED = setOf("let", "if", "else", "for", "in", "emit")

        private fun isIdentifier(s: String): Boolean =
            s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }
    }

    // ---- memory layout -----------------------------------------------------------------------

    internal class Layout(val stateNames: List<String>) {
        val inputBase = 0
        val outputBase = INPUTS.size
        val stateBase = outputBase + OUTPUTS.size
        val localBase = stateBase + stateNames.size
        val memorySize = localBase + MAX_LOCALS

        fun inputSlot(name: String): Int? = INPUTS.indexOf(name).takeIf { it >= 0 }?.let { inputBase + it }
        fun outputSlot(name: String): Int? = OUTPUTS.indexOfFirst { it.first == name }.takeIf { it >= 0 }?.let { outputBase + it }
        fun stateSlot(name: String): Int? = stateNames.indexOf(name).takeIf { it >= 0 }?.let { stateBase + it }
    }

    // ---- execution ---------------------------------------------------------------------------

    internal fun interface Expr {
        fun eval(vm: Vm): Float
    }

    internal fun interface Stmt {
        fun run(vm: Vm)
    }

    /** Thrown inside a step when it runs out of its operation budget; never escapes the runner. */
    internal class BudgetExceeded : RuntimeException()

    internal class Emit(val values: FloatArray, val present: Int)

    internal class Vm(val mem: FloatArray, val rng: Random, val seed: Int, val ledger: StrokeLedger) {
        var nowMs = 0L
        var ops = 0
        var emitsThisStep = 0
        var emitsThisStroke = 0
        val emitted = ArrayList<Emit>()

        fun tick(cost: Int = 1) {
            ops += cost
            if (ops > MAX_OPS_PER_STEP) throw BudgetExceeded()
        }
    }

    // ---- lexer -------------------------------------------------------------------------------

    internal enum class T { NUM, ID, OP, END }

    internal class Token(val type: T, val text: String, val value: Float, val line: Int)

    internal class Lexer(private val src: String) {
        private var i = 0
        private var line = 1

        fun tokens(): List<Token> {
            val out = ArrayList<Token>()
            while (true) {
                skipBlank()
                if (i >= src.length) {
                    out.add(Token(T.END, "end of program", 0f, line))
                    return out
                }
                val c = src[i]
                when {
                    c.isDigit() -> out.add(number())
                    c.isLetter() || c == '_' -> out.add(identifier())
                    else -> out.add(operator())
                }
            }
        }

        private fun skipBlank() {
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\n' -> { line++; i++ }
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                        while (i < src.length && src[i] != '\n') i++
                    }
                    else -> return
                }
            }
        }

        private fun number(): Token {
            val start = i
            while (i < src.length && src[i].isDigit()) i++
            // A '.' belongs to the number only when a digit follows, so "0..24" lexes as 0 .. 24.
            if (i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()) {
                i++
                while (i < src.length && src[i].isDigit()) i++
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                val save = i
                i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                if (i < src.length && src[i].isDigit()) {
                    while (i < src.length && src[i].isDigit()) i++
                } else {
                    i = save
                }
            }
            val text = src.substring(start, i)
            val value = text.toFloatOrNull()?.takeIf { it.isFinite() }
                ?: throw ProgramSyntaxException("line $line: '$text' is not a valid number")
            return Token(T.NUM, text, value, line)
        }

        private fun identifier(): Token {
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
            return Token(T.ID, src.substring(start, i), 0f, line)
        }

        private fun operator(): Token {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two in TWO_CHAR) {
                i += 2
                return Token(T.OP, two, 0f, line)
            }
            val one = src[i].toString()
            if (one in ONE_CHAR) {
                i++
                return Token(T.OP, one, 0f, line)
            }
            throw ProgramSyntaxException("line $line: unexpected character '$one'")
        }

        private companion object {
            val TWO_CHAR = setOf("<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "..")
            val ONE_CHAR = setOf("+", "-", "*", "/", "%", "(", ")", "{", "}", ",", ";", ":", "?", "<", ">", "=", "!")
        }
    }

    // ---- parser / compiler -------------------------------------------------------------------

    private class Parser(private val tokens: List<Token>, private val layout: Layout) {
        private var pos = 0
        private var depth = 0
        private var exprDepth = 0
        private var nextLocal = 0
        private val scopes = ArrayList<MutableMap<String, Int>>()

        fun parseProgram(): List<Stmt> {
            scopes.add(HashMap())
            val out = ArrayList<Stmt>()
            while (peek().type != T.END) out.add(statement())
            return out
        }

        private fun peek() = tokens[pos]
        private fun next() = tokens[pos++]
        private fun isOp(text: String) = peek().type == T.OP && peek().text == text
        private fun isId(text: String) = peek().type == T.ID && peek().text == text

        private fun fail(message: String, token: Token = peek()): Nothing =
            throw ProgramSyntaxException("line ${token.line}: $message")

        private fun expectOp(text: String) {
            if (!isOp(text)) fail("expected '$text' but found '${peek().text}'")
            pos++
        }

        private fun enter() {
            if (++depth > MAX_NESTING) fail("nested more than $MAX_NESTING levels deep")
        }

        private fun leave() {
            depth--
        }

        private fun enterExpression() {
            if (++exprDepth > MAX_EXPRESSION_DEPTH) fail("expression nested more than $MAX_EXPRESSION_DEPTH levels deep")
        }

        private fun leaveExpression() {
            exprDepth--
        }

        private fun lookupLocal(name: String): Int? {
            for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
            return null
        }

        private fun declareLocal(name: String, at: Token): Int {
            if (name in RESERVED) fail("'$name' is a reserved word", at)
            if (lookupLocal(name) != null || layout.stateSlot(name) != null ||
                layout.inputSlot(name) != null || layout.outputSlot(name) != null
            ) {
                fail("'$name' is already defined", at)
            }
            if (nextLocal >= MAX_LOCALS) fail("more than $MAX_LOCALS local variables", at)
            val slot = layout.localBase + nextLocal++
            scopes.last()[name] = slot
            return slot
        }

        private fun statement(): Stmt {
            val t = peek()
            if (t.type == T.ID) {
                when (t.text) {
                    "let" -> return letStatement()
                    "if" -> return ifStatement()
                    "for" -> return forStatement()
                    "emit" -> return emitStatement()
                }
            }
            if (t.type == T.ID && tokens[pos + 1].let { it.type == T.OP && it.text == "(" }) return callStatement()
            if (t.type == T.ID) return assignment()
            fail("a statement can't start with '${t.text}'")
        }

        /** A call used for what it does (`spray(...)`), its value discarded. */
        private fun callStatement(): Stmt {
            val nameToken = next()
            val expr = call(nameToken)
            expectOp(";")
            return Stmt { vm -> vm.tick(); expr.eval(vm) }
        }

        private fun block(): Array<Stmt> {
            expectOp("{")
            enter()
            scopes.add(HashMap())
            val out = ArrayList<Stmt>()
            while (!isOp("}")) {
                if (peek().type == T.END) fail("missing '}'")
                out.add(statement())
            }
            expectOp("}")
            scopes.removeAt(scopes.size - 1)
            leave()
            return out.toTypedArray()
        }

        private fun letStatement(): Stmt {
            next()
            val nameToken = next()
            if (nameToken.type != T.ID) fail("expected a name after 'let'", nameToken)
            expectOp("=")
            // The initialiser is parsed before the name exists, so `let a = a` can't read itself.
            val init = expression()
            expectOp(";")
            val slot = declareLocal(nameToken.text, nameToken)
            return Stmt { vm ->
                vm.tick()
                vm.mem[slot] = init.eval(vm)
            }
        }

        private fun assignment(): Stmt {
            val nameToken = next()
            val name = nameToken.text
            val slot = lookupLocal(name) ?: layout.stateSlot(name) ?: layout.outputSlot(name)
                ?: if (layout.inputSlot(name) != null) {
                    fail("'$name' is read-only", nameToken)
                } else {
                    fail("'$name' is not defined", nameToken)
                }
            val opToken = next()
            val op = opToken.text
            if (opToken.type != T.OP || op !in ASSIGN_OPS) fail("expected '=' after '$name'", opToken)
            val rhs = expression()
            expectOp(";")
            return when (op) {
                "=" -> Stmt { vm -> vm.tick(); vm.mem[slot] = rhs.eval(vm) }
                "+=" -> Stmt { vm -> vm.tick(); vm.mem[slot] = vm.mem[slot] + rhs.eval(vm) }
                "-=" -> Stmt { vm -> vm.tick(); vm.mem[slot] = vm.mem[slot] - rhs.eval(vm) }
                "*=" -> Stmt { vm -> vm.tick(); vm.mem[slot] = vm.mem[slot] * rhs.eval(vm) }
                else -> Stmt { vm -> vm.tick(); vm.mem[slot] = vm.mem[slot] / rhs.eval(vm) }
            }
        }

        private fun ifStatement(): Stmt {
            next()
            expectOp("(")
            val cond = expression()
            expectOp(")")
            val then = block()
            var otherwise: Array<Stmt> = emptyArray()
            if (isId("else")) {
                next()
                otherwise = if (isId("if")) {
                    enter()
                    val nested = ifStatement()
                    leave()
                    arrayOf(nested)
                } else {
                    block()
                }
            }
            return Stmt { vm ->
                vm.tick()
                val branch = if (cond.eval(vm) != 0f) then else otherwise
                for (s in branch) s.run(vm)
            }
        }

        private fun forStatement(): Stmt {
            next()
            val nameToken = next()
            if (nameToken.type != T.ID) fail("expected a loop variable after 'for'", nameToken)
            if (!isId("in")) fail("expected 'in' after the loop variable")
            next()
            val from = integerLiteral()
            expectOp("..")
            val to = integerLiteral()
            if (to < from) fail("the loop runs backwards")
            if (to - from > MAX_LOOP_ITERATIONS) fail("a loop may run at most $MAX_LOOP_ITERATIONS times")
            scopes.add(HashMap())
            val slot = declareLocal(nameToken.text, nameToken)
            val body = block()
            scopes.removeAt(scopes.size - 1)
            return Stmt { vm ->
                for (i in from until to) {
                    vm.tick()
                    vm.mem[slot] = i.toFloat()
                    for (s in body) s.run(vm)
                }
            }
        }

        private fun integerLiteral(): Int {
            val t = next()
            if (t.type != T.NUM || t.text.any { !it.isDigit() }) fail("loop bounds must be whole-number literals", t)
            return t.text.toInt()
        }

        private fun emitStatement(): Stmt {
            next()
            expectOp("{")
            val fields = ArrayList<Pair<Int, Expr>>()
            var present = 0
            while (!isOp("}")) {
                val nameToken = next()
                val index = EMIT_FIELDS.indexOf(nameToken.text)
                if (nameToken.type != T.ID || index < 0) {
                    fail("'${nameToken.text}' is not an emit field (${EMIT_FIELDS.joinToString()})", nameToken)
                }
                if (present and (1 shl index) != 0) fail("emit field '${nameToken.text}' is set twice", nameToken)
                expectOp(":")
                fields.add(index to expression())
                present = present or (1 shl index)
                if (isOp(",")) next() else if (!isOp("}")) fail("expected ',' or '}' in emit")
            }
            expectOp("}")
            expectOp(";")
            val frozen = fields.toTypedArray()
            val mask = present
            return Stmt { vm ->
                vm.tick(2)
                if (vm.emitsThisStep < MAX_EMITS_PER_STEP && vm.emitsThisStroke < MAX_EMITS_PER_STROKE) {
                    val values = FloatArray(EMIT_FIELDS.size)
                    for ((index, expr) in frozen) values[index] = expr.eval(vm)
                    vm.emitted.add(Emit(values, mask))
                    vm.emitsThisStep++
                    vm.emitsThisStroke++
                }
            }
        }

        // ---- expressions, lowest precedence first ----

        private fun expression(): Expr {
            enterExpression()
            val e = ternary()
            leaveExpression()
            return e
        }

        private fun ternary(): Expr {
            val cond = or()
            if (!isOp("?")) return cond
            next()
            val a = ternary()
            expectOp(":")
            val b = ternary()
            return Expr { vm -> vm.tick(); if (cond.eval(vm) != 0f) a.eval(vm) else b.eval(vm) }
        }

        private fun or(): Expr {
            var left = and()
            while (isOp("||")) {
                next()
                val l = left
                val r = and()
                left = Expr { vm -> vm.tick(); if (l.eval(vm) != 0f || r.eval(vm) != 0f) 1f else 0f }
            }
            return left
        }

        private fun and(): Expr {
            var left = equality()
            while (isOp("&&")) {
                next()
                val l = left
                val r = equality()
                left = Expr { vm -> vm.tick(); if (l.eval(vm) != 0f && r.eval(vm) != 0f) 1f else 0f }
            }
            return left
        }

        private fun equality(): Expr {
            var left = comparison()
            while (isOp("==") || isOp("!=")) {
                val eq = next().text == "=="
                val l = left
                val r = comparison()
                left = Expr { vm -> vm.tick(); if ((l.eval(vm) == r.eval(vm)) == eq) 1f else 0f }
            }
            return left
        }

        private fun comparison(): Expr {
            var left = additive()
            while (isOp("<") || isOp("<=") || isOp(">") || isOp(">=")) {
                val op = next().text
                val l = left
                val r = additive()
                left = Expr { vm ->
                    vm.tick()
                    val a = l.eval(vm)
                    val b = r.eval(vm)
                    val hit = when (op) {
                        "<" -> a < b
                        "<=" -> a <= b
                        ">" -> a > b
                        else -> a >= b
                    }
                    if (hit) 1f else 0f
                }
            }
            return left
        }

        private fun additive(): Expr {
            var left = multiplicative()
            while (isOp("+") || isOp("-")) {
                val plus = next().text == "+"
                val l = left
                val r = multiplicative()
                left = if (plus) {
                    Expr { vm -> vm.tick(); l.eval(vm) + r.eval(vm) }
                } else {
                    Expr { vm -> vm.tick(); l.eval(vm) - r.eval(vm) }
                }
            }
            return left
        }

        private fun multiplicative(): Expr {
            var left = unary()
            while (isOp("*") || isOp("/") || isOp("%")) {
                val op = next().text
                val l = left
                val r = unary()
                left = when (op) {
                    "*" -> Expr { vm -> vm.tick(); l.eval(vm) * r.eval(vm) }
                    "/" -> Expr { vm -> vm.tick(); safeDiv(l.eval(vm), r.eval(vm)) }
                    else -> Expr { vm -> vm.tick(); floorMod(l.eval(vm), r.eval(vm)) }
                }
            }
            return left
        }

        private fun unary(): Expr {
            if (isOp("-")) {
                next()
                enterExpression()
                val e = unary()
                leaveExpression()
                return Expr { vm -> vm.tick(); -e.eval(vm) }
            }
            if (isOp("!")) {
                next()
                enterExpression()
                val e = unary()
                leaveExpression()
                return Expr { vm -> vm.tick(); if (e.eval(vm) == 0f) 1f else 0f }
            }
            return primary()
        }

        private fun primary(): Expr {
            val t = next()
            when (t.type) {
                T.NUM -> {
                    val v = t.value
                    return Expr { v }
                }
                T.ID -> return identifierExpression(t)
                T.OP -> if (t.text == "(") {
                    val e = expression()
                    expectOp(")")
                    return e
                }
                T.END -> Unit
            }
            fail("unexpected '${t.text}'", t)
        }

        private fun identifierExpression(t: Token): Expr {
            if (isOp("(")) return call(t)
            val name = t.text
            if (name == "PI") {
                val v = PI.toFloat()
                return Expr { v }
            }
            val slot = lookupLocal(name) ?: layout.stateSlot(name) ?: layout.outputSlot(name)
                ?: layout.inputSlot(name) ?: fail("'$name' is not defined", t)
            return Expr { vm -> vm.mem[slot] }
        }

        private fun call(t: Token): Expr {
            val fn = FUNCTIONS[t.text] ?: HOST_FUNCTIONS[t.text] ?: fail("'${t.text}' is not a function", t)
            expectOp("(")
            val args = ArrayList<Expr>()
            if (!isOp(")")) {
                while (true) {
                    args.add(expression())
                    if (isOp(",")) next() else break
                }
            }
            expectOp(")")
            if (args.size != fn.arity) fail("${t.text}() takes ${fn.arity} argument(s), not ${args.size}", t)
            val a = args.toTypedArray()
            return when (fn.arity) {
                0 -> Expr { vm -> vm.tick(2); fn.impl(vm, 0f, 0f, 0f, 0f) }
                1 -> Expr { vm -> vm.tick(2); fn.impl(vm, a[0].eval(vm), 0f, 0f, 0f) }
                2 -> Expr { vm -> vm.tick(2); fn.impl(vm, a[0].eval(vm), a[1].eval(vm), 0f, 0f) }
                3 -> Expr { vm -> vm.tick(2); fn.impl(vm, a[0].eval(vm), a[1].eval(vm), a[2].eval(vm), 0f) }
                else -> Expr { vm ->
                    vm.tick(2)
                    fn.impl(vm, a[0].eval(vm), a[1].eval(vm), a[2].eval(vm), a[3].eval(vm))
                }
            }
        }

        private companion object {
            val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=")
        }
    }

    internal class ProgramSyntaxException(message: String) : RuntimeException(message)
}

private typealias ProgramVm = StrokeProgram.Vm

private class Fn(val arity: Int, val impl: (ProgramVm, Float, Float, Float, Float) -> Float)

private const val DEG = 57.29578f
private const val RAD = 0.017453292f
private const val UNIT_SCALE = 16777216f

private fun safeDiv(a: Float, b: Float): Float = if (b == 0f) 0f else a / b

private fun floorMod(a: Float, b: Float): Float = if (b == 0f) 0f else a - b * floor(a / b)

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun mixBits(a: Int): Int {
    var h = a
    h = (h xor (h ushr 16)) * -0x7a143595
    h = (h xor (h ushr 13)) * -0x3d4d51cb
    return h xor (h ushr 16)
}

private fun hashUnit(seed: Int, a: Int, b: Int = 0): Float {
    val h = mixBits(mixBits(seed xor a * 0x1B873593) + b * 0x27D4EB2D)
    return (h ushr 8) / UNIT_SCALE
}

private fun noise1(seed: Int, t: Float): Float {
    val i = floor(t)
    val f = t - i
    val ii = i.toInt()
    return hashUnit(seed, ii) + (hashUnit(seed, ii + 1) - hashUnit(seed, ii)) * smooth(f)
}

private fun noise2(seed: Int, x: Float, y: Float): Float {
    val ix = floor(x)
    val iy = floor(y)
    val fx = smooth(x - ix)
    val fy = smooth(y - iy)
    val xi = ix.toInt()
    val yi = iy.toInt()
    val a = hashUnit(seed, xi, yi)
    val b = hashUnit(seed, xi + 1, yi)
    val c = hashUnit(seed, xi, yi + 1)
    val d = hashUnit(seed, xi + 1, yi + 1)
    val top = a + (b - a) * fx
    val bottom = c + (d - c) * fx
    return top + (bottom - top) * fy
}

private fun finite(v: Float): Float = if (v.isFinite()) v else 0f

private val FUNCTIONS: Map<String, Fn> = mapOf(
    "abs" to Fn(1) { _, a, _, _, _ -> abs(a) },
    "min" to Fn(2) { _, a, b, _, _ -> min(a, b) },
    "max" to Fn(2) { _, a, b, _, _ -> max(a, b) },
    "clamp" to Fn(3) { _, v, lo, hi, _ -> if (hi < lo) lo else v.coerceIn(lo, hi) },
    "lerp" to Fn(3) { _, a, b, t, _ -> a + (b - a) * t },
    "smoothstep" to Fn(3) { _, e0, e1, x, _ ->
        if (e1 == e0) {
            if (x < e0) 0f else 1f
        } else {
            smooth(((x - e0) / (e1 - e0)).coerceIn(0f, 1f))
        }
    },
    // gate(edge, x): 1 once x reaches edge, else 0.
    "gate" to Fn(2) { _, edge, x, _, _ -> if (x >= edge) 1f else 0f },
    // Angles are in degrees throughout.
    "sin" to Fn(1) { _, a, _, _, _ -> sin(a * RAD) },
    "cos" to Fn(1) { _, a, _, _, _ -> cos(a * RAD) },
    "tan" to Fn(1) { _, a, _, _, _ -> finite(tan(a * RAD)) },
    "atan2" to Fn(2) { _, y, x, _, _ -> atan2(y, x) * DEG },
    "sqrt" to Fn(1) { _, a, _, _, _ -> if (a <= 0f) 0f else sqrt(a) },
    "pow" to Fn(2) { _, a, b, _, _ -> finite(a.pow(b)) },
    "exp" to Fn(1) { _, a, _, _, _ -> finite(exp(a.coerceIn(-60f, 60f))) },
    "log" to Fn(1) { _, a, _, _, _ -> if (a <= 0f) 0f else ln(a) },
    "floor" to Fn(1) { _, a, _, _, _ -> floor(a) },
    "ceil" to Fn(1) { _, a, _, _, _ -> ceil(a) },
    "round" to Fn(1) { _, a, _, _, _ -> if (a.isFinite()) a.roundToInt().toFloat() else 0f },
    "fract" to Fn(1) { _, a, _, _, _ -> a - floor(a) },
    "mod" to Fn(2) { _, a, b, _, _ -> floorMod(a, b) },
    "sign" to Fn(1) { _, a, _, _, _ -> if (a > 0f) 1f else if (a < 0f) -1f else 0f },
    "hypot" to Fn(2) { _, a, b, _, _ -> hypot(a, b) },
    // Stateless and seeded by the stroke: the same input gives the same value everywhere in a stroke.
    "hash" to Fn(1) { vm, a, _, _, _ -> hashUnit(vm.seed, a.toBits()) },
    "hash2" to Fn(2) { vm, a, b, _, _ -> hashUnit(vm.seed, a.toBits(), b.toBits()) },
    // Smooth value noise in [0, 1].
    "noise" to Fn(1) { vm, t, _, _, _ -> noise1(vm.seed, t) },
    "noise2" to Fn(2) { vm, x, y, _, _ -> noise2(vm.seed, x, y) },
    // Sequential random streams: each call advances the stroke's stream.
    "rand" to Fn(0) { vm, _, _, _, _ -> vm.rng.nextFloat() },
    "rands" to Fn(0) { vm, _, _, _, _ -> vm.rng.nextFloat() * 2f - 1f },
)

/** Calls that reach the stroke's wet-paint ledger; the stroke program's only window on the layer. */
private val HOST_FUNCTIONS: Map<String, Fn> = mapOf(
    // spray(x, y, r, dh): lay up to dh micrometres of wet film at the centre, falling to nothing at r.
    "spray" to Fn(4) { vm, x, y, r, dh ->
        vm.tick(footprintCost(r))
        vm.ledger.spray(x, y, r, dh, vm.nowMs)
        0f
    },
    // drain(x, y, r, dh): take up to dh micrometres off (a drip leaving), never below dry.
    "drain" to Fn(4) { vm, x, y, r, dh ->
        vm.tick(footprintCost(r))
        vm.ledger.drain(x, y, r, dh, vm.nowMs)
        0f
    },
    // wetAt(x, y, r): mean film under a footprint, in micrometres, dried to now.
    "wetAt" to Fn(3) { vm, x, y, r, _ ->
        vm.tick(footprintCost(r))
        finite(vm.ledger.wetAt(x, y, r, vm.nowMs))
    },
)

private fun footprintCost(r: Float): Int {
    val reach = if (r.isFinite()) r.coerceIn(0f, FOOTPRINT_COST_REACH_PX) else 0f
    return (reach * reach / FOOTPRINT_COST_DIVISOR).toInt()
}

private const val FOOTPRINT_COST_REACH_PX = 320f
private const val FOOTPRINT_COST_DIVISOR = 512f

private const val PROGRAM_SEED_SALT = 0x5052_4F47_5F53_4544L

/**
 * One stroke's execution of a [StrokeProgram]. It owns the stroke's persistent state and random
 * stream, and is a pure function of the frames it is fed: replaying a stroke's samples through a
 * fresh runner with the same seed reproduces every dab it painted.
 */
class StrokeProgramRunner internal constructor(
    private val program: StrokeProgram,
    seed: Long,
    private val brush: AzphaltBrush,
    private val ledger: StrokeLedger,
) {
    private val vm = ProgramVm(
        program.newMemory(),
        Random(seed xor PROGRAM_SEED_SALT),
        (seed xor (seed ushr 32)).toInt() xor 0x5EED,
        ledger,
    )

    /** The film this stroke touched, as it stands at [nowMs], for the host to hand to the next stroke. */
    fun film(nowMs: Long): PaintSnapshot = ledger.film(nowMs)
    private val snapshot = FloatArray(program.stateSize)
    private var started = false

    /** Most operations any one step has used so far, against [StrokeProgram.MAX_OPS_PER_STEP]. */
    var peakOps = 0
        private set

    /** Steps that ran out of budget and painted the brush unchanged. */
    var overruns = 0
        private set

    /**
     * Runs the program for one dab step and returns what to paint. [base] is what the brush itself
     * would paint for the step; the program's outputs reshape it, and its `emit`s add to it. A step
     * that overruns its budget paints [base] unchanged and leaves the program's state as it was.
     */
    fun apply(frame: StrokeProgramFrame, base: List<Dab>): StrokeProgramStep {
        val mem = vm.mem
        val template = base.firstOrNull() ?: defaultTemplate(frame)
        // Persistent state is snapshotted so an aborted step leaves no half-applied change behind.
        mem.copyInto(snapshot, 0, program.stateBase, program.stateBase + program.stateSize)
        load(frame, template)
        vm.ops = 0
        vm.nowMs = frame.nowMs
        vm.emitsThisStep = 0
        vm.emitted.clear()
        try {
            if (!started) {
                started = true
                program.runInit(vm)
            }
            program.runStep(vm)
        } catch (_: StrokeProgram.BudgetExceeded) {
            snapshot.copyInto(mem, program.stateBase)
            vm.emitted.clear()
            overruns++
            peakOps = StrokeProgram.MAX_OPS_PER_STEP
            return StrokeProgramStep(base, 1f)
        }
        peakOps = max(peakOps, vm.ops)
        return finish(frame, template, base)
    }

    private fun load(frame: StrokeProgramFrame, template: Dab) {
        val m = vm.mem
        val inputs = floatArrayOf(
            frame.x, frame.y, frame.pressure, frame.speedPxPerMs, frame.headingDeg, frame.turnDeg,
            frame.distancePx, frame.timeMs, frame.dtMs, frame.index.toFloat(), frame.diameterPx,
            frame.tiltDeg, frame.azimuthDeg, frame.dwellMs, frame.stepPx,
            template.alpha, template.angleDeg, template.tipRatio, template.hardness,
            frame.layerSpeedPxPerMs, frame.phase.toFloat(),
        )
        for (i in inputs.indices) m[i] = finite(inputs[i])
        for ((i, out) in StrokeProgram.OUTPUTS.withIndex()) m[program.outputBase + i] = out.second
    }

    private fun output(name: String): Float {
        val index = StrokeProgram.OUTPUTS.indexOfFirst { it.first == name }
        val v = vm.mem[program.outputBase + index]
        return if (v.isFinite()) v else StrokeProgram.OUTPUTS[index].second
    }

    private fun finish(frame: StrokeProgramFrame, template: Dab, base: List<Dab>): StrokeProgramStep {
        val out = ArrayList<Dab>(base.size + vm.emitted.size)
        if (output("hide") == 0f) {
            val sx = output("shiftX")
            val sy = output("shiftY")
            val size = output("sizeMul").coerceAtLeast(0f)
            val alpha = output("alphaMul").coerceAtLeast(0f)
            val tip = output("tipMul").coerceAtLeast(0f)
            val hard = output("hardMul").coerceAtLeast(0f)
            val rot = output("rotate")
            val hue = output("hueShift")
            val sat = output("satMul").coerceAtLeast(0f)
            val value = output("valMul").coerceAtLeast(0f)
            for (d in base) {
                out.add(
                    d.copy(
                        x = d.x + sx,
                        y = d.y + sy,
                        radius = d.radius * size,
                        alpha = (d.alpha * alpha).coerceIn(0f, 1f),
                        angleDeg = d.angleDeg + rot,
                        tipRatio = (d.tipRatio * tip).coerceIn(0.05f, 1f),
                        hardness = (d.hardness * hard).coerceIn(0f, 1f),
                        hueShiftDeg = d.hueShiftDeg + hue,
                        saturationMultiplier = d.saturationMultiplier * sat,
                        valueMultiplier = d.valueMultiplier * value,
                        mask = d.mask?.copy(x = d.mask.x + sx, y = d.mask.y + sy),
                    ),
                )
            }
        }
        for (e in vm.emitted) emitted(e, template)?.let(out::add)
        val gap = output("gapMul").coerceIn(StrokeProgram.MIN_GAP, StrokeProgram.MAX_GAP)
        return StrokeProgramStep(out, gap)
    }

    private fun emitted(e: StrokeProgram.Emit, template: Dab): Dab? {
        fun field(index: Int): Float? =
            if (e.present and (1 shl index) != 0) e.values[index] else null
        val values = e.values
        if (values.any { !it.isFinite() }) return null
        val size = field(2)
        if (size != null && size <= 0f) return null
        val alpha = field(3)
        if (alpha != null && alpha <= 0f) return null
        return template.copy(
            x = field(0) ?: template.x,
            y = field(1) ?: template.y,
            radius = if (size != null) size / 2f else template.radius,
            alpha = alpha?.coerceIn(0f, 1f) ?: template.alpha,
            angleDeg = field(4) ?: template.angleDeg,
            tipRatio = field(5)?.coerceIn(0.05f, 1f) ?: template.tipRatio,
            hardness = field(6)?.coerceIn(0f, 1f) ?: template.hardness,
            hueShiftDeg = field(7) ?: 0f,
            saturationMultiplier = field(8)?.coerceAtLeast(0f) ?: 1f,
            valueMultiplier = field(9)?.coerceAtLeast(0f) ?: 1f,
            colorMix = field(10)?.coerceIn(0f, 1f) ?: template.colorMix,
            flowMultiplier = field(11)?.coerceAtLeast(0f) ?: template.flowMultiplier,
            sourceRandom = vm.rng.nextFloat(),
            mask = null,
        )
    }

    private fun defaultTemplate(frame: StrokeProgramFrame): Dab = Dab(
        x = frame.x,
        y = frame.y,
        radius = frame.diameterPx / 2f,
        alpha = brush.opacity,
        angleDeg = brush.angle,
        tipRatio = brush.tipRatio,
        hardness = brush.hardness,
    )
}
