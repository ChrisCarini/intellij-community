// COMPILER_ARGUMENTS: -Xwhen-guards

fun test(param: Any) {
    when (param) {
        is Int if <caret>param < 0 || param > 10 -> println("foo")
        else -> println("bar")
    }
}
