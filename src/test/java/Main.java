public class Main {
    public static void main(final String[] args) {
        System.out.println();
        final int a = 0;
        final int b = 0;
        final int c = 0;
        final float x = 0;
        final float y = 0;

        new TestA().x(a, b, c);
        new TestA().y(a, b, x, y);
        new TestA().z();
    }
}