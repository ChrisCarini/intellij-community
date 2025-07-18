// WITH_STDLIB
// PROBLEM: Java collection 'ConcurrentSkipListSet' is parameterized with a nullable type
import java.util.concurrent.ConcurrentSkipListSet

interface Source<T : String?> {
    fun nextT(): ConcurrentSkipListSet<<caret>T>
}