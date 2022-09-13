/*
 * @test /nodynamiccopyright/
 * @bug 8077786
 * @summary Check varargs access against inferred signature
 * @compile/fail/ref=VarargsInferredPrivateType.out -nowarn -XDrawDiagnostics VarargsInferredPrivateType.java OtherPackage.java
 * @compile/fail/ref=VarargsInferredPrivateType.out --release 8 -nowarn -XDrawDiagnostics VarargsInferredPrivateType.java OtherPackage.java
 *
 */

class VarargsInferredPrivateType {
    interface I {
        <T> void m(T... t);
    }

    void m(I i) {
        i.m(otherpackage.OtherPackage.getPrivate());
    }
}
