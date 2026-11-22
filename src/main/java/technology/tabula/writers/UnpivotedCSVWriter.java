package technology.tabula.writers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.unpivot.UnpivotException;
import technology.tabula.unpivot.UnpivotSpec;

/**
 * Writes extracted wide tables in long (unpivoted) CSV form, suitable for
 * loading into a fact table.
 *
 * The first row of every extracted table is treated as its header; tables on
 * different pages may reorder or omit columns. Header columns are matched by
 * name against the configured dimension columns, or parsed as
 * {@code "<metric> <month>"} where the month is either {@code YYYY-MM} or
 * {@code YYYY年M月}. Each source record then yields one row per month, sorted
 * by month ascending, carrying the original dimension values, the month in
 * {@code YYYY-MM} form and one column per configured metric. Missing columns
 * and empty cells stay blank; a month whose metrics are all blank produces no
 * row, while {@code 0} is a regular value. The fixed output header is written
 * exactly once.
 */
public class UnpivotedCSVWriter implements Writer {

    // A trailing token that "looks like" a month; strict range validation happens in parseMonth.
    private static final Pattern DATE_SHAPED = Pattern.compile("^\\d{4}\\D.*(?:\\d|月)$");
    private static final Pattern ISO_MONTH = Pattern.compile("^(\\d{4})-(\\d{1,2})$");
    private static final Pattern CHINESE_MONTH = Pattern.compile("^(\\d{4})年(\\d{1,2})月$");
    // Splits on any Unicode whitespace, including non-breaking and ideographic spaces
    // that tabula may preserve inside header text.
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000]+");

    private final CSVFormat format;
    private final UnpivotSpec spec;

    public UnpivotedCSVWriter(UnpivotSpec spec) {
        this(CSVFormat.EXCEL, spec);
    }

    protected UnpivotedCSVWriter(CSVFormat format, UnpivotSpec spec) {
        this.format = format;
        this.spec = spec;
    }

    @Override
    public void write(Appendable out, Table table) throws IOException {
        write(out, Collections.singletonList(table));
    }

    @Override
    public void write(Appendable out, List<Table> tables) throws IOException {
        // First validate every header so no partial output is produced on error.
        Map<MetricMonth, ColumnLocation> seen = new HashMap<>();
        List<ParsedHeader> parsedHeaders = new ArrayList<>(tables.size());
        for (Table table : tables) {
            parsedHeaders.add(table.getRowCount() > 0 ? parseHeader(table, seen) : null);
        }

        try (CSVPrinter printer = new CSVPrinter(out, format)) {
            List<String> outputHeader = new ArrayList<>(
                    spec.getDimensions().size() + 1 + spec.getMetrics().size());
            outputHeader.addAll(spec.getDimensions());
            outputHeader.add(UnpivotSpec.MONTH_COLUMN);
            outputHeader.addAll(spec.getMetrics());
            printer.printRecord(outputHeader);

            for (int i = 0; i < tables.size(); i++) {
                Table table = tables.get(i);
                if (table.getRowCount() > 1) {
                    writeRows(printer, table, parsedHeaders.get(i));
                }
            }
            printer.flush();
        }
    }

    /** Column metadata resolved from one table's header row. */
    private static final class ParsedHeader {
        final Map<String, Integer> dimensionColumns = new HashMap<>();
        final List<MetricColumn> metricColumns = new ArrayList<>();
    }

    private static final class MetricColumn {
        final String metric;
        final String month;
        final int columnIndex;

        MetricColumn(String metric, String month, int columnIndex) {
            this.metric = metric;
            this.month = month;
            this.columnIndex = columnIndex;
        }
    }

    private static final class MetricMonth {
        final String metric;
        final String month;

        MetricMonth(String metric, String month) {
            this.metric = metric;
            this.month = month;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MetricMonth)) {
                return false;
            }
            MetricMonth other = (MetricMonth) o;
            return metric.equals(other.metric) && month.equals(other.month);
        }

        @Override
        public int hashCode() {
            return 31 * metric.hashCode() + month.hashCode();
        }
    }

    private static final class ColumnLocation {
        final int pageNumber;
        final int columnNumber;

        ColumnLocation(int pageNumber, int columnNumber) {
            this.pageNumber = pageNumber;
            this.columnNumber = columnNumber;
        }
    }

    private ParsedHeader parseHeader(Table table, Map<MetricMonth, ColumnLocation> seen)
            throws UnpivotException {
        int page = table.getPageNumber();
        ParsedHeader parsed = new ParsedHeader();
        Set<MetricMonth> tableKeys = new HashSet<>();
        List<RectangularTextContainer> headerRow = table.getRows().get(0);

        for (int col = 0; col < headerRow.size(); col++) {
            String header = headerRow.get(col).getText().trim();
            int columnNumber = col + 1;
            if (header.isEmpty()) {
                continue;
            }

            if (spec.getDimensions().contains(header)) {
                Integer previous = parsed.dimensionColumns.put(header, col);
                if (previous != null) {
                    throw new UnpivotException(String.format(
                            "Duplicate dimension column '%s' on page %d column %d",
                            header, page, columnNumber));
                }
                continue;
            }

            Matcher tokenMatcher = WHITESPACE.matcher(header);
            List<int[]> tokenSpans = new ArrayList<>();
            int searchFrom = 0;
            while (tokenMatcher.find(searchFrom)) {
                if (tokenMatcher.start() > searchFrom) {
                    tokenSpans.add(new int[]{searchFrom, tokenMatcher.start()});
                }
                searchFrom = tokenMatcher.end();
            }
            if (searchFrom < header.length()) {
                tokenSpans.add(new int[]{searchFrom, header.length()});
            }
            if (tokenSpans.isEmpty()) {
                continue;
            }
            String lastToken = header.substring(
                    tokenSpans.get(tokenSpans.size() - 1)[0],
                    tokenSpans.get(tokenSpans.size() - 1)[1]);
            if (DATE_SHAPED.matcher(lastToken).matches()) {
                String month = parseMonth(lastToken, page, columnNumber);
                // Keep the metric name verbatim, including any original spacing inside it.
                int metricEnd = tokenSpans.get(tokenSpans.size() - 1)[0];
                String metric = header.substring(0, metricEnd).trim();
                if (metric.isEmpty()) {
                    throw new UnpivotException(String.format(
                            "Missing metric name before month '%s' on page %d column %d",
                            lastToken, page, columnNumber));
                }
                if (!spec.getMetrics().contains(metric)) {
                    throw new UnpivotException(String.format(
                            "Unknown metric '%s' on page %d column %d (configured metrics: %s)",
                            metric, page, columnNumber, String.join(", ", spec.getMetrics())));
                }

                MetricMonth key = new MetricMonth(metric, month);
                if (!tableKeys.add(key)) {
                    throw new UnpivotException(String.format(
                            "Conflicting columns for metric '%s' and month %s on page %d column %d",
                            metric, month, page, columnNumber));
                }
                ColumnLocation earlier = seen.get(key);
                if (earlier != null) {
                    throw new UnpivotException(String.format(
                            "Conflicting columns for metric '%s' and month %s: "
                                    + "page %d column %d and page %d column %d",
                            metric, month,
                            earlier.pageNumber, earlier.columnNumber,
                            page, columnNumber));
                }
                seen.put(key, new ColumnLocation(page, columnNumber));
                parsed.metricColumns.add(new MetricColumn(metric, month, col));
            }
            // Any other header is not data for this transformation and is ignored.
        }

        for (String dimension : spec.getDimensions()) {
            if (!parsed.dimensionColumns.containsKey(dimension)) {
                throw new UnpivotException(String.format(
                        "Missing dimension column '%s' on page %d", dimension, page));
            }
        }
        return parsed;
    }

    /**
     * Accepts {@code YYYY-MM} and {@code YYYY年M月}, normalizes to
     * {@code YYYY-MM} and rejects out-of-range months or malformed tokens.
     */
    private static String parseMonth(String token, int page, int columnNumber)
            throws UnpivotException {
        Matcher iso = ISO_MONTH.matcher(token);
        Matcher chinese = CHINESE_MONTH.matcher(token);
        Matcher matcher = iso.matches() ? iso : (chinese.matches() ? chinese : null);
        if (matcher == null) {
            throw new UnpivotException(String.format(
                    "Illegal month '%s' on page %d column %d; expected YYYY-MM or YYYY年M月",
                    token, page, columnNumber));
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        if (year < 1 || month < 1 || month > 12) {
            throw new UnpivotException(String.format(
                    "Illegal month '%s' on page %d column %d; expected YYYY-MM or YYYY年M月",
                    token, page, columnNumber));
        }
        return String.format("%04d-%02d", year, month);
    }

    private void writeRows(CSVPrinter printer, Table table, ParsedHeader parsed) throws IOException {
        for (int rowIndex = 1; rowIndex < table.getRowCount(); rowIndex++) {
            List<RectangularTextContainer> row = table.getRows().get(rowIndex);

            List<String> dimensionValues = new ArrayList<>(spec.getDimensions().size());
            for (String dimension : spec.getDimensions()) {
                Integer col = parsed.dimensionColumns.get(dimension);
                dimensionValues.add(col != null && col < row.size() ? row.get(col).getText() : "");
            }

            // month (ascending) -> metric -> raw cell text
            TreeMap<String, Map<String, String>> byMonth = new TreeMap<>();
            for (MetricColumn metricColumn : parsed.metricColumns) {
                String value = metricColumn.columnIndex < row.size()
                        ? row.get(metricColumn.columnIndex).getText() : "";
                Map<String, String> monthValues = byMonth.get(metricColumn.month);
                if (monthValues == null) {
                    monthValues = new HashMap<>();
                    byMonth.put(metricColumn.month, monthValues);
                }
                monthValues.put(metricColumn.metric, value);
            }

            for (Map.Entry<String, Map<String, String>> entry : byMonth.entrySet()) {
                Map<String, String> monthValues = entry.getValue();
                boolean allBlank = true;
                for (String metric : spec.getMetrics()) {
                    if (!monthValues.getOrDefault(metric, "").trim().isEmpty()) {
                        allBlank = false;
                        break;
                    }
                }
                if (allBlank) {
                    continue;
                }

                List<String> record = new ArrayList<>(
                        spec.getDimensions().size() + 1 + spec.getMetrics().size());
                record.addAll(dimensionValues);
                record.add(entry.getKey());
                for (String metric : spec.getMetrics()) {
                    record.add(monthValues.getOrDefault(metric, ""));
                }
                printer.printRecord(record);
            }
        }
    }
}
