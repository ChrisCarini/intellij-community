// "Add non-null asserted (project!!) call" "true"
class Foo {
    val project: Project? = null

    fun quux() {
        baz(<caret>project)
    }

    fun baz(project: Project) {}

    class Project
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddExclExclCallFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddExclExclCallFix