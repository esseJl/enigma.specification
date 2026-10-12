package ir.sinqro.enigma.exception;


public class QueryRuleException extends RuntimeException {

    public QueryRuleException(String message) {
        super(message);
    }


    public QueryRuleException(String message, Exception ex) {
        super(message, ex);
    }
}
