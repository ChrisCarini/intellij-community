// "Replace with 'newFun(p1, p2, 0)'" "true"

interface X {
    @Deprecated("", ReplaceWith("newFun(p1, p2, 0)"))
    fun oldFun(p1: Int, p2: Int)

    fun newFun(p1: Int, p2: Int, p3: Int)
}

fun foo(x: X) {
    x.<caret>oldFun(
            1,
            2
    )
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.replaceWith.DeprecatedSymbolUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.replaceWith.DeprecatedSymbolUsageFix