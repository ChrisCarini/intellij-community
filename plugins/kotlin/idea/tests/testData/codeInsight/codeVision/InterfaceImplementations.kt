// MODE: inheritors

interface SomeInterface {}/*<# [3 Implementations] #>*/
interface SomeOtherInterface : SomeInterface {} // <== (1): interface extension
class SomeClass : SomeInterface { // <== (2): interface implementation
    fun acceptsInterface(param: SomeInterface) {}
    fun main() = acceptsInterface(object : SomeInterface {}) // <== (3): anonymous class instance
}