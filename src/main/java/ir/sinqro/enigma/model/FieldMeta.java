package ir.sinqro.enigma.model;

public record FieldMeta(String dtoName, String entityPath, boolean disabled, String disableReason,
                        boolean throughCollection) {
    public boolean isAllowed() {
        return !disabled;
    }

    public boolean isCollection() {
        return throughCollection;
    }
}
