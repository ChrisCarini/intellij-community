// "Create property 'v2' as constructor parameter" "true"
// ERROR: No value passed for parameter 'v2'
// K2_AFTER_ERROR: No value passed for parameter 'v2'.

data class DataToFix(val p1: Int)
fun dataContext() {
    val (v1, v2) = DataToFix(<caret>1)
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.createFromUsage.createVariable.CreateParameterFromUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage.K2CreateParameterFromUsageBuilder$CreateParameterFromUsageAction