package java.lang.invoke;

/** Compile-time stub only: the public API android.jar leaves it out, and d8 desugars every lambda. */
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
            MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType) {
        throw new UnsupportedOperationException();
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
            Object... args) {
        throw new UnsupportedOperationException();
    }
}
