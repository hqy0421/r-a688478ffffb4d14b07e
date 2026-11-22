package technology.tabula.unpivot;

import java.io.IOException;

/**
 * Raised when a wide table cannot be transformed into long format:
 * invalid month tokens, missing or duplicate dimension columns, unknown
 * metrics or two columns carrying the same metric for the same month.
 * Messages always identify the PDF page and the 1-based column number.
 */
public class UnpivotException extends IOException {

    private static final long serialVersionUID = 1L;

    public UnpivotException(String message) {
        super(message);
    }
}
