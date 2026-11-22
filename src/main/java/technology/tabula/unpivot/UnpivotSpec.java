package technology.tabula.unpivot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for the optional wide-to-long (unpivot) transformation of
 * CSV output. Columns named in {@link #dimensions} are kept as-is; columns
 * whose header looks like {@code "<metric> <month>"} are melted into one
 * output row per month.
 */
public class UnpivotSpec {

    /** Header of the generated month column; values use the {@code YYYY-MM} form. */
    public static final String MONTH_COLUMN = "月份";

    private final List<String> dimensions;
    private final List<String> metrics;

    public UnpivotSpec(List<String> dimensions, List<String> metrics) {
        this.dimensions = Collections.unmodifiableList(new ArrayList<>(dimensions));
        this.metrics = Collections.unmodifiableList(new ArrayList<>(metrics));
    }

    /** Dimension column names, in the order they must appear in the output. */
    public List<String> getDimensions() {
        return dimensions;
    }

    /** Metric names, in the order they must appear in the output. */
    public List<String> getMetrics() {
        return metrics;
    }
}
