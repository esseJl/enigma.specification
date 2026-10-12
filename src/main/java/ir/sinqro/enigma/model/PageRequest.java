package ir.sinqro.enigma.model;

public record PageRequest(int page, int size) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 1000;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }

        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }

        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be <= " + MAX_SIZE);
        }
    }

    public static PageRequest firstPage() {
        return new PageRequest(0, DEFAULT_SIZE);
    }

    public int offset() {
        return Math.multiplyExact(page, size);
    }
}
