// "Replace method reference with lambda" "true-preview"
public class Foo {
    static void foo() {
        Ar<String> a = Stri<caret>ng[]::new;
    }

    interface Ar<T> {
        T[] jjj(int p);
    }
}



